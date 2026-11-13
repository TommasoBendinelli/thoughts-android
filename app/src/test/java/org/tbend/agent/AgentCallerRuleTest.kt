// Written for Thoughts. License is GPLv3.

package org.tbend.agent

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tbend.agent.AgentCallerRule.CallerPackage
import org.tbend.agent.AgentCallerRule.Decision
import org.w3c.dom.Element

class AgentCallerRuleTest {
    private val ownUid = 10_123
    private val otherUid = 10_456
    private val certificate = "a".repeat(64)
    private val otherCertificate = "b".repeat(64)

    private fun decide(
        uid: Int,
        sameSignature: Boolean = false,
        packages: List<CallerPackage> = emptyList(),
        allowed: Map<String, String?> = emptyMap(),
    ) = AgentCallerRule.decide(uid, ownUid, { sameSignature }, { packages }, allowed)

    @Test
    fun adbAndTheAppItselfAreAllowedWithoutLookingAnythingUp() {
        for (uid in listOf(AgentCallerRule.ROOT_UID, AgentCallerRule.SHELL_UID, ownUid)) {
            assertEquals(
                Decision.Allow,
                AgentCallerRule.decide(uid, ownUid, { error("not asked") }, { error("not asked") }, emptyMap()),
            )
        }
    }

    @Test
    fun anOrdinaryInstalledAppIsRefused() {
        val refused = decide(otherUid, packages = listOf(CallerPackage("com.example.other", listOf(certificate))))
        assertTrue(refused is Decision.Deny)
        assertTrue((refused as Decision.Deny).reason.contains("allow this app from adb"))
        // A caller without a uid is never anybody.
        assertTrue(decide(-1, sameSignature = true) is Decision.Deny)
    }

    @Test
    fun anAppSignedWithTheSameKeyIsAllowed() {
        assertEquals(Decision.Allow, decide(otherUid, sameSignature = true))
    }

    @Test
    fun anAllowedPackageIsHeldToTheCertificateItWasAllowedWith() {
        val allowed = mapOf("com.example.terminal" to certificate)
        assertEquals(
            Decision.Allow,
            decide(otherUid, packages = listOf(CallerPackage("com.example.terminal", listOf(certificate))), allowed = allowed),
        )
        // The same name signed by somebody else (the allowed app was removed and another took its name).
        val impostor = decide(otherUid, packages = listOf(CallerPackage("com.example.terminal", listOf(otherCertificate))), allowed = allowed)
        assertTrue(impostor is Decision.Deny)
        assertTrue((impostor as Decision.Deny).reason.contains("certificate"))
        // An update that rotated the key still proves the allowed certificate.
        assertEquals(
            Decision.Allow,
            decide(otherUid, packages = listOf(CallerPackage("com.example.terminal", listOf(otherCertificate, certificate))), allowed = allowed),
        )
        // A package that cannot be read has no certificate and is not allowed.
        assertTrue(decide(otherUid, packages = listOf(CallerPackage("com.example.terminal", emptyList())), allowed = allowed) is Decision.Deny)
    }

    @Test
    fun anEntryWithoutACertificateIsPinnedAtItsFirstCall() {
        // Allowed by an earlier version (names only), or before the app was installed.
        val allowed = mapOf<String, String?>("com.example.terminal" to null)
        assertEquals(
            Decision.AllowAndPin("com.example.terminal", certificate),
            decide(otherUid, packages = listOf(CallerPackage("com.example.terminal", listOf(certificate, otherCertificate))), allowed = allowed),
        )
        assertTrue(decide(otherUid, packages = listOf(CallerPackage("com.example.terminal", emptyList())), allowed = allowed) is Decision.Deny)
        assertTrue(decide(otherUid, packages = listOf(CallerPackage("com.example.other", listOf(certificate))), allowed = allowed) is Decision.Deny)
    }

    @Test
    fun aStartIsAcceptedFromANamedAllowedCallerOrThroughThePermissionGuardedEntry() {
        val rule = { uid: Int -> decide(uid) }
        // adb: Android does not name the caller; the manifest permission of AgentActivity has let it through.
        assertEquals(Decision.Allow, AgentCallerRule.decideStart(null, permissionGuardedEntry = true, rule))
        // The alias for apps has no permission: an unnamed caller runs nothing.
        assertTrue(AgentCallerRule.decideStart(null, permissionGuardedEntry = false, rule) is Decision.Deny)
        // A named caller is held to the caller rule at either entry.
        for (guarded in listOf(true, false)) {
            assertEquals(Decision.Allow, AgentCallerRule.decideStart(ownUid, guarded, rule))
            assertEquals(Decision.Allow, AgentCallerRule.decideStart(AgentCallerRule.SHELL_UID, guarded, rule))
            assertTrue(AgentCallerRule.decideStart(otherUid, guarded, rule) is Decision.Deny)
        }
    }

    @Test
    fun theStoredListKeepsNamesAndPins() {
        val stored = setOf("com.example.terminal=$certificate", "com.example.legacy", "com.example.bad=nothex", "not a package=$certificate")
        val allowed = AgentCallerRule.parseAllowed(stored)
        assertEquals(mapOf("com.example.terminal" to certificate, "com.example.legacy" to null, "com.example.bad" to null), allowed)
        assertEquals(
            setOf("com.example.terminal=$certificate", "com.example.legacy", "com.example.bad"),
            AgentCallerRule.formatAllowed(allowed),
        )
        assertEquals(certificate, AgentCallerRule.normalizeCertificateDigest(" " + "AA:".repeat(31) + "AA "))
        assertNull(AgentCallerRule.normalizeCertificateDigest("aa"))
        assertTrue(AgentCallerRule.isPackageName("com.example.terminal"))
        assertFalse(AgentCallerRule.isPackageName("terminal"))
        assertFalse(AgentCallerRule.isPackageName("com.example.terminal=x"))
        assertFalse(AgentCallerRule.isPackageName(""))
    }

    /** The manifest is half of the rule: what Android checks before any of this code runs. */
    @Test
    fun theManifestGuardsTheEntriesWhoseCallerAndroidDoesNotName() {
        val android = "http://schemas.android.com/apk/res/android"
        val manifest = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml")).documentElement
        fun component(tag: String, name: String): Element {
            val nodes = manifest.getElementsByTagName(tag)
            return (0 until nodes.length).map { nodes.item(it) as Element }.single { it.getAttributeNS(android, "name") == name }
        }
        val shellOnly = "android.permission.DUMP"
        assertEquals(shellOnly, component("receiver", ".data.sync.SyncCommandReceiver").getAttributeNS(android, "permission"))
        assertEquals(shellOnly, component("activity", "org.tbend.agent.AgentActivity").getAttributeNS(android, "permission"))
        val alias = component("activity-alias", "org.tbend.agent.AgentAppActivity")
        assertEquals("org.tbend.agent.AgentActivity", alias.getAttributeNS(android, "targetActivity"))
        assertEquals("", alias.getAttributeNS(android, "permission"))
        // The app never asks for the permission itself, and no installed app can be granted it by the user.
        val requested = manifest.getElementsByTagName("uses-permission")
        assertTrue((0 until requested.length).none { (requested.item(it) as Element).getAttributeNS(android, "name") == shellOnly })
    }
}
