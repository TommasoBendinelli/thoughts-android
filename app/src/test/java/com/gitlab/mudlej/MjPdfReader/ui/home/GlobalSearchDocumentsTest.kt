// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import com.gitlab.mudlej.MjPdfReader.data.search.GlobalSearchEntry
import com.gitlab.mudlej.MjPdfReader.data.search.GlobalSearchIndex
import com.gitlab.mudlej.MjPdfReader.data.search.GlobalSearchItem
import com.gitlab.mudlej.MjPdfReader.data.search.GlobalSearchKind
import com.gitlab.mudlej.MjPdfReader.data.search.GlobalSearchQuery
import com.gitlab.mudlej.MjPdfReader.data.search.GlobalSearchRow
import com.gitlab.mudlej.MjPdfReader.data.search.JdbcGlobalSearchSql
import com.gitlab.mudlej.MjPdfReader.data.search.planGlobalSearchUpdate
import com.gitlab.mudlej.MjPdfReader.data.search.startsLikePdf
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager

/** The Home search over Project documents: names from the catalogue, text from the PDFs on the phone. */
class GlobalSearchDocumentsTest {
    private lateinit var root: File
    private lateinit var pdfs: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("global-search-documents").toFile()
        pdfs = File(root, "current-pdfs").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun fileRef(file: File) = "file://" + file.absolutePath

    private fun pdf(name: String) = File(pdfs, name).apply { writeText("%PDF-1.7\n% made-up test PDF\n") }

    private fun document(id: String, name: String, filename: String, startPage: Int = 1, pageCount: Int = 2) = JsonObject().apply {
        addProperty("document_id", id); addProperty("name", name); addProperty("filename", filename)
        addProperty("start_page", startPage); addProperty("page_count", pageCount); addProperty("sha256", "sha-$id")
    }

    private fun project(id: String, name: String, vararg documents: JsonObject) = JsonObject().apply {
        addProperty("id", id); addProperty("name", name)
        add("documents", JsonArray().apply { documents.forEach(::add) })
    }

    private fun projects(vararg projects: JsonObject) = JsonArray().apply { projects.forEach(::add) }

    private fun record(file: File, title: String = file.nameWithoutExtension, available: Boolean = true) = GlobalSearchRecord(
        ref = fileRef(file), path = file.absolutePath, title = title, fileName = file.name,
        libraryDisplayName = "", identity = "record-hash:${file.length()}:${file.lastModified()}", available = available,
    )

    private fun library(projects: JsonArray, records: List<GlobalSearchRecord> = emptyList()) =
        globalSearchLibrary(projects, records, pdfs, ::fileRef)

    @Test
    fun documentInsideAProjectMatchesByTitleUnderItsProject() {
        val file = pdf("hobbies-0a1b2c_music.pdf")
        val catalogue = projects(
            project("p-hobbies", "Hobbies", document("d-music", "Music", file.name), document("d-garden", "Gardening", pdf("hobbies-0a1b2c_garden.pdf").name)),
            // Found only through its Project's name, so it comes after a document named so.
            project("p-musicians", "Musicians", document("d-bach", "Bach biography", pdf("musicians-3d4e5f_bach.pdf").name)),
        )

        val named = library(catalogue).named(GlobalSearchQuery.parse("musi"))

        assertEquals(listOf("Music", "Bach biography"), named.map { it.title })
        assertEquals(listOf("Hobbies", "Musicians"), named.map { it.subtitle })
        assertEquals("d-music", named.first().projectEntry?.get("document_id")?.asString)
        assertEquals(listOf("Music"), library(catalogue).named(GlobalSearchQuery.parse("hobbies musi")).map { it.title })
    }

    @Test
    fun pagesOfAProjectDocumentAreIndexedAndFoundAsThatDocument() {
        val file = pdf("hobbies-0a1b2c_music.pdf")
        val searchLibrary = library(projects(project("p-hobbies", "Hobbies", document("d-music", "Music", file.name))))
        val indexed = searchLibrary.pdfs.single()
        assertEquals(fileRef(file), indexed.ref)

        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            val index = GlobalSearchIndex(JdbcGlobalSearchSql(connection)).also(GlobalSearchIndex::ensureSchema)
            val wanted = searchLibrary.pdfs.map { GlobalSearchItem(GlobalSearchIndex.PDF_PREFIX + it.ref, it.identity) }
            val plan = planGlobalSearchUpdate(wanted, index.items(), setOf(GlobalSearchIndex.PDF_PREFIX))
            assertEquals(wanted, plan.changed)
            index.replace(plan.changed.single(), listOf(
                GlobalSearchEntry(GlobalSearchKind.PAGE, indexed.ref, 1, "", "Scales and modes"),
                GlobalSearchEntry(GlobalSearchKind.PAGE, indexed.ref, 2, "", "Harmony in folk tunes"),
            ))
            // Incremental: the next update reads nothing while the PDF is unchanged.
            assertTrue(planGlobalSearchUpdate(wanted, index.items(), setOf(GlobalSearchIndex.PDF_PREFIX)).changed.isEmpty())

            val hit = index.search(GlobalSearchQuery.parse("harmon"), GlobalSearchKind.PAGE, 10).single()
            val found = searchLibrary.documentForPage(hit.ref, hit.position)
            assertEquals("Music", found?.title)
            assertEquals(2, hit.position)
        }
    }

    @Test
    fun projectReadingsAreNeverReadAsPdfs() {
        val reading = File(pdfs, "project_0123456789abcdef01234567.thoughts-project").apply {
            writeText("""{"schema_version":1,"kind":"thoughts-project-reading","documents":[]}""")
        }
        val file = pdf("hobbies-0a1b2c_music.pdf")
        // The reader opened the Project (its reading) and, once, one of its documents.
        val searchLibrary = library(
            projects(project("p-hobbies", "Hobbies", document("d-music", "Music", file.name))),
            listOf(record(reading, title = "Hobbies"), record(file)),
        )

        assertTrue(searchLibrary.pdfs.none { it.ref.endsWith(".thoughts-project") })
        assertTrue(searchLibrary.documents.none { it.record?.path == reading.absolutePath })
        // The document is listed once, under its Project, indexed as if it had never been opened.
        val music = searchLibrary.documents.single()
        assertEquals("Hobbies", music.subtitle)
        assertEquals(library(projects(project("p-hobbies", "Hobbies", document("d-music", "Music", file.name)))).pdfs, searchLibrary.pdfs)
        assertTrue(isProjectReading(reading.absolutePath))
        assertFalse(startsLikePdf(reading))
        assertTrue(startsLikePdf(file))
    }

    @Test
    fun aRecordUnderAnotherSpellingOfItsPathIsStillThatDocument() {
        val file = pdf("hobbies-0a1b2c_music.pdf")
        // Like Android's /data/data, a link to the directory of the Library's PDFs.
        val alias = Files.createSymbolicLink(File(root, "alias").toPath(), pdfs.toPath()).toFile()
        val opened = record(File(alias, file.name))
        val searchLibrary = library(projects(project("p-hobbies", "Hobbies", document("d-music", "Music", file.name))), listOf(opened))

        assertEquals(listOf("Music"), searchLibrary.documents.map { it.title })
        assertEquals(fileRef(file), searchLibrary.pdfs.single().ref)
    }

    @Test
    fun projectDocumentNotOnThePhoneStillMatchesByTitle() {
        val missing = document("d-music", "Music", "hobbies-0a1b2c_music.pdf")
        val waiting = document("d-songs", "Songbook", "hobbies-0a1b2c_songs.pdf", startPage = 0, pageCount = 0)
        pdf(waiting.get("filename").asString)
        val searchLibrary = library(projects(project("p-hobbies", "Hobbies", missing, waiting)))

        val music = searchLibrary.named(GlobalSearchQuery.parse("musi")).single()
        assertEquals("Music", music.title)
        assertNull(music.pdf)
        // Opening it goes where the Library goes: that PDF, which Sync downloads.
        assertEquals(File(pdfs, "hobbies-0a1b2c_music.pdf"), libraryProjectDocumentFile(music.projectEntry!!, pdfs))
        // A document waiting for its first build is found too, and not read.
        assertNull(searchLibrary.named(GlobalSearchQuery.parse("songbook")).single().pdf)
        assertTrue(searchLibrary.pdfs.isEmpty())
    }

    @Test
    fun pdfsOutsideProjectsAreSearchedAsBefore() {
        val loose = pdf("search-basics_0f0e0d0c0b0a.pdf")
        val gone = File(root, "Downloads/old-notes.pdf")
        val searchLibrary = library(JsonArray(), listOf(record(loose, title = "Search basics"), record(gone, available = false)))

        val found = searchLibrary.named(GlobalSearchQuery.parse("search"))
        assertEquals(listOf("Search basics"), found.map { it.title })
        assertEquals(loose.name, found.single().subtitle)
        assertEquals(listOf(fileRef(loose)), searchLibrary.pdfs.map { it.ref })
        assertNotNull(searchLibrary.named(GlobalSearchQuery.parse("old notes")).single().record)
    }

    @Test
    fun textHitsAreGroupedByDocumentInAStableOrder() {
        val music = pdf("hobbies-0a1b2c_music.pdf")
        val garden = pdf("hobbies-0a1b2c_garden.pdf")
        val travel = pdf("hobbies-0a1b2c_travel.pdf")
        val searchLibrary = library(projects(project("p-hobbies", "Hobbies",
            document("d-music", "Music", music.name), document("d-garden", "Gardening", garden.name),
            document("d-travel", "Travel", travel.name))))
        val removed = File(root, "removed-from-the-library.pdf")
        fun hit(file: File, page: Int) = GlobalSearchRow(GlobalSearchKind.PAGE, fileRef(file), page, "", "music")
        val query = GlobalSearchQuery.parse("musi")

        // Whatever order the index returns them in: the document named so first, then most pages, then by title.
        for (hits in listOf(
            listOf(hit(travel, 4), hit(garden, 1), hit(music, 2), hit(travel, 1), hit(removed, 1)),
            listOf(hit(music, 2), hit(travel, 1), hit(garden, 1), hit(travel, 4)),
        )) {
            val grouped = searchLibrary.pagesByDocument(query, hits)
            assertEquals(listOf("Music", "Travel", "Gardening"), grouped.map { it.first.title })
            assertEquals(listOf(1, 4), grouped[1].second.map { it.position })
        }
    }

    @Test
    fun aCombinedProjectPdfSendsEachPageToItsDocument() {
        val combined = pdf("handbook.pdf")
        val searchLibrary = library(projects(project("p-handbook", "Handbook",
            document("d-intro", "Introduction", combined.name, startPage = 1, pageCount = 3),
            document("d-tools", "Tools", combined.name, startPage = 4, pageCount = 5),
        )))

        assertEquals(1, searchLibrary.pdfs.size)
        assertEquals("Introduction", searchLibrary.documentForPage(fileRef(combined), 3)?.title)
        assertEquals("Tools", searchLibrary.documentForPage(fileRef(combined), 4)?.title)
        assertNull(searchLibrary.documentForPage("file:///elsewhere.pdf", 1))
    }
}
