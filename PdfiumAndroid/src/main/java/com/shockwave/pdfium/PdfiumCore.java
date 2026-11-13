package com.shockwave.pdfium;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.Surface;

import com.shockwave.pdfium.util.Size;
import com.shockwave.pdfium.util.SizeF;

import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PdfiumCore {
    private static final String TAG = PdfiumCore.class.getName();

    public static final int RENDER_STATUS_READY = 0;
    public static final int RENDER_STATUS_TO_BE_CONTINUED = 1;
    public static final int RENDER_STATUS_DONE = 2;
    public static final int RENDER_STATUS_FAILED = 3;

    public static final int RENDER_FLAG_NO_SMOOTHIMAGE = 0x2000;

    private static final int MAX_BOOKMARK_DEPTH = 64;

    private static final int FPDF_SAVE_REMOVE_SECURITY = 3;

    static {
        try {
            System.loadLibrary("c++_shared");
            System.loadLibrary("modpng");
            System.loadLibrary("modft2");
            System.loadLibrary("modpdfium");
            System.loadLibrary("jniPdfium");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Native libraries failed to load - " + e);
        }
    }

    private native long nativeOpenDocument(int fd, String password);

    private native long nativeOpenMemDocument(byte[] data, String password);

    private native void nativeCloseDocument(long docPtr);

    private native boolean nativeSaveAsCopy(long docPtr, int fd);
    private native boolean nativeSaveAsCopyWithFlags(long docPtr, int fd, int flags);
    private native boolean nativeWritePages(long docPtr, int[] pageIndices, int fd);
    private native boolean nativeWriteMergedDocuments(long primaryDocPtr, long appendixDocPtr, int fd);

    private native int nativeGetPageCount(long docPtr);

    private native long nativeLoadPage(long docPtr, int pageIndex);

    private native long[] nativeLoadPages(long docPtr, int fromIndex, int toIndex);

    private native void nativeClosePage(long docPtr, long pagePtr);

    private native int nativeGetPageWidthPixel(long pagePtr, int dpi);

    private native int nativeGetPageHeightPixel(long pagePtr, int dpi);

    private native int nativeGetPageWidthPoint(long pagePtr);

    private native int nativeGetPageHeightPoint(long pagePtr);

    //private native long nativeGetNativeWindow(Surface surface);
    //private native void nativeRenderPage(long pagePtr, long nativeWindowPtr);
    private native void nativeRenderPage(long pagePtr, Surface surface, int dpi,
                                         int startX, int startY,
                                         int drawSizeHor, int drawSizeVer,
                                         boolean renderAnnot);

    private native void nativeRenderPageBitmap(long docPtr, long pagePtr, Bitmap bitmap, int dpi,
                                               int startX, int startY,
                                               int drawSizeHor, int drawSizeVer,
                                               boolean renderAnnot);

    private native long nativeRenderChunkedStart(long docPtr, long pagePtr, Bitmap bitmap,
                                                 int startX, int startY,
                                                 int drawSizeHor, int drawSizeVer,
                                                 boolean renderAnnot, int extraFlags);

    private native int nativeRenderChunkedStatus(long ctxPtr);

    private native int nativeRenderChunkedContinue(long ctxPtr, long pagePtr);

    private native void nativeRenderChunkedClose(long ctxPtr, long docPtr, long pagePtr, Bitmap bitmap,
                                                 boolean drawForms, boolean pageAlive, boolean completed);

    private static native void nativeSetTimingLogsEnabled(boolean enabled);

    private native String nativeGetDocumentMetaText(long docPtr, String tag);

    private native Long nativeGetFirstChildBookmark(long docPtr, Long bookmarkPtr);

    private native Long nativeGetSiblingBookmark(long docPtr, long bookmarkPtr);

    private native String nativeGetBookmarkTitle(long bookmarkPtr);

    private native long nativeGetBookmarkDestIndex(long docPtr, long bookmarkPtr);

    private native Size nativeGetPageSizeByIndex(long docPtr, int pageIndex, int dpi);

    private native SizeF nativeGetPageSizePointByIndex(long docPtr, int pageIndex);

    private native float[] nativeGetPageSizes(long docPtr, int dpi);

    private native long[] nativeGetPageLinks(long pagePtr);

    private native String nativeGetPageText(long pagePtr);

    private native Rect[] nativeGetPageTextBounds(long pagePtr, int start, int count);

    private native long nativeLoadTextPage(long pagePtr);

    private native void nativeCloseTextPage(long textPagePtr);

    private native int nativeTextCountChars(long textPagePtr);

    private native int nativeCharIndexAtPos(long textPagePtr, double x, double y,
                                            double xTolerance, double yTolerance);

    private native boolean nativeLooseCharBox(long textPagePtr, int index, float[] out4);

    private native boolean nativeTightCharBox(long textPagePtr, int index, float[] out4);

    private native int nativeCharUnicode(long textPagePtr, int index);

    private native String nativeTextRange(long textPagePtr, int start, int count);

    private native float[] nativeTextGetRects(long textPagePtr, int start, int count);

    private native double[] nativeTextCharMetrics(long textPagePtr, int start, int count);


    private native boolean nativeCreateAnnotInPage(long pagePtr, int l, int r, int t, int b, int dpi, boolean padding);

    private native boolean nativeCreateHighlightAnnotation(long docPtr, long pagePtr, int pageIndex,
                                                           float[][] rects,
                                                           int r, int g, int b, int a,
                                                           String contents, String groupKey,
                                                           String creationDate);

    private native PdfDocument.HighlightAnnotation[] nativeGetHighlightAnnotations(long pagePtr);

    private native PdfDocument.PdfComment[] nativeGetPdfComments(long pagePtr);

    private native int nativeCountUnsupportedPdfAnnotations(long pagePtr);

    private native boolean nativeCreatePdfComment(long docPtr, long pagePtr, int pageIndex,
                                                  float[] rect, int r, int g, int b,
                                                  String name, String contents, String author,
                                                  String creationDate, String modifiedDate);

    private native boolean nativeCreatePdfMarkupComment(long pagePtr, int subtype, float[][] rects,
                                                           int r, int g, int b, int a,
                                                           String name, String contents,
                                                           String author, String creationDate,
                                                           String modifiedDate);

    private native boolean nativeCreatePdfReply(long pagePtr, float[] rect,
                                                int parentAnnotationIndex, int parentSubtype,
                                                String parentName, String name,
                                                String contents, String author,
                                                String creationDate, String modifiedDate);

    private native boolean nativeSetPdfCommentContents(long pagePtr, int annotationIndex,
                                                       int subtype, String name, String contents,
                                                       String modifiedDate);

    private native boolean nativeSetPdfCommentColor(long pagePtr, int annotationIndex,
                                                    int subtype, String name,
                                                    int r, int g, int b);

    private native boolean nativeSetPdfCommentBounds(long pagePtr, int annotationIndex,
                                                     int subtype, String name, float[] rect);

    private native boolean nativeRemovePdfComment(long pagePtr, int annotationIndex,
                                                  int subtype, String name);

    private native String[] nativeGetPageFonts(long pagePtr);

    private native boolean nativeAddSignatureContent(long docPtr, long pagePtr,
                                                     float[][] strokes,
                                                     int r, int g, int b,
                                                     float strokeWidth);

    private native PdfDocument.FormField nativeGetFormFieldAtPoint(long docPtr, long pagePtr,
                                                                   float x, float y, float tolerance);

    private native float[] nativeGetFormFieldRects(long docPtr, long pagePtr);

    private native boolean nativeSetFormFieldText(long docPtr, long pagePtr, int annotIndex,
                                                  String text);

    private native boolean nativeSetFormFieldChecked(long docPtr, long pagePtr, int annotIndex,
                                                     boolean checked);

    private native boolean nativeSetHighlightAnnotationColor(long pagePtr, int annotationIndex,
                                                             String groupKey, int r, int g, int b);

    private native boolean nativeSetHighlightAnnotationNote(long pagePtr, int annotationIndex,
                                                            String groupKey, String note,
                                                            String modifiedDate);

    private native boolean nativeRemoveHighlightAnnotation(long pagePtr, int annotationIndex,
                                                           String groupKey);

    private native int nativeClearSearchResultAnnot(long pagePtr, int pageIndex);

    private native Integer nativeGetDestPageIndex(long docPtr, long linkPtr);
    private native String nativeGetRemoteLinkFile(long linkPtr);
    private native float[] nativeGetRemoteLinkDestination(long docPtr, long linkPtr);

    private native float[] nativeGetDestLocation(long docPtr, long linkPtr);

    private native String nativeGetLinkURI(long docPtr, long linkPtr);
    private native float[] nativeGetNamedDestination(long docPtr, String name);

    public PdfDocument.Link getNamedDestination(PdfDocument doc, String name) {
        if (doc.isComposition()) {
            if (doc.closed) return null;
            PdfDocument.Link found = null;
            for (int i = 0; i < doc.documents.size(); i++) {
                PdfDocument.Link candidate = getNamedDestination(doc.documents.get(i), name);
                if (candidate != null) {
                    if (found != null) return null; // Ambiguous unqualified names must not jump to the wrong document.
                    found = offsetLink(candidate, doc.documentStarts[i]);
                }
            }
            return found;
        }
        synchronized (lock) {
            float[] dest = nativeGetNamedDestination(doc.mNativeDocPtr, name);
            if (dest == null || dest.length != 3) return null;
            return new PdfDocument.Link(new RectF(), (int) dest[0], null,
                    linkDestinationPoint(new float[]{dest[1], dest[2]}));
        }
    }

    private native RectF nativeGetLinkRect(long linkPtr);

    private native Point nativePageCoordsToDevice(long pagePtr, int startX, int startY, int sizeX,
                                                  int sizeY, int rotate, double pageX, double pageY);


    /* synchronize native methods */
    public static native String[] referenceSyncTex(String output, int page, float x, float y);
    private native float[] nativeSourcePoint(long page, float x, float y);
    public float[] sourcePoint(PdfDocument doc, int pageIndex, float x, float y) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            return sourcePoint(doc.documents.get(child), pageIndex - doc.documentStarts[child], x, y);
        }
        synchronized (lock) {
            Long page = doc.mNativePagesPtr.get(pageIndex);
            return page == null ? null : nativeSourcePoint(page, x, y);
        }
    }

    private static final Object lock = new Object();
    private int mCurrentDpi;

    public static int getNumFd(ParcelFileDescriptor fdObj) {
        try {
            return fdObj.getFd();
        } catch (IllegalStateException e) {
            Log.e(TAG, "getNumFd: file descriptor already closed", e);
            return -1;
        }
    }


    /** Context needed to get screen density */
    public PdfiumCore(Context ctx) {
        mCurrentDpi = ctx.getResources().getDisplayMetrics().densityDpi;
//        Log.d(TAG, "Starting PdfiumAndroid " + BuildConfig.VERSION_NAME);
    }

    /** Takes ownership only on success; callers close children if validation fails. */
    public PdfDocument newComposition(List<PdfDocument> documents, String[] ids, String[] titles,
                                      int[] expectedPages, String title) throws IOException {
        if (documents.isEmpty() || documents.size() > 10000 || ids.length != documents.size()
                || titles.length != documents.size() || expectedPages.length != documents.size())
            throw new IOException("Invalid project document list");
        PdfDocument result = new PdfDocument();
        result.documentStarts = new int[documents.size()];
        Set<String> unique = new HashSet<>();
        Set<PdfDocument> handles = new HashSet<>();
        for (int i = 0; i < documents.size(); i++) {
            PdfDocument child = documents.get(i);
            if (child == null || child.closed || child.isComposition() || !handles.add(child)
                    || ids[i] == null || ids[i].isEmpty() || !unique.add(ids[i]))
                throw new IOException("Invalid project document identity");
            int pages = getPageCount(child);
            if (pages <= 0 || pages != expectedPages[i] || result.compositionPages > 10000000 - pages)
                throw new IOException("Project PDF page count does not match the verified composition");
            result.documentStarts[i] = result.compositionPages;
            result.compositionPages += pages;
        }
        result.documents = java.util.Collections.unmodifiableList(new ArrayList<>(documents));
        result.documentIds = ids.clone();
        result.documentTitles = titles.clone();
        result.projectTitle = title;
        return result;
    }

    private static PdfDocument.Link offsetLink(PdfDocument.Link link, int start) {
        Integer page = link.getDestPageIdx();
        return new PdfDocument.Link(link.getBounds(), page == null ? null : start + page,
                link.getUri(), link.getDestPoint());
    }

    private static void offsetBookmarks(List<PdfDocument.Bookmark> bookmarks, int start) {
        for (PdfDocument.Bookmark bookmark : bookmarks) {
            if (bookmark.pageIdx >= 0) bookmark.pageIdx += start;
            offsetBookmarks(bookmark.getChildren(), start);
        }
    }

    /** Create new document from file */
    public PdfDocument newDocument(ParcelFileDescriptor fd) throws IOException {
        return newDocument(fd, null);
    }

    /** Create new document from file with password */
    public PdfDocument newDocument(ParcelFileDescriptor fd, String password) throws IOException {
        PdfDocument document = new PdfDocument();
        document.parcelFileDescriptor = fd;
        synchronized (lock) {
            document.mNativeDocPtr = nativeOpenDocument(getNumFd(fd), password);
        }
        return document;
    }

    /** Create new document from bytearray */
    public PdfDocument newDocument(byte[] data) throws IOException {
        return newDocument(data, null);
    }

    /** Create new document from bytearray with password */
    public PdfDocument newDocument(byte[] data, String password) throws IOException {
        PdfDocument document = new PdfDocument();
        synchronized (lock) {
            document.mNativeDocPtr = nativeOpenMemDocument(data, password);
        }
        return document;
    }

    /** Get total numer of pages in document */
    public int getPageCount(PdfDocument doc) {
        if (doc.isComposition()) return doc.closed ? 0 : doc.compositionPages;
        synchronized (lock) {
            if (doc.closed) {
                return 0;
            }
            return nativeGetPageCount(doc.mNativeDocPtr);
        }
    }

    public boolean saveAsCopy(PdfDocument doc, ParcelFileDescriptor fd) {
        if (doc != null && doc.isComposition()) return false; // Use an explicit project export.
        synchronized (lock) {
            if (doc == null || fd == null || doc.closed) {
                return false;
            }
            return nativeSaveAsCopy(doc.mNativeDocPtr, getNumFd(fd));
        }
    }

    public boolean saveDecryptedCopy(PdfDocument doc, ParcelFileDescriptor fd) {
        if (doc != null && doc.isComposition()) return false; // Use an explicit project export.
        synchronized (lock) {
            if (doc == null || fd == null || doc.closed) {
                return false;
            }
            return nativeSaveAsCopyWithFlags(doc.mNativeDocPtr, getNumFd(fd), FPDF_SAVE_REMOVE_SECURITY);
        }
    }

    /** Write the selected source pages, in order, to a new PDF document. */
    public boolean writePages(PdfDocument doc, int[] pageIndices, ParcelFileDescriptor fd) {
        if (doc != null && doc.isComposition()) return false;
        synchronized (lock) {
            if (doc == null || fd == null || doc.closed || pageIndices == null
                    || pageIndices.length == 0) {
                return false;
            }
            int pageCount = nativeGetPageCount(doc.mNativeDocPtr);
            for (int pageIndex : pageIndices) {
                if (pageIndex < 0 || pageIndex >= pageCount) {
                    return false;
                }
            }
            return nativeWritePages(doc.mNativeDocPtr, pageIndices, getNumFd(fd));
        }
    }

    /** Write every primary page followed by every appendix page to a new PDF document. */
    public boolean writeMergedDocuments(
            PdfDocument primary,
            PdfDocument appendix,
            ParcelFileDescriptor fd
    ) {
        synchronized (lock) {
            if (primary == null || appendix == null || primary.isComposition() || appendix.isComposition() || fd == null
                    || primary.closed || appendix.closed
                    || nativeGetPageCount(primary.mNativeDocPtr) <= 0
                    || nativeGetPageCount(appendix.mNativeDocPtr) <= 0) {
                return false;
            }
            return nativeWriteMergedDocuments(
                    primary.mNativeDocPtr,
                    appendix.mNativeDocPtr,
                    getNumFd(fd)
            );
        }
    }

    /** Open page and store native pointer in {@link PdfDocument} */
    public long openPage(PdfDocument doc, int pageIndex) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            return openPage(doc.documents.get(child), pageIndex - doc.documentStarts[child]);
        }
        long pagePtr;
        synchronized (lock) {
            if (doc.closed) {
                return 0L;
            }
            Long existing = doc.mNativePagesPtr.get(pageIndex);
            if (existing != null) {
                return existing;
            }
            pagePtr = nativeLoadPage(doc.mNativeDocPtr, pageIndex);
            doc.mNativePagesPtr.put(pageIndex, pagePtr);
            return pagePtr;
        }

    }

    public void closePage(PdfDocument doc, int pageIndex) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            closePage(doc.documents.get(child), pageIndex - doc.documentStarts[child]);
            return;
        }
        synchronized (lock) {
            Long textPagePtr = doc.mNativeTextPagesPtr.remove(pageIndex);
            if (textPagePtr != null && textPagePtr != 0L) {
                nativeCloseTextPage(textPagePtr);
            }
            Long pagePtr = doc.mNativePagesPtr.remove(pageIndex);
            if (pagePtr != null && pagePtr != 0L) {
                nativeClosePage(doc.mNativeDocPtr, pagePtr);
            }
        }
    }

    public long openTextPage(PdfDocument doc, int pageIndex) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            return openTextPage(doc.documents.get(child), pageIndex - doc.documentStarts[child]);
        }
        synchronized (lock) {
            if (doc.closed) {
                return 0L;
            }
            Long existing = doc.mNativeTextPagesPtr.get(pageIndex);
            if (existing != null && existing != 0L) {
                return existing;
            }

            Long pagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (pagePtr == null || pagePtr == 0L) {
                pagePtr = nativeLoadPage(doc.mNativeDocPtr, pageIndex);
                doc.mNativePagesPtr.put(pageIndex, pagePtr);
            }
            if (pagePtr == null || pagePtr == 0L) {
                return 0L;
            }

            long textPagePtr = nativeLoadTextPage(pagePtr);
            if (textPagePtr != 0L) {
                doc.mNativeTextPagesPtr.put(pageIndex, textPagePtr);
            }
            return textPagePtr;
        }
    }

    public void closeTextPage(PdfDocument doc, int pageIndex) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            closeTextPage(doc.documents.get(child), pageIndex - doc.documentStarts[child]);
            return;
        }
        synchronized (lock) {
            Long textPagePtr = doc.mNativeTextPagesPtr.remove(pageIndex);
            if (textPagePtr != null && textPagePtr != 0L) {
                nativeCloseTextPage(textPagePtr);
            }
        }
    }

    public void closeTextPages(PdfDocument doc) {
        if (doc.isComposition()) {
            for (PdfDocument child : doc.documents) closeTextPages(child);
            return;
        }
        synchronized (lock) {
            for (Long textPagePtr : doc.mNativeTextPagesPtr.values()) {
                if (textPagePtr != null && textPagePtr != 0L) {
                    nativeCloseTextPage(textPagePtr);
                }
            }
            doc.mNativeTextPagesPtr.clear();
        }
    }

    public int textCountChars(PdfDocument doc, int pageIndex) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            return textCountChars(doc.documents.get(child), pageIndex - doc.documentStarts[child]);
        }
        synchronized (lock) {
            Long textPagePtr = doc.mNativeTextPagesPtr.get(pageIndex);
            if (textPagePtr == null || textPagePtr == 0L) {
                return 0;
            }
            return Math.max(0, nativeTextCountChars(textPagePtr));
        }
    }

    public int charIndexAtPos(PdfDocument doc, int pageIndex, double x, double y, double tolerance) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            return charIndexAtPos(doc.documents.get(child), pageIndex - doc.documentStarts[child], x, y, tolerance);
        }
        synchronized (lock) {
            Long textPagePtr = doc.mNativeTextPagesPtr.get(pageIndex);
            if (textPagePtr == null || textPagePtr == 0L) {
                return -1;
            }
            return nativeCharIndexAtPos(textPagePtr, x, y, tolerance, tolerance);
        }
    }

    public boolean looseCharBox(PdfDocument doc, int pageIndex, int charIndex, float[] out4) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            return looseCharBox(doc.documents.get(child), pageIndex - doc.documentStarts[child], charIndex, out4);
        }
        synchronized (lock) {
            Long textPagePtr = doc.mNativeTextPagesPtr.get(pageIndex);
            if (textPagePtr == null || textPagePtr == 0L || out4 == null || out4.length < 4) {
                return false;
            }
            return nativeLooseCharBox(textPagePtr, charIndex, out4);
        }
    }

    public boolean tightCharBox(PdfDocument doc, int pageIndex, int charIndex, float[] out4) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            return tightCharBox(doc.documents.get(child), pageIndex - doc.documentStarts[child], charIndex, out4);
        }
        synchronized (lock) {
            Long textPagePtr = doc.mNativeTextPagesPtr.get(pageIndex);
            if (textPagePtr == null || textPagePtr == 0L || out4 == null || out4.length < 4) {
                return false;
            }
            return nativeTightCharBox(textPagePtr, charIndex, out4);
        }
    }

    public int charUnicode(PdfDocument doc, int pageIndex, int charIndex) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            return charUnicode(doc.documents.get(child), pageIndex - doc.documentStarts[child], charIndex);
        }
        synchronized (lock) {
            Long textPagePtr = doc.mNativeTextPagesPtr.get(pageIndex);
            if (textPagePtr == null || textPagePtr == 0L) {
                return 0;
            }
            return nativeCharUnicode(textPagePtr, charIndex);
        }
    }

    public String textRange(PdfDocument doc, int pageIndex, int start, int count) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            return textRange(doc.documents.get(child), pageIndex - doc.documentStarts[child], start, count);
        }
        synchronized (lock) {
            Long textPagePtr = doc.mNativeTextPagesPtr.get(pageIndex);
            if (textPagePtr == null || textPagePtr == 0L || count <= 0) {
                return "";
            }
            String text = nativeTextRange(textPagePtr, start, count);
            return text == null ? "" : text;
        }
    }

    public float[] textRects(PdfDocument doc, int pageIndex, int start, int count) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            return textRects(doc.documents.get(child), pageIndex - doc.documentStarts[child], start, count);
        }
        synchronized (lock) {
            Long textPagePtr = doc.mNativeTextPagesPtr.get(pageIndex);
            if (textPagePtr == null || textPagePtr == 0L || count <= 0) {
                return new float[0];
            }
            float[] rects = nativeTextGetRects(textPagePtr, start, count);
            return rects == null ? new float[0] : rects;
        }
    }

    public double[] textCharMetrics(PdfDocument doc, int pageIndex, int start, int count) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            return textCharMetrics(doc.documents.get(child), pageIndex - doc.documentStarts[child], start, count);
        }
        synchronized (lock) {
            Long textPagePtr = doc.mNativeTextPagesPtr.get(pageIndex);
            if (textPagePtr == null || textPagePtr == 0L || count <= 0) {
                return new double[0];
            }
            double[] values = nativeTextCharMetrics(textPagePtr, start, count);
            return values == null ? new double[0] : values;
        }
    }

    /** Open range of pages and store native pointers in {@link PdfDocument} */
    public long[] openPages(PdfDocument doc, int fromIndex, int toIndex) {
        if (doc.isComposition()) {
            if (doc.closed) return new long[0];
            doc.documentIndex(fromIndex);
            doc.documentIndex(toIndex);
            if (toIndex < fromIndex) throw new IllegalArgumentException("Invalid page range");
            long[] pages = new long[toIndex - fromIndex + 1];
            for (int i = fromIndex; i <= toIndex; i++) pages[i - fromIndex] = openPage(doc, i);
            return pages;
        }
        long[] pagesPtr;
        synchronized (lock) {
            if (doc.closed) {
                return new long[0];
            }
            pagesPtr = nativeLoadPages(doc.mNativeDocPtr, fromIndex, toIndex);
            int pageIndex = fromIndex;
            for (long page : pagesPtr) {
                if (pageIndex > toIndex) break;
                doc.mNativePagesPtr.put(pageIndex, page);
                pageIndex++;
            }

            return pagesPtr;
        }
    }

    /**
     * Get page width in pixels. <br>
     * This method requires page to be opened.
     */
    public int getPageWidth(PdfDocument doc, int index) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(index);
            return getPageWidth(doc.documents.get(child), index - doc.documentStarts[child]);
        }
        synchronized (lock) {
            Long pagePtr;
            if ((pagePtr = doc.mNativePagesPtr.get(index)) != null) {
                return nativeGetPageWidthPixel(pagePtr, mCurrentDpi);
            }
            return 0;
        }
    }

    /**
     * Get page height in pixels. <br>
     * This method requires page to be opened.
     */
    public int getPageHeight(PdfDocument doc, int index) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(index);
            return getPageHeight(doc.documents.get(child), index - doc.documentStarts[child]);
        }
        synchronized (lock) {
            Long pagePtr;
            if ((pagePtr = doc.mNativePagesPtr.get(index)) != null) {
                return nativeGetPageHeightPixel(pagePtr, mCurrentDpi);
            }
            return 0;
        }
    }

    /**
     * Get page width in PostScript points (1/72th of an inch).<br>
     * This method requires page to be opened.
     */
    public int getPageWidthPoint(PdfDocument doc, int index) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(index);
            return getPageWidthPoint(doc.documents.get(child), index - doc.documentStarts[child]);
        }
        synchronized (lock) {
            Long pagePtr;
            if ((pagePtr = doc.mNativePagesPtr.get(index)) != null) {
                return nativeGetPageWidthPoint(pagePtr);
            }
            return 0;
        }
    }

    /**
     * Get page height in PostScript points (1/72th of an inch).<br>
     * This method requires page to be opened.
     */
    public int getPageHeightPoint(PdfDocument doc, int index) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(index);
            return getPageHeightPoint(doc.documents.get(child), index - doc.documentStarts[child]);
        }
        synchronized (lock) {
            Long pagePtr;
            if ((pagePtr = doc.mNativePagesPtr.get(index)) != null) {
                return nativeGetPageHeightPoint(pagePtr);
            }
            return 0;
        }
    }

    /**
     * Get size of page in pixels.<br>
     * This method does not require given page to be opened.
     */
    public Size getPageSize(PdfDocument doc, int index) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(index);
            return getPageSize(doc.documents.get(child), index - doc.documentStarts[child]);
        }
        synchronized (lock) {
            if (doc.closed) {
                return new Size(0, 0);
            }
            return nativeGetPageSizeByIndex(doc.mNativeDocPtr, index, mCurrentDpi);
        }
    }

    /**
     * Get size of page in PDF points. This method does not require given page to be opened.
     */
    public SizeF getPageSizePoint(PdfDocument doc, int index) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(index);
            return getPageSizePoint(doc.documents.get(child), index - doc.documentStarts[child]);
        }
        synchronized (lock) {
            if (doc.closed) {
                return new SizeF(0, 0);
            }
            return nativeGetPageSizePointByIndex(doc.mNativeDocPtr, index);
        }
    }

    /**
     * Get pixel and PDF-point geometry for every page in one native call.
     * Each page occupies four values: pixel width, pixel height, point width, point height.
     */
    public float[] getPageSizes(PdfDocument doc) {
        if (doc.isComposition()) {
            if (doc.closed) return new float[0];
            float[] sizes = new float[doc.compositionPages * 4];
            for (int i = 0; i < doc.documents.size(); i++) {
                float[] child = getPageSizes(doc.documents.get(i));
                System.arraycopy(child, 0, sizes, doc.documentStarts[i] * 4, child.length);
            }
            return sizes;
        }
        synchronized (lock) {
            if (doc.closed) {
                return new float[0];
            }
            return nativeGetPageSizes(doc.mNativeDocPtr, mCurrentDpi);
        }
    }

    /**
     * Render page fragment on {@link Surface}.<br>
     * Page must be opened before rendering.
     */
    public void renderPage(PdfDocument doc, Surface surface, int pageIndex,
                           int startX, int startY, int drawSizeX, int drawSizeY) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            renderPage(doc.documents.get(child), surface, pageIndex - doc.documentStarts[child], startX, startY, drawSizeX, drawSizeY);
            return;
        }
        renderPage(doc, surface, pageIndex, startX, startY, drawSizeX, drawSizeY, false);
    }

    /**
     * Render page fragment on {@link Surface}. This method allows to render annotations.<br>
     * Page must be opened before rendering.
     */
    public void renderPage(PdfDocument doc, Surface surface, int pageIndex,
                           int startX, int startY, int drawSizeX, int drawSizeY,
                           boolean renderAnnot) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            renderPage(doc.documents.get(child), surface, pageIndex - doc.documentStarts[child], startX, startY, drawSizeX, drawSizeY, renderAnnot);
            return;
        }
        synchronized (lock) {
            try {
                Long pagePtr = doc.mNativePagesPtr.get(pageIndex);
                if (pagePtr == null || pagePtr == 0L) {
                    Log.e(TAG, "renderPage: page " + pageIndex + " is not open");
                    return;
                }
                nativeRenderPage(pagePtr, surface, mCurrentDpi,
                        startX, startY, drawSizeX, drawSizeY, renderAnnot);
            } catch (Exception e) {
                Log.e(TAG, "renderPage: exception thrown from native", e);
            }
        }
    }

    /**
     * Render page fragment on {@link Bitmap}.<br>
     * Page must be opened before rendering.
     * <p>
     * Supported bitmap configurations:
     * <ul>
     * <li>ARGB_8888 - best quality, high memory usage, higher possibility of OutOfMemoryError
     * <li>RGB_565 - little worse quality, twice less memory usage
     * </ul>
     */
    public void renderPageBitmap(PdfDocument doc, Bitmap bitmap, int pageIndex,
                                 int startX, int startY, int drawSizeX, int drawSizeY) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            renderPageBitmap(doc.documents.get(child), bitmap, pageIndex - doc.documentStarts[child], startX, startY, drawSizeX, drawSizeY);
            return;
        }
        renderPageBitmap(doc, bitmap, pageIndex, startX, startY, drawSizeX, drawSizeY, false);
    }

    /**
     * Render page fragment on {@link Bitmap}. This method allows to render annotations.<br>
     * Page must be opened before rendering.
     * <p>
     * For more info see {@link PdfiumCore#renderPageBitmap(PdfDocument, Bitmap, int, int, int, int, int)}
     */
    public void renderPageBitmap(PdfDocument doc, Bitmap bitmap, int pageIndex,
                                 int startX, int startY, int drawSizeX, int drawSizeY,
                                 boolean renderAnnot) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            renderPageBitmap(doc.documents.get(child), bitmap, pageIndex - doc.documentStarts[child], startX, startY, drawSizeX, drawSizeY, renderAnnot);
            return;
        }
        synchronized (lock) {
            try {
                Long pagePtr = doc.mNativePagesPtr.get(pageIndex);
                if (pagePtr == null || pagePtr == 0L) {
                    Log.e(TAG, "renderPageBitmap: page " + pageIndex + " is not open");
                    return;
                }
                nativeRenderPageBitmap(doc.mNativeDocPtr, pagePtr, bitmap, mCurrentDpi,
                        startX, startY, drawSizeX, drawSizeY, renderAnnot);
            } catch (Exception e) {
                Log.e(TAG, "renderPageBitmap: exception thrown from native", e);
            }
        }
    }

    public long renderPageBitmapChunkedStart(PdfDocument doc, Bitmap bitmap, int pageIndex,
                                             int startX, int startY, int drawSizeX, int drawSizeY,
                                             boolean renderAnnot, int extraFlags) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            return renderPageBitmapChunkedStart(doc.documents.get(child), bitmap, pageIndex - doc.documentStarts[child], startX, startY, drawSizeX, drawSizeY, renderAnnot, extraFlags);
        }
        synchronized (lock) {
            if (doc == null || doc.closed) {
                return 0L;
            }
            Long pagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (pagePtr == null || pagePtr == 0L) {
                Log.e(TAG, "renderPageBitmapChunkedStart: page " + pageIndex + " is not open");
                return 0L;
            }
            try {
                return nativeRenderChunkedStart(doc.mNativeDocPtr, pagePtr, bitmap,
                        startX, startY, drawSizeX, drawSizeY, renderAnnot, extraFlags);
            } catch (Exception e) {
                Log.e(TAG, "renderPageBitmapChunkedStart: exception thrown from native", e);
                return 0L;
            }
        }
    }

    public int renderPageBitmapChunkedStatus(long ctxPtr) {
        synchronized (lock) {
            return nativeRenderChunkedStatus(ctxPtr);
        }
    }

    public int renderPageBitmapChunkedContinue(PdfDocument doc, long ctxPtr, int pageIndex) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            return renderPageBitmapChunkedContinue(doc.documents.get(child), ctxPtr, pageIndex - doc.documentStarts[child]);
        }
        synchronized (lock) {
            if (doc == null || doc.closed) {
                return RENDER_STATUS_FAILED;
            }
            Long pagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (pagePtr == null || pagePtr == 0L) {
                return RENDER_STATUS_FAILED;
            }
            try {
                return nativeRenderChunkedContinue(ctxPtr, pagePtr);
            } catch (Exception e) {
                Log.e(TAG, "renderPageBitmapChunkedContinue: exception thrown from native", e);
                return RENDER_STATUS_FAILED;
            }
        }
    }

    public void renderPageBitmapChunkedClose(PdfDocument doc, long ctxPtr, int pageIndex, Bitmap bitmap,
                                             boolean drawForms, boolean pageAlive, boolean completed) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            renderPageBitmapChunkedClose(doc.documents.get(child), ctxPtr, pageIndex - doc.documentStarts[child], bitmap, drawForms, pageAlive, completed);
            return;
        }
        synchronized (lock) {
            long docPtr = doc == null ? 0L : doc.mNativeDocPtr;
            Long pagePtr = doc == null ? null : doc.mNativePagesPtr.get(pageIndex);
            long page = pagePtr == null ? 0L : pagePtr;
            try {
                nativeRenderChunkedClose(ctxPtr, docPtr, page, bitmap,
                        drawForms, pageAlive && page != 0L, completed);
            } catch (Exception e) {
                Log.e(TAG, "renderPageBitmapChunkedClose: exception thrown from native", e);
            }
        }
    }

    public static void setTimingLogsEnabled(boolean enabled) {
        nativeSetTimingLogsEnabled(enabled);
    }

    /** Release native resources and opened file */
    public void closeDocument(PdfDocument doc) {
        if (doc.isComposition()) {
            synchronized (lock) {
                if (doc.closed) return;
                doc.closed = true;
                for (PdfDocument child : doc.documents) closeDocument(child);
            }
            return;
        }
        synchronized (lock) {
            if (doc.closed) {
                return;
            }
            doc.closed = true;
            closeTextPages(doc);
            for (Integer index : doc.mNativePagesPtr.keySet()) {
                Long pagePtr = doc.mNativePagesPtr.get(index);
                if (pagePtr != null && pagePtr != 0L) {
                    nativeClosePage(doc.mNativeDocPtr, pagePtr);
                }
            }
            doc.mNativePagesPtr.clear();

            nativeCloseDocument(doc.mNativeDocPtr);

            if (doc.parcelFileDescriptor != null) { //if document was loaded from file
                try {
                    doc.parcelFileDescriptor.close();
                } catch (IOException e) {
                /* ignore */
                }
                doc.parcelFileDescriptor = null;
            }
        }
    }

    /** Get metadata for given document */
    public PdfDocument.Meta getDocumentMeta(PdfDocument doc) {
        if (doc.isComposition()) {
            PdfDocument.Meta meta = new PdfDocument.Meta();
            meta.title = doc.projectTitle;
            meta.totalPages = getPageCount(doc);
            return meta;
        }
        synchronized (lock) {
            PdfDocument.Meta meta = new PdfDocument.Meta();
            if (doc.closed) {
                return meta;
            }
            meta.title = nativeGetDocumentMetaText(doc.mNativeDocPtr, "Title");
            meta.author = nativeGetDocumentMetaText(doc.mNativeDocPtr, "Author");
            meta.subject = nativeGetDocumentMetaText(doc.mNativeDocPtr, "Subject");
            meta.keywords = nativeGetDocumentMetaText(doc.mNativeDocPtr, "Keywords");
            meta.creator = nativeGetDocumentMetaText(doc.mNativeDocPtr, "Creator");
            meta.producer = nativeGetDocumentMetaText(doc.mNativeDocPtr, "Producer");
            meta.creationDate = nativeGetDocumentMetaText(doc.mNativeDocPtr, "CreationDate");
            meta.modDate = nativeGetDocumentMetaText(doc.mNativeDocPtr, "ModDate");
            meta.totalPages = getPageCount(doc);
            return meta;
        }
    }

    /** Get table of contents (bookmarks) for given document */
    public List<PdfDocument.Bookmark> getTableOfContents(PdfDocument doc) {
        if (doc.isComposition()) {
            List<PdfDocument.Bookmark> result = new ArrayList<>();
            if (doc.closed) return result;
            for (int i = 0; i < doc.documents.size(); i++) {
                PdfDocument.Bookmark heading = new PdfDocument.Bookmark();
                heading.title = doc.documentTitles[i];
                heading.pageIdx = doc.documentStarts[i];
                List<PdfDocument.Bookmark> children = getTableOfContents(doc.documents.get(i));
                offsetBookmarks(children, doc.documentStarts[i]);
                heading.setChildren(children);
                result.add(heading);
            }
            return result;
        }
        synchronized (lock) {
            List<PdfDocument.Bookmark> topLevel = new ArrayList<>();
            if (doc.closed) {
                return topLevel;
            }
            Long first = nativeGetFirstChildBookmark(doc.mNativeDocPtr, null);
            if (first != null) {
                recursiveGetBookmark(topLevel, doc, first, new HashSet<Long>(), 0);
            }
            return topLevel;
        }
    }

    private void recursiveGetBookmark(List<PdfDocument.Bookmark> tree, PdfDocument doc, long bookmarkPtr,
                                      Set<Long> visited, int depth) {
        if (depth > MAX_BOOKMARK_DEPTH) {
            return;
        }
        Long currentPtr = bookmarkPtr;
        while (currentPtr != null && visited.add(currentPtr)) {
            PdfDocument.Bookmark bookmark = new PdfDocument.Bookmark();
            bookmark.mNativePtr = currentPtr;
            bookmark.title = nativeGetBookmarkTitle(currentPtr);
            bookmark.pageIdx = nativeGetBookmarkDestIndex(doc.mNativeDocPtr, currentPtr);
            tree.add(bookmark);

            Long child = nativeGetFirstChildBookmark(doc.mNativeDocPtr, currentPtr);
            if (child != null) {
                recursiveGetBookmark(bookmark.getChildren(), doc, child, visited, depth + 1);
            }

            currentPtr = nativeGetSiblingBookmark(doc.mNativeDocPtr, currentPtr);
        }
    }

    public String getPageText(PdfDocument doc, int pageIndex) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            return getPageText(doc.documents.get(child), pageIndex - doc.documentStarts[child]);
        }
        synchronized (lock) {
            Long nativePagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (nativePagePtr == null) {
                return "";
            }
            String text = normalizeExtractedText(nativeGetPageText(nativePagePtr));

            // ------------------ Unrelated Code:
            // Size size = nativeGetPageSizeByIndex(doc.mNativeDocPtr, pageIndex, mCurrentDpi);
            // Log.d(TAG, "getPageText: size-width:" + size.getWidth());
            // Log.d(TAG, "getPageText: size-height:" + size.getHeight());
            // ------------------
            return text == null ? "" : text;
        }
    }

    public String getPageRawText(PdfDocument doc, int pageIndex) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            return getPageRawText(doc.documents.get(child), pageIndex - doc.documentStarts[child]);
        }
        synchronized (lock) {
            Long nativePagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (nativePagePtr == null) {
                return "";
            }
            String text = nativeGetPageText(nativePagePtr);
            return text == null ? "" : text;
        }
    }

    public String normalizeExtractedText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return Normalizer.normalize(mapPresentationFormMarks(text), Normalizer.Form.NFKC)
                .replace("\uFFFE\r\n", "")
                .replace("\uFFFE\n", "")
                .replace("\uFFFE\r", "")
                .replace("\uFFFE", "")
                .replace("\u200B", "")
                .replace("\r\n", "\n")
                .replace('\r', '\n');
    }

    public static String mapPresentationFormMarks(String text) {
        char[] chars = null;
        for (int i = 0; i < text.length(); i++) {
            char mapped = mapPresentationFormMark(text.charAt(i));
            if (mapped != text.charAt(i)) {
                if (chars == null) {
                    chars = text.toCharArray();
                }
                chars[i] = mapped;
            }
        }
        return chars == null ? text : new String(chars);
    }

    private static char mapPresentationFormMark(char c) {
        switch (c) {
            case '\uFE70':  // fathatan isolated form
            case '\uFE71':  // tatweel with fathatan above
                return '\u064B';  // fathatan
            case '\uFE72':  // dammatan isolated form
                return '\u064C';  // dammatan
            case '\uFE74':  // kasratan isolated form
                return '\u064D';  // kasratan
            case '\uFE76':  // fatha isolated form
            case '\uFE77':  // fatha medial form
                return '\u064E';  // fatha
            case '\uFE78':  // damma isolated form
            case '\uFE79':  // damma medial form
                return '\u064F';  // damma
            case '\uFE7A':  // kasra isolated form
            case '\uFE7B':  // kasra medial form
                return '\u0650';  // kasra
            case '\uFE7C':  // shadda isolated form
            case '\uFE7D':  // shadda medial form
                return '\u0651';  // shadda
            case '\uFE7E':  // sukun isolated form
            case '\uFE7F':  // sukun medial form
                return '\u0652';  // sukun
            case '\uFE73':  // tail fragment, a rendering artifact
                return '\u200B';  // zero width space, stripped by the normalizers
            default:
                return c;
        }
    }

    public Map<Integer, String> getPagesText(PdfDocument doc, int start, int end) {
        if (doc.isComposition()) {
            Map<Integer, String> result = new HashMap<>();
            for (int page = start; page <= end; page++) result.put(page, getPageText(doc, page));
            return result;
        }
        synchronized (lock) {
            Map<Integer, String> pagesText = new HashMap<>();
            for (int i = start; i <= end; ++i) {
                Long pagePtr = doc.mNativePagesPtr.get(i);
                if (pagePtr == null || pagePtr == 0L) {
                    pagesText.put(i, "");
                    continue;
                }
                pagesText.put(i, normalizeExtractedText(nativeGetPageText(pagePtr)));
            }
            return pagesText;
        }
    }

    public Rect[] createHighlightText(PdfDocument doc, int pageIndex, int start, int end, boolean padding) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            return createHighlightText(doc.documents.get(child), pageIndex - doc.documentStarts[child], start, end, padding);
        }
        synchronized (lock) {
            Long nativePagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (nativePagePtr == null || nativePagePtr == 0L) {
                return new Rect[0];
            }
            Rect[] rects = nativeGetPageTextBounds(nativePagePtr, start, end);
            Log.d(TAG, "createHighlightText: rects.length: " + rects.length);
            for (Rect rect : rects) {
                nativeCreateAnnotInPage(nativePagePtr, rect.left, rect.right, rect.top, rect.bottom, mCurrentDpi, padding);
            }
            return rects;
        }
    }

    public boolean createHighlightAnnotation(PdfDocument doc, int pageIndex, List<RectF> rects,
                                              int color, String contents, String groupKey,
                                              String creationDate) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            return createHighlightAnnotation(doc.documents.get(child), pageIndex - doc.documentStarts[child], rects, color, contents, groupKey, creationDate);
        }
        synchronized (lock) {
            Long nativePagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (nativePagePtr == null || rects == null || rects.isEmpty()) {
                return false;
            }

            float[][] nativeRects = new float[rects.size()][4];
            for (int i = 0; i < rects.size(); i++) {
                RectF rect = rects.get(i);
                nativeRects[i][0] = rect.left;
                nativeRects[i][1] = rect.top;
                nativeRects[i][2] = rect.right;
                nativeRects[i][3] = rect.bottom;
            }
            return nativeCreateHighlightAnnotation(doc.mNativeDocPtr, nativePagePtr, pageIndex, nativeRects,
                    Color.red(color), Color.green(color), Color.blue(color), Color.alpha(color),
                    contents == null ? "" : contents, groupKey == null ? "" : groupKey, creationDate);
        }
    }

    public boolean addSignatureContent(PdfDocument doc, int pageIndex,
                                       float[][] strokes, int color, float strokeWidthPts) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            return addSignatureContent(doc.documents.get(child), pageIndex - doc.documentStarts[child], strokes, color, strokeWidthPts);
        }
        synchronized (lock) {
            Long nativePagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (nativePagePtr == null || strokes == null || strokes.length == 0) {
                return false;
            }
            return nativeAddSignatureContent(doc.mNativeDocPtr, nativePagePtr, strokes,
                    Color.red(color), Color.green(color), Color.blue(color), strokeWidthPts);
        }
    }

    public List<PdfDocument.HighlightAnnotation> getHighlightAnnotations(PdfDocument doc, int pageIndex) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            return getHighlightAnnotations(doc.documents.get(child), pageIndex - doc.documentStarts[child]);
        }
        synchronized (lock) {
            Long nativePagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (nativePagePtr == null) {
                return new ArrayList<>();
            }
            PdfDocument.HighlightAnnotation[] annotations = nativeGetHighlightAnnotations(nativePagePtr);
            List<PdfDocument.HighlightAnnotation> result = new ArrayList<>();
            if (annotations != null) {
                for (PdfDocument.HighlightAnnotation annotation : annotations) {
                    if (annotation != null) {
                        result.add(annotation);
                    }
                }
            }
            return result;
        }
    }

    public List<PdfDocument.PdfComment> getPdfComments(PdfDocument doc, int pageIndex) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            return getPdfComments(doc.documents.get(child), pageIndex - doc.documentStarts[child]);
        }
        synchronized (lock) {
            boolean wasOpen = doc.mNativePagesPtr.get(pageIndex) != null;
            Long nativePagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (nativePagePtr == null) {
                nativePagePtr = openPage(doc, pageIndex);
            }
            if (nativePagePtr == null || nativePagePtr == 0L) {
                return new ArrayList<>();
            }
            try {
                PdfDocument.PdfComment[] comments = nativeGetPdfComments(nativePagePtr);
                List<PdfDocument.PdfComment> result = new ArrayList<>();
                if (comments != null) {
                    for (PdfDocument.PdfComment comment : comments) {
                        if (comment != null) {
                            result.add(comment);
                        }
                    }
                }
                return result;
            } finally {
                if (!wasOpen) {
                    closePage(doc, pageIndex);
                }
            }
        }
    }

    public int countUnsupportedPdfAnnotations(PdfDocument doc, int pageIndex) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            return countUnsupportedPdfAnnotations(doc.documents.get(child), pageIndex - doc.documentStarts[child]);
        }
        synchronized (lock) {
            boolean wasOpen = doc.mNativePagesPtr.get(pageIndex) != null;
            Long nativePagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (nativePagePtr == null) {
                nativePagePtr = openPage(doc, pageIndex);
            }
            if (nativePagePtr == null || nativePagePtr == 0L) {
                return -1;
            }
            try {
                return nativeCountUnsupportedPdfAnnotations(nativePagePtr);
            } finally {
                if (!wasOpen) {
                    closePage(doc, pageIndex);
                }
            }
        }
    }

    public boolean createPdfComment(PdfDocument doc, int pageIndex, RectF rect,
                                    String name, String contents, String author,
                                    String creationDate, String modifiedDate) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            return createPdfComment(doc.documents.get(child), pageIndex - doc.documentStarts[child], rect, name, contents, author, creationDate, modifiedDate);
        }
        return createPdfComment(doc, pageIndex, rect, Color.rgb(255, 255, 0),
                name, contents, author, creationDate, modifiedDate);
    }

    public boolean createPdfComment(PdfDocument doc, int pageIndex, RectF rect, int color,
                                    String name, String contents, String author,
                                    String creationDate, String modifiedDate) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            return createPdfComment(doc.documents.get(child), pageIndex - doc.documentStarts[child], rect, color, name, contents, author, creationDate, modifiedDate);
        }
        synchronized (lock) {
            Long nativePagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (nativePagePtr == null || rect == null) {
                return false;
            }
            float[] nativeRect = new float[] {
                    rect.left,
                    rect.top,
                    rect.right,
                    rect.bottom,
            };
            return nativeCreatePdfComment(
                    doc.mNativeDocPtr,
                    nativePagePtr,
                    pageIndex,
                    nativeRect,
                    Color.red(color),
                    Color.green(color),
                    Color.blue(color),
                    name == null ? "" : name,
                    contents == null ? "" : contents,
                    author == null ? "" : author,
                    creationDate == null ? "" : creationDate,
                    modifiedDate == null ? "" : modifiedDate
            );
        }
    }

    public boolean createPdfHighlightComment(PdfDocument doc, int pageIndex, List<RectF> rects,
                                             int color, String name, String contents,
                                             String author, String creationDate,
                                             String modifiedDate) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            return createPdfHighlightComment(doc.documents.get(child), pageIndex - doc.documentStarts[child], rects, color, name, contents, author, creationDate, modifiedDate);
        }
        return createPdfMarkupComment(doc, pageIndex, rects, color, name, contents, author, creationDate, modifiedDate, PdfDocument.PdfComment.SUBTYPE_HIGHLIGHT);
    }

    public boolean createPdfMarkupComment(PdfDocument doc, int pageIndex, List<RectF> rects,
                                             int color, String name, String contents,
                                             String author, String creationDate,
                                             String modifiedDate, int subtype) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            return createPdfMarkupComment(doc.documents.get(child), pageIndex - doc.documentStarts[child], rects, color, name, contents, author, creationDate, modifiedDate, subtype);
        }
        synchronized (lock) {
            Long nativePagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (nativePagePtr == null || rects == null || rects.isEmpty()) {
                return false;
            }
            float[][] nativeRects = new float[rects.size()][4];
            for (int i = 0; i < rects.size(); i++) {
                RectF rect = rects.get(i);
                if (rect == null) {
                    return false;
                }
                nativeRects[i][0] = rect.left;
                nativeRects[i][1] = rect.top;
                nativeRects[i][2] = rect.right;
                nativeRects[i][3] = rect.bottom;
            }
            return nativeCreatePdfMarkupComment(
                    nativePagePtr,
                    subtype,
                    nativeRects,
                    Color.red(color),
                    Color.green(color),
                    Color.blue(color),
                    Color.alpha(color),
                    name == null ? "" : name,
                    contents == null ? "" : contents,
                    author == null ? "" : author,
                    creationDate == null ? "" : creationDate,
                    modifiedDate == null ? "" : modifiedDate
            );
        }
    }

    public boolean createPdfReply(PdfDocument doc, int pageIndex, RectF rect,
                                  int parentAnnotationIndex, int parentSubtype,
                                  String parentName, String name, String contents,
                                  String author, String creationDate, String modifiedDate) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            return createPdfReply(doc.documents.get(child), pageIndex - doc.documentStarts[child], rect, parentAnnotationIndex, parentSubtype, parentName, name, contents, author, creationDate, modifiedDate);
        }
        synchronized (lock) {
            Long nativePagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (nativePagePtr == null || rect == null) {
                return false;
            }
            float[] nativeRect = new float[] {
                    rect.left,
                    rect.top,
                    rect.right,
                    rect.bottom,
            };
            return nativeCreatePdfReply(
                    nativePagePtr,
                    nativeRect,
                    parentAnnotationIndex,
                    parentSubtype,
                    parentName == null ? "" : parentName,
                    name == null ? "" : name,
                    contents == null ? "" : contents,
                    author == null ? "" : author,
                    creationDate == null ? "" : creationDate,
                    modifiedDate == null ? "" : modifiedDate
            );
        }
    }

    public boolean setPdfCommentContents(PdfDocument doc, int pageIndex, int annotationIndex,
                                         int subtype, String name, String contents,
                                         String modifiedDate) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            return setPdfCommentContents(doc.documents.get(child), pageIndex - doc.documentStarts[child], annotationIndex, subtype, name, contents, modifiedDate);
        }
        synchronized (lock) {
            Long nativePagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (nativePagePtr == null) {
                return false;
            }
            return nativeSetPdfCommentContents(
                    nativePagePtr,
                    annotationIndex,
                    subtype,
                    name == null ? "" : name,
                    contents == null ? "" : contents,
                    modifiedDate == null ? "" : modifiedDate
            );
        }
    }

    public boolean setPdfCommentColor(PdfDocument doc, int pageIndex, int annotationIndex,
                                      int subtype, String name, int color) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            return setPdfCommentColor(doc.documents.get(child), pageIndex - doc.documentStarts[child], annotationIndex, subtype, name, color);
        }
        synchronized (lock) {
            Long nativePagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (nativePagePtr == null) {
                return false;
            }
            return nativeSetPdfCommentColor(nativePagePtr, annotationIndex, subtype,
                    name == null ? "" : name,
                    Color.red(color), Color.green(color), Color.blue(color));
        }
    }

    public boolean setPdfCommentBounds(PdfDocument doc, int pageIndex, int annotationIndex,
                                       int subtype, String name, RectF rect) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            return setPdfCommentBounds(doc.documents.get(child), pageIndex - doc.documentStarts[child], annotationIndex, subtype, name, rect);
        }
        synchronized (lock) {
            Long nativePagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (nativePagePtr == null || rect == null) {
                return false;
            }
            return nativeSetPdfCommentBounds(nativePagePtr, annotationIndex, subtype,
                    name == null ? "" : name,
                    new float[] { rect.left, rect.top, rect.right, rect.bottom });
        }
    }

    public boolean removePdfComment(PdfDocument doc, int pageIndex, int annotationIndex,
                                    int subtype, String name) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            return removePdfComment(doc.documents.get(child), pageIndex - doc.documentStarts[child], annotationIndex, subtype, name);
        }
        synchronized (lock) {
            Long nativePagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (nativePagePtr == null) {
                return false;
            }
            return nativeRemovePdfComment(
                    nativePagePtr,
                    annotationIndex,
                    subtype,
                    name == null ? "" : name
            );
        }
    }

    public List<PdfDocument.FontInfo> getPageFonts(PdfDocument doc, int pageIndex) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            return getPageFonts(doc.documents.get(child), pageIndex - doc.documentStarts[child]);
        }
        synchronized (lock) {
            boolean wasOpen = doc.mNativePagesPtr.get(pageIndex) != null;
            long pagePtr = openPage(doc, pageIndex);
            List<PdfDocument.FontInfo> result = new ArrayList<>();
            if (pagePtr == 0L) {
                return result;
            }
            try {
                String[] entries = nativeGetPageFonts(pagePtr);
                if (entries != null) {
                    for (String entry : entries) {
                        if (entry == null || entry.isEmpty()) {
                            continue;
                        }
                        int separator = entry.lastIndexOf('\t');
                        if (separator <= 0) {
                            continue;
                        }
                        String name = entry.substring(0, separator);
                        boolean embedded = "1".equals(entry.substring(separator + 1));
                        result.add(new PdfDocument.FontInfo(name, embedded));
                    }
                }
            } finally {
                if (!wasOpen) {
                    closePage(doc, pageIndex);
                }
            }
            return result;
        }
    }

    public boolean setHighlightAnnotationColor(PdfDocument doc, int pageIndex, int annotationIndex,
                                               String groupKey, int color) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            return setHighlightAnnotationColor(doc.documents.get(child), pageIndex - doc.documentStarts[child], annotationIndex, groupKey, color);
        }
        synchronized (lock) {
            Long nativePagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (nativePagePtr == null) {
                return false;
            }
            return nativeSetHighlightAnnotationColor(nativePagePtr, annotationIndex, groupKey,
                    Color.red(color), Color.green(color), Color.blue(color));
        }
    }

    public boolean setHighlightAnnotationNote(PdfDocument doc, int pageIndex, int annotationIndex,
                                              String groupKey, String note, String modifiedDate) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            return setHighlightAnnotationNote(doc.documents.get(child), pageIndex - doc.documentStarts[child], annotationIndex, groupKey, note, modifiedDate);
        }
        synchronized (lock) {
            Long nativePagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (nativePagePtr == null) {
                return false;
            }
            return nativeSetHighlightAnnotationNote(nativePagePtr, annotationIndex, groupKey,
                    note == null ? "" : note, modifiedDate);
        }
    }

    public boolean removeHighlightAnnotation(PdfDocument doc, int pageIndex, int annotationIndex,
                                             String groupKey) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            return removeHighlightAnnotation(doc.documents.get(child), pageIndex - doc.documentStarts[child], annotationIndex, groupKey);
        }
        synchronized (lock) {
            Long nativePagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (nativePagePtr == null) {
                return false;
            }
            return nativeRemoveHighlightAnnotation(nativePagePtr, annotationIndex, groupKey);
        }
    }

    public void clearSearchResultsAnnot(PdfDocument doc, int pageIndex) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            clearSearchResultsAnnot(doc.documents.get(child), pageIndex - doc.documentStarts[child]);
            return;
        }
        synchronized (lock) {
            Long nativePagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (nativePagePtr == null || nativePagePtr == 0L) {
                return;
            }
            nativeClearSearchResultAnnot(nativePagePtr, pageIndex);
        }
    }

    public PdfDocument.FormField getFormFieldAtPoint(PdfDocument doc, int pageIndex,
                                                     float x, float y, float tolerance) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            return getFormFieldAtPoint(doc.documents.get(child), pageIndex - doc.documentStarts[child], x, y, tolerance);
        }
        synchronized (lock) {
            Long nativePagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (nativePagePtr == null) {
                return null;
            }
            return nativeGetFormFieldAtPoint(doc.mNativeDocPtr, nativePagePtr, x, y, tolerance);
        }
    }

    public float[] getFormFieldRects(PdfDocument doc, int pageIndex) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            return getFormFieldRects(doc.documents.get(child), pageIndex - doc.documentStarts[child]);
        }
        synchronized (lock) {
            Long nativePagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (nativePagePtr == null) {
                return new float[0];
            }
            float[] rects = nativeGetFormFieldRects(doc.mNativeDocPtr, nativePagePtr);
            return rects == null ? new float[0] : rects;
        }
    }

    public boolean setFormFieldText(PdfDocument doc, int pageIndex, int annotationIndex, String text) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            return setFormFieldText(doc.documents.get(child), pageIndex - doc.documentStarts[child], annotationIndex, text);
        }
        synchronized (lock) {
            Long nativePagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (nativePagePtr == null) {
                return false;
            }
            return nativeSetFormFieldText(doc.mNativeDocPtr, nativePagePtr, annotationIndex,
                    text == null ? "" : text);
        }
    }

    public boolean setFormFieldChecked(PdfDocument doc, int pageIndex, int annotationIndex, boolean checked) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            return setFormFieldChecked(doc.documents.get(child), pageIndex - doc.documentStarts[child], annotationIndex, checked);
        }
        synchronized (lock) {
            Long nativePagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (nativePagePtr == null) {
                return false;
            }
            return nativeSetFormFieldChecked(doc.mNativeDocPtr, nativePagePtr, annotationIndex, checked);
        }
    }

    /** Finds the installed file a link to another PDF names, or null when it is not available. */
    public interface RemoteDocumentResolver {
        java.io.File resolve(String filename);
    }

    /** URI scheme of a link from a single document to a page of another installed PDF. */
    public static final String REMOTE_PDF_LINK_SCHEME = "thoughts-pdf-link";

    private static volatile RemoteDocumentResolver remoteDocumentResolver;

    public static void setRemoteDocumentResolver(RemoteDocumentResolver resolver) {
        remoteDocumentResolver = resolver;
    }

    /**
     * Resolves a link to another PDF against that PDF, which a single document does not hold.
     * The destination becomes a {@link #REMOTE_PDF_LINK_SCHEME} URI for the app to open.
     */
    private PdfDocument.Link remotePdfLink(long linkPtr, String path, Map<String, PdfDocument> opened) {
        RemoteDocumentResolver resolver = remoteDocumentResolver;
        if (resolver == null) return null;
        String filename = new java.io.File(path).getName();
        if (!opened.containsKey(filename)) {
            PdfDocument target = null;
            java.io.File file = resolver.resolve(filename);
            if (file != null) {
                ParcelFileDescriptor fd = null;
                try {
                    fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
                    target = newDocument(fd);
                } catch (Exception e) {
                    if (fd != null) try { fd.close(); } catch (IOException ignored) { }
                }
            }
            opened.put(filename, target);
        }
        PdfDocument target = opened.get(filename);
        if (target == null) return null;
        // A destination the target does not name still opens the target, at its first page.
        float[] destination = nativeGetRemoteLinkDestination(target.mNativeDocPtr, linkPtr);
        if (destination == null) destination = new float[]{0f, Float.NaN, Float.NaN};
        RectF rect = nativeGetLinkRect(linkPtr);
        if (rect == null) return null;
        StringBuilder uri = new StringBuilder(REMOTE_PDF_LINK_SCHEME).append(':').append(filename)
                .append("?page=").append((int) destination[0]);
        if (!Float.isNaN(destination[1])) uri.append("&x=").append(String.format(java.util.Locale.ROOT, "%.3f", destination[1]));
        if (!Float.isNaN(destination[2])) uri.append("&y=").append(String.format(java.util.Locale.ROOT, "%.3f", destination[2]));
        return new PdfDocument.Link(rect, null, uri.toString());
    }

    /** Get all links from given page */
    public List<PdfDocument.Link> getPageLinks(PdfDocument doc, int pageIndex) {
        return getPageLinks(doc, pageIndex, true);
    }

    private List<PdfDocument.Link> getPageLinks(PdfDocument doc, int pageIndex, boolean openRemoteDocuments) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            List<PdfDocument.Link> result = new ArrayList<>();
            // The composition resolves its children's remote links against its own documents.
            for (PdfDocument.Link link : getPageLinks(doc.documents.get(child), pageIndex - doc.documentStarts[child], false))
                result.add(offsetLink(link, doc.documentStarts[child]));
            if (doc.documentFilenames != null) synchronized (lock) {
                PdfDocument source = doc.documents.get(child);
                Long page = source.mNativePagesPtr.get(pageIndex - doc.documentStarts[child]);
                Map<String, PdfDocument> outside = new HashMap<>();
                try {
                if (page != null) for (long pointer : nativeGetPageLinks(page)) {
                    String path = nativeGetRemoteLinkFile(pointer);
                    if (path == null) continue;
                    String filename = new java.io.File(path).getName();
                    boolean inComposition = false;
                    for (int target = 0; target < doc.documents.size(); target++) {
                        if (!filename.equals(doc.documentFilenames[target])) continue;
                        inComposition = true;
                        float[] destination = nativeGetRemoteLinkDestination(doc.documents.get(target).mNativeDocPtr, pointer);
                        if (destination == null) destination = new float[]{0f, Float.NaN, Float.NaN};
                        RectF rect = nativeGetLinkRect(pointer);
                        if (rect != null) result.add(new PdfDocument.Link(rect,
                            doc.documentStarts[target] + (int) destination[0], null,
                            new PointF(destination[1], destination[2])));
                        break;
                    }
                    if (!inComposition) {
                        PdfDocument.Link remote = remotePdfLink(pointer, path, outside);
                        if (remote != null) result.add(offsetLink(remote, doc.documentStarts[child]));
                    }
                }
                } finally {
                    for (PdfDocument remote : outside.values()) if (remote != null) closeDocument(remote);
                }
            }
            return result;
        }
        synchronized (lock) {
            List<PdfDocument.Link> links = new ArrayList<>();
            Long nativePagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (nativePagePtr == null) {
                return links;
            }
            long[] linkPtrs = nativeGetPageLinks(nativePagePtr);
            Map<String, PdfDocument> remoteDocuments = new HashMap<>();
            try {
            for (long linkPtr : linkPtrs) {
                // Remote destinations must be resolved against their target document.
                // Resolving them against this child can create a false page-zero link
                // that consumes the tap before the composition's correct link.
                String remoteFile = nativeGetRemoteLinkFile(linkPtr);
                if (remoteFile != null) {
                    if (openRemoteDocuments) {
                        PdfDocument.Link remote = remotePdfLink(linkPtr, remoteFile, remoteDocuments);
                        if (remote != null) links.add(remote);
                    }
                    continue;
                }
                Integer index = nativeGetDestPageIndex(doc.mNativeDocPtr, linkPtr);
                float[] destinationLocation = nativeGetDestLocation(doc.mNativeDocPtr, linkPtr);
                String uri = nativeGetLinkURI(doc.mNativeDocPtr, linkPtr);

                RectF rect = nativeGetLinkRect(linkPtr);
                if (rect != null && (index != null || (uri != null && !uri.isEmpty()))) {
                    links.add(new PdfDocument.Link(
                            rect,
                            index,
                            uri,
                            linkDestinationPoint(destinationLocation)
                    ));
                }

            }
            } finally {
                for (PdfDocument remote : remoteDocuments.values()) if (remote != null) closeDocument(remote);
            }
            return links;
        }
    }

    private static PointF linkDestinationPoint(float[] location) {
        if (location == null || location.length < 2) {
            return null;
        }
        float x = location[0];
        float y = location[1];
        boolean hasX = !Float.isNaN(x) && !Float.isInfinite(x);
        boolean hasY = !Float.isNaN(y) && !Float.isInfinite(y);
        return hasX || hasY ? new PointF(x, y) : null;
    }

    /**
     * Map page coordinates to device screen coordinates
     *
     * @param doc       pdf document
     * @param pageIndex index of page
     * @param startX    left pixel position of the display area in device coordinates
     * @param startY    top pixel position of the display area in device coordinates
     * @param sizeX     horizontal size (in pixels) for displaying the page
     * @param sizeY     vertical size (in pixels) for displaying the page
     * @param rotate    page orientation: 0 (normal), 1 (rotated 90 degrees clockwise),
     *                  2 (rotated 180 degrees), 3 (rotated 90 degrees counter-clockwise)
     * @param pageX     X value in page coordinates
     * @param pageY     Y value in page coordinate
     * @return mapped coordinates
     */
    public Point mapPageCoordsToDevice(PdfDocument doc, int pageIndex, int startX, int startY, int sizeX,
                                       int sizeY, int rotate, double pageX, double pageY) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            return mapPageCoordsToDevice(doc.documents.get(child), pageIndex - doc.documentStarts[child], startX, startY, sizeX, sizeY, rotate, pageX, pageY);
        }
        synchronized (lock) {
            Long pagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (pagePtr == null || pagePtr == 0L) {
                return null;
            }
            return nativePageCoordsToDevice(pagePtr, startX, startY, sizeX, sizeY, rotate, pageX, pageY);
        }
    }

    /**
     * @return mapped coordinates
     * @see PdfiumCore#mapPageCoordsToDevice(PdfDocument, int, int, int, int, int, int, double, double)
     */
    public RectF mapRectToDevice(PdfDocument doc, int pageIndex, int startX, int startY, int sizeX,
                                 int sizeY, int rotate, RectF coords) {
        if (doc.isComposition()) {
            int child = doc.documentIndex(pageIndex);
            return mapRectToDevice(doc.documents.get(child), pageIndex - doc.documentStarts[child], startX, startY, sizeX, sizeY, rotate, coords);
        }

        Point leftTop = mapPageCoordsToDevice(doc, pageIndex, startX, startY, sizeX, sizeY, rotate,
                coords.left, coords.top);
        Point rightBottom = mapPageCoordsToDevice(doc, pageIndex, startX, startY, sizeX, sizeY, rotate,
                coords.right, coords.bottom);
        if (leftTop == null || rightBottom == null) {
            return null;
        }
        return new RectF(leftTop.x, leftTop.y, rightBottom.x, rightBottom.y);
    }
}
