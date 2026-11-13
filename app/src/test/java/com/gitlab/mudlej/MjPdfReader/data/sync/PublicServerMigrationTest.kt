// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.gitlab.mudlej.MjPdfReader.data.sync.PublicServerMigration.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicServerMigrationTest {
    private class FakeStore(var url: String?, var token: String? = TOKEN) : PublicServerMigration.AddressStore {
        var replacements = 0
        override fun baseUrl(): String? = url
        override fun token(): String? = token
        override fun replaceBaseUrl(expected: String, replacement: String): Boolean {
            if (url != expected) return false
            replacements += 1
            url = replacement
            return true
        }
    }

    private class FakeLedger : PublicServerMigration.Ledger {
        var completedFrom: String? = null
        var completedTo: String? = null
        val failures = mutableListOf<Outcome>()
        override fun completed(): Boolean = completedTo != null
        override fun recordCompleted(from: String, to: String) {
            completedFrom = from
            completedTo = to
        }
        override fun recordFailedAttempt(outcome: Outcome, detail: String) {
            failures += outcome
        }
    }

    private class FakeProbe(
        var healthy: Boolean = true,
        var accepts: Boolean = true,
        val healthError: Throwable? = null,
        val tokenError: Throwable? = null,
    ) : PublicServerMigration.Probe {
        val healthChecks = mutableListOf<String>()
        val tokenChecks = mutableListOf<Pair<String, String>>()
        override fun healthy(baseUrl: String): Boolean {
            healthChecks += baseUrl
            healthError?.let { throw it }
            return healthy
        }
        override fun acceptsToken(baseUrl: String, token: String): Boolean {
            tokenChecks += baseUrl to token
            tokenError?.let { throw it }
            return accepts
        }
    }

    private fun migrate(
        store: FakeStore,
        ledger: FakeLedger = FakeLedger(),
        probe: FakeProbe = FakeProbe(),
        isTestEnvironment: Boolean = false,
        moved: MutableList<Pair<String, String>> = mutableListOf(),
        legacyHosts: Set<String> = HOSTS,
    ): Outcome = PublicServerMigration.run(store, ledger, probe, isTestEnvironment, legacyHosts, PUBLIC) { from, to -> moved += from to to }

    private fun isLegacy(address: String?, legacyHosts: Set<String> = HOSTS) =
        PublicServerMigration.isLegacyProductionAddress(address, legacyHosts)

    @Test
    fun recognisesOnlyTheProductionTailscaleAddress() {
        assertTrue(isLegacy(LEGACY))
        assertTrue(isLegacy("https://server.example.ts.net/"))
        assertTrue(isLegacy("https://Server.Example.TS.NET"))
        assertTrue(isLegacy("https://server.example.ts.net:443"))
        // The isolated test server, other paths, other hosts and schemes stay as they are.
        assertFalse(isLegacy("https://server.example.ts.net:8443"))
        assertFalse(isLegacy("https://server.example.ts.net/thoughts"))
        assertFalse(isLegacy("http://server.example.ts.net"))
        assertFalse(isLegacy("https://other.example.ts.net"))
        assertFalse(isLegacy(PUBLIC))
        assertFalse(isLegacy(null))
        assertFalse(isLegacy("not a url"))
        // Any of several legacy hosts counts.
        assertTrue(isLegacy("https://old.example.org", HOSTS + "old.example.org"))
    }

    @Test
    fun aBuildWithoutLegacyHostsNeverMigrates() {
        assertFalse(isLegacy(LEGACY, emptySet()))
        val store = FakeStore(LEGACY)
        val ledger = FakeLedger()
        val probe = FakeProbe()
        assertEquals(Outcome.NOT_NEEDED, migrate(store, ledger, probe, legacyHosts = emptySet()))
        assertEquals(LEGACY, store.url)
        assertTrue(probe.healthChecks.isEmpty())
        assertFalse(ledger.completed())
        // The build's own options: a saved address is moved only when it names one of its legacy hosts.
        assertEquals(
            ThoughtsServerAddress.LEGACY_HOSTS.isNotEmpty(),
            PublicServerMigration.isLegacyProductionAddress("https://" + (ThoughtsServerAddress.LEGACY_HOSTS.firstOrNull() ?: LEGACY_HOST)),
        )
    }

    @Test
    fun movesTheLegacyAddressToThePublicOneAndKeepsTheToken() {
        val store = FakeStore(LEGACY)
        val ledger = FakeLedger()
        val probe = FakeProbe()
        val moved = mutableListOf<Pair<String, String>>()

        assertEquals(Outcome.MIGRATED, migrate(store, ledger, probe, moved = moved))

        assertEquals(PUBLIC, store.url)
        assertEquals(TOKEN, store.token)
        assertEquals(listOf(PUBLIC), probe.healthChecks)
        assertEquals(listOf(PUBLIC to TOKEN), probe.tokenChecks)
        assertEquals(LEGACY, ledger.completedFrom)
        assertEquals(PUBLIC, ledger.completedTo)
        assertEquals(listOf(LEGACY to PUBLIC), moved)
    }

    @Test
    fun runsOnlyOnce() {
        val store = FakeStore(LEGACY)
        val ledger = FakeLedger()
        assertEquals(Outcome.MIGRATED, migrate(store, ledger))

        // Even an address set back to Tailscale by hand afterwards is left alone.
        store.url = LEGACY
        val probe = FakeProbe()
        assertEquals(Outcome.ALREADY_DONE, migrate(store, ledger, probe))
        assertEquals(LEGACY, store.url)
        assertEquals(1, store.replacements)
        assertTrue(probe.healthChecks.isEmpty())
    }

    @Test
    fun keepsTheOldAddressWhenThePublicServerIsUnhealthyAndRetriesLater() {
        val store = FakeStore(LEGACY)
        val ledger = FakeLedger()
        val probe = FakeProbe(healthy = false)

        assertEquals(Outcome.PUBLIC_SERVER_UNHEALTHY, migrate(store, ledger, probe))
        assertEquals(LEGACY, store.url)
        assertTrue(probe.tokenChecks.isEmpty())
        assertFalse(ledger.completed())
        assertEquals(listOf(Outcome.PUBLIC_SERVER_UNHEALTHY), ledger.failures)

        // An unreachable server (exception) is treated the same.
        assertEquals(
            Outcome.PUBLIC_SERVER_UNHEALTHY,
            migrate(store, ledger, FakeProbe(healthError = java.net.UnknownHostException("thoughts.example.org"))),
        )
        assertEquals(LEGACY, store.url)

        // A later start, with the server back, moves it.
        assertEquals(Outcome.MIGRATED, migrate(store, ledger, FakeProbe()))
        assertEquals(PUBLIC, store.url)
    }

    @Test
    fun keepsTheOldAddressWhenThePublicServerRejectsTheToken() {
        val store = FakeStore(LEGACY)
        val ledger = FakeLedger()
        assertEquals(Outcome.TOKEN_REJECTED, migrate(store, ledger, FakeProbe(accepts = false)))
        assertEquals(
            Outcome.TOKEN_REJECTED,
            migrate(store, ledger, FakeProbe(tokenError = IllegalStateException("401 Unauthorized"))),
        )
        assertEquals(LEGACY, store.url)
        assertFalse(ledger.completed())
        assertEquals(listOf(Outcome.TOKEN_REJECTED, Outcome.TOKEN_REJECTED), ledger.failures)
    }

    @Test
    fun leavesOtherAddressesAndTestBuildsAlone() {
        for (address in listOf(
            "https://server.example.ts.net:8443",
            PUBLIC,
            "https://example.org",
            null,
        )) {
            val store = FakeStore(address)
            val ledger = FakeLedger()
            val probe = FakeProbe()
            assertEquals(Outcome.NOT_NEEDED, migrate(store, ledger, probe))
            assertEquals(address, store.url)
            assertTrue(probe.healthChecks.isEmpty())
            // Nothing is recorded, so a configuration restored later is still moved.
            assertFalse(ledger.completed())
        }

        val sandbox = FakeStore(LEGACY)
        val probe = FakeProbe()
        assertEquals(Outcome.NOT_NEEDED, migrate(sandbox, probe = probe, isTestEnvironment = true))
        assertEquals(LEGACY, sandbox.url)
        assertTrue(probe.healthChecks.isEmpty())
    }

    @Test
    fun needsASavedToken() {
        val store = FakeStore(LEGACY, token = null)
        val probe = FakeProbe()
        assertEquals(Outcome.NOT_NEEDED, migrate(store, probe = probe))
        assertEquals(LEGACY, store.url)
        assertTrue(probe.healthChecks.isEmpty())
    }

    @Test
    fun respectsAnAddressChangedDuringTheCheck() {
        val store = FakeStore(LEGACY)
        val ledger = FakeLedger()
        val probe = object : PublicServerMigration.Probe {
            override fun healthy(baseUrl: String): Boolean = true
            override fun acceptsToken(baseUrl: String, token: String): Boolean {
                store.url = "https://example.org" // Saved in Settings meanwhile.
                return true
            }
        }
        assertEquals(Outcome.CHANGED_CONCURRENTLY, PublicServerMigration.run(store, ledger, probe, false, HOSTS, PUBLIC))
        assertEquals("https://example.org", store.url)
        assertNull(ledger.completedTo)
    }

    private companion object {
        const val LEGACY_HOST = "server.example.ts.net"
        const val LEGACY = "https://$LEGACY_HOST"
        const val PUBLIC = "https://thoughts.example.org"
        val HOSTS = setOf(LEGACY_HOST)
        const val TOKEN = "device-token-123"
    }
}
