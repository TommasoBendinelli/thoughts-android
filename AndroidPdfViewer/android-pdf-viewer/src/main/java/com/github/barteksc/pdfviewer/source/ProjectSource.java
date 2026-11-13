// Written for Thoughts. License is GPLv3.

package com.github.barteksc.pdfviewer.source;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import com.shockwave.pdfium.PdfDocument;
import com.shockwave.pdfium.PdfiumCore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Opens the verified, local document objects of one immutable reading generation. */
public final class ProjectSource implements DocumentSource {
    private final File manifest;
    public ProjectSource(File manifest) { this.manifest = manifest; }

    private static String identity(JSONObject row, String key) throws Exception {
        Object value = row.get(key);
        if (!(value instanceof String)) throw new IOException("Invalid project identity");
        String text = (String) value;
        if (text.isEmpty() || text.length() > 1024) throw new IOException("Invalid project identity");
        for (int i = 0; i < text.length(); i++)
            if (text.charAt(i) < 32) throw new IOException("Invalid project identity");
        return text;
    }
    private static long integer(JSONObject row, String key) throws Exception {
        Object value = row.get(key);
        if (!(value instanceof Number)) throw new IOException("Invalid project number");
        return new java.math.BigDecimal(value.toString()).longValueExact();
    }
    private static void verify(File file, String expected, long size) throws Exception {
        if (!file.isFile() || file.length() != size) throw new IOException("Project document is unavailable");
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (FileInputStream stream = new FileInputStream(file)) {
            byte[] buffer = new byte[256 * 1024];
            for (int count; (count = stream.read(buffer)) != -1;) hash.update(buffer, 0, count);
        }
        StringBuilder actual = new StringBuilder();
        for (byte b : hash.digest()) actual.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        if (!expected.contentEquals(actual)) throw new IOException("Project document failed verification");
    }
    @Override public PdfDocument createDocument(Context context, PdfiumCore core, String password) throws IOException {
        List<PdfDocument> children = new ArrayList<>();
        try {
            if (manifest.length() > 16 * 1024 * 1024) throw new IOException("Project manifest is too large");
            byte[] bytes;
            try (FileInputStream stream = new FileInputStream(manifest)) {
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[16384];
                for (int count; (count = stream.read(buffer)) != -1;) {
                    if (output.size() + count > 16 * 1024 * 1024) throw new IOException("Project manifest is too large");
                    output.write(buffer, 0, count);
                }
                bytes = output.toByteArray();
            }
            JSONObject reading = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (integer(reading, "schema_version") != 1 || !identity(reading, "kind").equals("thoughts-project-reading"))
                throw new IOException("Unsupported project reading format");
            identity(reading, "project_id");
            String title = identity(reading, "title");
            JSONArray rows = reading.getJSONArray("documents");
            if (rows.length() < 1 || rows.length() > 10000) throw new IOException("Invalid project document list");
            String[] ids = new String[rows.length()], titles = new String[rows.length()], filenames = new String[rows.length()];
            int[] pages = new int[rows.length()];
            Set<String> seen = new HashSet<>();
            long total = 0;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                ids[i] = identity(row, "document_id"); titles[i] = identity(row, "title");
                String hash = identity(row, "pdf_sha256"), pack = identity(row, "package_sha256");
                long count = integer(row, "page_count"), size = integer(row, "pdf_size_bytes");
                if (!seen.add(ids[i]) || !hash.matches("[0-9a-f]{64}") || !pack.matches("[0-9a-f]{64}")
                        || count < 1 || count > 1000000 || size < 1 || total + count > 10000000)
                    throw new IOException("Invalid project document");
                pages[i] = (int) count; total += count;
                File packageFile = new File(new File(manifest.getParentFile(), "objects"), pack);
                verify(packageFile, pack, packageFile.length());
                JSONObject metadata = new JSONObject(new String(java.nio.file.Files.readAllBytes(packageFile.toPath()), StandardCharsets.UTF_8));
                filenames[i] = metadata.getString("filename");
                if (!filenames[i].matches("[A-Za-z0-9][A-Za-z0-9_.-]*\\.pdf")) throw new IOException("Invalid document filename");
                String kind = metadata.getString("kind");
                if (!kind.equals("thoughts-document-source-artifact") && !kind.equals("thoughts-document-reading-package"))
                    throw new IOException("Unsupported document package");
                JSONObject pdf = metadata.optJSONObject("pdf");
                if (pdf == null) pdf = metadata;
                if (!hash.equals(pdf.getString("pdf_sha256")) || integer(pdf, "page_count") != count
                    || (kind.equals("thoughts-document-reading-package") && !ids[i].equals(metadata.getString("document_id"))))
                    throw new IOException("Document package does not match its PDF");
                File object = new File(new File(manifest.getParentFile(), "objects"), hash);
                verify(object, hash, size);
                ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(object, ParcelFileDescriptor.MODE_READ_ONLY);
                try { children.add(core.newDocument(descriptor, password)); }
                catch (Throwable error) { descriptor.close(); throw error; }
            }
            if (integer(reading, "page_count") != total) throw new IOException("Invalid project page count");
            PdfDocument result = core.newComposition(children, ids, titles, pages, title);
            result.setDocumentFilenames(filenames);
            return result;
        } catch (Throwable error) {
            for (PdfDocument child : children) core.closeDocument(child);
            if (error instanceof Error) throw (Error) error;
            throw new IOException("Cannot open the synchronized project", error);
        }
    }
}
