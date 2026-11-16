// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Firebase starts only when the app sets it up: no start-up provider, no automatic token, one private
 * service, all in the manifest only a build with push has (app/build.gradle, thoughtsPush).
 */
class PushManifestTest {
    private val android = "http://schemas.android.com/apk/res/android"
    private val tools = "http://schemas.android.com/tools"

    private fun parse(path: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(File(path))

    private val manifest = parse("src/push/AndroidManifest.xml")

    private fun elements(tag: String, document: org.w3c.dom.Document = manifest) = document.getElementsByTagName(tag).let { nodes ->
        (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.androidName() = getAttributeNS(android, "name")

    @Test fun firebaseIsNeverInitializedOrGivenATokenByItself() {
        val provider = elements("provider").single { it.androidName() == "com.google.firebase.provider.FirebaseInitProvider" }
        assertEquals("remove", provider.getAttributeNS(tools, "node"))
        val autoInit = elements("meta-data").single { it.androidName() == "firebase_messaging_auto_init_enabled" }
        assertEquals("false", autoInit.getAttributeNS(android, "value"))
    }

    @Test fun thePushServiceIsPrivateAndReceivesMessagingEvents() {
        val service = elements("service").single { it.androidName() == ".data.agentnotes.ThoughtsPushService" }
        assertEquals("false", service.getAttributeNS(android, "exported"))
        val actions = service.getElementsByTagName("action")
        assertTrue((0 until actions.length).any {
            (actions.item(it) as Element).getAttributeNS(android, "name") == "com.google.firebase.MESSAGING_EVENT"
        })
    }

    @Test fun aBuildWithoutPushHasNoFirebaseInItsManifest() {
        // Every element and attribute of the main manifest (its comments may name the push manifest).
        val main = parse("src/main/AndroidManifest.xml")
        val nodes = elements("*", main).flatMap { element ->
            listOf(element.tagName) + (0 until element.attributes.length).map { element.attributes.item(it).nodeValue }
        }
        assertTrue(nodes.size > 100)
        val pushOnly = nodes.filter { node ->
            listOf("firebase", "ThoughtsPushService", "com.google.android.gms").any { node.contains(it, ignoreCase = true) }
        }
        assertEquals(emptyList<String>(), pushOnly)
        // The two PushProvider versions: only src/push names Google's libraries.
        val provider = "java/com/gitlab/mudlej/MjPdfReader/data/agentnotes/PushProvider.kt"
        assertTrue(File("src/push/$provider").readText().contains("import com.google.firebase."))
        assertTrue(listOf("com.google.firebase", "com.google.android.gms").none { File("src/nopush/$provider").readText().contains(it) })
    }

    @Test fun thePushRegistrationStaysOnThisPhone() {
        for (rules in listOf("src/main/res/xml/backup_rules.xml", "src/main/res/xml/data_extraction_rules.xml")) {
            assertTrue(rules, File(rules).readText().contains("""path="agent_notes_push.xml""""))
        }
    }
}
