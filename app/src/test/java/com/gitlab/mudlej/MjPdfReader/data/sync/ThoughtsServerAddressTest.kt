// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ThoughtsServerAddressTest {

    @Test
    fun acceptsAnyHttpsAddressWithAHost() {
        assertEquals("https://thoughts.example.org", ThoughtsServerAddress.normalize(" https://thoughts.example.org/ "))
        assertEquals("https://server.example.ts.net", ThoughtsServerAddress.normalize("https://server.example.ts.net"))
        assertEquals("https://server.example.ts.net:8443", ThoughtsServerAddress.normalize("https://server.example.ts.net:8443//"))
        assertEquals("https://example.org/thoughts", ThoughtsServerAddress.normalize("https://example.org/thoughts/"))
        assertEquals(ThoughtsServerAddress.PUBLIC_BASE_URL, ThoughtsServerAddress.normalize(ThoughtsServerAddress.PUBLIC_BASE_URL + "/"))
    }

    @Test
    fun rejectsOtherSchemesMissingHostsUserInfoQueriesAndFragments() {
        for (address in listOf(
            "",
            "thoughts.example.org",
            "http://thoughts.example.org",
            "HTTPS://thoughts.example.org",
            "ftp://thoughts.example.org",
            "https://",
            "https:///path",
            "https:thoughts.example.org",
            "https://user@thoughts.example.org",
            "https://user:secret@thoughts.example.org",
            "https://thoughts.example.org?token=x",
            "https://thoughts.example.org/?",
            "https://thoughts.example.org#top",
            "https://thoughts example.org",
        )) {
            assertThrows(address, IllegalArgumentException::class.java) {
                ThoughtsServerAddress.normalize(address)
            }
        }
    }

    @Test
    fun dialogStartsBlankOnANewInstallAndKeepsASavedAddress() {
        assertEquals("", ThoughtsServerAddress.dialogPrefill(null))
        assertEquals("", ThoughtsServerAddress.dialogPrefill("  "))
        assertEquals("https://thoughts.example.org", ThoughtsServerAddress.dialogPrefill("https://thoughts.example.org"))
        assertEquals("https://sam.example.net/thoughts", ThoughtsServerAddress.dialogPrefill("https://sam.example.net/thoughts"))
        // The placeholder is a valid address shape but not anybody's server.
        assertEquals(ThoughtsServerAddress.EXAMPLE_BASE_URL, ThoughtsServerAddress.normalize(ThoughtsServerAddress.EXAMPLE_BASE_URL))
        assertFalse(ThoughtsServerAddress.requiresVpn(ThoughtsServerAddress.EXAMPLE_BASE_URL))
    }

    @Test
    fun onlyTailscaleHostsRequireTheVpn() {
        assertTrue(ThoughtsServerAddress.requiresVpn("https://server.example.ts.net"))
        assertTrue(ThoughtsServerAddress.requiresVpn("https://Server.Example.TS.NET:8443"))
        assertFalse(ThoughtsServerAddress.requiresVpn("https://thoughts.example.org"))
        assertFalse(ThoughtsServerAddress.requiresVpn("https://ts.net.example.org"))
        assertFalse(ThoughtsServerAddress.requiresVpn("https://examplets.net"))
        assertFalse(ThoughtsServerAddress.requiresVpn(null))
        assertFalse(ThoughtsServerAddress.requiresVpn("not a url"))
    }

    @Test
    fun addressesInTailscalesOwnRangesRequireTheVpnToo() {
        assertTrue(ThoughtsServerAddress.requiresVpn("http://100.64.0.1:8798"))
        assertTrue(ThoughtsServerAddress.requiresVpn("https://100.127.255.254"))
        assertTrue(ThoughtsServerAddress.requiresVpn("http://[fd7a:115c:a1e0:ab12::1]:8798"))
        // The neighbouring ranges, private networks and names that only look like addresses do not.
        assertFalse(ThoughtsServerAddress.requiresVpn("http://100.63.255.255"))
        assertFalse(ThoughtsServerAddress.requiresVpn("http://100.128.0.1"))
        assertFalse(ThoughtsServerAddress.requiresVpn("http://192.168.1.20"))
        assertFalse(ThoughtsServerAddress.requiresVpn("http://[fd7a:115c:a1e1::1]"))
        assertFalse(ThoughtsServerAddress.requiresVpn("http://100.64.0.1.example.org"))
    }

    @Test
    fun loopbackHostsAreRecognizedWithoutALookup() {
        for (host in listOf("localhost", "LOCALHOST.", "app.localhost", "127.0.0.1", "127.255.255.254", "[::1]", "::1", "[0:0:0:0:0:0:0:1]")) {
            assertTrue(host, ThoughtsServerAddress.isLoopbackHost(host))
        }
        for (host in listOf(null, "", "localhost.example.org", "127.0.0.1.example.org", "128.0.0.1", "127.0.0", "127.0.0.256",
            "[::2]", "[::]", "thoughts.example.org", "0x7f.0.0.1")) {
            assertFalse(host.toString(), ThoughtsServerAddress.isLoopbackHost(host))
        }
    }

    @Test
    fun vpnPrecheckBlocksOnlyTailscaleServersWithoutVpn() {
        val tailscale = "https://server.example.ts.net"
        val public = "https://thoughts.example.org"

        assertFalse(ThoughtsServerAddress.vpnPrecheckPasses(tailscale) { false })
        assertTrue(ThoughtsServerAddress.vpnPrecheckPasses(tailscale) { true })
        assertTrue(ThoughtsServerAddress.vpnPrecheckPasses(public) { false })
        assertTrue(ThoughtsServerAddress.vpnPrecheckPasses(public) { error("the VPN must not be checked") })
        assertTrue(ThoughtsServerAddress.vpnPrecheckPasses(null) { false })
    }

    @Test
    fun connectionFailuresMentionTailscaleOnlyForTailscaleServers() {
        val failure = UnknownHostException("thoughts.example.org")

        assertEquals(
            SyncOperationStore.USER_MESSAGE_CANT_CONNECT,
            userFacingSyncFailureMessage(
                failure,
                ThoughtsServerAddress.vpnPrecheckPasses("https://thoughts.example.org") { false },
            ),
        )
        assertEquals(
            SyncOperationStore.USER_MESSAGE_TAILSCALE_OFF,
            userFacingSyncFailureMessage(
                failure,
                ThoughtsServerAddress.vpnPrecheckPasses("https://server.example.ts.net") { false },
            ),
        )
        assertEquals(
            SyncOperationStore.USER_MESSAGE_INTERRUPTED,
            interruptedSyncFailureMessage(
                ThoughtsServerAddress.vpnPrecheckPasses("https://thoughts.example.org") { false },
            ),
        )
        assertFalse(SyncOperationStore.USER_MESSAGE_CANT_CONNECT.contains("Tailscale"))
    }

    @Test
    fun citationHostsFollowTheConfiguredServer() {
        assertEquals(PUBLIC_HOST, ThoughtsServerAddress.citationLinkHost(null, PUBLIC_HOST))
        assertEquals(PUBLIC_HOST, ThoughtsServerAddress.citationLinkHost("https://Thoughts.Example.org/", PUBLIC_HOST))
        assertEquals(LEGACY_HOST, ThoughtsServerAddress.citationLinkHost("https://server.example.ts.net", PUBLIC_HOST))
        assertEquals(
            setOf(PUBLIC_HOST, LEGACY_HOST),
            ThoughtsServerAddress.citationLinkHosts(null, PUBLIC_HOST, setOf(LEGACY_HOST)),
        )
        assertEquals(
            setOf(PUBLIC_HOST, LEGACY_HOST, "my-server.example.net"),
            ThoughtsServerAddress.citationLinkHosts("https://My-Server.Example.net/", PUBLIC_HOST, setOf(LEGACY_HOST)),
        )
        // A build without legacy hosts opens links of its public host and of the configured server only.
        assertEquals(setOf(PUBLIC_HOST), ThoughtsServerAddress.citationLinkHosts(null, PUBLIC_HOST, emptySet()))
    }

    @Test
    fun theBuildsOwnHostsFollowItsOptions() {
        // Whatever the build options say, the public host is that of the public URL and is always a link host.
        assertEquals(ThoughtsServerAddress.hostOf(ThoughtsServerAddress.PUBLIC_BASE_URL), ThoughtsServerAddress.PUBLIC_HOST)
        assertEquals(ThoughtsServerAddress.PUBLIC_HOST, ThoughtsServerAddress.citationLinkHost(null))
        assertTrue(
            ThoughtsServerAddress.citationLinkHosts()
                .containsAll(ThoughtsServerAddress.LEGACY_HOSTS + ThoughtsServerAddress.PUBLIC_HOST),
        )
    }

    @Test
    fun legacyHostListsAreTrimmedAndLowerCased() {
        assertEquals(emptySet<String>(), ThoughtsServerAddress.parseHosts(""))
        assertEquals(emptySet<String>(), ThoughtsServerAddress.parseHosts(null))
        assertEquals(
            setOf("server.example.ts.net", "old.example.org"),
            ThoughtsServerAddress.parseHosts(" Server.Example.TS.NET., old.example.org ,,"),
        )
    }

    @Test
    fun aBlankTokenInTheServerDialogKeepsTheSavedToken() {
        // Changing only the address: the field is left blank.
        assertEquals("saved-token", ThoughtsServerAddress.tokenToSave("", "saved-token"))
        assertEquals("saved-token", ThoughtsServerAddress.tokenToSave("   ", "saved-token"))
        assertEquals("saved-token", ThoughtsServerAddress.tokenToSave(null, "saved-token"))
        // A typed token replaces it.
        assertEquals("new-token", ThoughtsServerAddress.tokenToSave(" new-token ", "saved-token"))
        // Nothing saved and nothing typed: blank, which the secret store rejects.
        assertEquals("", ThoughtsServerAddress.tokenToSave("", null))
        assertEquals("first-token", ThoughtsServerAddress.tokenToSave("first-token", null))
    }

    @Test
    fun theOriginIgnoresPathsTrailingSlashesCaseAndDefaultPorts() {
        val origin = "https://thoughts.example.org:443"
        for (address in listOf(
            "https://thoughts.example.org",
            "https://thoughts.example.org/",
            " https://Thoughts.Example.org:443/ ",
            "HTTPS://thoughts.example.org./thoughts",
            "https://thoughts.example.org/another/path",
        )) {
            assertEquals(address, origin, ThoughtsServerAddress.originOf(address))
        }
        assertEquals("https://thoughts.example.org:8443", ThoughtsServerAddress.originOf("https://thoughts.example.org:8443"))
        assertEquals("http://10.0.2.2:8080", ThoughtsServerAddress.originOf("http://10.0.2.2:8080"))
        assertEquals("http://localhost:80", ThoughtsServerAddress.originOf("http://localhost"))
        for (address in listOf(null, "", "thoughts.example.org", "https://", "https:///path", "ftp://thoughts.example.org", "not a url")) {
            assertEquals(address.toString(), null, ThoughtsServerAddress.originOf(address))
        }
    }

    @Test
    fun aBlankTokenKeepsTheSavedTokenOnlyOnTheServerItWasEnteredFor() {
        val saved = "https://thoughts.example.org"
        fun kept(entered: String) = ThoughtsServerAddress.tokenForAddress("  ", "saved-token", saved, entered)
        // The same server: unchanged, a trailing slash, the default port, another path.
        assertEquals("saved-token", kept("https://thoughts.example.org"))
        assertEquals("saved-token", kept("https://thoughts.example.org/"))
        assertEquals("saved-token", kept("https://thoughts.example.org:443"))
        assertEquals("saved-token", kept("https://THOUGHTS.example.org/thoughts"))
        // Another host, port or scheme is another server: the saved token is not used for it.
        assertEquals(null, kept("https://server.example.ts.net"))
        assertEquals(null, kept("https://thoughts.example.org:8443"))
        assertEquals(null, kept("https://thoughts.example.org.attacker.example"))
        assertEquals(null, kept("https://thoughts.example.org@attacker.example"))
        assertEquals(null, kept("http://thoughts.example.org"))
        assertEquals(null, kept("not a url"))
        // A typed token is the one saved, for any address.
        assertEquals("new-token", ThoughtsServerAddress.tokenForAddress(" new-token ", "saved-token", saved, "https://server.example.ts.net"))
        assertEquals("new-token", ThoughtsServerAddress.tokenForAddress("new-token", "saved-token", saved, saved))
        assertEquals("first-token", ThoughtsServerAddress.tokenForAddress("first-token", null, null, saved))
        // Nothing saved and nothing typed: asked for.
        assertEquals(null, ThoughtsServerAddress.tokenForAddress("", null, null, saved))
        assertEquals(null, ThoughtsServerAddress.tokenForAddress(null, "saved-token", null, saved))
    }

    @Test
    fun aStoredTokenIsUsedOnlyForTheAddressItIsBoundTo() {
        val address = "https://thoughts.example.org"
        // An installation from before tokens were bound: the token belongs to the address saved with it.
        assertEquals(ThoughtsServerAddress.StoredToken.BIND_TO_SAVED_ADDRESS, ThoughtsServerAddress.storedTokenUse(address, null))
        assertEquals(
            ThoughtsServerAddress.StoredToken.USE,
            ThoughtsServerAddress.storedTokenUse(address, ThoughtsServerAddress.originOf(address)),
        )
        assertEquals(
            ThoughtsServerAddress.StoredToken.USE,
            ThoughtsServerAddress.storedTokenUse("https://thoughts.example.org:443/thoughts/", ThoughtsServerAddress.originOf(address)),
        )
        // The address was changed under the token: it is not sent to the other server.
        assertEquals(
            ThoughtsServerAddress.StoredToken.FORGET,
            ThoughtsServerAddress.storedTokenUse("https://server.example.ts.net", ThoughtsServerAddress.originOf(address)),
        )
        assertEquals(
            ThoughtsServerAddress.StoredToken.FORGET,
            ThoughtsServerAddress.storedTokenUse("https://thoughts.example.org:8443", ThoughtsServerAddress.originOf(address)),
        )
    }

    private companion object {
        const val PUBLIC_HOST = "thoughts.example.org"
        const val LEGACY_HOST = "server.example.ts.net"
    }
}
