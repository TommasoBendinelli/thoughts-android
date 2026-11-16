// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.agentnotes

import java.io.IOException
import java.security.MessageDigest

/**
 * Push for agent notes, without Android (protocol: server capability [PUSH_FCM_CAPABILITY]). The
 * server sends this phone a content-free Firebase Cloud Messaging ping when a note is waiting; the
 * phone then asks its inbox as the live loop and the periodic job do. While push is active the
 * Study mode listener (a foreground service with a notification Android forces) is not needed.
 */
const val PUSH_FCM_CAPABILITY = "push_fcm_v1"

/** The four values FirebaseOptions needs, from this build's BuildConfig (empty: the build has none). */
data class PushFirebaseConfig(val appId: String, val apiKey: String, val projectId: String, val senderId: String) {
    val present: Boolean get() = listOf(appId, apiKey, projectId, senderId).all(String::isNotBlank)
}

/** The server's push endpoints, as the phone uses them. Blocking; throws IOException. */
interface ThoughtsPushApi {
    /** What the server can do (`GET /v1/health`). */
    fun capabilities(): Set<String>
    /** `PUT /v1/push/registration`: whether the server registered [token] for this device. */
    fun registerPush(token: String, app: String): Boolean
    /** `DELETE /v1/push/registration` for [token], or every registration of this device (null). */
    fun unregisterPush(token: String?): Int
    /** `POST /v1/push/test`: a ping of kind "test" to this device only. 409 when push is off on the server. */
    fun sendTestPush(): PushTestResult
}

/** The answer to `POST /v1/push/test`: how many pings went out, and each registration's result as sent. */
data class PushTestResult(val sent: Int, val results: List<String>)

/** Why a push attempt left the phone without a working registration. */
enum class PushFailure {
    /** The server could not be reached (or answered 5xx); tried again with back-off. */
    UNREACHABLE,
    /** The server refused the registration (HTTP 4xx, or `registered` false). */
    REFUSED,
    /** No push token from Google's push service. */
    NO_TOKEN,
}

/**
 * What this phone knows about its push registration, kept in its own preferences (never backed up
 * or moved to another device). Everything is about [server]: another server starts afresh.
 */
data class PushRecord(
    /** The server address the facts below are about. */
    val server: String? = null,
    /** Whether that server advertised [PUSH_FCM_CAPABILITY] when last asked; null before asking. */
    val serverHasPush: Boolean? = null,
    /** Whether Google Play services were usable when last checked; null before checking. */
    val playServices: Boolean? = null,
    /** SHA-256 of the push token as last obtained or announced by Firebase (onNewToken). */
    val currentTokenSha256: String? = null,
    /** The token the server accepted for this phone (kept so a replaced or unwanted one can be removed). */
    val registeredToken: String? = null,
    /** The application ID it was registered for. */
    val registeredApp: String? = null,
    val registeredAtMillis: Long = 0L,
    /** The last attempt's failure; null after a success. */
    val failure: PushFailure? = null,
)

/** Why push is off right now, said in plain words on screen. */
enum class PushOffReason {
    /** This build has no Firebase config. */
    NOT_BUILT,
    NO_PLAY_SERVICES,
    /** "Receive agent notes on this phone" is off. */
    NOT_RECEIVING,
    NO_SERVER,
    /** Nothing known yet about the current server. */
    CHECKING,
    SERVER_WITHOUT_PUSH,
    /** Waiting for the registration of the current token. */
    REGISTERING,
    REFUSED,
    NO_TOKEN,
    UNREACHABLE,
}

/** Push as the screens show it: active, or off with a reason. [serverSupportsPush] offers "Send a test push". */
data class PushStatus(val active: Boolean, val offReason: PushOffReason?, val serverSupportsPush: Boolean) {
    companion object {
        fun off(reason: PushOffReason, serverSupportsPush: Boolean = false) = PushStatus(false, reason, serverSupportsPush)
    }
}

fun pushTokenSha256(token: String): String =
    MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/**
 * Push is active when the build has a Firebase config, Google Play services work, this phone
 * receives agent notes, the current [server] advertises [PUSH_FCM_CAPABILITY], and the server
 * accepted the current push token for [app]. A later attempt that failed only because the server
 * could not be reached leaves an earlier registration of the same token in place (the server still
 * has it); a refusal or a new token does not.
 */
fun pushStatus(hasConfig: Boolean, receiving: Boolean, server: String?, app: String, record: PushRecord): PushStatus {
    if (!hasConfig) return PushStatus.off(PushOffReason.NOT_BUILT)
    if (record.playServices == false) return PushStatus.off(PushOffReason.NO_PLAY_SERVICES)
    if (server == null) return PushStatus.off(PushOffReason.NO_SERVER)
    val known = record.takeIf { it.server == server }
    val supports = known?.serverHasPush == true
    if (!receiving) return PushStatus.off(PushOffReason.NOT_RECEIVING, supports)
    if (known == null) return PushStatus.off(PushOffReason.CHECKING)
    when (known.serverHasPush) {
        false -> return PushStatus.off(PushOffReason.SERVER_WITHOUT_PUSH)
        null -> return PushStatus.off(PushOffReason.CHECKING)
        true -> Unit
    }
    val registered = known.registeredToken?.takeIf { known.registeredApp == app }
    if (registered == null) {
        return PushStatus.off(
            when (known.failure) {
                PushFailure.REFUSED -> PushOffReason.REFUSED
                PushFailure.NO_TOKEN -> PushOffReason.NO_TOKEN
                PushFailure.UNREACHABLE -> PushOffReason.UNREACHABLE
                null -> PushOffReason.REGISTERING
            },
            supports,
        )
    }
    if (known.currentTokenSha256 != null && pushTokenSha256(registered) != known.currentTokenSha256) {
        return PushStatus.off(PushOffReason.REGISTERING, supports)
    }
    return PushStatus(active = true, offReason = null, serverSupportsPush = true)
}

/** Where [PushRegistrar] keeps the [PushRecord]. */
interface PushRecordStore {
    fun get(): PushRecord
    fun set(record: PushRecord)
}

/**
 * Brings the server's registration in line with what this phone wants, idempotently: PUT the
 * current token when push should work, DELETE the registration when this phone stops receiving
 * notes, the build cannot push or the server no longer advertises push. [run] returns whether the
 * job is done or should be tried again (network failures, no token yet).
 */
class PushRegistrar(
    private val api: ThoughtsPushApi,
    private val store: PushRecordStore,
    private val log: (String) -> Unit = {},
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    enum class Outcome { REGISTERED, REMOVED, OFF, RETRY, REFUSED }

    /**
     * [server] is the configured server address (null: none). [playServices] and [token] are
     * asked only when needed; [token] throws when Google's push service gives none.
     */
    fun run(
        hasConfig: Boolean,
        receiving: Boolean,
        server: String?,
        app: String,
        playServices: () -> Boolean,
        token: () -> String,
    ): Outcome {
        val stored = store.get()
        if (server == null) {
            // No server, nothing to ask; a registration on a server forgotten here cannot be reached any more.
            if (stored.server != null) store.set(PushRecord(playServices = stored.playServices, currentTokenSha256 = stored.currentTokenSha256))
            return Outcome.OFF
        }
        var record = if (stored.server == server) stored else {
            PushRecord(server = server, playServices = stored.playServices, currentTokenSha256 = stored.currentTokenSha256)
        }
        var wanted = hasConfig && receiving
        if (wanted) {
            val usable = playServices()
            record = record.copy(playServices = usable)
            wanted = usable
        }
        if (!wanted) return remove(record)

        val capabilities = try {
            api.capabilities()
        } catch (error: IOException) {
            store.set(record.copy(failure = PushFailure.UNREACHABLE))
            log("push: capabilities failed ${error.javaClass.simpleName}")
            return Outcome.RETRY
        }
        if (PUSH_FCM_CAPABILITY !in capabilities) {
            record = record.copy(serverHasPush = false)
            record.registeredToken?.let { old ->
                // Best effort: a server without push may not know the route any more.
                runCatching { api.unregisterPush(old) }
                log("push: the server no longer advertises push; registration removed")
            }
            store.set(record.copy(registeredToken = null, registeredApp = null, failure = null))
            return Outcome.OFF
        }
        record = record.copy(serverHasPush = true)

        val fresh = try {
            token()
        } catch (error: Exception) {
            store.set(record.copy(failure = PushFailure.NO_TOKEN))
            log("push: no token ${error.javaClass.simpleName}")
            return Outcome.RETRY
        }
        record = record.copy(currentTokenSha256 = pushTokenSha256(fresh))
        val previous = record.registeredToken
        val accepted = try {
            api.registerPush(fresh, app)
        } catch (error: AgentNotesHttpException) {
            if (error.permanent) {
                store.set(record.copy(registeredToken = null, registeredApp = null, failure = PushFailure.REFUSED))
                log("push: registration refused status=${error.statusCode} code=${error.errorCode}")
                return Outcome.REFUSED
            }
            store.set(record.copy(failure = PushFailure.UNREACHABLE))
            return Outcome.RETRY
        } catch (error: IOException) {
            store.set(record.copy(failure = PushFailure.UNREACHABLE))
            return Outcome.RETRY
        }
        if (!accepted) {
            store.set(record.copy(registeredToken = null, registeredApp = null, failure = PushFailure.REFUSED))
            log("push: registration not accepted")
            return Outcome.REFUSED
        }
        store.set(record.copy(registeredToken = fresh, registeredApp = app, registeredAtMillis = nowMillis(), failure = null))
        log("push: registered")
        if (previous != null && previous != fresh) {
            // The token Firebase replaced: the server would only get errors for it.
            runCatching { api.unregisterPush(previous) }
        }
        return Outcome.REGISTERED
    }

    private fun remove(record: PushRecord): Outcome {
        val registered = record.registeredToken
        if (registered == null) {
            store.set(record)
            return Outcome.OFF
        }
        return try {
            api.unregisterPush(registered)
            store.set(record.copy(registeredToken = null, registeredApp = null, failure = null))
            log("push: registration removed")
            Outcome.REMOVED
        } catch (error: AgentNotesHttpException) {
            if (!error.permanent) {
                store.set(record)
                return Outcome.RETRY
            }
            // Unknown to the server already (or no such route): nothing left to remove.
            store.set(record.copy(registeredToken = null, registeredApp = null, failure = null))
            Outcome.REMOVED
        } catch (error: IOException) {
            store.set(record)
            Outcome.RETRY
        }
    }
}

/** What a push message asks for, by its data `kind`. */
enum class PushPingAction { FETCH_NOTES, SHOW_TEST, IGNORE }

/**
 * `agent_notes` asks the inbox now (only while this phone receives notes), `test` shows "Push
 * works", anything else is ignored: the message carries no content, only its kind.
 */
fun pushPingAction(data: Map<String, String>, receiving: Boolean): PushPingAction = when (data["kind"]) {
    "agent_notes" -> if (receiving) PushPingAction.FETCH_NOTES else PushPingAction.IGNORE
    "test" -> PushPingAction.SHOW_TEST
    else -> PushPingAction.IGNORE
}

/**
 * The 30-minute listener and Study mode's listener start only while this phone receives notes and
 * push does not deliver them already (after a reply, a page sent, "Copy for agent", a note opened).
 */
fun agentNotesListenerShouldStart(receiving: Boolean, pushActive: Boolean): Boolean = receiving && !pushActive
