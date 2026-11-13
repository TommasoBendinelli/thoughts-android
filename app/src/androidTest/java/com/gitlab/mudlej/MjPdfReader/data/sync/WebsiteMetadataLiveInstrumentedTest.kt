// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.data.importing.PaperWebsite
import com.gitlab.mudlej.MjPdfReader.data.importing.PreparedPaperImport
import org.junit.Assert.*
import org.junit.Test

/** Explicitly selected live-network smoke test; never imports into the user's Library. */
class WebsiteMetadataLiveInstrumentedTest {
    @Test fun proceedingsHaveTitlesAndReadablePdfs() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        listOf(
            Triple("https://proceedings.mlr.press/v235/zheng24e.html", "PMLR",
                "GPT-4V(ision) is a Generalist Web Agent, if Grounded"),
            Triple("https://proceedings.neurips.cc/paper_files/paper/2023/hash/c9450295fd667740a39a68148fc17f6e-Abstract-Conference.html",
                "NeurIPS Proceedings", "Concept Distillation: Leveraging Human-Centered Explanations for Model Improvement"),
            Triple("https://papers.nips.cc/paper/2017/file/3f5ee243547dee91fbd053c1c4a845aa-Paper.pdf",
                "NeurIPS Proceedings", "Attention is All you Need"),
            Triple("https://proceedings.iclr.cc/paper_files/paper/2026/hash/93f3ac5bb6d24ed7cec95ae11209ca34-Abstract-Conference.html",
                "ICLR Proceedings", "Off-Trajectory Reasoning: Can LLMs Collaborate on Reasoning Trajectories?"),
            // Exact regression: the direct ICLR 2026 conference PDF link.
            Triple("https://proceedings.iclr.cc/paper_files/paper/2026/file/93f3ac5bb6d24ed7cec95ae11209ca34-Paper-Conference.pdf",
                "ICLR Proceedings", "Off-Trajectory Reasoning: Can LLMs Collaborate on Reasoning Trajectories?"),
        ).forEach { (url, site, title) ->
            val prepared = PreparedPaperImport.prepare(context, PaperWebsite.resolve(url))
            prepared.use {
                assertEquals(title.lowercase(), it.title?.lowercase())
                assertEquals(site, it.paper.websiteType)
                assertTrue(it.pageCount > 0)
                assertTrue(it.sizeBytes > 1000)
                android.util.Log.i("WebsiteMetadataLiveTest", "site=$site; title=${it.title}; pages=${it.pageCount}; bytes=${it.sizeBytes}")
            }
            assertFalse(prepared.file.exists())
        }
    }

    @Test fun critiqueGrpoHasItsRealTitleAndReadablePdfBeforeImport() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prepared = PreparedPaperImport.prepare(context, PaperWebsite.resolve("https://arxiv.org/abs/2506.03106"))
        prepared.use {
            assertEquals("Critique-GRPO: Advancing LLM Reasoning with Natural Language and Numerical Feedback", it.title)
            assertEquals("arXiv", it.paper.websiteType)
            assertTrue(it.pageCount > 0)
            assertEquals(it.file.length(), it.sizeBytes)
            assertTrue(it.sizeBytes > 1000)
            android.util.Log.i("WebsiteMetadataLiveTest", "title=${it.title}; pages=${it.pageCount}; bytes=${it.sizeBytes}")
        }
        assertFalse(prepared.file.exists())
    }
}
