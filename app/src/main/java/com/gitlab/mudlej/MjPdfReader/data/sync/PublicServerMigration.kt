// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import android.content.Context
import android.util.Log
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import org.tbend.thoughts.sdk.SyncClient

/**
 * One-time move of a phone configured with a former address of this build's server
 * (`https://<legacy host>`, Gradle property `thoughtsLegacyServerHosts`) to its public address
 * (`thoughtsPublicServerUrl`), keeping the encrypted device token. A build without legacy hosts
 * never migrates anything.
 *
 * The saved address changes only after the public server answers `/v1/health` and accepts the
 * device token; otherwise the old address stays and a later start tries again. A test server on
 * another port (e.g. `:8443`) and every other address are never touched.
 */
internal object PublicServerMigration {
    const val LOG_TAG = "ThoughtsServerMigration"

    enum class Outcome { MIGRATED, NOT_NEEDED, ALREADY_DONE, PUBLIC_SERVER_UNHEALTHY, TOKEN_REJECTED, CHANGED_CONCURRENTLY }

    /** The saved server address and token. */
    interface AddressStore {
        fun baseUrl(): String?
        fun token(): String?
        /** Replaces the address only while it is still [expected]; the token stays as stored. */
        fun replaceBaseUrl(expected: String, replacement: String): Boolean
    }

    /** Remembers that the migration happened, so it runs once. */
    interface Ledger {
        fun completed(): Boolean
        fun recordCompleted(from: String, to: String)
        fun recordFailedAttempt(outcome: Outcome, detail: String)
    }

    interface Probe {
        /** True when an unauthenticated `GET /v1/health` answers 200 with status "ok". */
        fun healthy(baseUrl: String): Boolean
        /** True when an authenticated request with [token] succeeds. */
        fun acceptsToken(baseUrl: String, token: String): Boolean
    }

    /**
     * True only for `https://<one of [legacyHosts]>` on the default port, without a path: not a test
     * server on another port (e.g. `:8443`).
     */
    fun isLegacyProductionAddress(
        baseUrl: String?,
        legacyHosts: Set<String> = ThoughtsServerAddress.LEGACY_HOSTS,
    ): Boolean {
        if (legacyHosts.isEmpty()) return false
        val uri = baseUrl?.trim()?.trimEnd('/')?.let { runCatching { URI(it) }.getOrNull() } ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.host?.trimEnd('.')?.lowercase(Locale.ROOT) in legacyHosts &&
            (uri.port == -1 || uri.port == 443) &&
            uri.rawPath.isNullOrEmpty() &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null
    }

    fun run(
        store: AddressStore,
        ledger: Ledger,
        probe: Probe,
        isTestEnvironment: Boolean,
        legacyHosts: Set<String> = ThoughtsServerAddress.LEGACY_HOSTS,
        target: String = ThoughtsServerAddress.PUBLIC_BASE_URL,
        afterMigration: (from: String, to: String) -> Unit = { _, _ -> },
    ): Outcome {
        if (isTestEnvironment) return Outcome.NOT_NEEDED
        if (ledger.completed()) return Outcome.ALREADY_DONE
        val current = store.baseUrl()
        // Unconfigured or another address: nothing to do, and nothing is recorded, so a server
        // configuration restored later is still moved.
        if (!isLegacyProductionAddress(current, legacyHosts)) return Outcome.NOT_NEEDED
        val token = store.token()?.takeIf(String::isNotBlank) ?: return Outcome.NOT_NEEDED
        if (!runCatching { probe.healthy(target) }.getOrDefault(false)) {
            ledger.recordFailedAttempt(Outcome.PUBLIC_SERVER_UNHEALTHY, "$target/v1/health did not answer ok")
            return Outcome.PUBLIC_SERVER_UNHEALTHY
        }
        val accepted = runCatching { probe.acceptsToken(target, token) }
        if (accepted.getOrDefault(false) != true) {
            ledger.recordFailedAttempt(
                Outcome.TOKEN_REJECTED,
                accepted.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" }
                    ?: "The public server did not accept the device token",
            )
            return Outcome.TOKEN_REJECTED
        }
        if (!store.replaceBaseUrl(checkNotNull(current), target)) {
            // The address changed meanwhile (for example in Settings); respect that choice.
            return Outcome.CHANGED_CONCURRENTLY
        }
        ledger.recordCompleted(current, target)
        afterMigration(current, target)
        return Outcome.MIGRATED
    }

    private val running = AtomicBoolean(false)

    /** Runs the migration on a background thread unless it already completed or is running. */
    fun startInBackground(context: Context, waitForStartup: Boolean = false) {
        if (BuildConfig.IS_TEST_ENVIRONMENT || ThoughtsServerAddress.LEGACY_HOSTS.isEmpty()) return
        val appContext = context.applicationContext
        if (!running.compareAndSet(false, true)) return
        Thread(
            {
                try {
                    if (waitForStartup) com.gitlab.mudlej.MjPdfReader.data.StartupReadiness.awaitMaintenanceWindow()
                    // Reading preferences here keeps them off the main thread during a cold start.
                    if (!PreferencesLedger(appContext).completed() &&
                        isLegacyProductionAddress(ThoughtsServerSecretStore(appContext).baseUrl())
                    ) {
                        runNow(appContext)
                    }
                } finally {
                    running.set(false)
                }
            },
            "thoughts-public-url-migration",
        ).start()
    }

    /** Runs the migration on the calling (background) thread. */
    fun runNow(context: Context): Outcome {
        val appContext = context.applicationContext
        val outcome = runCatching {
            run(
                store = SecretStoreAddresses(ThoughtsServerSecretStore(appContext)),
                ledger = PreferencesLedger(appContext),
                probe = HttpProbe,
                isTestEnvironment = BuildConfig.IS_TEST_ENVIRONMENT,
                afterMigration = { from, to ->
                    // Capabilities are cached per address; carry them over so nothing waits for the
                    // next Sync to learn them again.
                    ReviewSourceCapabilityStore(appContext).copy(from, to)
                    // Push is registered per address: register at the new one.
                    com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNotesPush.serverChanged(appContext)
                },
            )
        }.getOrElse { error ->
            Log.w(LOG_TAG, "Public address migration failed", error)
            return Outcome.PUBLIC_SERVER_UNHEALTHY
        }
        when (outcome) {
            Outcome.MIGRATED -> Log.i(LOG_TAG, "Thoughts server moved to ${ThoughtsServerAddress.PUBLIC_BASE_URL}")
            Outcome.PUBLIC_SERVER_UNHEALTHY, Outcome.TOKEN_REJECTED, Outcome.CHANGED_CONCURRENTLY ->
                Log.w(LOG_TAG, "Public address migration postponed: $outcome")
            Outcome.NOT_NEEDED, Outcome.ALREADY_DONE -> Unit
        }
        return outcome
    }

    private class SecretStoreAddresses(private val secrets: ThoughtsServerSecretStore) : AddressStore {
        override fun baseUrl(): String? = secrets.baseUrl()
        override fun token(): String? = secrets.config()?.token
        override fun replaceBaseUrl(expected: String, replacement: String): Boolean =
            secrets.replaceBaseUrl(expected, replacement)
    }

    internal class PreferencesLedger(context: Context) : Ledger {
        private val preferences = context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

        override fun completed(): Boolean = preferences.getLong(KEY_COMPLETED_AT, 0L) > 0L

        override fun recordCompleted(from: String, to: String) {
            preferences.edit()
                .putLong(KEY_COMPLETED_AT, System.currentTimeMillis())
                .putString(KEY_FROM, from)
                .putString(KEY_TO, to)
                .remove(KEY_LAST_ERROR)
                .commit()
        }

        override fun recordFailedAttempt(outcome: Outcome, detail: String) {
            preferences.edit()
                .putInt(KEY_ATTEMPTS, preferences.getInt(KEY_ATTEMPTS, 0) + 1)
                .putLong(KEY_LAST_ATTEMPT_AT, System.currentTimeMillis())
                .putString(KEY_LAST_ERROR, "$outcome: ${detail.take(300)}")
                .apply()
        }

        private companion object {
            const val PREFERENCES_NAME = "thoughts_server_migrations"
            const val KEY_COMPLETED_AT = "public_url_v1.completed_at"
            const val KEY_FROM = "public_url_v1.from"
            const val KEY_TO = "public_url_v1.to"
            const val KEY_ATTEMPTS = "public_url_v1.failed_attempts"
            const val KEY_LAST_ATTEMPT_AT = "public_url_v1.last_failed_at"
            const val KEY_LAST_ERROR = "public_url_v1.last_error"
        }
    }

    private object HttpProbe : Probe {
        private const val TIMEOUT_MILLIS = 8_000

        override fun healthy(baseUrl: String): Boolean {
            val connection = URL("$baseUrl/v1/health").openConnection() as HttpURLConnection
            return try {
                connection.connectTimeout = TIMEOUT_MILLIS
                connection.readTimeout = TIMEOUT_MILLIS
                connection.setRequestProperty("Accept", "application/json")
                connection.responseCode == 200 && connection.inputStream.bufferedReader().use { reader ->
                    JsonParser.parseReader(reader).asJsonObject.get("status")?.asString == "ok"
                }
            } finally {
                connection.disconnect()
            }
        }

        /** `/v1/device-sequence` is the cheapest request that needs the device token. */
        override fun acceptsToken(baseUrl: String, token: String): Boolean =
            SyncClient(
                baseUrl,
                token,
                connectTimeoutMillis = TIMEOUT_MILLIS,
                readTimeoutMillis = TIMEOUT_MILLIS,
                metadataTimeoutMillis = TIMEOUT_MILLIS,
                connectionFactory = TokenConnections.open,
            ).deviceSequence().deviceId.isNotBlank()
    }
}
