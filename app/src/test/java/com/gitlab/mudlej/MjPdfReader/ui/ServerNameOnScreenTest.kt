// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The app talks to "the server": the machine it happens to run on is never named on screen.
 * Resource names and view ids may keep their old names; only the texts are checked.
 */
class ServerNameOnScreenTest {
    private val resources = File("src/main/res")

    private fun xmlFiles(prefix: String): List<File> = resources.listFiles().orEmpty()
        .filter { it.isDirectory && (it.name == prefix || it.name.startsWith("$prefix-")) }
        .flatMap { it.listFiles().orEmpty().filter { file -> file.extension == "xml" } }

    private fun mentionsHost(text: String) = text.contains("fedora", ignoreCase = true)

    @Test fun noStringResourceNamesTheServerMachine() {
        val offending = mutableListOf<String>()
        var checked = 0
        for (file in xmlFiles("values")) {
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            for (tag in listOf("string", "item")) {
                val nodes = document.getElementsByTagName(tag)
                for (index in 0 until nodes.length) {
                    val element = nodes.item(index) as Element
                    checked++
                    if (mentionsHost(element.textContent)) {
                        offending += "${file.parentFile.name}/${file.name}: ${element.getAttribute("name")}"
                    }
                }
            }
        }
        assertTrue("no strings were read", checked > 1000)
        assertEquals(emptyList<String>(), offending)
    }

    @Test fun noLayoutMenuOrPreferenceTextNamesTheServerMachine() {
        val shown = setOf("text", "hint", "title", "summary", "contentDescription", "tooltipText", "dialogTitle", "dialogMessage")
        val offending = mutableListOf<String>()
        for (file in xmlFiles("layout") + xmlFiles("menu") + xmlFiles("xml")) {
            val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(file)
            val nodes = document.getElementsByTagName("*")
            for (index in 0 until nodes.length) {
                val attributes = (nodes.item(index) as Element).attributes
                for (position in 0 until attributes.length) {
                    val attribute = attributes.item(position)
                    if (attribute.namespaceURI == "http://schemas.android.com/apk/res/android" &&
                        attribute.localName in shown && !attribute.nodeValue.startsWith("@") &&
                        mentionsHost(attribute.nodeValue)
                    ) {
                        // A reference (@string/…) names a resource; its text is checked above.
                        offending += "${file.parentFile.name}/${file.name}: ${attribute.localName}=${attribute.nodeValue}"
                    }
                }
            }
        }
        assertEquals(emptyList<String>(), offending)
    }
}
