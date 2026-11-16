// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.agentnotes

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsServerSecretStore
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Push for agent notes on Android ([PushRegistrar], [pushStatus]): Firebase set up by hand from
 * this build's config (no google-services plugin, no automatic init), the registration job, the
 * fetch a ping starts, and [status] for the screens. Push is a build option (app/build.gradle,
 * thoughtsPush): only a build with push has Firebase compiled in ([PushProvider]). Without push in
 * the build, without a config, without Google Play services, or when the server or the
 * registration fails, push is off and Study mode's listener ("Instant notes") works as before.
 */
object AgentNotesPush {
    private const val PREFERENCES_FILE = "agent_notes_push"
    private const val KEY_RECORD = "record"
    private const val REGISTRATION_WORK = "agent-notes-push-registration"
    private const val FETCH_WORK = "agent-notes-push-fetch"

    val firebaseConfig = PushFirebaseConfig(
        appId = BuildConfig.FIREBASE_APP_ID,
        apiKey = BuildConfig.FIREBASE_API_KEY,
        projectId = BuildConfig.FIREBASE_PROJECT_ID,
        senderId = BuildConfig.FIREBASE_SENDER_ID,
    )

    /** Whether this build can push at all: Firebase compiled in and a config for this app. */
    val buildHasConfig: Boolean get() = PushProvider.compiledIn && firebaseConfig.present

    @Volatile private var statusFlow: MutableStateFlow<PushStatus>? = null

    /** Push as it is now (active, or off and why); follows registrations, receiving and server changes. */
    fun status(context: Context): StateFlow<PushStatus> = flow(context)

    /** Whether the server pushes agent notes to this phone now: no listener is needed then. */
    fun isActive(context: Context): Boolean = flow(context).value.active

    private fun flow(context: Context): MutableStateFlow<PushStatus> =
        statusFlow ?: synchronized(this) {
            statusFlow ?: MutableStateFlow(compute(context.applicationContext)).also { statusFlow = it }
        }

    private fun compute(context: Context): PushStatus = pushStatus(
        hasConfig = buildHasConfig,
        receiving = AgentNotesReceiving.isOn(context),
        server = runCatching { ThoughtsServerSecretStore(context).baseUrlWithToken() }.getOrNull(),
        app = context.packageName,
        record = recordStore(context).get(),
    )

    /** Recomputes [status]; push that just became active retires Study mode's listener. */
    fun refresh(context: Context) {
        val app = context.applicationContext
        val next = compute(app)
        val flow = flow(app)
        val wasActive = flow.value.active
        flow.value = next
        if (next.active) {
            val retired = AgentStudyMode.retireForPush(app)
            if (!retired && !wasActive) AgentStudyListenerService.stop(app)
        }
    }

    /** The process started: register again (idempotent) when this build can push or once did. */
    fun onAppStart(context: Context) {
        val app = context.applicationContext
        refresh(app)
        if (buildHasConfig || recordStore(app).get().registeredToken != null) requestRegistration(app)
    }

    fun serverChanged(context: Context) {
        refresh(context)
        requestRegistration(context)
    }

    /** "Receive agent notes on this phone" changed: off removes the registration, on registers. */
    fun receivingChanged(context: Context) {
        refresh(context)
        if (buildHasConfig || recordStore(context).get().registeredToken != null) requestRegistration(context)
    }

    /** Firebase replaced the token: push waits for the server to take the new one. */
    fun tokenChanged(context: Context, token: String) {
        val store = recordStore(context)
        store.set(store.get().copy(currentTokenSha256 = pushTokenSha256(token)))
        refresh(context)
        requestRegistration(context)
    }

    /** Runs the registration job when the network allows; the latest request replaces an older one. */
    fun requestRegistration(context: Context) {
        val request = OneTimeWorkRequestBuilder<PushRegistrationWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        runCatching {
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(REGISTRATION_WORK, ExistingWorkPolicy.REPLACE, request)
        }.onFailure { Log.w(AgentNotes.TAG, "push: registration not scheduled ${it.javaClass.simpleName}") }
    }

    /**
     * A ping said a note is waiting: asks the inbox at once (expedited; a high-priority message
     * grants the quota). A second ping waits for the first fetch to end, then asks again.
     */
    fun fetchNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<AgentNotesPushFetchWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        runCatching {
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(FETCH_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }.onFailure { Log.w(AgentNotes.TAG, "push: fetch not scheduled ${it.javaClass.simpleName}") }
    }

    /** Google Play services can deliver pushes on this phone (never in a build without push). */
    fun playServicesUsable(context: Context): Boolean = PushProvider.playServicesUsable(context)

    /** The current push token; blocking, never on the main thread. Throws when there is none. */
    fun token(context: Context): String = PushProvider.token(context, firebaseConfig)

    fun recordStore(context: Context): PushRecordStore = PreferencesPushRecordStore(context.applicationContext)

    /** The [PushRecord] as one JSON string with fixed names (release builds rename fields, so no Gson binding). */
    private class PreferencesPushRecordStore(context: Context) : PushRecordStore {
        private val preferences = context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)

        override fun get(): PushRecord {
            val json = preferences.getString(KEY_RECORD, null) ?: return PushRecord()
            return runCatching {
                val root = JsonParser.parseString(json).asJsonObject
                PushRecord(
                    server = root.string("server"),
                    serverHasPush = root.get("server_has_push")?.takeIf { it.isJsonPrimitive }?.asBoolean,
                    playServices = root.get("play_services")?.takeIf { it.isJsonPrimitive }?.asBoolean,
                    currentTokenSha256 = root.string("current_token_sha256"),
                    registeredToken = root.string("registered_token"),
                    registeredApp = root.string("registered_app"),
                    registeredAtMillis = root.get("registered_at_millis")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
                    failure = root.string("failure")?.let { name -> PushFailure.entries.firstOrNull { it.name == name } },
                )
            }.getOrElse { PushRecord() }
        }

        override fun set(record: PushRecord) {
            val root = JsonObject().apply {
                record.server?.let { addProperty("server", it) }
                record.serverHasPush?.let { addProperty("server_has_push", it) }
                record.playServices?.let { addProperty("play_services", it) }
                record.currentTokenSha256?.let { addProperty("current_token_sha256", it) }
                record.registeredToken?.let { addProperty("registered_token", it) }
                record.registeredApp?.let { addProperty("registered_app", it) }
                addProperty("registered_at_millis", record.registeredAtMillis)
                record.failure?.let { addProperty("failure", it.name) }
            }
            preferences.edit().putString(KEY_RECORD, root.toString()).commit()
        }
    }
}
