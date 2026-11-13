// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.share

import com.gitlab.mudlej.MjPdfReader.core.io.PdfPageSelection
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsServerAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ThoughtsCitationLinksTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val context = ThoughtsCitationContext(
        documentId = "f9b62a55-e56e-5135-aaad-faf149099d7f",
        versionSha256 = "a".repeat(64),
    )

    @Test
    fun fullPdfPromptUsesFirstPageOne() {
        assertEquals(
            "Instructions for ChatGPT\n\n" +
                "When referring to a page in the attached PDF, include:\n" +
                "[Open page N in Thoughts](https://thoughts.example.org/open/document/" +
                "f9b62a55-e56e-5135-aaad-faf149099d7f?page=N&first=1&version=${"a".repeat(64)})\n\n" +
                "Replace N with the cited one-based page number from the attached PDF.\n" +
                "Keep the document ID, first-page value, and version unchanged.",
            buildThoughtsCitationPrompt(context, PdfPageSelection.All, PUBLIC_HOST),
        )
    }

    @Test
    fun instructionFileNameIdentifiesDocumentMappingAndVersion() {
        assertEquals(
            "THOUGHTS_CITATION_INSTRUCTIONS_" +
                "f9b62a55-e56e-5135-aaad-faf149099d7f_5_aaaaaaaaaaaa.txt",
            citationInstructionFileName(context, PdfPageSelection.Range(4, 7)),
        )
    }

    @Test
    fun subsetPromptMapsSharedPagesToOriginalPages() {
        val prompt = buildThoughtsCitationPrompt(context, PdfPageSelection.Range(4, 7))
        val concreteLink = prompt.substringAfter('(').substringBeforeLast(')').replace("page=N", "page=2")

        assertEquals(6, parseThoughtsCitationLink(concreteLink)?.originalPageNumber)
    }

    @Test
    fun promptNamesTheConfiguredServerHost() {
        val prompt = buildThoughtsCitationPrompt(
            context,
            PdfPageSelection.All,
            ThoughtsServerAddress.citationLinkHost("https://server.example.ts.net", PUBLIC_HOST),
        )

        assertTrue(prompt.contains("(https://server.example.ts.net/open/document/${context.documentId}?"))
        assertTrue(
            buildThoughtsCitationPrompt(
                context,
                PdfPageSelection.All,
                ThoughtsServerAddress.citationLinkHost(null, PUBLIC_HOST),
            ).contains("(https://thoughts.example.org/open/document/${context.documentId}?"),
        )
        // Without a server, a build names its own public host.
        assertTrue(
            buildThoughtsCitationPrompt(context, PdfPageSelection.All)
                .contains("(https://${ThoughtsServerAddress.PUBLIC_HOST}/open/document/${context.documentId}?"),
        )
    }

    @Test
    fun publicAndLegacyHostLinksBothParse() {
        val query = "?page=2&first=3&version=${context.versionSha256}"

        for (host in listOf("thoughts.example.org", "server.example.ts.net", "Thoughts.Example.ORG")) {
            val link = parseThoughtsCitationLink("https://$host/open/document/${context.documentId}$query", linkHosts = HOSTS)
            assertEquals(host, ThoughtsCitationLink(context.documentId, 2, 3, context.versionSha256), link)
        }
    }

    @Test
    fun configuredHostLinksParseOnlyWhenConfigured() {
        val link = "https://my-server.example.net/open/document/${context.documentId}" +
            "?page=1&first=1&version=${context.versionSha256}"
        fun hosts(configured: String?) = ThoughtsServerAddress.citationLinkHosts(configured, PUBLIC_HOST, setOf(LEGACY_HOST))

        assertNull(parseThoughtsCitationLink(link, linkHosts = hosts(null)))
        assertEquals(1, parseThoughtsCitationLink(link, "https://my-server.example.net", hosts("https://my-server.example.net"))?.originalPageNumber)
        // The default hosts include the configured server's.
        assertEquals(1, parseThoughtsCitationLink(link, "https://my-server.example.net")?.originalPageNumber)
    }

    @Test
    fun customSchemeFallbackParses() {
        val link = "thoughts://open/document/${context.documentId}" +
            "?page=3&first=5&version=${context.versionSha256}"

        assertEquals(7, parseThoughtsCitationLink(link)?.originalPageNumber)
    }

    @Test
    fun parserRejectsUnexpectedHostsFieldsAndTraversal() {
        val query = "?page=1&first=1&version=${context.versionSha256}"

        assertNull(parseThoughtsCitationLink("https://example.com/open/document/${context.documentId}$query", linkHosts = HOSTS))
        assertNull(parseThoughtsCitationLink("http://thoughts.example.org/open/document/${context.documentId}$query", linkHosts = HOSTS))
        assertNull(parseThoughtsCitationLink("https://thoughts.example.org:8443/open/document/${context.documentId}$query", linkHosts = HOSTS))
        assertNull(parseThoughtsCitationLink("https://evil.thoughts.example.org/open/document/${context.documentId}$query", linkHosts = HOSTS))
        assertNull(parseThoughtsCitationLink("https://user@thoughts.example.org/open/document/${context.documentId}$query", linkHosts = HOSTS))
        assertNull(parseThoughtsCitationLink("https://server.example.ts.net/open/document/${context.documentId}$query&token=secret", linkHosts = HOSTS))
        assertNull(parseThoughtsCitationLink("https://server.example.ts.net/open/document/../${context.documentId}$query", linkHosts = HOSTS))
        assertNull(parseThoughtsCitationLink("https://server.example.ts.net/open/document/${context.documentId}?page=0&first=1&version=${context.versionSha256}", linkHosts = HOSTS))
    }

    @Test
    fun expiredCitationInstructionFilesArePurged() {
        val now = 2 * CitationInstructionExporter.CACHE_MAX_AGE_MILLIS
        val oldCitation = temporary.newFile(
            "THOUGHTS_CITATION_INSTRUCTIONS_old_1_aaaaaaaaaaaa.txt"
        ).apply { setLastModified(now - CitationInstructionExporter.CACHE_MAX_AGE_MILLIS - 1) }
        val currentCitation = temporary.newFile(
            "THOUGHTS_CITATION_INSTRUCTIONS_current_1_aaaaaaaaaaaa.txt"
        ).apply { setLastModified(now) }
        val unrelated = temporary.newFile("keep.txt").apply { setLastModified(0) }

        purgeExpiredCitationFiles(temporary.root, now)

        assertFalse(oldCitation.exists())
        assertTrue(currentCitation.exists())
        assertTrue(unrelated.exists())
    }

    private companion object {
        const val PUBLIC_HOST = "thoughts.example.org"
        const val LEGACY_HOST = "server.example.ts.net"
        val HOSTS = setOf(PUBLIC_HOST, LEGACY_HOST)
    }
}
