// Written for MJ PDF. License is GPLv3.

package com.shockwave.pdfium;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.RectF;
import android.os.ParcelFileDescriptor;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@RunWith(AndroidJUnit4.class)
public class PdfCommentNativeInstrumentedTest {

    @Test
    public void emptyMultilineStrikethroughSurvivesExportAndReopen() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PdfiumCore core = new PdfiumCore(context);
        PdfDocument doc = core.newDocument(createCommentFixture());
        File file = new File(context.getCacheDir(), "strike-roundtrip.pdf");
        List<RectF> rects = Arrays.asList(new RectF(100, 650, 300, 630), new RectF(100, 625, 230, 605));
        try {
            core.openPage(doc, 0);
            assertTrue(core.createPdfMarkupComment(doc, 0, rects, 0xFFD32F2F,
                    "strike-empty", "", "Alex", "", "", PdfDocument.PdfComment.SUBTYPE_STRIKEOUT));
            assertNotNull(findByName(core.getPdfComments(doc, 0), "strike-empty"));
            save(core, doc, file);
        } finally { core.closeDocument(doc); }
        PdfDocument reopened = open(core, file);
        try {
            core.openPage(reopened, 0);
            PdfDocument.PdfComment strike = findByName(core.getPdfComments(reopened, 0), "strike-empty");
            assertNotNull(strike);
            assertEquals(PdfDocument.PdfComment.SUBTYPE_STRIKEOUT, strike.getSubtype());
            assertEquals("", strike.getContents());
            assertEquals(rects, strike.getMarkupRects());
            assertTrue(core.setPdfCommentContents(reopened, 0, strike.getAnnotationIndex(),
                    strike.getSubtype(), strike.getName(), "Keep this comment", ""));
            assertEquals("Keep this comment", findByName(core.getPdfComments(reopened, 0), "strike-empty").getContents());
            assertTrue(core.removePdfComment(reopened, 0, strike.getAnnotationIndex(), strike.getSubtype(), strike.getName()));
            assertFalse(hasName(core.getPdfComments(reopened, 0), "strike-empty"));
        } finally { core.closeDocument(reopened); file.delete(); }
    }

    @Test
    public void selectedPagesRetainOrderAndOnlyTheirAnnotations() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PdfiumCore core = new PdfiumCore(context);
        PdfDocument source = core.newDocument(createPageRangeFixture());
        File subsetFile = new File(context.getCacheDir(), "pdf-page-range.pdf");
        try {
            assertEquals(3, core.getPageCount(source));
            assertTrue(pageText(core, source, 0).contains("PAGE ONE"));
            assertTrue(pageText(core, source, 1).contains("PAGE TWO"));
            assertTrue(pageText(core, source, 2).contains("PAGE THREE"));
            assertNotNull(findByName(core.getPdfComments(source, 0), "range-page-one"));
            assertNotNull(findByName(core.getPdfComments(source, 1), "range-page-two"));
            assertNotNull(findByName(core.getPdfComments(source, 2), "range-page-three"));

            ParcelFileDescriptor output = ParcelFileDescriptor.open(
                    subsetFile,
                    ParcelFileDescriptor.MODE_CREATE
                            | ParcelFileDescriptor.MODE_TRUNCATE
                            | ParcelFileDescriptor.MODE_READ_WRITE
            );
            try {
                assertTrue(core.writePages(source, new int[]{1, 2}, output));
            } finally {
                output.close();
            }

            assertEquals(3, core.getPageCount(source));
            assertNotNull(findByName(core.getPdfComments(source, 0), "range-page-one"));
        } finally {
            core.closeDocument(source);
        }

        PdfDocument subset = open(core, subsetFile);
        try {
            assertEquals(2, core.getPageCount(subset));
            assertTrue(pageText(core, subset, 0).contains("PAGE TWO"));
            assertTrue(pageText(core, subset, 1).contains("PAGE THREE"));
            assertNotNull(findByName(core.getPdfComments(subset, 0), "range-page-two"));
            assertNotNull(findByName(core.getPdfComments(subset, 1), "range-page-three"));
            assertFalse(hasName(core.getPdfComments(subset, 0), "range-page-one"));
            assertFalse(hasName(core.getPdfComments(subset, 1), "range-page-one"));
        } finally {
            core.closeDocument(subset);
            subsetFile.delete();
        }
    }

    @Test
    public void mergedDocumentsKeepPrimaryPagesBeforeAppendixPages() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PdfiumCore core = new PdfiumCore(context);
        PdfDocument primary = core.newDocument(createPageRangeFixture());
        PdfDocument appendix = core.newDocument(createPageRangeFixture());
        File mergedFile = new File(context.getCacheDir(), "pdf-merged-documents.pdf");
        try {
            ParcelFileDescriptor output = ParcelFileDescriptor.open(
                    mergedFile,
                    ParcelFileDescriptor.MODE_CREATE
                            | ParcelFileDescriptor.MODE_TRUNCATE
                            | ParcelFileDescriptor.MODE_READ_WRITE
            );
            try {
                assertTrue(core.writeMergedDocuments(primary, appendix, output));
            } finally {
                output.close();
            }
        } finally {
            core.closeDocument(appendix);
            core.closeDocument(primary);
        }

        PdfDocument merged = open(core, mergedFile);
        try {
            assertEquals(6, core.getPageCount(merged));
            assertTrue(pageText(core, merged, 0).contains("PAGE ONE"));
            assertTrue(pageText(core, merged, 2).contains("PAGE THREE"));
            assertTrue(pageText(core, merged, 3).contains("PAGE ONE"));
            assertTrue(pageText(core, merged, 5).contains("PAGE THREE"));
            assertNotNull(findByName(core.getPdfComments(merged, 0), "range-page-one"));
            assertNotNull(findByName(core.getPdfComments(merged, 3), "range-page-one"));
        } finally {
            core.closeDocument(merged);
            mergedFile.delete();
        }
    }

    @Test
    public void standardCommentsRoundTripWithoutIncludingMjHighlights() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PdfiumCore core = new PdfiumCore(context);
        PdfDocument document = core.newDocument(createCommentFixture());
        File firstSave = new File(context.getCacheDir(), "pdf-comment-round-trip.pdf");
        File secondSave = new File(context.getCacheDir(), "pdf-comment-delete-round-trip.pdf");
        try {
            assertEquals(1, core.getPageCount(document));
            assertTrue(core.openPage(document, 0) != 0L);

            List<PdfDocument.PdfComment> comments = core.getPdfComments(document, 0);
            assertEquals(2, comments.size());
            PdfDocument.PdfComment textComment = findByName(comments, "clean-text-id");
            PdfDocument.PdfComment highlightComment = findByName(comments, "external-highlight-id");
            assertNotNull(textComment);
            assertNotNull(highlightComment);
            assertEquals(PdfDocument.PdfComment.SUBTYPE_TEXT, textComment.getSubtype());
            assertEquals(PdfDocument.PdfComment.SUBTYPE_HIGHLIGHT, highlightComment.getSubtype());
            assertEquals("Clean Author", textComment.getAuthor());
            assertEquals("needs_anchor", textComment.getAnchorStatus());
            assertTrue(textComment.isSourceAnchorUnresolved());
            assertFalse(highlightComment.isSourceAnchorUnresolved());

            assertTrue(core.setPdfCommentContents(
                    document,
                    0,
                    highlightComment.getAnnotationIndex(),
                    highlightComment.getSubtype(),
                    highlightComment.getName(),
                    "Edited external highlight comment",
                    "D:20260724125500"
            ));
            assertEquals(
                    "Edited external highlight comment",
                    findByName(
                            core.getPdfComments(document, 0),
                            "external-highlight-id"
                    ).getContents()
            );

            assertTrue(core.setPdfCommentContents(
                    document,
                    0,
                    textComment.getAnnotationIndex(),
                    textComment.getSubtype(),
                    textComment.getName(),
                    "Edited clean comment",
                    "D:20260724130000"
            ));
            assertEquals(
                    "Edited clean comment",
                    findByName(core.getPdfComments(document, 0), "clean-text-id").getContents()
            );
            RectF movedBounds = new RectF(160, 710, 184, 686);
            assertTrue(core.setPdfCommentBounds(
                    document,
                    0,
                    textComment.getAnnotationIndex(),
                    textComment.getSubtype(),
                    textComment.getName(),
                    movedBounds
            ));
            assertBoundsEqual(
                    movedBounds,
                    findByName(core.getPdfComments(document, 0), "clean-text-id").getBounds()
            );

            int highlightedCommentColor = 0xFFFFF176;
            assertTrue(core.createPdfHighlightComment(
                    document,
                    0,
                    Arrays.asList(
                            new RectF(300, 650, 430, 630),
                            new RectF(300, 625, 390, 605)
                    ),
                    highlightedCommentColor,
                    "new-highlight-comment-id",
                    "New highlighted comment",
                    "Alex Example",
                    "D:20260724135000",
                    "D:20260724135000"
            ));
            PdfDocument.PdfComment createdHighlight = findByName(
                    core.getPdfComments(document, 0),
                    "new-highlight-comment-id"
            );
            assertNotNull(createdHighlight);
            assertEquals(
                    PdfDocument.PdfComment.SUBTYPE_HIGHLIGHT,
                    createdHighlight.getSubtype()
            );
            assertEquals("Alex Example", createdHighlight.getAuthor());
            List<PdfDocument.HighlightAnnotation> createdHighlightPieces = highlightPieces(
                    core.getHighlightAnnotations(document, 0),
                    createdHighlight.getAnnotationIndex(),
                    highlightedCommentColor
            );
            assertEquals(2, createdHighlightPieces.size());
            assertBoundsEqual(
                    new RectF(300, 650, 430, 630),
                    createdHighlightPieces.get(0).getBounds()
            );

            assertTrue(core.createPdfComment(
                    document,
                    0,
                    new RectF(300, 724, 324, 700),
                    "new-standard-id",
                    "New standard comment",
                    "Alex Example",
                    "D:20260724140000",
                    "D:20260724140000"
            ));
            assertEquals(4, core.getPdfComments(document, 0).size());
            PdfDocument.PdfComment createdRoot = findByName(
                    core.getPdfComments(document, 0),
                    "new-standard-id"
            );
            assertNotNull(createdRoot);
            assertTrue(core.createPdfReply(
                    document,
                    0,
                    createdHighlight.getBounds(),
                    createdHighlight.getAnnotationIndex(),
                    createdHighlight.getSubtype(),
                    createdHighlight.getName(),
                    "new-reply-id",
                    "A standard reply",
                    "Alex Example",
                    "D:20260724140500",
                    "D:20260724140500"
            ));
            PdfDocument.PdfComment createdReply = findByName(
                    core.getPdfComments(document, 0),
                    "new-reply-id"
            );
            assertNotNull(createdReply);
            assertTrue(createdReply.isReply());
            assertEquals("new-highlight-comment-id", createdReply.getInReplyToName());
            assertTrue(core.createPdfReply(
                    document,
                    0,
                    createdRoot.getBounds(),
                    createdRoot.getAnnotationIndex(),
                    createdRoot.getSubtype(),
                    createdRoot.getName(),
                    "new-text-reply-id",
                    "A reply to a text comment",
                    "Alex Example",
                    "D:20260724141000",
                    "D:20260724141000"
            ));
            PdfDocument.PdfComment createdTextReply = findByName(
                    core.getPdfComments(document, 0),
                    "new-text-reply-id"
            );
            assertNotNull(createdTextReply);
            assertTrue(createdTextReply.isReply());
            assertEquals("new-standard-id", createdTextReply.getInReplyToName());
            assertEquals(6, core.getPdfComments(document, 0).size());

            assertTrue(core.removePdfComment(
                    document,
                    0,
                    highlightComment.getAnnotationIndex(),
                    highlightComment.getSubtype(),
                    highlightComment.getName()
            ));
            assertFalse(hasName(core.getPdfComments(document, 0), "external-highlight-id"));
            save(core, document, firstSave);
            assertPdfObjectUsesCommentIcon(firstSave, "new-standard-id");
            assertPdfObjectUsesCommentIcon(firstSave, "new-text-reply-id");
        } finally {
            core.closeDocument(document);
        }

        PdfDocument reopened = open(core, firstSave);
        try {
            List<PdfDocument.PdfComment> comments = core.getPdfComments(reopened, 0);
            assertEquals(5, comments.size());
            assertEquals("Edited clean comment", findByName(comments, "clean-text-id").getContents());
            assertBoundsEqual(
                    new RectF(160, 710, 184, 686),
                    findByName(comments, "clean-text-id").getBounds()
            );
            PdfDocument.PdfComment created = findByName(comments, "new-standard-id");
            assertNotNull(created);
            assertEquals(PdfDocument.PdfComment.SUBTYPE_TEXT, created.getSubtype());
            assertEquals("Alex Example", created.getAuthor());
            PdfDocument.PdfComment highlighted = findByName(
                    comments,
                    "new-highlight-comment-id"
            );
            assertNotNull(highlighted);
            assertEquals(PdfDocument.PdfComment.SUBTYPE_HIGHLIGHT, highlighted.getSubtype());
            PdfDocument.PdfComment reply = findByName(comments, "new-reply-id");
            assertNotNull(reply);
            assertEquals("new-highlight-comment-id", reply.getInReplyToName());
            PdfDocument.PdfComment textReply = findByName(comments, "new-text-reply-id");
            assertNotNull(textReply);
            assertEquals("new-standard-id", textReply.getInReplyToName());

            PdfDocument.PdfComment textComment = findByName(comments, "clean-text-id");
            assertTrue(core.removePdfComment(
                    reopened,
                    0,
                    textComment.getAnnotationIndex(),
                    textComment.getSubtype(),
                    textComment.getName()
            ));
            save(core, reopened, secondSave);
        } finally {
            core.closeDocument(reopened);
        }

        PdfDocument afterDelete = open(core, secondSave);
        try {
            List<PdfDocument.PdfComment> comments = core.getPdfComments(afterDelete, 0);
            assertEquals(4, comments.size());
            assertNotNull(findByName(comments, "new-standard-id"));
            assertNotNull(findByName(comments, "new-highlight-comment-id"));
            assertNotNull(findByName(comments, "new-reply-id"));
            assertNotNull(findByName(comments, "new-text-reply-id"));
        } finally {
            core.closeDocument(afterDelete);
            firstSave.delete();
            secondSave.delete();
        }
    }

    private static PdfDocument open(PdfiumCore core, File file) throws Exception {
        ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_READ_ONLY
        );
        PdfDocument document = core.newDocument(descriptor);
        assertTrue(core.openPage(document, 0) != 0L);
        return document;
    }

    private static void save(PdfiumCore core, PdfDocument document, File file) throws Exception {
        ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_CREATE
                        | ParcelFileDescriptor.MODE_TRUNCATE
                        | ParcelFileDescriptor.MODE_READ_WRITE
        );
        try {
            assertTrue(core.saveAsCopy(document, descriptor));
        } finally {
            descriptor.close();
        }
    }

    private static PdfDocument.PdfComment findByName(
            List<PdfDocument.PdfComment> comments,
            String name
    ) {
        for (PdfDocument.PdfComment comment : comments) {
            if (name.equals(comment.getName())) {
                return comment;
            }
        }
        return null;
    }

    private static boolean hasName(List<PdfDocument.PdfComment> comments, String name) {
        return findByName(comments, name) != null;
    }

    private static void assertPdfObjectUsesCommentIcon(File file, String annotationId)
            throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        try (FileInputStream input = new FileInputStream(file)) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                bytes.write(buffer, 0, count);
            }
        }
        String pdf = new String(bytes.toByteArray(), StandardCharsets.ISO_8859_1);
        int marker = pdf.indexOf("(" + annotationId + ")");
        assertTrue("Missing annotation " + annotationId, marker >= 0);
        int objectStart = pdf.lastIndexOf(" obj", marker);
        int objectEnd = pdf.indexOf("endobj", marker);
        assertTrue("Missing PDF object for " + annotationId, objectStart >= 0 && objectEnd > marker);
        String object = pdf.substring(objectStart, objectEnd);
        assertTrue(
                "Annotation " + annotationId + " must use /Name /Comment",
                object.matches("(?s).*\\/Name\\s*\\/Comment.*")
        );
    }

    private static void assertBoundsEqual(RectF expected, RectF actual) {
        assertNotNull(actual);
        assertEquals(expected.left, actual.left, 0.01f);
        assertEquals(expected.top, actual.top, 0.01f);
        assertEquals(expected.right, actual.right, 0.01f);
        assertEquals(expected.bottom, actual.bottom, 0.01f);
    }

    private static String pageText(PdfiumCore core, PdfDocument document, int pageIndex) {
        assertTrue(core.openPage(document, pageIndex) != 0L);
        return core.getPageText(document, pageIndex);
    }

    private static List<PdfDocument.HighlightAnnotation> highlightPieces(
            List<PdfDocument.HighlightAnnotation> highlights,
            int annotationIndex,
            int color
    ) {
        List<PdfDocument.HighlightAnnotation> matching = new ArrayList<>();
        for (PdfDocument.HighlightAnnotation highlight : highlights) {
            if (highlight.getAnnotationIndex() == annotationIndex) {
                assertEquals(color, highlight.getColor());
                assertFalse(highlight.isAppOwned());
                matching.add(highlight);
            }
        }
        return matching;
    }

    private static byte[] createCommentFixture() throws Exception {
        List<String> objects = new ArrayList<>();
        objects.add("<< /Type /Catalog /Pages 2 0 R >>");
        objects.add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
        objects.add(
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
                        + "/Resources << >> /Contents 4 0 R "
                        + "/Annots [5 0 R 6 0 R 7 0 R 8 0 R] >>"
        );
        objects.add("<< /Length 0 >>\nstream\n\nendstream");
        objects.add(
                "<< /Type /Annot /Subtype /Text /Rect [72 700 96 724] "
                        + "/Contents (Clean text comment) /T (Clean Author) "
                        + "/NM (clean-text-id) /CreationDate (D:20260724120000) "
                        + "/M (D:20260724120000) /C [0.98 0.85 0] /F 28 "
                        + "/Name /Comment /Popup 8 0 R /ReviewThreadId (keep-me) "
                        + "/ReviewAnchorStatus (needs_anchor) >>"
        );
        objects.add(
                "<< /Type /Annot /Subtype /Highlight /Rect [100 600 250 620] "
                        + "/QuadPoints [100 620 250 620 100 600 250 600] "
                        + "/Contents (External highlight comment) /T (External Author) "
                        + "/NM (external-highlight-id) /M (D:20260724121000) "
                        + "/C [1 1 0] /F 4 >>"
        );
        objects.add(
                "<< /Type /Annot /Subtype /Highlight /Rect [100 500 250 520] "
                        + "/QuadPoints [100 520 250 520 100 500 250 500] "
                        + "/Contents (MJ private note) /T (MJ PDF) /NM (mj-highlight-id) "
                        + "/MJGroup (mj-group) /MJQuote (MJ quote) /F 4 >>"
        );
        objects.add(
                "<< /Type /Annot /Subtype /Popup /Rect [100 650 300 750] "
                        + "/Parent 5 0 R /Open false >>"
        );

        return createPdf(objects);
    }

    private static byte[] createPageRangeFixture() throws Exception {
        List<String> objects = new ArrayList<>();
        objects.add("<< /Type /Catalog /Pages 2 0 R >>");
        objects.add("<< /Type /Pages /Kids [3 0 R 4 0 R 5 0 R] /Count 3 >>");
        objects.add(pageObject(6, 10));
        objects.add(pageObject(7, 11));
        objects.add(pageObject(8, 12));
        objects.add(textStream("PAGE ONE"));
        objects.add(textStream("PAGE TWO"));
        objects.add(textStream("PAGE THREE"));
        objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");
        objects.add(textAnnotation("range-page-one", "Comment on page one"));
        objects.add(textAnnotation("range-page-two", "Comment on page two"));
        objects.add(textAnnotation("range-page-three", "Comment on page three"));
        return createPdf(objects);
    }

    private static String pageObject(int contentsObject, int annotationObject) {
        return "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
                + "/Resources << /Font << /F1 9 0 R >> >> "
                + "/Contents " + contentsObject + " 0 R "
                + "/Annots [" + annotationObject + " 0 R] >>";
    }

    private static String textStream(String text) {
        String content = "BT /F1 12 Tf 72 720 Td (" + text + ") Tj ET";
        return "<< /Length " + content.getBytes(StandardCharsets.US_ASCII).length
                + " >>\nstream\n" + content + "\nendstream";
    }

    private static String textAnnotation(String name, String contents) {
        return "<< /Type /Annot /Subtype /Text /Rect [72 700 96 724] "
                + "/Contents (" + contents + ") /T (Range Author) "
                + "/NM (" + name + ") /F 4 >>";
    }

    private static byte[] createPdf(List<String> objects) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeAscii(output, "%PDF-1.7\n");
        List<Integer> offsets = new ArrayList<>();
        offsets.add(0);
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(output.size());
            writeAscii(output, (i + 1) + " 0 obj\n");
            writeAscii(output, objects.get(i));
            writeAscii(output, "\nendobj\n");
        }
        int xrefOffset = output.size();
        writeAscii(output, "xref\n0 " + offsets.size() + "\n");
        writeAscii(output, "0000000000 65535 f \n");
        for (int i = 1; i < offsets.size(); i++) {
            writeAscii(
                    output,
                    String.format(Locale.US, "%010d 00000 n \n", offsets.get(i))
            );
        }
        writeAscii(
                output,
                "trailer\n<< /Size " + offsets.size() + " /Root 1 0 R >>\n"
                        + "startxref\n" + xrefOffset + "\n%%EOF\n"
        );
        return output.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream output, String value) throws Exception {
        output.write(value.getBytes(StandardCharsets.US_ASCII));
    }
}
