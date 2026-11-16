// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.agentnotes

import android.content.Context
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.util.concurrent.TimeUnit

/**
 * The parts of push that need Google's libraries (Firebase Cloud Messaging and Google Play services),
 * compiled only into a build with push (app/build.gradle, thoughtsPush). A build without push has
 * src/nopush's version, and agent notes reach the phone as before push.
 */
internal object PushProvider {
    /** This build has Firebase Cloud Messaging compiled in. */
    const val compiledIn = true

    /** Google Play services can deliver pushes on this phone. */
    fun playServicesUsable(context: Context): Boolean = runCatching {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context.applicationContext) == ConnectionResult.SUCCESS
    }.getOrDefault(false)

    /**
     * The default FirebaseApp, set up from this build's config on first use (the push manifest removes
     * Firebase's own start-up provider and its automatic token). False when it could not be set up.
     */
    @Synchronized
    fun ensureFirebase(context: Context, config: PushFirebaseConfig): Boolean {
        if (!config.present) return false
        val app = context.applicationContext
        if (FirebaseApp.getApps(app).any { it.name == FirebaseApp.DEFAULT_APP_NAME }) return true
        val options = FirebaseOptions.Builder()
            .setApplicationId(config.appId)
            .setApiKey(config.apiKey)
            .setProjectId(config.projectId)
            .setGcmSenderId(config.senderId)
            .build()
        return runCatching { FirebaseApp.initializeApp(app, options) }
            .onFailure { Log.w(AgentNotes.TAG, "push: Firebase not set up ${it.javaClass.simpleName}") }
            .isSuccess
    }

    /**
     * The current push token; blocking, never on the main thread. Throws when there is none.
     * firebase-messaging 25.1 deprecated getToken and onNewToken without naming a replacement in
     * its release notes; both still work, and the server sends to this token.
     */
    @Suppress("DEPRECATION")
    fun token(context: Context, config: PushFirebaseConfig): String {
        check(ensureFirebase(context, config)) { "This build has no Firebase config" }
        return Tasks.await(FirebaseMessaging.getInstance().token, 60, TimeUnit.SECONDS)
            ?.takeIf(String::isNotBlank) ?: throw IllegalStateException("No push token")
    }
}

/**
 * Receives the server's pings ([pushPingAction]) and Firebase's token refreshes. A ping carries no
 * content, only its kind; the token is never logged.
 */
class ThoughtsPushService : FirebaseMessagingService() {
    override fun onCreate() {
        // Firebase is set up by hand (no start-up provider): before the base class needs it.
        PushProvider.ensureFirebase(this, AgentNotesPush.firebaseConfig)
        super.onCreate()
    }

    /** Deprecated in firebase-messaging 25.1 (no replacement named), still how a token refresh is announced. */
    @Deprecated("Firebase deprecated onNewToken; it is still called when the token changes")
    override fun onNewToken(token: String) {
        Log.i(AgentNotes.TAG, "push: new token")
        AgentNotesPush.tokenChanged(this, token)
    }

    /**
     * Firebase's newer registration callback. Thoughts registers through getToken, so this only
     * means something changed: the registration job asks for the token again and sends that one.
     */
    override fun onRegistered(token: String) {
        Log.i(AgentNotes.TAG, "push: registered with Firebase")
        AgentNotesPush.tokenChanged(this, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val receiving = AgentNotesReceiving.isOn(this)
        when (pushPingAction(message.data, receiving)) {
            PushPingAction.FETCH_NOTES -> {
                Log.i(AgentNotes.TAG, "push: agent_notes ping")
                AgentNotesPush.fetchNow(this)
            }
            PushPingAction.SHOW_TEST -> {
                Log.i(AgentNotes.TAG, "push: test ping")
                AgentNoteNotifier.notifyPushTest(this)
            }
            PushPingAction.IGNORE -> {
                val kind = message.data["kind"].orEmpty().filter { it.isLetterOrDigit() || it == '_' }.take(32)
                Log.i(AgentNotes.TAG, "push: ping ignored kind=$kind receiving=$receiving")
                // A ping for a phone that no longer receives notes: make sure the server forgets it.
                if (!receiving && kind == "agent_notes") AgentNotesPush.requestRegistration(this)
            }
        }
    }

    /** Google dropped pings it could not deliver in time: one look at the inbox covers them all. */
    override fun onDeletedMessages() {
        if (AgentNotesReceiving.isOn(this)) {
            Log.i(AgentNotes.TAG, "push: pings dropped by Google; asking the inbox")
            AgentNotesPush.fetchNow(this)
        }
    }
}
