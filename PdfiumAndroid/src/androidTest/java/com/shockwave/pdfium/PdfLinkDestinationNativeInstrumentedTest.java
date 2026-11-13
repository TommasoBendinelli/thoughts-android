// Written for MJ PDF. License is GPLv3.

package com.shockwave.pdfium;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.PointF;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@RunWith(AndroidJUnit4.class)
public class PdfLinkDestinationNativeInstrumentedTest {

    @Test
    public void namedDestinationIsResolvedLocally() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PdfiumCore core = new PdfiumCore(context);
        PdfDocument document = core.newDocument(createLinkFixture());
        try {
            PdfDocument.Link destination = core.getNamedDestination(document, "target");
            assertNotNull(destination);
            assertEquals(Integer.valueOf(1), destination.getDestPageIdx());
            assertPoint(destination.getDestPoint(), 72f, 500f);
            assertNull(core.getNamedDestination(document, "removed-section"));
            core.openPage(document, 0);
            // A link to another PDF that is not installed exposes no link.
            for (PdfDocument.Link link : core.getPageLinks(document, 0)) {
                assertTrue(Math.abs(link.getBounds().left - 350f) >= 0.01f);
            }
        } finally {
            core.closeDocument(document);
        }
    }

    @Test
    public void internalLinksExposeAvailableDestinationCoordinates() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PdfiumCore core = new PdfiumCore(context);
        PdfDocument document = core.newDocument(createLinkFixture());
        try {
            assertTrue(core.openPage(document, 0) != 0L);
            List<PdfDocument.Link> links = core.getPageLinks(document, 0);
            assertEquals(5, links.size());

            PdfDocument.Link xyz = linkAtLeft(links, 50f);
            assertEquals(Integer.valueOf(1), xyz.getDestPageIdx());
            assertPoint(xyz.getDestPoint(), 72f, 500f);

            PointF fitH = linkAtLeft(links, 110f).getDestPoint();
            assertNotNull(fitH);
            assertTrue(Float.isNaN(fitH.x));
            assertEquals(640f, fitH.y, 0.01f);

            PointF fitV = linkAtLeft(links, 170f).getDestPoint();
            assertNotNull(fitV);
            assertEquals(120f, fitV.x, 0.01f);
            assertTrue(Float.isNaN(fitV.y));

            assertPoint(linkAtLeft(links, 230f).getDestPoint(), 180f, 300f);
            assertNull(linkAtLeft(links, 290f).getDestPoint());
        } finally {
            core.closeDocument(document);
        }
    }

    @Test
    public void singleDocumentResolvesLinksToAnotherInstalledPdf() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        java.io.File directory = new java.io.File(context.getCacheDir(), "remote-link-test");
        directory.mkdirs();
        java.io.File target = new java.io.File(directory, "target.pdf");
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(target)) {
            output.write(createLinkFixture());
        }
        PdfiumCore.setRemoteDocumentResolver(filename -> {
            java.io.File file = new java.io.File(directory, filename);
            return file.isFile() ? file : null;
        });
        PdfiumCore core = new PdfiumCore(context);
        List<String> objects = new ArrayList<>();
        objects.add("<< /Type /Catalog /Pages 2 0 R >>");
        objects.add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
        objects.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << >> "
                + "/Contents 4 0 R /Annots [5 0 R 6 0 R 7 0 R] >>");
        objects.add("<< /Length 0 >>\nstream\n\nendstream");
        objects.add(linkObject(50, "/A << /S /GoToR /F (target.pdf) /D (target) >>"));
        objects.add(linkObject(110, "/A << /S /GoToR /F (target.pdf) /D (section*.3) >>"));
        objects.add(linkObject(170, "/A << /S /GoToR /F (missing.pdf) /D (target) >>"));
        PdfDocument document = core.newDocument(createPdf(objects));
        try {
            core.openPage(document, 0);
            List<PdfDocument.Link> links = core.getPageLinks(document, 0);
            assertEquals(2, links.size());
            assertEquals("thoughts-pdf-link:target.pdf?page=1&x=72.000&y=500.000",
                    linkAtLeft(links, 50f).getUri());
            // The target does not name this destination, so the link opens its first page.
            assertEquals("thoughts-pdf-link:target.pdf?page=0", linkAtLeft(links, 110f).getUri());
            assertNull(linkAtLeft(links, 50f).getDestPageIdx());
        } finally {
            core.closeDocument(document);
            PdfiumCore.setRemoteDocumentResolver(null);
            target.delete();
        }
    }

    private static PdfDocument.Link linkAtLeft(List<PdfDocument.Link> links, float left) {
        for (PdfDocument.Link link : links) {
            if (Math.abs(link.getBounds().left - left) < 0.01f) {
                return link;
            }
        }
        throw new AssertionError("Missing link at x=" + left);
    }

    private static void assertPoint(PointF point, float x, float y) {
        assertNotNull(point);
        assertEquals(x, point.x, 0.01f);
        assertEquals(y, point.y, 0.01f);
    }

    private static byte[] createLinkFixture() throws Exception {
        List<String> objects = new ArrayList<>();
        objects.add(
                "<< /Type /Catalog /Pages 2 0 R "
                        + "/Names << /Dests << /Names [(target) [4 0 R /XYZ 72 500 null]] >> >> >>"
        );
        objects.add("<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>");
        objects.add(
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
                        + "/Resources << >> /Contents 5 0 R "
                        + "/Annots [6 0 R 7 0 R 8 0 R 9 0 R 10 0 R 11 0 R] >>"
        );
        objects.add(
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
                        + "/Resources << >> /Contents 5 0 R >>"
        );
        objects.add("<< /Length 0 >>\nstream\n\nendstream");
        objects.add(linkObject(50, "/A << /S /GoTo /D (target) >>"));
        objects.add(linkObject(110, "/Dest [4 0 R /FitH 640]"));
        objects.add(linkObject(170, "/Dest [4 0 R /FitV 120]"));
        objects.add(linkObject(230, "/Dest [4 0 R /FitR 80 100 280 500]"));
        objects.add(linkObject(290, "/Dest [4 0 R /Fit]"));
        objects.add(linkObject(350, "/A << /S /GoToR /F (other.pdf) /D (target) >>"));
        return createPdf(objects);
    }

    private static String linkObject(int left, String destination) {
        return "<< /Type /Annot /Subtype /Link /Rect [" + left + " 700 "
                + (left + 40) + " 720] " + destination + " >>";
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
