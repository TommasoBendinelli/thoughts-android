/**
 * Copyright 2016 Bartosz Schiller
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.barteksc.pdfviewer;

import static com.github.barteksc.pdfviewer.util.Constants.Pinch.MAXIMUM_ZOOM;
import static com.github.barteksc.pdfviewer.util.Constants.Pinch.MINIMUM_ZOOM;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.PaintFlagsDrawFilter;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.RelativeLayout;

import com.github.barteksc.pdfviewer.exception.PageRenderingException;
import com.github.barteksc.pdfviewer.link.DefaultLinkHandler;
import com.github.barteksc.pdfviewer.link.LinkHandler;
import com.github.barteksc.pdfviewer.listener.Callbacks;
import com.github.barteksc.pdfviewer.listener.OnDocumentInteractionListener;
import com.github.barteksc.pdfviewer.listener.OnDrawListener;
import com.github.barteksc.pdfviewer.listener.OnErrorListener;
import com.github.barteksc.pdfviewer.listener.OnLoadCompleteListener;
import com.github.barteksc.pdfviewer.listener.OnLongPressListener;
import com.github.barteksc.pdfviewer.listener.OnPageChangeListener;
import com.github.barteksc.pdfviewer.listener.OnPageErrorListener;
import com.github.barteksc.pdfviewer.listener.OnPageScrollListener;
import com.github.barteksc.pdfviewer.listener.OnRenderListener;
import com.github.barteksc.pdfviewer.listener.OnTapListener;
import com.github.barteksc.pdfviewer.listener.OnTextSelectionChangeListener;
import com.github.barteksc.pdfviewer.model.CropBounds;
import com.github.barteksc.pdfviewer.model.CropMargins;
import com.github.barteksc.pdfviewer.model.PagePart;
import com.github.barteksc.pdfviewer.preview.GenerationSource;
import com.github.barteksc.pdfviewer.preview.PreviewBitmapAdapter;
import com.github.barteksc.pdfviewer.preview.PreviewBitmapPool;
import com.github.barteksc.pdfviewer.preview.PreviewCodec;
import com.github.barteksc.pdfviewer.preview.PreviewStore;
import com.github.barteksc.pdfviewer.preview.PreviewSweepCursor;
import com.github.barteksc.pdfviewer.preview.TagSource;
import com.github.barteksc.pdfviewer.preview.TransientPageFilter;
import com.github.barteksc.pdfviewer.scroll.ScrollHandle;
import com.github.barteksc.pdfviewer.source.AssetSource;
import com.github.barteksc.pdfviewer.source.ByteArraySource;
import com.github.barteksc.pdfviewer.source.DocumentSource;
import com.github.barteksc.pdfviewer.source.FileSource;
import com.github.barteksc.pdfviewer.source.InputStreamSource;
import com.github.barteksc.pdfviewer.source.UriSource;
import com.github.barteksc.pdfviewer.util.Constants;
import com.github.barteksc.pdfviewer.util.FitPolicy;
import com.github.barteksc.pdfviewer.util.MathUtils;
import com.github.barteksc.pdfviewer.util.SnapEdge;
import com.github.barteksc.pdfviewer.util.Util;
import com.shockwave.pdfium.PdfDocument;
import com.shockwave.pdfium.PdfiumCore;
import com.shockwave.pdfium.util.Size;
import com.shockwave.pdfium.util.SizeF;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * It supports animations, zoom, cache, and swipe.
 * <p>
 * To fully understand this class you must know its principles :
 * - The PDF document is seen as if we always want to draw all the pages.
 * - The thing is that we only draw the visible parts.
 * - All parts are the same size, this is because we can't interrupt a native page rendering,
 * so we need these renderings to be as fast as possible, and be able to interrupt them
 * as soon as we can.
 * - The parts are loaded when the current offset or the current zoom level changes
 * <p>
 * Important :
 * - DocumentPage = A page of the PDF document.
 * - UserPage = A page as defined by the user.
 * By default, they're the same. But the user can change the pages order
 * using {@link #load(DocumentSource, String, int[])}. In this
 * particular case, a userPage of 5 can refer to a documentPage of 17.
 */
public class PDFView extends RelativeLayout {

    public interface OnPdfCommentMoveListener {
        boolean onMove(RectF pdfBounds);
    }

    private static final String TAG = PDFView.class.getSimpleName();

    public static final float DEFAULT_MAX_SCALE = 3.0f;
    public static final float DEFAULT_MID_SCALE = 1.75f;
    public static final float DEFAULT_MIN_SCALE = 1.0f;

    public static final float NORMAL_SCALE = 1.0f;

    private static final float HIGHLIGHT_HIT_TOLERANCE = 2.5f;
    private static final float HIGHLIGHT_MATCH_TOLERANCE = 1.5f;

    private static final int COMMENT_MARKER_FILL_COLOR = 0xFFFFFF00;
    private static final int COMMENT_MARKER_UNSELECTED_ALPHA = 191;
    private static final int COMMENT_MARKER_SHADOW_COLOR = 0x30000000;
    private static final float COMMENT_MARKER_SIZE_DP = 20f;
    private static final float HIGHLIGHT_COMMENT_MARKER_SIZE_DP = 16f;
    private static final float COMMENT_MARKER_CORNER_RADIUS_DP = 4f;
    private static final float COMMENT_MARKER_SHADOW_SPREAD_DP = 1f;
    private static final float COMMENT_MARKER_SHADOW_OFFSET_Y_DP = 1f;
    private static final float COMMENT_MARKER_BADGE_DIAMETER_DP = 6f;
    private static final float COMMENT_MARKER_BADGE_INSET_DP = 1f;
    private static final int COMMENT_MARKER_BADGE_COLOR = 0xFF5C4600;
    private static final int COMMENT_MARKER_BADGE_GLYPH_COLOR = 0xFFFFF4BE;
    private static final float COMMENT_MARKER_TOUCH_TARGET_DP = 48f;
    private static final String COMMENT_FRAGMENT_MARKER = "#oe-fragment-";

    private static final int FORM_FIELD_FILL_COLOR = 0x282196F3; // 16% Material blue tint

    private static final int FORM_FIELD_STROKE_COLOR = 0x662196F3; // 40% Material blue hairline

    private static final float FORM_FIELD_TOUCH_TOLERANCE_DP = 12f;

    private static final int LINK_STROKE_COLOR = 0xCC1976D2;

    private static final int LINK_STROKE_NIGHT_COLOR = 0xE642A5F5;

    private static final float LINK_STROKE_WIDTH_DP = 1f;

    private static final float LINK_CORNER_RADIUS_DP = 2f;

    private static final int LINK_DESTINATION_FILL_COLOR = 0xFFFFD54F;

    private static final int LINK_DESTINATION_STROKE_COLOR = 0xFFFFA000;

    private static final int LINK_DESTINATION_NIGHT_FILL_COLOR = 0xFFFFE082;

    private static final int LINK_DESTINATION_NIGHT_STROKE_COLOR = 0xFFFFC107;

    private static final float LINK_DESTINATION_RADIUS_START_DP = 14f;

    private static final float LINK_DESTINATION_RADIUS_END_DP = 26f;

    private static final float LINK_DESTINATION_STROKE_WIDTH_DP = 2.5f;

    private static final long LINK_DESTINATION_DURATION_MS = 2200L;

    private static final long WHEEL_SETTLE_DELAY_MS = 250;

    public static class ViewState {

        public final float zoom;
        public final int pageIndex;
        public final boolean swipeVertical;
        public final boolean horizontalReadingDirectionRtl;
        public final float relativeCrossAxisCenter;
        public final float pageCenterOffsetRatio;
        public final int pagesPerRow;
        public final boolean firstPageAlone;

        public ViewState(float zoom, int pageIndex, boolean swipeVertical, boolean horizontalReadingDirectionRtl,
                         float relativeCrossAxisCenter, float pageCenterOffsetRatio,
                         int pagesPerRow, boolean firstPageAlone) {
            this.zoom = zoom;
            this.pageIndex = pageIndex;
            this.swipeVertical = swipeVertical;
            this.horizontalReadingDirectionRtl = horizontalReadingDirectionRtl;
            this.relativeCrossAxisCenter = relativeCrossAxisCenter;
            this.pageCenterOffsetRatio = pageCenterOffsetRatio;
            this.pagesPerRow = pagesPerRow;
            this.firstPageAlone = firstPageAlone;
        }
    }

    public static final class HighlightFragment {
        public final int pageIndex;
        public final List<RectF> pdfRects;
        public final String selectedText;

        public HighlightFragment(int pageIndex, List<RectF> pdfRects, String selectedText) {
            List<RectF> safeRects = pdfRects == null ? Collections.<RectF>emptyList() : pdfRects;
            this.pageIndex = pageIndex;
            this.pdfRects = Collections.unmodifiableList(new ArrayList<>(safeRects));
            this.selectedText = selectedText == null ? "" : selectedText;
        }
    }

    public static final class HighlightRequest {
        public final List<HighlightFragment> fragments;
        public final int pageIndex;
        public final List<RectF> pdfRects;
        public final String selectedText;

        public HighlightRequest(int pageIndex, List<RectF> pdfRects, String selectedText) {
            this(Collections.singletonList(new HighlightFragment(pageIndex, pdfRects, selectedText)));
        }

        public HighlightRequest(List<HighlightFragment> fragments) {
            List<HighlightFragment> safeFragments = fragments == null
                    ? Collections.<HighlightFragment>emptyList()
                    : new ArrayList<>(fragments);
            this.fragments = Collections.unmodifiableList(safeFragments);
            HighlightFragment first = safeFragments.isEmpty()
                    ? new HighlightFragment(-1, Collections.<RectF>emptyList(), "")
                    : safeFragments.get(0);
            this.pageIndex = first.pageIndex;
            this.pdfRects = first.pdfRects;
            StringBuilder text = new StringBuilder();
            for (HighlightFragment fragment : safeFragments) {
                if (fragment.selectedText.isEmpty()) {
                    continue;
                }
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(fragment.selectedText);
            }
            this.selectedText = text.toString();
        }
    }

    public static final class FormField {
        public final int pageIndex;
        public final int annotationIndex;
        public final int type;
        public final String name;
        public final String alternateName;
        public final String value;
        public final boolean checked;
        public final boolean readOnly;
        public final boolean multiline;

        public FormField(int pageIndex, PdfDocument.FormField field) {
            this.pageIndex = pageIndex;
            this.annotationIndex = field.getAnnotationIndex();
            this.type = field.getType();
            this.name = field.getName();
            this.alternateName = field.getAlternateName();
            this.value = field.getValue();
            this.checked = field.isChecked();
            this.readOnly = field.isReadOnly();
            this.multiline = field.isMultiline();
        }
    }

    public static final class HighlightAnnotation {
        public final int pageIndex;
        public final int annotationIndex;
        public final String groupKey;
        public final RectF viewBounds;
        public final RectF pdfBounds;
        public final String quote;
        public final String note;

        public HighlightAnnotation(int pageIndex, int annotationIndex, String groupKey,
                                   RectF viewBounds, String quote) {
            this(pageIndex, annotationIndex, groupKey, viewBounds, null, quote, null);
        }

        public HighlightAnnotation(int pageIndex, int annotationIndex, String groupKey,
                                   RectF viewBounds, RectF pdfBounds, String quote) {
            this(pageIndex, annotationIndex, groupKey, viewBounds, pdfBounds, quote, null);
        }

        public HighlightAnnotation(int pageIndex, int annotationIndex, String groupKey,
                                   RectF viewBounds, RectF pdfBounds, String quote, String note) {
            this.pageIndex = pageIndex;
            this.annotationIndex = annotationIndex;
            this.groupKey = groupKey == null ? "" : groupKey;
            this.viewBounds = viewBounds == null ? null : new RectF(viewBounds);
            this.pdfBounds = pdfBounds == null ? null : new RectF(pdfBounds);
            this.quote = quote == null ? "" : quote;
            this.note = note == null ? "" : note;
        }
    }

    public static final class PdfComment {
        public static final int SUBTYPE_TEXT = PdfDocument.PdfComment.SUBTYPE_TEXT;
        public static final int SUBTYPE_HIGHLIGHT = PdfDocument.PdfComment.SUBTYPE_HIGHLIGHT;
        public static final int SUBTYPE_STRIKEOUT = PdfDocument.PdfComment.SUBTYPE_STRIKEOUT;
        public static boolean isTextMarkup(int subtype) { return PdfDocument.PdfComment.isTextMarkup(subtype); }
        public static String subtypeName(int subtype) { return PdfDocument.PdfComment.subtypeName(subtype); }
        public static int subtypeFromName(String name) { return PdfDocument.PdfComment.subtypeFromName(name); }

        public final int pageIndex;
        public final int annotationIndex;
        public final int subtype;
        public final String name;
        public final RectF viewBounds;
        public final RectF pdfBounds;
        public final int color;
        public final String contents;
        public final String author;
        public final String creationDate;
        public final String modifiedDate;
        public final String inReplyToName;
        public final String anchorStatus;
        public List<RectF> pdfRects;

        public PdfComment withPdfRects(List<RectF> rects) {
            List<RectF> copies = new ArrayList<>();
            for (RectF rect : rects) copies.add(new RectF(rect));
            pdfRects = Collections.unmodifiableList(copies);
            return this;
        }

        public PdfComment(int pageIndex, int annotationIndex, int subtype, String name,
                          RectF viewBounds, RectF pdfBounds, String contents, String author,
                          String creationDate, String modifiedDate, String inReplyToName) {
            this(pageIndex, annotationIndex, subtype, name, viewBounds, pdfBounds,
                    COMMENT_MARKER_FILL_COLOR, contents, author, creationDate, modifiedDate,
                    inReplyToName, "");
        }

        public PdfComment(int pageIndex, int annotationIndex, int subtype, String name,
                          RectF viewBounds, RectF pdfBounds, int color, String contents, String author,
                          String creationDate, String modifiedDate, String inReplyToName) {
            this(pageIndex, annotationIndex, subtype, name, viewBounds, pdfBounds, color,
                    contents, author, creationDate, modifiedDate, inReplyToName, "");
        }

        public PdfComment(int pageIndex, int annotationIndex, int subtype, String name,
                          RectF viewBounds, RectF pdfBounds, int color, String contents, String author,
                          String creationDate, String modifiedDate, String inReplyToName,
                          String anchorStatus) {
            this.pageIndex = pageIndex;
            this.annotationIndex = annotationIndex;
            this.subtype = subtype;
            this.name = name == null ? "" : name;
            this.viewBounds = viewBounds == null ? null : new RectF(viewBounds);
            this.pdfBounds = pdfBounds == null ? null : new RectF(pdfBounds);
            this.pdfRects = pdfBounds == null ? Collections.emptyList()
                    : Collections.singletonList(new RectF(pdfBounds));
            this.color = color;
            this.contents = contents == null ? "" : contents;
            this.author = author == null ? "" : author;
            this.creationDate = creationDate == null ? "" : creationDate;
            this.modifiedDate = modifiedDate == null ? "" : modifiedDate;
            this.inReplyToName = inReplyToName == null ? "" : inReplyToName;
            this.anchorStatus = anchorStatus == null ? "" : anchorStatus;
        }

        public boolean isReply() {
            return !inReplyToName.isEmpty();
        }
    }

    /**
     * One physical, sidecar-backed comment fragment.
     *
     * Managed comments deliberately live outside Pdfium while a document is being read. This
     * keeps the sidecar authoritative and avoids rebuilding the native annotation tree whenever
     * a managed document opens. Multi-page highlights are represented by one record per page;
     * continuation names use {@link #managedCommentFragmentName(String, int)}.
     */
    public static final class ManagedPdfComment {
        public final int pageIndex;
        public final int subtype;
        public final String name;
        public final List<RectF> pdfRects;
        public final int color;
        public final String contents;
        public final String author;
        public final String creationDate;
        public final String modifiedDate;
        public final String inReplyToName;
        public final String anchorStatus;

        public ManagedPdfComment(
                int pageIndex,
                int subtype,
                String name,
                List<RectF> pdfRects,
                int color,
                String contents,
                String author,
                String creationDate,
                String modifiedDate,
                String inReplyToName,
                String anchorStatus) {
            this.pageIndex = pageIndex;
            this.subtype = subtype;
            this.name = name == null ? "" : name;
            List<RectF> safeRects = new ArrayList<>();
            if (pdfRects != null) {
                for (RectF rect : pdfRects) {
                    if (rect != null) {
                        safeRects.add(new RectF(rect));
                    }
                }
            }
            this.pdfRects = Collections.unmodifiableList(safeRects);
            this.color = color;
            this.contents = contents == null ? "" : contents;
            this.author = author == null ? "" : author;
            this.creationDate = creationDate == null ? "" : creationDate;
            this.modifiedDate = modifiedDate == null ? "" : modifiedDate;
            this.inReplyToName = inReplyToName == null ? "" : inReplyToName;
            this.anchorStatus = anchorStatus == null ? "" : anchorStatus;
        }
    }

    public static String managedCommentFragmentName(String logicalName, int fragmentIndex) {
        if (fragmentIndex <= 0) {
            return logicalName == null ? "" : logicalName;
        }
        return (logicalName == null ? "" : logicalName)
                + COMMENT_FRAGMENT_MARKER + fragmentIndex;
    }

    private float minZoom = DEFAULT_MIN_SCALE;
    private float midZoom = DEFAULT_MID_SCALE;
    private float maxZoom = DEFAULT_MAX_SCALE;

    public void clearCache() {
        cacheManager.recycle();
    }

    public void reloadPages() {
        clearCache();
        loadPages();
    }

    public void refreshPage(int pageIndex) {
        pageContentChanged(pageIndex);
    }

    public void pageContentChanged(int page) {
        if (pdfFile == null) {
            return;
        }
        invalidatePageContent(page);
        if (renderScheduler != null) {
            renderScheduler.cancelPage(page);
        }
        loadPages();
    }

    private void invalidatePageContent(int page) {
        bumpPageGeneration(page);
        cacheManager.invalidatePageParts(page);
        pdfFile.invalidateHighlightAnnotationCache(page);
        pdfFile.invalidatePdfCommentCache(page);
        pdfFile.invalidateFormFieldRectCache(page);
        pdfFile.invalidateLinkCache(page);
        PreviewStore<Bitmap> store = previewStore;
        if (store != null) {
            store.invalidatePage(page);
        }
        sweepCursor.reset(page);
        requestPrewarm(page);
    }

    public void overlayContentChanged(int page) {
        if (pdfFile == null) {
            return;
        }
        requestPrewarm(page);
        invalidate();
    }

    private void bumpPageGeneration(int page) {
        if (pageGenerations != null && page >= 0 && page < pageGenerations.length()) {
            pageGenerations.incrementAndGet(page);
        }
    }

    public int getPageGeneration(int page) {
        if (pageGenerations != null && page >= 0 && page < pageGenerations.length()) {
            return pageGenerations.get(page);
        }
        return 0;
    }

    int getCurrentRenderEpoch() {
        return currentRenderEpoch;
    }

    void requestPrewarm(int page) {
        if (renderScheduler == null || page < 0) {
            return;
        }
        synchronized (prewarmPending) {
            if (!prewarmPending.add(page)) {
                return;
            }
        }
        renderScheduler.submitPrewarm(page);
    }

    public void setRenderInteractionActive(boolean active) {
        if (renderScheduler != null) {
            renderScheduler.setInteractionActive(active);
        }
    }

    void onPrewarmStarted(int page) {
        synchronized (prewarmPending) {
            prewarmPending.remove(page);
        }
    }

    void onPrewarmComplete(int page) {
        synchronized (prewarmPending) {
            prewarmPending.remove(page);
        }
        postInvalidate();
    }

    void requestPreview(int page) {
        final PreviewStore<Bitmap> store = previewStore;
        if (store == null || pdfFile == null || page < 0 || page >= pdfFile.getPagesCount()) {
            return;
        }
        synchronized (previewPending) {
            if (!previewPending.add(page)) {
                return;
            }
        }
        final int requested = page;
        store.requestDecode(page, new Runnable() {
            @Override
            public void run() {
                clearPreviewPending(requested);
                postInvalidate();
            }
        }, new Runnable() {
            @Override
            public void run() {
                submitPreviewRender(requested);
            }
        });
    }

    private void submitPreviewRender(int page) {
        RenderScheduler scheduler = renderScheduler;
        RenderTask task = buildPreviewTask(page, RenderTask.P0);
        if (scheduler == null || task == null) {
            clearPreviewPending(page);
            return;
        }
        scheduler.submit(task);
    }

    private RenderTask buildPreviewTask(int page, int priorityClass) {
        if (pdfFile == null || page < 0 || page >= pdfFile.getPagesCount()) {
            return null;
        }
        float width = previewBucket;
        float height = width * pdfFile.fullPageAspectRatio(page);
        if (width <= 0f || height <= 0f) {
            return null;
        }
        return RenderTask.preview(page, width, height, annotationRendering, priorityClass);
    }

    private void clearPreviewPending(int page) {
        synchronized (previewPending) {
            previewPending.remove(page);
        }
    }

    Bitmap peekPreview(int page) {
        PreviewStore<Bitmap> store = previewStore;
        return store == null ? null : store.peek(page);
    }

    void onPreviewStarted(int page) {
        clearPreviewPending(page);
    }

    boolean onPreviewRendered(int page, Bitmap bitmap, int generation) {
        PreviewStore<Bitmap> store = previewStore;
        if (store != null && generation == getPageGeneration(page)) {
            store.put(page, bitmap, generation);
        } else if (bitmap != null) {
            synchronized (bitmap) {
                bitmap.recycle();
            }
        }
        clearPreviewPending(page);
        boolean repaint = isPageInPreviewRange(page);
        if (repaint) {
            postInvalidate();
        }
        return repaint;
    }

    public void setPreviewTagSource(TagSource tagSource) {
        this.previewTagSource = tagSource;
    }

    public void setRenderFlinging(boolean flinging) {
        if (renderScheduler != null) {
            renderScheduler.setFlinging(flinging);
        }
    }

    public void attachPreviewDisk(File dir, String docKey) {
        PreviewStore<Bitmap> store = previewStore;
        if (store == null || dir == null || docKey == null) {
            return;
        }
        store.attachDisk(dir, docKey);
        sweepCursor.resetAll();
    }

    private boolean isPreviewLowTier() {
        Boolean cached = previewLowTierCache;
        if (cached != null) {
            return cached;
        }
        boolean low = PreviewBucketPolicy.isLowTier(getContext());
        previewLowTierCache = low;
        return low;
    }

    int previewExtraFlags() {
        return isPreviewLowTier() ? PdfiumCore.RENDER_FLAG_NO_SMOOTHIMAGE : 0;
    }

    private void initPreviewStore(PdfFile file) {
        previewBucket = PreviewBucketPolicy.bucketFor(file.getMaxPageWidth(), isPreviewLowTier());

        previewEncoder = Executors.newSingleThreadExecutor(previewThreadFactory("pdf-preview-encode"));
        previewDecoder = Executors.newFixedThreadPool(2, previewThreadFactory("pdf-preview-decode"));

        AndroidPreviewBitmaps bitmaps = new AndroidPreviewBitmaps(previewBucket);
        GenerationSource generationSource = new GenerationSource() {
            @Override
            public int generationOf(int page) {
                return getPageGeneration(page);
            }
        };
        TagSource tagSource = new TagSource() {
            @Override
            public int tagOf(int page) {
                TagSource source = previewTagSource;
                return source == null ? 0 : source.tagOf(page);
            }
        };
        TransientPageFilter transientFilter = new TransientPageFilter() {
            @Override
            public boolean isTransient(int page) {
                return isSearchMarkerPage(page);
            }
        };
        long memoryBudget = Runtime.getRuntime().maxMemory() / 8;
        long diskBudget = Constants.PREVIEW_DISK_BUDGET_BYTES;
        previewStore = new PreviewStore<>(bitmaps, bitmaps, generationSource, tagSource, transientFilter,
                previewEncoder, previewDecoder, memoryBudget, diskBudget, 4);
        previewStore.setBucket(previewBucket);
        bitmaps.attachPool(previewStore.getPool());
        if (pendingPreviewDiskDir != null && pendingPreviewDocKey != null) {
            previewStore.attachDisk(pendingPreviewDiskDir, pendingPreviewDocKey);
        }
        pendingPreviewDiskDir = null;
        pendingPreviewDocKey = null;

        sweepCursor.ensureCapacity(file.getPagesCount());
        sweepCursor.resetAll();
        renderScheduler.setIdleProducer(createSweepProducer());
        registerPreviewTrimCallbacks();
    }

    private RenderQueue.IdleProducer createSweepProducer() {
        final PreviewSweepCursor.Coverage coverage = new PreviewSweepCursor.Coverage() {
            @Override
            public boolean covered(int page) {
                PreviewStore<Bitmap> store = previewStore;
                if (store == null) {
                    return true;
                }
                return store.peek(page) != null || store.hasOnDisk(page);
            }

            @Override
            public boolean pending(int page) {
                synchronized (previewPending) {
                    return previewPending.contains(page);
                }
            }
        };
        return new RenderQueue.IdleProducer() {
            @Override
            public RenderTask produce() {
                PdfFile file = pdfFile;
                if (file == null || previewStore == null) {
                    return null;
                }
                int count = file.getPagesCount();
                sweepCursor.ensureCapacity(count);
                int page = sweepCursor.nextPage(currentPage, lastScrollDir, coverage);
                if (page < 0) {
                    return null;
                }
                return buildPreviewTask(page, RenderTask.P2);
            }
        };
    }

    private void registerPreviewTrimCallbacks() {
        if (previewTrimCallbacks != null) {
            return;
        }
        previewTrimCallbacks = new ComponentCallbacks2() {
            @Override
            public void onTrimMemory(int level) {
                PreviewStore<Bitmap> store = previewStore;
                if (store != null && (level >= TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_UI_HIDDEN)) {
                    store.dropMemory();
                }
            }

            @Override
            public void onConfigurationChanged(Configuration newConfig) {
            }

            @Override
            public void onLowMemory() {
            }
        };
        getContext().registerComponentCallbacks(previewTrimCallbacks);
    }

    private static ThreadFactory previewThreadFactory(final String name) {
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, name);
                thread.setPriority(Thread.NORM_PRIORITY);
                return thread;
            }
        };
    }

    boolean isSearchMarkerPage(int page) {
        synchronized (searchMarkerPages) {
            return searchMarkerPages.contains(page);
        }
    }

    /**
     * START - scrolling in first page direction
     * END - scrolling in last page direction
     * NONE - not scrolling
     */
    enum ScrollDir {
        NONE, START, END
    }

    private ScrollDir scrollDir = ScrollDir.NONE;

    /**
     * Rendered parts go to the cache manager
     */
    CacheManager cacheManager;

    /**
     * Animation manager manage all offset and zoom animation
     */
    private AnimationManager animationManager;

    /**
     * Drag manager manage all touch events
     */
    private DragPinchManager dragPinchManager;

    private TextSelectionManager textSelectionManager;

    private StampPlacementManager stampPlacementManager;

    private ReviewRegionSelectionManager reviewRegionSelectionManager;

    private PdfCommentMoveManager pdfCommentMoveManager;

    private Runnable onStampPlacementDiscardListener;

    PdfFile pdfFile;

    /**
     * The index of the current sequence
     */
    private int currentPage;

    /**
     * If you picture all the pages side by side in their optimal width,
     * and taking into account the zoom level, the current offset is the
     * position of the left border of the screen in this big picture
     */
    private float currentXOffset = 0;

    /**
     * If you picture all the pages side by side in their optimal width,
     * and taking into account the zoom level, the current offset is the
     * position of the left border of the screen in this big picture
     */
    private float currentYOffset = 0;

    private final RectF contentViewport = new RectF();

    private boolean hasContentViewport = false;

    /**
     * The zoom level, always >= 1
     */
    private float zoom = 1f;

    /**
     * True if the PDFView has been recycled
     */
    private boolean recycled = true;

    private volatile int currentRenderEpoch;

    /**
     * Current state of the view
     */
    private State state = State.DEFAULT;

    /**
     * Async task used during the loading phase to decode a PDF document
     */
    private DecodingAsyncTask decodingAsyncTask;

    RenderScheduler renderScheduler;

    private PagesLoader pagesLoader;

    private AtomicIntegerArray pageGenerations;

    private final Set<Integer> prewarmPending = new LinkedHashSet<>();

    // Search is a transient overlay, independent of PDF annotation visibility and saving.
    private int searchHighlightPage = -1;
    private float searchBottomInset = 0f;
    /** Height of a card floating over the bottom of the view; the document can scroll above it. */
    private float overlayBottomInset = 0f;
    private List<RectF> searchHighlightRects = Collections.emptyList();
    private List<RectF> activeSearchHighlightRects = Collections.emptyList();
    private final Paint searchHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Set<Integer> searchMarkerPages = new LinkedHashSet<>();

    private static volatile Boolean previewLowTierCache;

    private volatile PreviewStore<Bitmap> previewStore;

    private final Set<Integer> previewPending = new LinkedHashSet<>();

    private volatile TagSource previewTagSource;

    private int previewBucket;

    private File pendingPreviewDiskDir;

    private String pendingPreviewDocKey;

    private float[] cachedPageGeometry;

    private final PreviewSweepCursor sweepCursor = new PreviewSweepCursor();

    private volatile int lastScrollDir = 1;

    private ExecutorService previewEncoder;

    private ExecutorService previewDecoder;

    private ComponentCallbacks2 previewTrimCallbacks;

    private final Set<Integer> previewVisiblePages = new LinkedHashSet<>();

    Callbacks callbacks = new Callbacks();

    /**
     * Paint object for drawing
     */
    private Paint paint;

    /**
     * Paint object for drawing debug stuff
     */
    private Paint debugPaint;

    private Paint selectedHighlightFillPaint;

    private Paint selectedHighlightStrokePaint;

    private Paint highlightAnnotationOverlayPaint;

    private Paint highlightAnnotationMultiplyPaint;

    private Paint managedHighlightPaint;
    private final Path managedHighlightPath = new Path();

    private Paint agentProposalStrikePaint;

    private Paint agentProposalSelectionPaint;

    private Paint agentProposalMarkerFillPaint;

    private Paint agentProposalMarkerStrokePaint;

    private Paint agentProposalMarkerGlyphPaint;
    /** Whether the press now ending may act on a marker (it neither stopped nor saw the page move). */
    private final MarkerPressGuard markerPressGuard = new MarkerPressGuard();
    /** The proposal markers as accessibility nodes, once the app says what they do. */
    private AgentProposalMarkerAccessibility markerAccessibility;

    private final Path agentProposalMarkerPath = new Path();

    private List<AgentProposalOverlay> agentProposalOverlays = Collections.emptyList();

    private List<AgentProposalMarkerGeometry.Marker> agentProposalMarkerLayout = Collections.emptyList();
    private List<AgentProposalOverlay> agentProposalMarkerLayoutSource;
    private PdfFile agentProposalMarkerLayoutPdf;
    private float agentProposalMarkerLayoutZoom = Float.NaN;
    private int agentProposalMarkerLayoutWidth, agentProposalMarkerLayoutHeight;

    private final Path highlightAnnotationGroupPath = new Path();

    private Paint commentMarkerFillPaint;

    private Paint commentMarkerShadowPaint;

    private Paint commentMarkerBadgePaint;

    private Paint commentMarkerBadgeGlyphPaint;

    private final Path commentMarkerPath = new Path();

    private final Path commentMarkerShadowPath = new Path();

    private Paint formFieldFillPaint;

    private Paint formFieldStrokePaint;

    private Paint linkStrokePaint;

    private Paint linkDestinationFillPaint;

    private Paint linkDestinationStrokePaint;

    private int linkDestinationPage = -1;

    private PointF linkDestinationPdfPoint;

    private long linkDestinationMarkerStartedAt;

    private Paint reviewMaskPaint;

    private int reviewMaskPage = -1;
    private boolean reviewMaskInverted = false;
    /** A revealed answer keeps its hidden area framed instead of covered, as on desktop. */
    private boolean reviewMaskRevealed = false;
    private Paint reviewRevealFramePaint;
    private Paint reviewRevealFillPaint;

    private final java.util.SortedMap<Integer, List<RectF>> reviewMaskRects = new java.util.TreeMap<>();

    private List<LogicalCommentOverlay> logicalCommentOverlays = Collections.emptyList();

    private int logicalCommentMarkerPage = -1;
    private String logicalCommentMarkerId = "";

    private final List<RectF> logicalCommentMarkerRects = new ArrayList<>();

    private HighlightAnnotation selectedHighlightAnnotation;

    private PdfComment selectedPdfComment;

    private final Object managedPdfCommentLock = new Object();

    /** null means that embedded Pdfium comments are in use; an empty list is authoritative. */
    private List<ManagedPdfComment> managedPdfCommentRecords;

    private List<PdfComment> managedPdfCommentSnapshot = Collections.emptyList();

    private Map<Integer, List<PdfComment>> managedPdfCommentsByPage = Collections.emptyMap();

    private Map<String, ManagedPdfComment> managedPdfCommentRecordsByName = Collections.emptyMap();

    private Set<String> sourceAnchorUnresolvedCommentNames = Collections.emptySet();

    /**
     * Policy for fitting pages to screen
     */
    private FitPolicy pageFitPolicy = FitPolicy.WIDTH;

    private boolean fitEachPage = false;

    private boolean cropMargins = false;

    private CropMargins cachedCropMargins = null;

    private int defaultPage = 0;

    private ViewState defaultViewState = null;

    /* True if should scroll through pages vertically instead of horizontally */
    private boolean swipeVertical = true;

    private boolean horizontalReadingDirectionRtl = false;

    private boolean enableSwipe = true;

    private boolean horizontalSwipeDisabled = false;

    private boolean zoomDisabled = false;

    private boolean doubleTapEnabled = true;

    private boolean nightMode = false;

    private boolean pageSnap = true;

    private boolean freeScrollMode = false;

    /**
     * Pdfium core for loading and rendering PDFs
     */
    private PdfiumCore pdfiumCore;
    private String loadedDocumentPassword;

    private ScrollHandle scrollHandle;

    private boolean isScrollHandleInit = false;

    public ScrollHandle getScrollHandle() {
        return scrollHandle;
    }

    /**
     * True if bitmap should use ARGB_8888 format and take more memory
     * False if bitmap should be compressed by using RGB_565 format and take less memory
     */
    private boolean bestQuality = false;

    /**
     * True if annotations should be rendered
     * False otherwise
     */
    private boolean annotationRendering = false;

    /**
     * True if the view should render during scaling<br/>
     * Can not be forced on older API versions (< Build.VERSION_CODES.KITKAT) as the GestureDetector does
     * not detect scrolling while scaling.<br/>
     * False otherwise
     */
    private boolean renderDuringScale = false;

    /**
     * Antialiasing and bitmap filtering
     */
    private boolean enableAntialiasing = true;
    private PaintFlagsDrawFilter antialiasFilter =
        new PaintFlagsDrawFilter(0, Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    /**
     * Spacing between pages, in px
     */
    private int spacingPx = 0;

    private int pagesPerRow = 1;

    private boolean firstPageAlone = false;

    /**
     * Add dynamic spacing to fit each page separately on the screen.
     */
    private boolean autoSpacing = false;

    /**
     * If true,the PdfView would release automatically when it is detached from window,
     * otherwise false
     */
    private boolean autoReleasingWhenDetachedFromWindow = true;

    /**
     * Fling a single page at a time
     */
    private boolean pageFling = true;

    /**
     * Pages numbers used when calling onDrawAllListener
     */
    private List<Integer> onDrawPagesNums = new ArrayList<>(10);

    /**
     * Holds info whether view has been added to layout and has width and height
     */
    private boolean hasSize = false;

    /**
     * Holds last used Configurator that should be loaded when view has size
     */
    private Configurator waitingDocumentConfigurator;

    /**
     * Construct the initial view
     */
    public PDFView(Context context, AttributeSet set) {
        super(context, set);

        if (isInEditMode()) {
            return;
        }

        cacheManager = new CacheManager();
        animationManager = new AnimationManager(this);
        textSelectionManager = new TextSelectionManager(this);
        stampPlacementManager = new StampPlacementManager(this);
        reviewRegionSelectionManager = new ReviewRegionSelectionManager(this);
        pdfCommentMoveManager = new PdfCommentMoveManager(this);
        dragPinchManager = new DragPinchManager(this, animationManager);
        pagesLoader = new PagesLoader(this);

        paint = new Paint();
        debugPaint = new Paint();
        debugPaint.setStyle(Style.STROKE);
        selectedHighlightFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        selectedHighlightFillPaint.setStyle(Style.FILL);
        selectedHighlightStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        selectedHighlightStrokePaint.setStyle(Style.STROKE);
        selectedHighlightStrokePaint.setStrokeWidth(2f * getResources().getDisplayMetrics().density);
        highlightAnnotationOverlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        highlightAnnotationOverlayPaint.setStyle(Style.FILL);
        highlightAnnotationMultiplyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        highlightAnnotationMultiplyPaint.setStyle(Style.FILL);
        highlightAnnotationMultiplyPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.MULTIPLY));
        managedHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        managedHighlightPaint.setStyle(Style.FILL);
        // SRC_OVER preserves the page alpha on opaque hardware surfaces.
        managedHighlightPaint.setXfermode(null);
        agentProposalStrikePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        agentProposalStrikePaint.setStyle(Style.STROKE);
        agentProposalStrikePaint.setStrokeWidth(1.8f * getResources().getDisplayMetrics().density);
        agentProposalStrikePaint.setColor(0xFFD32F2F);
        agentProposalSelectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        agentProposalSelectionPaint.setStyle(Style.FILL);
        agentProposalSelectionPaint.setColor(0x38D32F2F);
        agentProposalMarkerFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        agentProposalMarkerFillPaint.setStyle(Style.FILL);
        agentProposalMarkerStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        agentProposalMarkerStrokePaint.setStyle(Style.STROKE);
        agentProposalMarkerGlyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        agentProposalMarkerGlyphPaint.setStyle(Style.STROKE);
        agentProposalMarkerGlyphPaint.setStrokeCap(Paint.Cap.ROUND);
        agentProposalMarkerGlyphPaint.setStrokeJoin(Paint.Join.ROUND);
        commentMarkerFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        commentMarkerFillPaint.setStyle(Style.FILL);
        commentMarkerShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        commentMarkerShadowPaint.setStyle(Style.FILL_AND_STROKE);
        commentMarkerShadowPaint.setStrokeJoin(Paint.Join.ROUND);
        commentMarkerShadowPaint.setColor(COMMENT_MARKER_SHADOW_COLOR);
        commentMarkerBadgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        commentMarkerBadgePaint.setStyle(Style.FILL);
        commentMarkerBadgeGlyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        commentMarkerBadgeGlyphPaint.setStyle(Style.STROKE);
        commentMarkerBadgeGlyphPaint.setStrokeCap(Paint.Cap.ROUND);
        formFieldFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        formFieldFillPaint.setStyle(Style.FILL);
        formFieldFillPaint.setColor(FORM_FIELD_FILL_COLOR);
        formFieldStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        formFieldStrokePaint.setStyle(Style.STROKE);
        formFieldStrokePaint.setStrokeWidth(getResources().getDisplayMetrics().density);
        formFieldStrokePaint.setColor(FORM_FIELD_STROKE_COLOR);
        linkStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linkStrokePaint.setStyle(Style.STROKE);
        linkStrokePaint.setStrokeWidth(LINK_STROKE_WIDTH_DP * getResources().getDisplayMetrics().density);
        linkDestinationFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linkDestinationFillPaint.setStyle(Style.FILL);
        linkDestinationStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linkDestinationStrokePaint.setStyle(Style.STROKE);
        linkDestinationStrokePaint.setStrokeWidth(
                LINK_DESTINATION_STROKE_WIDTH_DP * getResources().getDisplayMetrics().density
        );
        reviewMaskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        reviewMaskPaint.setStyle(Style.FILL);
        reviewMaskPaint.setColor(Color.WHITE);
        reviewRevealFramePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        reviewRevealFramePaint.setStyle(Style.STROKE);
        reviewRevealFramePaint.setColor(Color.rgb(25, 95, 190));
        reviewRevealFramePaint.setStrokeWidth(2f * getResources().getDisplayMetrics().density);
        reviewRevealFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        reviewRevealFillPaint.setStyle(Style.FILL);
        reviewRevealFillPaint.setColor(Color.argb(18, 25, 95, 190));
        setSelectedHighlightColor(0xFF3F51B5);

        pdfiumCore = new PdfiumCore(context);
        setWillNotDraw(false);
    }

    private void load(DocumentSource docSource, String password) {
        load(docSource, password, null);
    }

    private void load(DocumentSource docSource, String password, int[] userPages) {
        loadedDocumentPassword = password;

        if (!recycled) {
            throw new IllegalStateException("Don't call load on a PDF View without recycling it first.");
        }

        recycled = false;
        // Start decoding document
        float[] geometry = cachedPageGeometry;
        cachedPageGeometry = null;
        decodingAsyncTask = new DecodingAsyncTask(docSource, password, userPages, this, pdfiumCore, geometry);
        decodingAsyncTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /**
     * Go to the given page.
     *
     * @param page Page index.
     */
    public void jumpTo(int page, boolean withAnimation) {
        if (pdfFile == null) {
            return;
        }

        page = pdfFile.determineValidPageNumberFrom(page);
        float offset = -pdfFile.getPageOffset(page, zoom);
        if (swipeVertical) {
            if (withAnimation) {
                animationManager.startYAnimation(currentYOffset, offset);
            } else {
                moveTo(currentXOffset, offset);
            }
        } else {
            if (withAnimation) {
                animationManager.startXAnimation(currentXOffset, offset);
            } else {
                moveTo(offset, currentYOffset);
            }
        }
        showPage(page);
    }

    public void jumpTo(int pageIndex) {
        jumpTo(pageIndex, false);
    }

    public void jumpUsingPageNumber(int pageNumber) {
        jumpTo(pageNumber - 1, false);
    }

    void showPage(int pageNb) {
        if (recycled) {
            return;
        }

        // Check the page number and makes the
        // difference between UserPages and DocumentPages
        pageNb = pdfFile.determineValidPageNumberFrom(pageNb);
        currentPage = pageNb;

        loadPages();

        if (scrollHandle != null && !documentFitsView()) {
            scrollHandle.setPageNum(currentPage + 1);
            scrollHandle.setScroll(getPositionOffset());
        }

        callbacks.callOnPageChange(currentPage, pdfFile.getPagesCount());
    }

    /**
     * Get current position as ratio of document length to visible area.
     * 0 means that document start is visible, 1 that document end is visible
     *
     * @return offset between 0 and 1
     */
    public float getPositionOffset() {
        if (pdfFile == null) return 0;

        float offset;
        if (swipeVertical) {
            offset = -currentYOffset / (pdfFile.getDocLen(zoom) - getHeight());
        } else {
            offset = -currentXOffset / (pdfFile.getDocLen(zoom) - getWidth());
        }
        return MathUtils.limit(offset, 0, 1);
    }

    /**
     * @param progress   must be between 0 and 1
     * @param moveHandle whether to move scroll handle
     * @see PDFView#getPositionOffset()
     */
    public void setPositionOffset(float progress, boolean moveHandle) {
        if (pdfFile == null) return;

        if (swipeVertical) {
            moveTo(currentXOffset, (-pdfFile.getDocLen(zoom) + getHeight()) * progress, moveHandle);
        } else {
            moveTo((-pdfFile.getDocLen(zoom) + getWidth()) * progress, currentYOffset, moveHandle);
        }
        loadPageByOffset();
    }

    public void setPositionOffset(float progress) {
        setPositionOffset(progress, true);
    }

    public ViewState captureViewState() {
        if (state != State.SHOWN || pdfFile == null || pdfFile.getPagesCount() == 0 || getWidth() <= 0 || getHeight() <= 0) {
            return null;
        }

        int pageIndex = pdfFile.determineValidPageNumberFrom(findFocusPage(currentXOffset, currentYOffset));
        float centerX = -currentXOffset + getWidth() * 0.5f;
        float centerY = -currentYOffset + getHeight() * 0.5f;
        float relativeCrossAxisCenter;
        float pageCenterOffsetRatio;

        if (swipeVertical) {
            relativeCrossAxisCenter = ratioOrDefault(centerX, pdfFile.getMaxPageWidth(), 0.5f);
            pageCenterOffsetRatio = ratioOrDefault(
                    centerY - pdfFile.getPageOffset(pageIndex, zoom),
                    pdfFile.getPageLength(pageIndex, zoom),
                    0.5f);
        } else {
            relativeCrossAxisCenter = ratioOrDefault(centerY, pdfFile.getMaxPageHeight(), 0.5f);
            pageCenterOffsetRatio = ratioOrDefault(
                    centerX - pdfFile.getPageOffset(pageIndex, zoom),
                    pdfFile.getPageLength(pageIndex, zoom),
                    0.5f);
        }

        return new ViewState(
                zoom,
                pageIndex,
                swipeVertical,
                isHorizontalReadingDirectionRtl(),
                relativeCrossAxisCenter,
                MathUtils.limit(pageCenterOffsetRatio, 0f, 1f),
                pdfFile.getPagesPerRow(),
                pdfFile.isFirstPageAlone());
    }

    public void stopFling() {
        animationManager.stopFling();
    }

    public int getPageCount() {
        if (pdfFile == null) {
            return 0;
        }
        return pdfFile.getPagesCount();
    }

    public void setSwipeEnabled(boolean enableSwipe) {
        this.enableSwipe = enableSwipe;
    }

    public void setHorizontalSwipeDisabled(boolean horizontalSwipeDisabled) {
        this.horizontalSwipeDisabled = horizontalSwipeDisabled;
    }


    public void setZoomDisabled(boolean zoomDisabled) {
        this.zoomDisabled = zoomDisabled;
    }

    public void setNightMode(boolean nightMode) {
        this.nightMode = nightMode;
        reviewMaskPaint.setColor(nightMode ? Color.BLACK : Color.WHITE);
        if (nightMode) {
            ColorMatrix colorMatrixInverted =
                new ColorMatrix(new float[]{
                    -1, 0, 0, 0, 255,
                    0, -1, 0, 0, 255,
                    0, 0, -1, 0, 255,
                    0, 0, 0, 1, 0});

            ColorMatrixColorFilter filter = new ColorMatrixColorFilter(colorMatrixInverted);
            paint.setColorFilter(filter);
        } else {
            paint.setColorFilter(null);
        }
    }

    void enableDoubleTap(boolean enableDoubleTap) {
        this.doubleTapEnabled = enableDoubleTap;
    }

    boolean isDoubleTapEnabled() {
        return doubleTapEnabled;
    }

    void onPageError(PageRenderingException ex) {
        if (!callbacks.callOnPageError(ex.getPage(), ex.getCause())) {
            Log.e(TAG, "Cannot open page " + ex.getPage(), ex.getCause());
        }
    }

    public void recycle() {
        searchBottomInset = 0f;
        clearSearchHighlights();
        waitingDocumentConfigurator = null;

        animationManager.stopAll();
        dragPinchManager.disable();

        // Stop tasks
        if (renderScheduler != null) {
            renderScheduler.stop();
        }
        if (decodingAsyncTask != null) {
            decodingAsyncTask.cancel(true);
        }

        // Clear caches
        cacheManager.recycle();

        if (scrollHandle != null && isScrollHandleInit) {
            scrollHandle.destroyLayout();
        }

        if (textSelectionManager != null) {
            textSelectionManager.recycle();
        }
        if (stampPlacementManager != null) {
            stampPlacementManager.recycle();
        }
        if (reviewRegionSelectionManager != null) {
            reviewRegionSelectionManager.cancel();
        }
        if (pdfCommentMoveManager != null) {
            pdfCommentMoveManager.cancel();
        }
        logicalCommentMarkerPage = -1;
        logicalCommentMarkerId = "";
        logicalCommentMarkerRects.clear();
        reviewHighlights = Collections.emptyMap();
        selectedReviewHighlight = Collections.emptyMap();
        logicalCommentOverlays = Collections.emptyList();
        selectedHighlightAnnotation = null;
        agentProposalOverlays = Collections.emptyList();
        invalidateAgentProposalMarkerLayout();
        selectedPdfComment = null;
        clearManagedPdfComments();
        sourceAnchorUnresolvedCommentNames = Collections.emptySet();
        linkDestinationPage = -1;
        linkDestinationPdfPoint = null;
        linkDestinationMarkerStartedAt = 0L;
        reviewMaskPage = -1;
        reviewMaskRects.clear();
        reviewFeedback = Collections.emptyList();
        documentFeedback = Collections.emptyList();
        hasContentViewport = false;

        if (pdfFile != null) {
            pdfFile.dispose();
            pdfFile = null;
        }

        scrollHandle = null;
        isScrollHandleInit = false;
        currentXOffset = currentYOffset = 0;
        zoom = 1f;
        defaultViewState = null;
        synchronized (prewarmPending) {
            prewarmPending.clear();
        }
        synchronized (searchMarkerPages) {
            searchMarkerPages.clear();
        }

        PreviewStore<Bitmap> store = previewStore;
        previewStore = null;
        if (store != null) {
            store.close();
        }
        if (previewTrimCallbacks != null) {
            getContext().unregisterComponentCallbacks(previewTrimCallbacks);
            previewTrimCallbacks = null;
        }
        if (previewEncoder != null) {
            previewEncoder.shutdownNow();
            previewEncoder = null;
        }
        if (previewDecoder != null) {
            previewDecoder.shutdownNow();
            previewDecoder = null;
        }
        synchronized (previewPending) {
            previewPending.clear();
        }
        sweepCursor.resetAll();

        pageGenerations = null;
        recycled = true;
        currentRenderEpoch++;
        callbacks = new Callbacks();
        state = State.DEFAULT;
    }

    public boolean isRecycled() {
        return recycled;
    }

    /**
     * Handle fling animation
     */
    @Override
    public void computeScroll() {
        super.computeScroll();
        if (isInEditMode()) {
            return;
        }
        animationManager.computeFling();
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (pdfFile == null
                || !event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)
                || event.getActionMasked() != MotionEvent.ACTION_SCROLL) {
            return super.onGenericMotionEvent(event);
        }
        float vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
        float hScroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL);
        if (vScroll == 0f && hScroll == 0f) {
            return super.onGenericMotionEvent(event);
        }
        callbacks.callOnDocumentInteraction(event);
        if ((event.getMetaState() & KeyEvent.META_CTRL_ON) != 0) {
            if (zoomDisabled) {
                return true;
            }
            float dr = (float) Math.pow(1.15f, vScroll);
            float wantedZoom = zoom * dr;
            float minWheelZoom = Math.min(MINIMUM_ZOOM, getMinZoom());
            float maxWheelZoom = Math.min(MAXIMUM_ZOOM, getMaxZoom());
            if (wantedZoom < minWheelZoom) {
                dr = minWheelZoom / zoom;
            } else if (wantedZoom > maxWheelZoom) {
                dr = maxWheelZoom / zoom;
            }
            zoomCenteredRelativeTo(dr, new PointF(event.getX(), event.getY()));
            scheduleWheelSettle(false);
            return true;
        }
        if (!isSwipeEnabled()) {
            return true;
        }
        float factor = wheelScrollFactor();
        float dx;
        float dy;
        if (swipeVertical) {
            dx = horizontalSwipeDisabled ? 0 : -hScroll * factor;
            dy = vScroll * factor;
        } else {
            dx = isHorizontalReadingDirectionRtl() ? -vScroll * factor : vScroll * factor;
            dy = hScroll * factor;
        }
        moveRelativeTo(dx, dy);
        loadPageByOffset();
        scheduleWheelSettle(true);
        return true;
    }

    private float wheelScrollFactor() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return ViewConfiguration.get(getContext()).getScaledVerticalScrollFactor();
        }
        return 64 * getContext().getResources().getDisplayMetrics().density;
    }

    private boolean wheelSettleSnap = true;

    private void scheduleWheelSettle(boolean snap) {
        wheelSettleSnap = snap;
        removeCallbacks(wheelSettleRunnable);
        postDelayed(wheelSettleRunnable, WHEEL_SETTLE_DELAY_MS);
    }

    private final Runnable wheelSettleRunnable = new Runnable() {
        @Override
        public void run() {
            if (pdfFile == null || recycled) {
                return;
            }
            loadPages();
            if (wheelSettleSnap) {
                performPageSnap();
            }
        }
    };

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(wheelSettleRunnable);
        if (autoReleasingWhenDetachedFromWindow) {
            release();
        }
        super.onDetachedFromWindow();
    }

    public void release() {
        recycle();
        if (renderScheduler != null) {
            renderScheduler.join();
            renderScheduler = null;
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        hasSize = true;
        if (waitingDocumentConfigurator != null) {
            waitingDocumentConfigurator.load();
        }
        if (isInEditMode() || state != State.SHOWN) {
            return;
        }

        // calculates the position of the point which in the center of view relative to big strip
        float centerPointInStripXOffset = -currentXOffset + oldw * 0.5f;
        float centerPointInStripYOffset = -currentYOffset + oldh * 0.5f;

        float relativeCenterPointInStripXOffset;
        float relativeCenterPointInStripYOffset;

        if (swipeVertical) {
            relativeCenterPointInStripXOffset = centerPointInStripXOffset / pdfFile.getMaxPageWidth();
            relativeCenterPointInStripYOffset = centerPointInStripYOffset / pdfFile.getDocLen(zoom);
        } else {
            relativeCenterPointInStripXOffset = centerPointInStripXOffset / pdfFile.getDocLen(zoom);
            relativeCenterPointInStripYOffset = centerPointInStripYOffset / pdfFile.getMaxPageHeight();
        }

        animationManager.stopAll();
        pdfFile.recalculatePageSizes(new Size(w, h));

        if (swipeVertical) {
            currentXOffset = -relativeCenterPointInStripXOffset * pdfFile.getMaxPageWidth() + w * 0.5f;
            currentYOffset = -relativeCenterPointInStripYOffset * pdfFile.getDocLen(zoom) + h * 0.5f;
        } else {
            currentXOffset = -relativeCenterPointInStripXOffset * pdfFile.getDocLen(zoom) + w * 0.5f;
            currentYOffset = -relativeCenterPointInStripYOffset * pdfFile.getMaxPageHeight() + h * 0.5f;
        }
        moveTo(currentXOffset, currentYOffset);
        loadPageByOffset();
    }

    @Override
    public boolean canScrollHorizontally(int direction) {
        if (pdfFile == null) {
            return true;
        }

        if (swipeVertical) {
            if (direction < 0 && currentXOffset < 0) {
                return true;
            } else if (direction > 0 && currentXOffset + toCurrentScale(pdfFile.getMaxPageWidth()) > getWidth()) {
                return true;
            }
        } else {
            if (direction < 0 && currentXOffset < 0) {
                return true;
            } else if (direction > 0 && currentXOffset + pdfFile.getDocLen(zoom) > getWidth()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean canScrollVertically(int direction) {
        if (pdfFile == null) {
            return true;
        }

        if (swipeVertical) {
            if (direction < 0 && currentYOffset < 0) {
                return true;
            } else if (direction > 0 && currentYOffset + pdfFile.getDocLen(zoom) > getContentViewportBottom()) {
                return true;
            }
        } else {
            if (direction < 0 && currentYOffset < 0) {
                return true;
            } else if (direction > 0 && currentYOffset + toCurrentScale(pdfFile.getMaxPageHeight()) > getHeight()) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (isInEditMode()) {
            return;
        }
        // As I said in this class javadoc, we can think of this canvas as a huge
        // strip on which we draw all the images. We actually only draw the rendered
        // parts, of course, but we render them in the place they belong in this huge
        // strip.

        // That's where Canvas.translate(x, y) becomes very helpful.
        // This is the situation :
        //  _______________________________________________
        // |   			 |					 			   |
        // | the actual  |					The big strip  |
        // |	canvas	 | 								   |
        // |_____________|								   |
        // |_______________________________________________|
        //
        // If the rendered part is on the bottom right corner of the strip
        // we can draw it but we won't see it because the canvas is not big enough.

        // But if we call translate(-X, -Y) on the canvas just before drawing the object :
        //  _______________________________________________
        // |   			  					  _____________|
        // |   The big strip     			 |			   |
        // |		    					 |	the actual |
        // |								 |	canvas	   |
        // |_________________________________|_____________|
        //
        // The object will be on the canvas.
        // This technique is massively used in this method, and allows
        // abstraction of the screen position when rendering the parts.

        // Draws background

        if (enableAntialiasing) {
            canvas.setDrawFilter(antialiasFilter);
        }

        Drawable bg = getBackground();
        if (bg == null) {
            canvas.drawColor(nightMode ? Color.BLACK : Color.WHITE);
        } else {
            bg.draw(canvas);
        }

        if (recycled) {
            return;
        }

        if (state != State.SHOWN) {
            return;
        }

        // Moves the canvas before drawing any element
        float currentXOffset = this.currentXOffset;
        float currentYOffset = this.currentYOffset;
        canvas.translate(currentXOffset, currentYOffset);
        Set<Integer> visiblePages = new LinkedHashSet<>();

        drawPreviews(canvas);

        // Draws parts
        for (PagePart part : cacheManager.getPageParts()) {
            visiblePages.add(part.getPage());
            drawPart(canvas, part);
            if (callbacks.getOnDrawAll() != null
                && !onDrawPagesNums.contains(part.getPage())) {
                onDrawPagesNums.add(part.getPage());
            }
        }

        for (Integer page : onDrawPagesNums) {
            drawWithListener(canvas, page, callbacks.getOnDrawAll());
        }
        onDrawPagesNums.clear();

        drawWithListener(canvas, currentPage, callbacks.getOnDraw());
        visiblePages.add(currentPage);

        drawFormFieldIndicators(canvas, visiblePages);
        drawHighlightAnnotationOverlays(canvas, visiblePages);
        drawAgentProposalOverlays(canvas, visiblePages);
        drawPdfCommentMarkers(canvas, visiblePages);
        drawLinkIndicators(canvas, visiblePages);
        drawLinkDestinationMarker(canvas, visiblePages);
        drawSearchHighlights(canvas, visiblePages);
        drawSelectedHighlightAnnotation(canvas);

        if (textSelectionManager != null) {
            textSelectionManager.draw(canvas);
        }

        if (stampPlacementManager != null) {
            stampPlacementManager.draw(canvas);
        }

        if (reviewRegionSelectionManager != null) {
            reviewRegionSelectionManager.draw(canvas);
        }

        drawReviewHighlights(canvas, visiblePages);
        drawReviewMask(canvas, visiblePages);
        drawReviewFeedback(canvas, visiblePages);
        drawDocumentFeedback(canvas, visiblePages);
        drawReviewCardMarkers(canvas, visiblePages);

        drawLogicalCommentMarker(canvas, visiblePages);

        canvas.translate(-currentXOffset, -currentYOffset);
    }

    private boolean highlightComments = true;
    private boolean highlightAgentEdits = true;
    private Map<Integer, List<RectF>> reviewHighlights = Collections.emptyMap();

    public void setHighlightVisibility(boolean comments, boolean agentEdits) {
        highlightComments = comments;
        highlightAgentEdits = agentEdits;
        invalidate();
    }

    /** Show each card's fragments, grouped by zero-based page, as merged answer blocks. */
    public void setReviewHighlights(Map<Integer, List<List<RectF>>> cardRegions) {
        reviewHighlights = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<List<RectF>>> entry : cardRegions.entrySet()) {
            int page = entry.getKey();
            if (pdfFile == null || page < 0 || page >= pdfFile.getPagesCount()) continue;
            List<RectF> blocks = new ArrayList<>();
            for (List<RectF> card : entry.getValue()) {
                blocks.addAll(reviewAnswerBlocks(page, card));
            }
            reviewHighlights.put(page, blocks);
        }
        invalidate();
    }

    private Map<Integer, List<RectF>> selectedReviewHighlight = Collections.emptyMap();

    /**
     * The one card picked in the Review cards list, by zero-based page: drawn in the selection
     * color whether or not the Review cards layer is shown, like the desktop sidebar selection.
     */
    public void setSelectedReviewHighlight(Map<Integer, List<RectF>> regions) {
        selectedReviewHighlight = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<RectF>> entry : regions.entrySet()) {
            int page = entry.getKey();
            if (pdfFile == null || page < 0 || page >= pdfFile.getPagesCount()) continue;
            selectedReviewHighlight.put(page, reviewAnswerBlocks(page, entry.getValue()));
        }
        invalidate();
    }

    public void clearSelectedReviewHighlight() {
        if (selectedReviewHighlight.isEmpty()) return;
        selectedReviewHighlight = Collections.emptyMap();
        invalidate();
    }

    /** One card's normalized top-left fragments on [page], merged into answer blocks. */
    private List<RectF> reviewAnswerBlocks(int page, List<RectF> card) {
        SizeF pageSize = pdfFile.getPageSize(page);
        List<float[]> fragments = new ArrayList<>();
        for (RectF rect : card) {
            fragments.add(new float[]{rect.left, rect.top, rect.right, rect.bottom});
        }
        List<RectF> blocks = new ArrayList<>();
        for (float[] block : ReviewMaskGeometry.blocks(fragments,
                pageSize.getHeight() / pageSize.getWidth(), pageTextBoxes(page))) {
            blocks.add(new RectF(block[0], block[1], block[2], block[3]));
        }
        return blocks;
    }

    public static class ReviewCardMarker {
        public final String cardId;
        public final int page;
        public final RectF anchor;

        public ReviewCardMarker(String cardId, int page, RectF anchor) {
            this.cardId = cardId;
            this.page = page;
            this.anchor = new RectF(anchor);
        }
    }

    public interface OnReviewCardClickListener {
        void onClick(String cardId);
    }

    private List<ReviewCardMarker> reviewCardMarkers = Collections.emptyList();
    private OnReviewCardClickListener reviewCardClickListener;

    public void setReviewCardMarkers(List<ReviewCardMarker> markers, OnReviewCardClickListener listener) {
        reviewCardMarkers = new ArrayList<>(markers);
        reviewCardClickListener = listener;
        invalidate();
    }

    private RectF reviewCardMarkerBounds(ReviewCardMarker marker) {
        RectF anchor = normalizedTopLeftRectToDocument(marker.page, marker.anchor);
        if (anchor == null) return null;
        float size = 24f * getResources().getDisplayMetrics().density;
        return new RectF(anchor.left, Math.max(0, anchor.top - size),
                anchor.left + size, Math.max(0, anchor.top - size) + size);
    }

    boolean handleReviewCardTap(float x, float y) {
        if (pdfFile == null || reviewCardClickListener == null) return false;
        for (ReviewCardMarker marker : reviewCardMarkers) {
            RectF bounds = reviewCardMarkerBounds(marker);
            if (bounds != null && bounds.contains(x - currentXOffset, y - currentYOffset)) {
                reviewCardClickListener.onClick(marker.cardId);
                return true;
            }
        }
        return false;
    }

    private void drawReviewCardMarkers(Canvas canvas, Set<Integer> pages) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float density = getResources().getDisplayMetrics().density;
        for (ReviewCardMarker marker : reviewCardMarkers) {
            if (!pages.contains(marker.page)) continue;
            RectF bounds = reviewCardMarkerBounds(marker);
            if (bounds == null) continue;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF1976D2);
            canvas.drawRoundRect(bounds, 4f * density, 4f * density, paint);
            RectF card = new RectF(bounds);
            card.inset(5f * density, 6f * density);
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f * density);
            canvas.drawRoundRect(card, density, density, paint);
            canvas.drawLine(card.left + 3f * density, card.centerY(),
                    card.right - 3f * density, card.centerY(), paint);
        }
    }

    private void drawReviewHighlights(Canvas canvas, Set<Integer> pages) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0x5542A5F5);
        for (Map.Entry<Integer, List<RectF>> entry : reviewHighlights.entrySet()) {
            if (!pages.contains(entry.getKey())) continue;
            Path path = reviewBlocksPath(entry.getKey(), entry.getValue());
            Path selected = reviewBlocksPath(entry.getKey(), selectedReviewHighlight.get(entry.getKey()));
            // Do not stack the ordinary highlight underneath the selected color.
            if (!selected.isEmpty()) path.op(selected, Path.Op.DIFFERENCE);
            canvas.drawPath(path, paint);
        }
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(0x40195FBE);
        Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
        outline.setColor(0xFF195FBE);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(getResources().getDisplayMetrics().density);
        for (Map.Entry<Integer, List<RectF>> entry : selectedReviewHighlight.entrySet()) {
            if (!pages.contains(entry.getKey())) continue;
            Path path = reviewBlocksPath(entry.getKey(), entry.getValue());
            canvas.drawPath(path, fill);
            canvas.drawPath(path, outline);
        }
    }

    private Path reviewBlocksPath(int page, List<RectF> blocks) {
        Path path = new Path();
        if (blocks == null) return path;
        for (RectF rect : blocks) {
            RectF bounds = normalizedTopLeftRectToDocument(page, rect);
            if (bounds == null) continue;
            bounds.inset(-2f, -1.5f);
            path.addRect(bounds, Path.Direction.CW);
        }
        return path;
    }

    private void drawReviewMask(Canvas canvas, Set<Integer> visiblePages) {
        if (pdfFile == null || reviewMaskRects.isEmpty() || visiblePages == null) return;
        if (reviewMaskRevealed) {
            // Exactly on the hidden area, so the frame lines up with the card's edit box.
            for (java.util.Map.Entry<Integer, List<RectF>> entry : reviewMaskRects.entrySet()) {
                int page = entry.getKey();
                if (!visiblePages.contains(page)) continue;
                for (RectF normalized : entry.getValue()) {
                    RectF frame = normalizedTopLeftRectToDocument(page, normalized);
                    if (frame == null || frame.width() <= 0 || frame.height() <= 0) continue;
                    canvas.drawRect(frame, reviewRevealFillPaint);
                    canvas.drawRect(frame, reviewRevealFramePaint);
                }
            }
            return;
        }
        if (reviewMaskInverted) {
            for (Integer page : visiblePages) {
                if (page == null || page < 0 || page >= pdfFile.getPagesCount()) continue;
                SizeF size = pdfFile.getPagePointSize(page);
                RectF pageRect = pdfFile.pdfRectToDocument(page, zoom,
                        0f, 0f, size.getWidth(), size.getHeight());
                if (pageRect == null) continue;
                Path cover = new Path();
                cover.addRect(pageRect, Path.Direction.CW);
                List<RectF> answerRects = reviewMaskRects.get(page);
                if (answerRects != null) {
                    for (RectF normalized : answerRects) {
                        RectF answer = normalizedTopLeftRectToDocument(page, normalized);
                        if (answer == null) continue;
                        answer.inset(-2f, -1.5f);
                        Path hole = new Path();
                        hole.addRect(answer, Path.Direction.CW);
                        cover.op(hole, Path.Op.DIFFERENCE);
                    }
                }
                canvas.drawPath(cover, reviewMaskPaint);
            }
            return;
        }
        for (java.util.Map.Entry<Integer, List<RectF>> entry : reviewMaskRects.entrySet()) {
            int page = entry.getKey();
            if (!visiblePages.contains(page)) continue;
            for (RectF normalized : entry.getValue()) {
                RectF documentRect = normalizedTopLeftRectToDocument(page, normalized);
                if (documentRect != null && documentRect.width() > 0 && documentRect.height() > 0) {
                    documentRect.inset(-2f, -1.5f);
                    canvas.drawRect(documentRect, reviewMaskPaint);
                }
            }
        }
    }

    /** Display opaque transient answer masks, using zero-based page indices. */
    /** Text runs on a page, normalized with a top-left origin; empty when unknown. */
    private List<float[]> pageTextBoxes(int page) {
        List<float[]> boxes = new ArrayList<>();
        if (pdfFile == null) return boxes;
        pdfFile.ensureTextPage(page);
        int count = pdfFile.pageCharCount(page);
        SizeF points = pdfFile.getPagePointSize(page);
        if (count <= 0 || points == null || points.getWidth() <= 0f || points.getHeight() <= 0f) return boxes;
        float[] values = pdfFile.textRects(page, 0, count);
        for (int i = 0; i + 3 < values.length; i += 4) {
            float left = Math.min(values[i], values[i + 2]) / points.getWidth();
            float right = Math.max(values[i], values[i + 2]) / points.getWidth();
            float top = 1f - Math.max(values[i + 1], values[i + 3]) / points.getHeight();
            float bottom = 1f - Math.min(values[i + 1], values[i + 3]) / points.getHeight();
            if (right > left && bottom > top) boxes.add(new float[]{left, top, right, bottom});
        }
        return boxes;
    }

    public boolean setReviewMasks(java.util.Map<Integer, List<RectF>> regions) {
        if (pdfFile == null || regions == null || regions.isEmpty()) return false;
        java.util.SortedMap<Integer, List<RectF>> validated = new java.util.TreeMap<>();
        for (java.util.Map.Entry<Integer, List<RectF>> entry : regions.entrySet()) {
            Integer page = entry.getKey();
            if (page == null || page < 0 || page >= pdfFile.getPagesCount()
                    || entry.getValue() == null || entry.getValue().isEmpty()) return false;
            List<RectF> rects = new ArrayList<>();
            for (RectF rect : entry.getValue()) {
                if (rect == null || !Float.isFinite(rect.left) || !Float.isFinite(rect.top)
                        || !Float.isFinite(rect.right) || !Float.isFinite(rect.bottom)
                        || rect.left < 0f || rect.top < 0f || rect.right > 1f
                        || rect.bottom > 1f || rect.left >= rect.right || rect.top >= rect.bottom) return false;
                rects.add(new RectF(rect));
            }
            List<float[]> fragments = new ArrayList<>();
            for (RectF rect : rects) {
                fragments.add(new float[]{rect.left, rect.top, rect.right, rect.bottom});
            }
            SizeF pageSize = pdfFile.getPageSize(page);
            List<RectF> blocks = new ArrayList<>();
            // The page's own text bounds how far a table answer's mask grows.
            for (float[] block : ReviewMaskGeometry.blocks(fragments,
                    pageSize.getHeight() / pageSize.getWidth(), pageTextBoxes(page))) {
                blocks.add(new RectF(block[0], block[1], block[2], block[3]));
            }
            validated.put(page, blocks);
        }
        reviewMaskRects.clear();
        reviewMaskRects.putAll(validated);
        reviewMaskRevealed = false;
        reviewFeedback = Collections.emptyList();
        reviewMaskPage = validated.firstKey();
        jumpTo(reviewMaskPage, false);
        invalidate();
        return true;
    }

    public boolean setReviewMask(int pageIndex, List<RectF> normalizedRects) {
        return setReviewMasks(java.util.Collections.singletonMap(pageIndex, normalizedRects));
    }

    /** Cover the page outside the answer regions instead of covering the answers. */
    public void setReviewMaskInverted(boolean inverted) {
        if (reviewMaskInverted == inverted) return;
        reviewMaskInverted = inverted;
        invalidate();
    }

    /** Frame the answer areas instead of covering them; the masks themselves are kept. */
    public void setReviewMaskRevealed(boolean revealed) {
        if (reviewMaskRevealed == revealed) return;
        reviewMaskRevealed = revealed;
        invalidate();
    }

    /** The answer areas in reading order, as (zero-based page, normalized top-left rect). */
    public List<android.util.Pair<Integer, RectF>> reviewMaskBlocks() {
        List<android.util.Pair<Integer, RectF>> blocks = new ArrayList<>();
        for (java.util.Map.Entry<Integer, List<RectF>> entry : reviewMaskRects.entrySet()) {
            List<RectF> rects = new ArrayList<>(entry.getValue());
            java.util.Collections.sort(rects, (a, b) -> a.top != b.top ? Float.compare(a.top, b.top) : Float.compare(a.left, b.left));
            for (RectF rect : rects) blocks.add(new android.util.Pair<>(entry.getKey(), new RectF(rect)));
        }
        return blocks;
    }

    public void clearReviewMask() {
        reviewFeedback = Collections.emptyList();
        if (reviewMaskPage < 0 && reviewMaskRects.isEmpty() && !reviewMaskInverted && !reviewMaskRevealed) return;
        reviewMaskPage = -1;
        reviewMaskInverted = false;
        reviewMaskRevealed = false;
        reviewMaskRects.clear();
        invalidate();
    }

    /**
     * A liked or disliked passage of the answer under review (Thoughts review card feedback):
     * its line rectangles as zero-based pages and normalized top-left rects.
     */
    public static final class ReviewFeedbackHighlight {
        public final String id;
        public final boolean positive;
        /** A question or another comment: neither liked nor disliked. */
        public final boolean neutral;
        public final boolean hasComment;
        public final List<Integer> pages;
        public final List<RectF> rects;
        /** A drawing's strokes, each [x0, y0, x1, y1, ...] normalized top-left; null for a passage. */
        public final List<float[]> strokes;
        /** [strokes] as one smooth path in normalized page coordinates. */
        final Path strokePath;
        /** A text box's text, drawn from the top left of its one rect; null for anything else. */
        public final String text;
        /** A text box's font height, as a share of the page's height. */
        public final float fontSize;
        /** [text] laid out (TextBoxLayout), and the reference width it wraps at. */
        android.text.StaticLayout textLayout;
        int textLayoutWidth = -1;

        public ReviewFeedbackHighlight(String id, boolean positive, boolean hasComment, List<Integer> pages, List<RectF> rects) {
            this(id, positive, false, hasComment, pages, rects);
        }

        public ReviewFeedbackHighlight(String id, boolean positive, boolean neutral, boolean hasComment, List<Integer> pages, List<RectF> rects) {
            this.id = id;
            this.positive = positive;
            this.neutral = neutral;
            this.hasComment = hasComment;
            this.pages = Collections.unmodifiableList(new ArrayList<>(pages));
            List<RectF> copies = new ArrayList<>(rects.size());
            for (RectF rect : rects) copies.add(new RectF(rect));
            this.rects = Collections.unmodifiableList(copies);
            strokes = null;
            strokePath = null;
            text = null;
            fontSize = 0f;
        }

        /** A drawing on zero-based [page]: neutral, and its one rect is the area around the strokes. */
        public ReviewFeedbackHighlight(String id, boolean hasComment, int page, List<float[]> strokes) {
            this.id = id;
            positive = false;
            neutral = true;
            this.hasComment = hasComment;
            pages = Collections.singletonList(page);
            float[] bounds = DrawingStrokes.bounds(strokes);
            rects = Collections.singletonList(new RectF(bounds[0], bounds[1], bounds[2], bounds[3]));
            this.strokes = Collections.unmodifiableList(new ArrayList<>(strokes));
            strokePath = DrawingStrokes.path(strokes);
            text = null;
            fontSize = 0f;
        }

        /**
         * A text box on zero-based [page]: neutral, its one rect the normalized top-left [rect] where
         * [text] starts and wraps, in a font [fontSize] of the page's height. The text is its comment.
         */
        public ReviewFeedbackHighlight(String id, int page, RectF rect, String text, float fontSize) {
            this.id = id;
            positive = false;
            neutral = true;
            hasComment = false;
            pages = Collections.singletonList(page);
            rects = Collections.singletonList(new RectF(rect));
            strokes = null;
            strokePath = null;
            this.text = text;
            this.fontSize = fontSize;
        }
    }

    private static final int FEEDBACK_POSITIVE_LIGHT = 0xFF2E7D32;
    private static final int FEEDBACK_NEGATIVE_LIGHT = 0xFFC62828;
    private static final int FEEDBACK_POSITIVE_DARK = 0xFF81C784;
    private static final int FEEDBACK_NEGATIVE_DARK = 0xFFEF9A9A;
    private static final int FEEDBACK_NEUTRAL_LIGHT = 0xFF1565C0;
    private static final int FEEDBACK_NEUTRAL_DARK = 0xFF90CAF9;
    /** 22% (light) and 28% (dark) fill opacity. */
    private static final int FEEDBACK_FILL_ALPHA_LIGHT = 56;
    private static final int FEEDBACK_FILL_ALPHA_DARK = 71;
    /** A pencil stroke, as a share of the page's width: as thick on the page at every zoom. */
    private static final float DRAWING_STROKE_WIDTH = 0.0025f;
    /** How near a stroke a tap counts as on it, in dp: a line is thinner than a finger. */
    private static final float DRAWING_TAP_REACH_DP = 12f;

    /** Oldest first, so the most recently created is drawn on top and found first by a tap. */
    private List<ReviewFeedbackHighlight> reviewFeedback = Collections.emptyList();

    /**
     * Shows the feedback of the card under review. It is drawn only while that card's answer is
     * revealed (the review mask is framed, not covering), and goes with the mask on [clearReviewMask].
     */
    public void setReviewFeedback(List<ReviewFeedbackHighlight> highlights) {
        reviewFeedback = highlights == null ? Collections.<ReviewFeedbackHighlight>emptyList() : new ArrayList<>(highlights);
        invalidate();
    }

    public void clearReviewFeedback() {
        if (reviewFeedback.isEmpty()) return;
        reviewFeedback = Collections.emptyList();
        invalidate();
    }

    /** The feedback highlights currently drawn: none unless an answer is revealed. */
    public List<ReviewFeedbackHighlight> getVisibleReviewFeedback() {
        if (!reviewFeedbackVisible()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(reviewFeedback));
    }

    private boolean reviewFeedbackVisible() {
        return pdfFile != null && reviewMaskRevealed && !reviewMaskRects.isEmpty() && !reviewFeedback.isEmpty();
    }

    /** The id of the topmost feedback highlight under view point (x, y), or null. */
    public String findReviewFeedbackAt(float viewX, float viewY) {
        if (!reviewFeedbackVisible()) return null;
        return findFeedbackAt(reviewFeedback, viewX, viewY);
    }

    private String findFeedbackAt(List<ReviewFeedbackHighlight> highlights, float viewX, float viewY) {
        float x = viewX - currentXOffset;
        float y = viewY - currentYOffset;
        float slop = 4f * getResources().getDisplayMetrics().density;
        for (int index = highlights.size() - 1; index >= 0; index--) {
            ReviewFeedbackHighlight highlight = highlights.get(index);
            if (highlight.strokes != null) {
                // Only the strokes: a drawing may circle a passage whose taps belong to the page.
                RectF page = normalizedTopLeftRectToDocument(highlight.pages.get(0), new RectF(0f, 0f, 1f, 1f));
                float reach = DRAWING_TAP_REACH_DP * getResources().getDisplayMetrics().density;
                if (page != null && DrawingStrokes.near(highlight.strokes, page.left, page.top, page.width(), page.height(), x, y, reach)) {
                    return highlight.id;
                }
                continue;
            }
            if (highlight.text != null) {
                // The text box, and its text where it runs past the box.
                RectF bounds = textBoxArea(highlight);
                if (bounds == null) continue;
                bounds.inset(-slop, -slop);
                if (bounds.contains(x, y)) return highlight.id;
                continue;
            }
            for (int line = 0; line < highlight.rects.size(); line++) {
                RectF bounds = normalizedTopLeftRectToDocument(highlight.pages.get(line), highlight.rects.get(line));
                if (bounds == null) continue;
                bounds.inset(-slop, -slop);
                if (bounds.contains(x, y)) return highlight.id;
            }
        }
        return null;
    }

    /**
     * Liked and disliked passages of the document (Thoughts document feedback), oldest first.
     * Drawn like card feedback. During a review, not those in the card's answer area (card
     * feedback is shown there), and none while the page around the answer is covered. Kept
     * across reviews.
     */
    private List<ReviewFeedbackHighlight> documentFeedback = Collections.emptyList();

    public void setDocumentFeedback(List<ReviewFeedbackHighlight> highlights) {
        documentFeedback = highlights == null ? Collections.<ReviewFeedbackHighlight>emptyList() : new ArrayList<>(highlights);
        invalidate();
    }

    public void clearDocumentFeedback() {
        if (documentFeedback.isEmpty()) return;
        documentFeedback = Collections.emptyList();
        invalidate();
    }

    /** The document feedback highlights currently drawn. */
    public List<ReviewFeedbackHighlight> getVisibleDocumentFeedback() {
        return Collections.unmodifiableList(new ArrayList<>(shownDocumentFeedback()));
    }

    private List<ReviewFeedbackHighlight> shownDocumentFeedback() {
        if (pdfFile == null || documentFeedback.isEmpty()) return Collections.emptyList();
        if (reviewMaskRects.isEmpty()) return documentFeedback;
        if (reviewMaskInverted && !reviewMaskRevealed) return Collections.emptyList();
        List<ReviewFeedbackHighlight> shown = new ArrayList<>();
        for (ReviewFeedbackHighlight highlight : documentFeedback) {
            if (!inReviewAnswer(highlight)) shown.add(highlight);
        }
        return shown;
    }

    /** Whether a line of [highlight] meets the area of the card under review's answer. */
    private boolean inReviewAnswer(ReviewFeedbackHighlight highlight) {
        for (int line = 0; line < highlight.rects.size(); line++) {
            List<RectF> answer = reviewMaskRects.get(highlight.pages.get(line));
            if (answer == null) continue;
            for (RectF rect : answer) {
                if (RectF.intersects(rect, highlight.rects.get(line))) return true;
            }
        }
        return false;
    }

    /** The id of the topmost document feedback highlight under view point (x, y), or null. */
    public String findDocumentFeedbackAt(float viewX, float viewY) {
        List<ReviewFeedbackHighlight> shown = shownDocumentFeedback();
        if (shown.isEmpty()) return null;
        return findFeedbackAt(shown, viewX, viewY);
    }

    private void drawDocumentFeedback(Canvas canvas, Set<Integer> visiblePages) {
        if (visiblePages == null) return;
        List<ReviewFeedbackHighlight> shown = shownDocumentFeedback();
        if (!shown.isEmpty()) drawFeedbackHighlights(canvas, visiblePages, shown);
    }

    private void drawReviewFeedback(Canvas canvas, Set<Integer> visiblePages) {
        if (!reviewFeedbackVisible() || visiblePages == null) return;
        drawFeedbackHighlights(canvas, visiblePages, reviewFeedback);
    }

    private void drawFeedbackHighlights(Canvas canvas, Set<Integer> visiblePages, List<ReviewFeedbackHighlight> highlights) {
        float density = getResources().getDisplayMetrics().density;
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setStyle(Style.FILL);
        Paint underline = new Paint(Paint.ANTI_ALIAS_FLAG);
        underline.setStyle(Style.STROKE);
        underline.setStrokeWidth(2f * density);
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setStyle(Style.FILL);
        android.graphics.DashPathEffect dashes = new android.graphics.DashPathEffect(new float[]{4f * density, 3f * density}, 0f);
        android.graphics.DashPathEffect dots = new android.graphics.DashPathEffect(new float[]{1.5f * density, 3f * density}, 0f);
        for (ReviewFeedbackHighlight highlight : highlights) {
            int color = highlight.neutral
                    ? (nightMode ? FEEDBACK_NEUTRAL_DARK : FEEDBACK_NEUTRAL_LIGHT)
                    : highlight.positive
                    ? (nightMode ? FEEDBACK_POSITIVE_DARK : FEEDBACK_POSITIVE_LIGHT)
                    : (nightMode ? FEEDBACK_NEGATIVE_DARK : FEEDBACK_NEGATIVE_LIGHT);
            fill.setColor((color & 0x00FFFFFF) | ((nightMode ? FEEDBACK_FILL_ALPHA_DARK : FEEDBACK_FILL_ALPHA_LIGHT) << 24));
            underline.setColor(color);
            // Solid for liked, dashed for disliked, dotted for the others: they differ without colour.
            underline.setPathEffect(highlight.neutral ? dots : highlight.positive ? null : dashes);
            dot.setColor(color);
            RectF last = null;
            int lastPage = -1;
            for (int line = 0; line < highlight.rects.size(); line++) {
                int page = highlight.pages.get(line);
                RectF bounds = normalizedTopLeftRectToDocument(page, highlight.rects.get(line));
                if (bounds == null || bounds.width() <= 0 || bounds.height() <= 0) continue;
                last = bounds;
                lastPage = page;
                if (!visiblePages.contains(page)) continue;
                if (highlight.strokePath != null) {
                    drawStrokes(canvas, page, highlight.strokePath, color, density);
                    continue;
                }
                if (highlight.text != null) {
                    drawTextBox(canvas, highlight, color);
                    continue;
                }
                canvas.drawRect(bounds, fill);
                float y = bounds.bottom + density;
                Path path = new Path();
                path.moveTo(bounds.left, y);
                path.lineTo(bounds.right, y);
                canvas.drawPath(path, underline);
            }
            if (highlight.hasComment && last != null && visiblePages.contains(lastPage)) {
                canvas.drawCircle(last.right + 5f * density, last.centerY(), 3f * density, dot);
            }
        }
    }

    private void drawStrokes(Canvas canvas, int pageIndex, Path strokePath, int color, float density) {
        RectF page = normalizedTopLeftRectToDocument(pageIndex, new RectF(0f, 0f, 1f, 1f));
        if (page == null) return;
        Paint pencil = new Paint(Paint.ANTI_ALIAS_FLAG);
        pencil.setStyle(Style.STROKE);
        pencil.setStrokeCap(Paint.Cap.ROUND);
        pencil.setStrokeJoin(Paint.Join.ROUND);
        pencil.setColor(color);
        pencil.setStrokeWidth(Math.max(density, page.width() * DRAWING_STROKE_WIDTH));
        android.graphics.Matrix toPage = new android.graphics.Matrix();
        toPage.setScale(page.width(), page.height());
        toPage.postTranslate(page.left, page.top);
        Path onPage = new Path();
        strokePath.transform(toPage, onPage);
        canvas.drawPath(onPage, pencil);
    }

    /** The text of a text box in [color], at its font size on the page: scaled with the zoom, never filled. */
    private void drawTextBox(Canvas canvas, ReviewFeedbackHighlight highlight, int color) {
        int pageIndex = highlight.pages.get(0);
        RectF page = normalizedTopLeftRectToDocument(pageIndex, new RectF(0f, 0f, 1f, 1f));
        android.text.StaticLayout layout = textBoxLayout(highlight);
        if (page == null || layout == null) return;
        RectF rect = highlight.rects.get(0);
        float scale = TextBoxLayout.scale(highlight.fontSize, page.height());
        layout.getPaint().setColor(color);
        int saved = canvas.save();
        canvas.translate(page.left + rect.left * page.width(), page.top + rect.top * page.height());
        canvas.scale(scale, scale);
        layout.draw(canvas);
        canvas.restoreToCount(saved);
    }

    /** Where a text box's text is on the document at the current zoom: its rect, or more where the text runs past it. */
    private RectF textBoxArea(ReviewFeedbackHighlight highlight) {
        android.text.StaticLayout layout = textBoxLayout(highlight);
        Float aspect = pageAspect(highlight.pages.get(0));
        if (layout == null || aspect == null) return null;
        RectF rect = highlight.rects.get(0);
        float[] area = TextBoxLayout.area(new float[]{rect.left, rect.top, rect.right, rect.bottom}, highlight.fontSize, aspect,
                TextBoxLayout.width(layout), layout.getHeight());
        return normalizedTopLeftRectToDocument(highlight.pages.get(0), new RectF(area[0], area[1], area[2], area[3]));
    }

    /** [highlight]'s text laid out at the reference size, kept while its page's shape gives the same width. */
    private android.text.StaticLayout textBoxLayout(ReviewFeedbackHighlight highlight) {
        Float aspect = pageAspect(highlight.pages.get(0));
        if (aspect == null || highlight.text == null || highlight.fontSize <= 0f) return null;
        RectF rect = highlight.rects.get(0);
        int width = TextBoxLayout.referenceWidth(new float[]{rect.left, rect.top, rect.right, rect.bottom}, highlight.fontSize, aspect);
        if (highlight.textLayout == null || highlight.textLayoutWidth != width) {
            highlight.textLayout = TextBoxLayout.layout(highlight.text, width);
            highlight.textLayoutWidth = width;
        }
        return highlight.textLayout;
    }

    /** The width of zero-based [pageIndex] over its height, or null without a page. */
    private Float pageAspect(int pageIndex) {
        if (pdfFile == null || pageIndex < 0 || pageIndex >= pdfFile.getPagesCount()) return null;
        SizeF pageSize = pdfFile.getPagePointSize(pageIndex);
        if (pageSize == null || pageSize.getWidth() <= 0 || pageSize.getHeight() <= 0) return null;
        return pageSize.getWidth() / pageSize.getHeight();
    }

    private RectF normalizedTopLeftRectToDocument(int pageIndex, RectF normalized) {
        if (pdfFile == null || pageIndex < 0 || pageIndex >= pdfFile.getPagesCount()) {
            return null;
        }
        SizeF pageSize = pdfFile.getPagePointSize(pageIndex);
        if (pageSize == null || pageSize.getWidth() <= 0 || pageSize.getHeight() <= 0) {
            return null;
        }
        return pdfFile.pdfRectToDocument(
                pageIndex,
                zoom,
                normalized.left * pageSize.getWidth(),
                (1f - normalized.bottom) * pageSize.getHeight(),
                normalized.right * pageSize.getWidth(),
                (1f - normalized.top) * pageSize.getHeight()
        );
    }

    private void drawLogicalCommentMarker(Canvas canvas, Set<Integer> visiblePages) {
        if (!highlightComments) return;
        if (visiblePages == null || pdfFile == null) {
            return;
        }
        Map<String, List<LogicalCommentOverlay>> highlights = new LinkedHashMap<>();
        Map<String, LogicalCommentOverlay> markerRoots = new LinkedHashMap<>();
        for (LogicalCommentOverlay overlay : logicalCommentOverlays) {
            if (!overlay.isPoint && !hasManagedPdfComment(overlay.commentId)) {
                highlights.computeIfAbsent(overlay.commentId + ":" + overlay.pageIndex, ignored -> new ArrayList<>()).add(overlay);
                markerRoots.putIfAbsent(overlay.commentId, overlay);
            }
        }
        for (List<LogicalCommentOverlay> regions : highlights.values()) {
            LogicalCommentOverlay first = regions.get(0);
            managedHighlightPath.rewind();
            if (!visiblePages.contains(first.pageIndex)) continue;
            List<RectF> wordRects = new ArrayList<>();
            for (LogicalCommentOverlay region : regions) {
                wordRects.add(new RectF(region.left, region.top, region.right, region.bottom));
            }
            SizeF pageSize = pdfFile.getPagePointSize(first.pageIndex);
            for (RectF line : TextLineRects.merge(wordRects, pageSize.getWidth(), pageSize.getHeight())) {
                RectF bounds = normalizedTopLeftRectToDocument(first.pageIndex, line);
                if (bounds != null) {
                    if (first.subtype == PdfComment.SUBTYPE_STRIKEOUT) {
                        float halfWidth = Math.max(0.6f, bounds.height() * 0.045f);
                        bounds = new RectF(bounds.left, bounds.centerY() - halfWidth, bounds.right, bounds.centerY() + halfWidth);
                    }
                    managedHighlightPath.addRect(bounds, Path.Direction.CW);
                }
            }
            managedHighlightPaint.setColor(((first.subtype == PdfComment.SUBTYPE_STRIKEOUT ? 0xFF : (first.selected ? 0x58 : 0x48)) << 24) | (first.color & 0x00FFFFFF));
            canvas.drawPath(managedHighlightPath, managedHighlightPaint);
            if (first.hasThread && first == markerRoots.get(first.commentId)) {
                TextCommentMarkerGeometry.Bubble marker = logicalCommentMarker(first.pageIndex,
                        new RectF(first.left, first.top, first.right, first.bottom));
                if (marker != null) drawTextCommentMarker(canvas, marker,
                        ((first.selected ? 255 : COMMENT_MARKER_UNSELECTED_ALPHA) << 24) | 0xFFFF00, false);
            }
        }
        for (LogicalCommentOverlay overlay : logicalCommentOverlays) {
            if (!visiblePages.contains(overlay.pageIndex)) {
                continue;
            }
            RectF bounds = normalizedTopLeftRectToDocument(overlay.pageIndex,
                    new RectF(overlay.left, overlay.top, overlay.right, overlay.bottom));
            if (!overlay.isPoint) continue;
            TextCommentMarkerGeometry.Bubble pointBubble = logicalPointMarker(overlay);
            if (pointBubble != null) bounds = new RectF(pointBubble.left, pointBubble.top, pointBubble.right, pointBubble.bottom);
            if (bounds != null && Float.isFinite(overlay.sourceX) && Float.isFinite(overlay.sourceY)) {
                RectF source = normalizedTopLeftRectToDocument(overlay.pageIndex,
                        new RectF(overlay.sourceX, overlay.sourceY, overlay.sourceX, overlay.sourceY));
                if (source != null && Math.hypot(source.left - bounds.centerX(), source.top - bounds.centerY()) > 2f) {
                    Paint connector = new Paint(Paint.ANTI_ALIAS_FLAG);
                    connector.setColor(0xB0808000);
                    connector.setStrokeWidth(getResources().getDisplayMetrics().density);
                    canvas.drawLine(source.left, source.top, bounds.centerX(), bounds.centerY(), connector);
                    canvas.drawCircle(source.left, source.top, 2f * getResources().getDisplayMetrics().density, connector);
                }
            }
            if (hasManagedPdfComment(overlay.commentId)) continue;
            TextCommentMarkerGeometry.Bubble marker = pointBubble;
            if (marker != null) {
                int alpha = overlay.selected ? 255 : COMMENT_MARKER_UNSELECTED_ALPHA;
                drawTextCommentMarker(
                        canvas,
                        marker,
                        (alpha << 24) | (COMMENT_MARKER_FILL_COLOR & 0x00FFFFFF),
                        false
                );
            }
        }
        if (logicalCommentMarkerPage < 0 || logicalCommentMarkerRects.isEmpty()
                || !visiblePages.contains(logicalCommentMarkerPage)) {
            return;
        }
        // Navigation selects an existing comment; it must not create another marker.
        if (hasManagedPdfComment(logicalCommentMarkerId)
                || logicalCommentOverlays.stream().anyMatch(item -> item.commentId.equals(logicalCommentMarkerId))) return;
        TextCommentMarkerGeometry.Bubble marker = logicalCommentMarker(
                logicalCommentMarkerPage, logicalCommentMarkerRects.get(0));
        if (marker != null) drawTextCommentMarker(canvas, marker, COMMENT_MARKER_FILL_COLOR, false);
    }

    /** Marker dimensions are PDF points, shared with Desktop, not fixed screen pixels. */
    private float commentMarkerSize(int page, float points) {
        RectF bounds = pageDocumentBounds(page);
        SizeF original = pdfFile == null ? null : pdfFile.getPagePointSize(page);
        return bounds == null || original == null || original.getWidth() <= 0
                ? 0f : points * bounds.width() / original.getWidth();
    }

    private TextCommentMarkerGeometry.Bubble logicalCommentMarker(
            int pageIndex,
            RectF normalized) {
        RectF documentRect = normalizedTopLeftRectToDocument(pageIndex, normalized);
        RectF pageBounds = pageDocumentBounds(pageIndex);
        if (documentRect == null || pageBounds == null
                || documentRect.width() <= 0 || documentRect.height() <= 0) {
            return null;
        }
        float size = commentMarkerSize(pageIndex, HIGHLIGHT_COMMENT_MARKER_SIZE_DP);
        return TextCommentMarkerGeometry.atHighlightStart(
                documentRect.left,
                documentRect.top,
                documentRect.bottom,
                pageBounds.left,
                pageBounds.top,
                pageBounds.right,
                pageBounds.bottom,
                size
        );
    }

    private final java.util.Map<LogicalCommentOverlay, TextCommentMarkerGeometry.Bubble> pointMarkerLayout = new java.util.IdentityHashMap<>();
    private List<LogicalCommentOverlay> pointMarkerLayoutSource;
    private PdfFile pointMarkerLayoutPdf;
    private float pointMarkerLayoutZoom = Float.NaN;
    private int pointMarkerLayoutWidth, pointMarkerLayoutHeight;

    /** Reuse the same collision-free bubble rectangles for drawing and taps. */
    private TextCommentMarkerGeometry.Bubble logicalPointMarker(LogicalCommentOverlay overlay) {
        if (pointMarkerLayoutSource != logicalCommentOverlays || pointMarkerLayoutPdf != pdfFile
                || pointMarkerLayoutZoom != zoom || pointMarkerLayoutWidth != getWidth()
                || pointMarkerLayoutHeight != getHeight()) {
            pointMarkerLayout.clear();
            pointMarkerLayoutSource = logicalCommentOverlays;
            pointMarkerLayoutPdf = pdfFile;
            pointMarkerLayoutZoom = zoom;
            pointMarkerLayoutWidth = getWidth();
            pointMarkerLayoutHeight = getHeight();
            java.util.Map<Integer, List<RectF>> occupied = new java.util.HashMap<>();
            synchronized (managedPdfCommentLock) {
                if (managedPdfCommentRecords != null) for (ManagedPdfComment comment : managedPdfCommentRecords) {
                    if (!PdfComment.isTextMarkup(comment.subtype) || !comment.inReplyToName.isEmpty()
                            || !logicalCommentName(comment.name).equals(comment.name)) continue;
                    TextCommentMarkerGeometry.Bubble marker = managedHighlightCommentMarker(comment);
                    if (marker != null) occupied.computeIfAbsent(comment.pageIndex, key -> new ArrayList<>())
                            .add(new RectF(marker.left, marker.top, marker.right, marker.bottom));
                }
            }
            List<LogicalCommentOverlay> points = new ArrayList<>();
            for (LogicalCommentOverlay point : logicalCommentOverlays) if (point.isPoint) points.add(point);
            points.sort(java.util.Comparator.comparingInt((LogicalCommentOverlay point) -> point.pageIndex)
                    .thenComparing(point -> point.commentId).thenComparingDouble(point -> point.top)
                    .thenComparingDouble(point -> point.left));
            for (LogicalCommentOverlay point : points) {
                float gap = commentMarkerSize(point.pageIndex, 6f);
                TextCommentMarkerGeometry.Bubble initial = unadjustedLogicalPointMarker(point);
                RectF page = pageDocumentBounds(point.pageIndex);
                if (initial == null || page == null) continue;
                RectF chosen = new RectF(initial.left, initial.top, initial.right, initial.bottom);
                List<RectF> used = occupied.computeIfAbsent(point.pageIndex, key -> new ArrayList<>());
                float step = chosen.width() + gap;
                int maxRing = (int) Math.ceil(Math.max(page.width(), page.height()) / step);
                boolean found = markerRectAvailable(chosen, page, used, gap);
                for (int ring = 1; !found && ring <= maxRing; ring++) {
                    for (int dy = -ring; !found && dy <= ring; dy++) {
                        for (int dx = -ring; !found && dx <= ring; dx++) {
                            if (Math.abs(dx) != ring && Math.abs(dy) != ring) continue;
                            RectF candidate = new RectF(initial.left, initial.top, initial.right, initial.bottom);
                            candidate.offset(dx * step, dy * step);
                            if (markerRectAvailable(candidate, page, used, gap)) { chosen = candidate; found = true; }
                        }
                    }
                }
                used.add(chosen);
                pointMarkerLayout.put(point, TextCommentMarkerGeometry.centeredBubble(
                        chosen.centerX(), chosen.centerY(), chosen.centerX(), chosen.centerY(), chosen.width()));
            }
        }
        return pointMarkerLayout.get(overlay);
    }

    private static boolean markerRectAvailable(RectF candidate, RectF page, List<RectF> occupied, float gap) {
        if (!page.contains(candidate)) return false;
        for (RectF other : occupied) {
            if (candidate.left < other.right + gap && candidate.right > other.left - gap
                    && candidate.top < other.bottom + gap && candidate.bottom > other.top - gap) return false;
        }
        return true;
    }

    private TextCommentMarkerGeometry.Bubble unadjustedLogicalPointMarker(LogicalCommentOverlay overlay) {
        RectF bounds = normalizedTopLeftRectToDocument(overlay.pageIndex,
                new RectF(overlay.left, overlay.top, overlay.right, overlay.bottom));
        RectF page = pageDocumentBounds(overlay.pageIndex);
        if (bounds == null || page == null) return null;
        float density = getResources().getDisplayMetrics().density;
        float size = commentMarkerSize(overlay.pageIndex, COMMENT_MARKER_SIZE_DP);
        float x = bounds.centerX(), y = bounds.centerY();
        if (Float.isFinite(overlay.sourceX) && Float.isFinite(overlay.sourceY)) {
            RectF source = normalizedTopLeftRectToDocument(overlay.pageIndex,
                    new RectF(overlay.sourceX, overlay.sourceY, overlay.sourceX, overlay.sourceY));
            if (source == null) return null;
            java.util.SortedSet<String> roots = new java.util.TreeSet<>();
            for (LogicalCommentOverlay candidate : logicalCommentOverlays) {
                if (candidate.isPoint && candidate.pageIndex == overlay.pageIndex
                        && Float.isFinite(candidate.sourceX) && Float.isFinite(candidate.sourceY)
                        && Math.round(candidate.sourceX * 10000) == Math.round(overlay.sourceX * 10000)
                        && Math.round(candidate.sourceY * 10000) == Math.round(overlay.sourceY * 10000)) roots.add(candidate.commentId);
            }
            int slot = Math.max(0, new ArrayList<>(roots).indexOf(overlay.commentId));
            float step = size + commentMarkerSize(overlay.pageIndex, 6f);
            int directionX = overlay.sourceX <= 0.5f ? 1 : -1;
            int directionY = overlay.sourceY <= 0.5f ? 1 : -1;
            float available = directionX > 0 ? page.right - size/2f - source.left : source.left - page.left - size/2f;
            int columns = Math.max(1, (int) (available / step) + 1);
            x = source.left + directionX * (slot % columns) * step;
            y = source.top + directionY * (slot / columns) * step;
        }
        x = Math.max(page.left + size/2f, Math.min(page.right - size/2f, x));
        y = Math.max(page.top + size/2f, Math.min(page.bottom - size/2f, y));
        return TextCommentMarkerGeometry.centeredBubble(x, y, x, y, size);
    }

    private LogicalCommentOverlay logicalPointOverlay(ManagedPdfComment comment) {
        RectF actual = managedPdfRectToDocument(comment.pageIndex, managedPdfCommentBounds(comment));
        if (actual == null) return null;
        String id = logicalCommentName(comment.name);
        LogicalCommentOverlay result = null;
        double distance = Double.POSITIVE_INFINITY;
        for (LogicalCommentOverlay overlay : logicalCommentOverlays) {
            if (!overlay.isPoint || overlay.pageIndex != comment.pageIndex || !overlay.commentId.equals(id)) continue;
            RectF candidate = normalizedTopLeftRectToDocument(overlay.pageIndex,
                    new RectF(overlay.left, overlay.top, overlay.right, overlay.bottom));
            if (candidate == null) continue;
            double next = Math.hypot(candidate.centerX()-actual.centerX(), candidate.centerY()-actual.centerY());
            if (next < distance) { distance = next; result = overlay; }
        }
        return result;
    }

    private boolean hasManagedPdfComment(String logicalName) {
        synchronized (managedPdfCommentLock) {
            return managedPdfCommentRecords != null
                    && managedPdfCommentRecordsByName.containsKey(logicalName);
        }
    }

    /** Replace all revision-bound logical-comment overlays for the current PDF. */
    public void setLogicalCommentOverlays(List<LogicalCommentOverlay> overlays) {
        logicalCommentOverlays = overlays == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(overlays));
        invalidate();
    }

    /** Return the logical comment under a view-coordinate tap, if any. */
    public String logicalCommentAt(float viewX, float viewY) {
        if (!highlightComments) return null;
        if (pdfFile == null || logicalCommentOverlays.isEmpty()) {
            return null;
        }
        float docX = -getCurrentXOffset() + viewX;
        float docY = -getCurrentYOffset() + viewY;
        float padding = 12f * getResources().getDisplayMetrics().density;
        // A visible bubble wins over a neighbour's expanded touch target at low zoom.
        for (LogicalCommentOverlay point : logicalCommentOverlays) {
            if (!point.isPoint) continue;
            TextCommentMarkerGeometry.Bubble bubble = logicalPointMarker(point);
            if (bubble != null && docX >= bubble.left && docX <= bubble.right
                    && docY >= bubble.top && docY <= bubble.bottom) return point.commentId;
        }
        // Visible point bubbles win over the padded hit area of nearby text.
        Set<String> firstHighlights = new LinkedHashSet<>();
        for (LogicalCommentOverlay overlay : logicalCommentOverlays) {
            if (overlay.isPoint || !firstHighlights.add(overlay.commentId) || !overlay.hasThread
                    || hasManagedPdfComment(overlay.commentId)) continue;
            if (commentMarkerContainsViewPoint(logicalCommentMarker(overlay.pageIndex,
                    new RectF(overlay.left, overlay.top, overlay.right, overlay.bottom)), viewX, viewY)) return overlay.commentId;
        }
        for (int index = logicalCommentOverlays.size() - 1; index >= 0; index--) {
            LogicalCommentOverlay point = logicalCommentOverlays.get(index);
            if (point.isPoint && commentMarkerContainsViewPoint(logicalPointMarker(point), viewX, viewY)) return point.commentId;
        }
        for (int index = logicalCommentOverlays.size() - 1; index >= 0; index--) {
            LogicalCommentOverlay overlay = logicalCommentOverlays.get(index);
            RectF normalized = new RectF(
                    overlay.left,
                    overlay.top,
                    overlay.right,
                    overlay.bottom
            );
            if (overlay.isPoint) continue;
            RectF bounds = normalizedTopLeftRectToDocument(overlay.pageIndex, normalized);
            if (bounds != null) {
                bounds.inset(-padding, -padding);
                if (bounds.contains(docX, docY)) {
                    return overlay.commentId;
                }
            }
        }
        return null;
    }

    public static final class LogicalCommentOverlay {
        public final String commentId;
        public final int pageIndex;
        public final float left;
        public final float top;
        public final float right;
        public final float bottom;
        public final boolean selected;
        public final boolean isPoint;
        public final float sourceX;
        public final float sourceY;
        public final boolean hasThread;
        public final int color;
        public int subtype = PdfComment.SUBTYPE_HIGHLIGHT;

        public LogicalCommentOverlay(
                String commentId,
                int pageIndex,
                float left,
                float top,
                float right,
                float bottom,
                boolean selected, boolean isPoint, float sourceX, float sourceY) {
            this(commentId, pageIndex, left, top, right, bottom, selected, isPoint, sourceX, sourceY, true, COMMENT_MARKER_FILL_COLOR);
        }

        public LogicalCommentOverlay(String commentId, int pageIndex, float left, float top, float right, float bottom,
                boolean selected, boolean isPoint, float sourceX, float sourceY, boolean hasThread, int color) {
            this.hasThread = hasThread;
            this.color = color;
            this.commentId = commentId == null ? "" : commentId;
            this.pageIndex = pageIndex;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.selected = selected;
            this.isPoint = isPoint;
            this.sourceX = sourceX;
            this.sourceY = sourceY;
        }
    }

    /** Displays a transient logical-comment marker without modifying the PDF document. */
    public boolean setLogicalCommentMarker(int pageIndex, List<RectF> normalizedRects) {
        return setLogicalCommentMarker(pageIndex, normalizedRects, "");
    }

    public boolean setLogicalCommentMarker(int pageIndex, List<RectF> normalizedRects, String commentId) {
        if (pdfFile == null || pageIndex < 0 || pageIndex >= pdfFile.getPagesCount()
                || normalizedRects == null || normalizedRects.isEmpty()) {
            return false;
        }
        ArrayList<RectF> validated = new ArrayList<>(normalizedRects.size());
        for (RectF rect : normalizedRects) {
            if (rect == null || rect.left < 0f || rect.top < 0f || rect.right > 1f
                    || rect.bottom > 1f || rect.left >= rect.right || rect.top >= rect.bottom) {
                return false;
            }
            validated.add(new RectF(rect));
        }
        logicalCommentMarkerPage = pageIndex;
        logicalCommentMarkerId = commentId == null ? "" : commentId;
        logicalCommentMarkerRects.clear();
        logicalCommentMarkerRects.addAll(validated);
        jumpTo(pageIndex, false);
        invalidate();
        return true;
    }

    /** Returns true when a tap hits the currently displayed logical-comment marker. */
    public boolean hitLogicalCommentMarker(MotionEvent event) {
        if (!highlightComments) return false;
        if (event == null || logicalCommentMarkerPage < 0 || logicalCommentMarkerRects.isEmpty()) {
            return false;
        }
        float docX = -getCurrentXOffset() + event.getX();
        float docY = -getCurrentYOffset() + event.getY();
        float target = 12f * getResources().getDisplayMetrics().density;
        if (!hasManagedPdfComment(logicalCommentMarkerId)
                && logicalCommentOverlays.stream().noneMatch(item -> item.commentId.equals(logicalCommentMarkerId))
                && commentMarkerContainsViewPoint(logicalCommentMarker(logicalCommentMarkerPage,
                        logicalCommentMarkerRects.get(0)), event.getX(), event.getY())) return true;
        for (RectF normalized : logicalCommentMarkerRects) {
            RectF documentRect = normalizedTopLeftRectToDocument(logicalCommentMarkerPage, normalized);
            if (documentRect != null) {
                documentRect.inset(-target, -target);
                if (documentRect.contains(docX, docY)) {
                    return true;
                }
            }
        }
        return false;
    }

    public void clearLogicalCommentMarker() {
        if (logicalCommentMarkerPage < 0 && logicalCommentMarkerRects.isEmpty()) {
            return;
        }
        logicalCommentMarkerPage = -1;
        logicalCommentMarkerId = "";
        logicalCommentMarkerRects.clear();
        invalidate();
    }

    private void drawFormFieldIndicators(Canvas canvas, Set<Integer> pages) {
        if (pdfFile == null || pages == null || pages.isEmpty()) {
            return;
        }
        for (Integer page : pages) {
            if (page == null || page < 0 || page >= pdfFile.getPagesCount()) {
                continue;
            }
            float[] rects = pdfFile.peekFormFieldRects(page);
            if (rects == null) {
                requestPrewarm(page);
                continue;
            }
            for (int i = 0; i + 3 < rects.length; i += 4) {
                RectF docRect = pdfFile.pdfRectToDocument(
                        page,
                        zoom,
                        rects[i],
                        rects[i + 1],
                        rects[i + 2],
                        rects[i + 3]
                );
                if (docRect == null) {
                    continue;
                }
                canvas.drawRect(docRect, formFieldFillPaint);
                canvas.drawRect(docRect, formFieldStrokePaint);
            }
        }
    }

    private void drawLinkIndicators(Canvas canvas, Set<Integer> pages) {
        if (pdfFile == null || pages == null || pages.isEmpty()) {
            return;
        }
        linkStrokePaint.setColor(nightMode ? LINK_STROKE_NIGHT_COLOR : LINK_STROKE_COLOR);
        float radius = LINK_CORNER_RADIUS_DP * getResources().getDisplayMetrics().density;
        for (Integer page : pages) {
            if (page == null || page < 0 || page >= pdfFile.getPagesCount()) {
                continue;
            }
            List<RectF> indicatorRects = pdfFile.peekLinkIndicatorRects(page);
            if (indicatorRects == null) {
                requestPrewarm(page);
                continue;
            }
            for (RectF indicatorRect : indicatorRects) {
                RectF docRect = pdfFile.normalizedPageRectToDocument(page, zoom, indicatorRect);
                if (docRect == null || docRect.width() <= 0 || docRect.height() <= 0) {
                    continue;
                }
                canvas.drawRoundRect(docRect, radius, radius, linkStrokePaint);
            }
        }
    }

    private void drawLinkDestinationMarker(Canvas canvas, Set<Integer> pages) {
        if (pdfFile == null || linkDestinationPage < 0 || linkDestinationPdfPoint == null) {
            return;
        }
        long elapsed = SystemClock.uptimeMillis() - linkDestinationMarkerStartedAt;
        if (elapsed < 0 || elapsed >= LINK_DESTINATION_DURATION_MS) {
            linkDestinationPage = -1;
            linkDestinationPdfPoint = null;
            linkDestinationMarkerStartedAt = 0L;
            return;
        }
        if (pages == null || !pages.contains(linkDestinationPage)) {
            postInvalidateOnAnimation();
            return;
        }

        RectF anchorRect = pdfFile.pdfRectToDocument(
                linkDestinationPage,
                zoom,
                linkDestinationPdfPoint.x - 0.5f,
                linkDestinationPdfPoint.y - 0.5f,
                linkDestinationPdfPoint.x + 0.5f,
                linkDestinationPdfPoint.y + 0.5f,
                false
        );
        if (anchorRect == null) {
            return;
        }

        float progress = Math.min(1f, elapsed / (float) LINK_DESTINATION_DURATION_MS);
        float density = getResources().getDisplayMetrics().density;
        float radius = (LINK_DESTINATION_RADIUS_START_DP
                + (LINK_DESTINATION_RADIUS_END_DP - LINK_DESTINATION_RADIUS_START_DP) * progress)
                * density;
        float visibility = 1f - progress;
        int fillAlpha = Math.round(80f * visibility);
        int strokeAlpha = Math.round(255f * visibility);
        int fillColor = nightMode ? LINK_DESTINATION_NIGHT_FILL_COLOR : LINK_DESTINATION_FILL_COLOR;
        int strokeColor = nightMode ? LINK_DESTINATION_NIGHT_STROKE_COLOR : LINK_DESTINATION_STROKE_COLOR;
        linkDestinationFillPaint.setColor((fillAlpha << 24) | (fillColor & 0x00FFFFFF));
        linkDestinationStrokePaint.setColor((strokeAlpha << 24) | (strokeColor & 0x00FFFFFF));
        canvas.drawCircle(anchorRect.centerX(), anchorRect.centerY(), radius, linkDestinationFillPaint);
        canvas.drawCircle(anchorRect.centerX(), anchorRect.centerY(), radius, linkDestinationStrokePaint);
        postInvalidateOnAnimation();
    }

    private void drawSelectedHighlightAnnotation(Canvas canvas) {
        if (!highlightComments) return;
        if (selectedHighlightAnnotation == null || selectedHighlightAnnotation.pdfBounds == null
                || pdfFile == null) {
            return;
        }
        List<RectF> wordRects = new ArrayList<>();
        int page = selectedHighlightAnnotation.pageIndex;
        List<PdfDocument.HighlightAnnotation> annotations = pdfFile.peekHighlightAnnotations(page);
        if (annotations != null) for (PdfDocument.HighlightAnnotation annotation : annotations) {
            boolean matches = selectedHighlightAnnotation.groupKey.isEmpty()
                    ? annotation.getAnnotationIndex() == selectedHighlightAnnotation.annotationIndex
                    : selectedHighlightAnnotation.groupKey.equals(annotation.getGroupKey());
            if (matches && annotation.getBounds() != null) wordRects.add(annotation.getBounds());
        }
        if (wordRects.isEmpty()) wordRects.add(selectedHighlightAnnotation.pdfBounds);
        for (RectF line : TextLineRects.merge(wordRects)) {
            RectF docRect = pdfFile.pdfRectToDocument(page, zoom, line.left, line.bottom, line.right, line.top);
            if (docRect == null) continue;
            canvas.drawRect(docRect, selectedHighlightFillPaint);
            canvas.drawRect(docRect, selectedHighlightStrokePaint);
        }
    }

    private void drawPdfCommentMarkers(Canvas canvas, Set<Integer> pages) {
        if (!highlightComments) return;
        List<ManagedPdfComment> managed = managedPdfCommentRecordsSnapshotOrNull();
        if (managed != null) {
            drawManagedPdfComments(canvas, pages, managed);
            return;
        }
        if (!annotationRendering || pdfFile == null || pages == null || pages.isEmpty()) {
            return;
        }
        for (Integer page : pages) {
            if (page == null || page < 0 || page >= pdfFile.getPagesCount()) {
                continue;
            }
            List<PdfDocument.PdfComment> comments = pdfFile.peekPdfComments(page);
            if (comments == null) {
                requestPrewarm(page);
                continue;
            }
            List<PdfDocument.HighlightAnnotation> highlightAnnotations = null;
            boolean highlightAnnotationsLoaded = false;
            for (PdfDocument.PdfComment comment : comments) {
                if (comment.isReply() || comment.getBounds() == null) {
                    continue;
                }
                TextCommentMarkerGeometry.Bubble marker;
                if (comment.getSubtype() == PdfDocument.PdfComment.SUBTYPE_TEXT) {
                    RectF bounds = comment.getBounds();
                    if (pdfCommentMoveManager != null && pdfCommentMoveManager.matches(
                            page, comment.getName(), comment.getAnnotationIndex())) {
                        RectF preview = pdfCommentMoveManager.getPreviewBounds();
                        if (preview != null) {
                            bounds = preview;
                        }
                    }
                    RectF markerBounds = pdfFile.pdfRectToDocument(
                            page,
                            zoom,
                            bounds.left,
                            bounds.bottom,
                            bounds.right,
                            bounds.top
                    );
                    marker = markerBounds == null ? null : textCommentMarker(page, markerBounds);
                } else if (PdfComment.isTextMarkup(comment.getSubtype())
                        && isLogicalRootPdfComment(comment)) {
                    if (!highlightAnnotationsLoaded) {
                        highlightAnnotations = pdfFile.peekHighlightAnnotations(page);
                        highlightAnnotationsLoaded = true;
                        if (highlightAnnotations == null) {
                            requestPrewarm(page);
                        }
                    }
                    marker = highlightAnnotations == null
                            ? null
                            : highlightCommentMarker(page, comment, highlightAnnotations);
                } else {
                    continue;
                }
                if (marker == null) {
                    continue;
                }
                int color = 0xFF000000 | (comment.getColor() & 0x00FFFFFF);
                if (!isSelectedPdfComment(page, comment)) {
                    color = (COMMENT_MARKER_UNSELECTED_ALPHA << 24)
                            | (color & 0x00FFFFFF);
                }
                drawTextCommentMarker(
                        canvas,
                        marker,
                        color,
                        comment.isSourceAnchorUnresolved()
                                || sourceAnchorUnresolvedCommentNames.contains(
                                        logicalCommentName(comment.getName()))
                );
            }
        }
    }

    private List<ManagedPdfComment> managedPdfCommentRecordsSnapshotOrNull() {
        synchronized (managedPdfCommentLock) {
            return managedPdfCommentRecords == null
                    ? null
                    : new ArrayList<>(managedPdfCommentRecords);
        }
    }

    private void drawManagedPdfComments(
            Canvas canvas,
            Set<Integer> pages,
            List<ManagedPdfComment> comments) {
        if (pdfFile == null || pages == null || pages.isEmpty() || comments.isEmpty()) {
            return;
        }
        // Paint the union of one logical highlight's fragments once per page.
        Map<String, List<ManagedPdfComment>> highlights = new LinkedHashMap<>();
        for (ManagedPdfComment comment : comments) {
            if (PdfComment.isTextMarkup(comment.subtype) && comment.inReplyToName.isEmpty()
                    && pages.contains(comment.pageIndex)) {
                String key = logicalCommentName(comment.name) + ":" + comment.pageIndex;
                highlights.computeIfAbsent(key, ignored -> new ArrayList<>()).add(comment);
            }
        }
        for (List<ManagedPdfComment> fragments : highlights.values()) {
            ManagedPdfComment first = fragments.get(0);
            managedHighlightPath.rewind();
            List<RectF> wordRects = new ArrayList<>();
            for (ManagedPdfComment fragment : fragments) wordRects.addAll(fragment.pdfRects);
            for (RectF line : TextLineRects.merge(wordRects)) {
                RectF documentRect = managedPdfRectToDocument(first.pageIndex, line);
                if (documentRect != null) {
                    if (first.subtype == PdfComment.SUBTYPE_STRIKEOUT) {
                        float halfWidth = Math.max(0.6f, documentRect.height() * 0.045f);
                        documentRect = new RectF(documentRect.left, documentRect.centerY() - halfWidth,
                                documentRect.right, documentRect.centerY() + halfWidth);
                    }
                    managedHighlightPath.addRect(documentRect, Path.Direction.CW);
                }
            }
            int alpha = first.subtype == PdfComment.SUBTYPE_STRIKEOUT ? 0xFF : (isSelectedManagedPdfComment(first) ? 0x58 : 0x48);
            managedHighlightPaint.setColor((alpha << 24) | (first.color & 0x00FFFFFF));
            canvas.drawPath(managedHighlightPath, managedHighlightPaint);
        }
        for (ManagedPdfComment comment : comments) {
            if (!pages.contains(comment.pageIndex)
                    || comment.pageIndex < 0
                    || comment.pageIndex >= pdfFile.getPagesCount()
                    || !comment.inReplyToName.isEmpty()) {
                continue;
            }
            if (PdfComment.isTextMarkup(comment.subtype)
                    && (!logicalCommentName(comment.name).equals(comment.name)
                    || !managedHighlightHasThread(comment, comments))) continue;
            TextCommentMarkerGeometry.Bubble marker;
            if (PdfComment.isTextMarkup(comment.subtype)) {
                marker = managedHighlightCommentMarker(comment);
            } else if (comment.subtype == PdfComment.SUBTYPE_TEXT) {
                RectF bounds = managedPdfCommentBounds(comment);
                boolean moving = false;
                if (pdfCommentMoveManager != null && pdfCommentMoveManager.matches(
                        comment.pageIndex, comment.name, managedAnnotationIndex(comment.name))) {
                    RectF preview = pdfCommentMoveManager.getPreviewBounds();
                    if (preview != null) {
                        bounds = preview;
                        moving = true;
                    }
                }
                RectF documentBounds = managedPdfRectToDocument(comment.pageIndex, bounds);
                LogicalCommentOverlay logicalOverlay = moving ? null : logicalPointOverlay(comment);
                marker = logicalOverlay != null ? logicalPointMarker(logicalOverlay)
                        : documentBounds == null ? null : textCommentMarker(comment.pageIndex, documentBounds);
            } else {
                continue;
            }
            if (marker == null) {
                continue;
            }
            int color = COMMENT_MARKER_FILL_COLOR;
            if (!isSelectedManagedPdfComment(comment)) {
                color = (COMMENT_MARKER_UNSELECTED_ALPHA << 24) | (color & 0x00FFFFFF);
            }
            drawTextCommentMarker(
                    canvas,
                    marker,
                    color,
                    sourceAnchorUnresolvedCommentNames.contains(logicalCommentName(comment.name))
            );
        }
    }

    private int managedAnnotationIndex(String name) {
        List<PdfComment> comments = managedPdfCommentsSnapshotOrNull();
        if (comments == null) {
            return -1;
        }
        for (PdfComment comment : comments) {
            if (comment.name.equals(name)) {
                return comment.annotationIndex;
            }
        }
        return -1;
    }

    private boolean isSelectedManagedPdfComment(ManagedPdfComment comment) {
        if (selectedPdfComment == null || selectedPdfComment.subtype != comment.subtype) {
            return false;
        }
        String selectedName = logicalCommentName(selectedPdfComment.name);
        String candidateName = logicalCommentName(comment.name);
        return !selectedName.isEmpty() && selectedName.equals(candidateName);
    }

    private RectF managedPdfRectToDocument(int page, RectF bounds) {
        if (bounds == null) {
            return null;
        }
        return pdfFile.pdfRectToDocument(
                page,
                zoom,
                bounds.left,
                bounds.bottom,
                bounds.right,
                bounds.top
        );
    }

    private boolean managedHighlightHasThread(ManagedPdfComment comment, List<ManagedPdfComment> comments) {
        if (!comment.contents.trim().isEmpty()) return true;
        if (comments == null) return false;
        String root = logicalCommentName(comment.name);
        for (ManagedPdfComment candidate : comments) {
            if (root.equals(logicalCommentName(candidate.inReplyToName)) && !candidate.contents.trim().isEmpty()) return true;
        }
        return false;
    }

    private TextCommentMarkerGeometry.Bubble managedHighlightCommentMarker(
            ManagedPdfComment comment) {
        if (comment == null || comment.pdfRects.isEmpty()
                || !managedHighlightHasThread(comment, managedPdfCommentRecordsSnapshotOrNull())) {
            return null;
        }
        RectF startDocumentBounds = managedPdfRectToDocument(
                comment.pageIndex,
                comment.pdfRects.get(0)
        );
        RectF pageBounds = pageDocumentBounds(comment.pageIndex);
        if (startDocumentBounds == null || pageBounds == null) {
            return null;
        }
        float size = commentMarkerSize(comment.pageIndex, HIGHLIGHT_COMMENT_MARKER_SIZE_DP);
        return TextCommentMarkerGeometry.atHighlightStart(
                startDocumentBounds.left,
                startDocumentBounds.top,
                startDocumentBounds.bottom,
                pageBounds.left,
                pageBounds.top,
                pageBounds.right,
                pageBounds.bottom,
                size
        );
    }

    private static boolean isLogicalRootPdfComment(PdfDocument.PdfComment comment) {
        String name = comment == null ? "" : comment.getName();
        return logicalCommentName(name).equals(name == null ? "" : name);
    }

    private TextCommentMarkerGeometry.Bubble highlightCommentMarker(
            int page,
            PdfDocument.PdfComment comment,
            List<PdfDocument.HighlightAnnotation> highlightAnnotations) {
        if (comment == null || comment.getBounds() == null) {
            return null;
        }
        RectF startBounds = null;
        if (highlightAnnotations != null) {
            for (PdfDocument.HighlightAnnotation annotation : highlightAnnotations) {
                if (annotation != null
                        && annotation.getAnnotationIndex() == comment.getAnnotationIndex()
                        && annotation.getBounds() != null) {
                    startBounds = annotation.getBounds();
                    break;
                }
            }
        }
        if (startBounds == null) {
            startBounds = comment.getBounds();
        }
        RectF startDocumentBounds = pdfFile.pdfRectToDocument(
                page,
                zoom,
                startBounds.left,
                startBounds.bottom,
                startBounds.right,
                startBounds.top
        );
        RectF pageBounds = pageDocumentBounds(page);
        if (startDocumentBounds == null || pageBounds == null) {
            return null;
        }
        float size = commentMarkerSize(page, HIGHLIGHT_COMMENT_MARKER_SIZE_DP);
        return TextCommentMarkerGeometry.atHighlightStart(
                startDocumentBounds.left,
                startDocumentBounds.top,
                startDocumentBounds.bottom,
                pageBounds.left,
                pageBounds.top,
                pageBounds.right,
                pageBounds.bottom,
                size
        );
    }

    private RectF pageDocumentBounds(int page) {
        if (pdfFile == null || page < 0 || page >= pdfFile.getPagesCount()) {
            return null;
        }
        SizeF size = pdfFile.getScaledPageSize(page, zoom);
        float left = swipeVertical
                ? pdfFile.getSecondaryPageOffset(page, zoom)
                : pdfFile.getPageOffset(page, zoom);
        float top = swipeVertical
                ? pdfFile.getPageOffset(page, zoom)
                : pdfFile.getSecondaryPageOffset(page, zoom);
        return new RectF(left, top, left + size.getWidth(), top + size.getHeight());
    }

    private boolean isSelectedPdfComment(int page, PdfDocument.PdfComment comment) {
        if (selectedPdfComment == null || comment == null
                || selectedPdfComment.subtype != comment.getSubtype()) {
            return false;
        }
        String selectedName = logicalCommentName(selectedPdfComment.name);
        String candidateName = logicalCommentName(comment.getName());
        if (!selectedName.isEmpty() || !candidateName.isEmpty()) {
            return selectedName.equals(candidateName);
        }
        return selectedPdfComment.pageIndex == page
                && selectedPdfComment.annotationIndex == comment.getAnnotationIndex();
    }

    private void drawHighlightAnnotationOverlays(Canvas canvas, Set<Integer> pages) {
        if (!highlightComments) return;
        if (!annotationRendering || pdfFile == null || pages == null || pages.isEmpty()) {
            return;
        }
        for (Integer page : pages) {
            if (page == null || page < 0 || page >= pdfFile.getPagesCount()) {
                continue;
            }
            List<PdfDocument.HighlightAnnotation> annotations = pdfFile.peekHighlightAnnotations(page);
            if (annotations == null) {
                requestPrewarm(page);
                continue;
            }
            Map<String, List<PdfDocument.HighlightAnnotation>> groups = new LinkedHashMap<>();
            for (PdfDocument.HighlightAnnotation annotation : annotations) {
                if (annotation.getBounds() == null || annotation.isSearchResult()) {
                    continue;
                }
                String groupKey = annotation.getGroupKey();
                String key = groupKey == null || groupKey.isEmpty()
                        ? "i" + annotation.getAnnotationIndex()
                        : "g" + groupKey + "#" + annotation.getColor() + "#" + annotation.isAppOwned();
                List<PdfDocument.HighlightAnnotation> members = groups.get(key);
                if (members == null) {
                    members = new ArrayList<>();
                    groups.put(key, members);
                }
                members.add(annotation);
            }
            for (List<PdfDocument.HighlightAnnotation> members : groups.values()) {
                PdfDocument.HighlightAnnotation first = members.get(0);
                if (isStandaloneComment(first)) {
                    drawCommentMarker(canvas, page, first);
                    continue;
                }
                Paint overlayPaint = highlightOverlayPaint(first);
                if (overlayPaint == null) {
                    continue;
                }
                highlightAnnotationGroupPath.rewind();
                boolean hasRect = false;
                List<RectF> wordRects = new ArrayList<>();
                for (PdfDocument.HighlightAnnotation annotation : members) wordRects.add(annotation.getBounds());
                for (RectF bounds : TextLineRects.merge(wordRects)) {
                    RectF docRect = pdfFile.pdfRectToDocument(
                            page,
                            zoom,
                            bounds.left,
                            bounds.bottom,
                            bounds.right,
                            bounds.top
                    );
                    if (docRect == null) {
                        continue;
                    }
                    highlightAnnotationGroupPath.addRect(docRect, Path.Direction.CW);
                    hasRect = true;
                }
                if (hasRect) {
                    canvas.drawPath(highlightAnnotationGroupPath, overlayPaint);
                }
            }
        }
    }

    /** Replace the transient, view-only agent proposal geometry; no PDF annotation is created. */
    public void setAgentProposalOverlays(List<AgentProposalOverlay> overlays) {
        agentProposalOverlays = overlays == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(overlays));
        invalidateAgentProposalMarkerLayout();
        if (markerAccessibility != null) markerAccessibility.invalidateRoot();
        invalidate();
    }

    /** What a tap and a long press on a proposal's marker do, for accessibility services. */
    public interface AgentProposalMarkerActions {
        boolean onAgentProposalMarkerTap(String proposalId);

        boolean onAgentProposalMarkerLongPress(String proposalId);
    }

    /** Makes each proposal marker on screen an accessibility node saying [description]. */
    public void setAgentProposalMarkerAccessibility(String description, AgentProposalMarkerActions actions) {
        if (markerAccessibility == null) {
            markerAccessibility = new AgentProposalMarkerAccessibility(this);
            androidx.core.view.ViewCompat.setAccessibilityDelegate(this, markerAccessibility);
        }
        markerAccessibility.configure(description, actions);
    }

    @Override
    protected boolean dispatchHoverEvent(MotionEvent event) {
        if (markerAccessibility != null && markerAccessibility.dispatchHoverEvent(event)) return true;
        return super.dispatchHoverEvent(event);
    }

    /** The proposal markers on screen, in view coordinates. */
    List<AgentProposalMarkerAccessibility.VisibleMarker> visibleAgentProposalMarkers() {
        if (!highlightAgentEdits) return Collections.emptyList();
        List<AgentProposalMarkerGeometry.Marker> markers = agentProposalMarkers();
        if (markers.isEmpty()) return Collections.emptyList();
        float dx = getCurrentXOffset();
        float dy = getCurrentYOffset();
        RectF view = new RectF(0, 0, getWidth(), getHeight());
        List<AgentProposalMarkerAccessibility.VisibleMarker> visible = new ArrayList<>();
        for (AgentProposalMarkerGeometry.Marker marker : markers) {
            RectF bounds = new RectF(marker.bounds.left + dx, marker.bounds.top + dy,
                    marker.bounds.right + dx, marker.bounds.bottom + dy);
            if (RectF.intersects(bounds, view)) {
                visible.add(new AgentProposalMarkerAccessibility.VisibleMarker(marker.proposalId, bounds));
            }
        }
        return visible;
    }

    /** A finger went down on the page; [pageMoving]: the page was still moving by itself. */
    void onPressStarted(boolean pageMoving) {
        markerPressGuard.onDown(pageMoving, getCurrentXOffset(), getCurrentYOffset());
    }

    /**
     * True when the tap now confirmed may act on a marker: its press did not stop a moving page
     * (a fling coasting, a page snap) and the page did not move under it. A scroll is never a tap.
     */
    public boolean isStillMarkerTap() {
        return markerPressGuard.isStillTap(getCurrentXOffset(), getCurrentYOffset(),
                ViewConfiguration.get(getContext()).getScaledTouchSlop());
    }

    private void invalidateAgentProposalMarkerLayout() {
        agentProposalMarkerLayout = Collections.emptyList();
        agentProposalMarkerLayoutSource = null;
        agentProposalMarkerLayoutPdf = null;
        agentProposalMarkerLayoutZoom = Float.NaN;
        agentProposalMarkerLayoutWidth = 0;
        agentProposalMarkerLayoutHeight = 0;
    }

    private float agentProposalMarkerSize(int pageIndex) {
        return commentMarkerSize(pageIndex, COMMENT_MARKER_SIZE_DP);
    }

    private void drawAgentProposalOverlays(Canvas canvas, Set<Integer> pages) {
        if (!highlightAgentEdits) return;
        if (pdfFile == null || agentProposalOverlays.isEmpty()) {
            return;
        }
        // Different proposals, roles, selection states, and pages never share a run.
        Map<String, List<AgentProposalOverlay>> groups = new LinkedHashMap<>();
        for (AgentProposalOverlay overlay : agentProposalOverlays) {
            if (!pages.contains(overlay.pageIndex) || overlay.pageIndex < 0
                    || overlay.pageIndex >= pdfFile.getPagesCount()) continue;
            String key = overlay.proposalId + ":" + overlay.pageIndex + ":" + overlay.role + ":" + overlay.selected;
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(overlay);
        }
        for (List<AgentProposalOverlay> group : groups.values()) {
            AgentProposalOverlay first = group.get(0);
            float pageHeight = pdfFile.getPagePointSize(first.pageIndex).getHeight();
            List<RectF> wordRects = new ArrayList<>();
            for (AgentProposalOverlay overlay : group) {
                wordRects.add(new RectF(overlay.left, overlay.top, overlay.right, overlay.bottom));
            }
            boolean after = "after".equals(first.role);
            agentProposalSelectionPaint.setColor(after ? 0x3038A84A : 0x30D32F2F);
            for (RectF line : TextLineRects.merge(wordRects)) {
                RectF bounds = pdfFile.pdfRectToDocument(first.pageIndex, zoom,
                        line.left, pageHeight - line.bottom, line.right, pageHeight - line.top);
                if (bounds == null) continue;
                canvas.drawRect(bounds, agentProposalSelectionPaint);
                if (!after) {
                    float strikeY = bounds.top + bounds.height() * 0.53f;
                    canvas.drawLine(bounds.left, strikeY, bounds.right, strikeY, agentProposalStrikePaint);
                }
            }
        }
        drawAgentProposalMarkers(canvas, pages);
    }

    /**
     * Draw one marker (a check mark) per proposal at its document-wide first fragment,
     * skipping LaTeX-made duplicates such as contents entries when it can.
     *
     * <p>The anchor is chosen before layout visibility is considered, so a page
     * that merely shows a later fragment of the same proposal never acquires a
     * second marker and scrolling never moves the existing one.
     */
    private void drawAgentProposalMarkers(Canvas canvas, Set<Integer> pages) {
        List<AgentProposalMarkerGeometry.Marker> markers = agentProposalMarkers();
        if (markers.isEmpty()) {
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        agentProposalMarkerStrokePaint.setColor(commentMarkerOutlineColor());
        agentProposalMarkerStrokePaint.setStrokeWidth(Math.max(1f, density));
        agentProposalMarkerFillPaint.setColor(commentMarkerSurfaceColor());
        agentProposalMarkerGlyphPaint.setColor(agentProposalCheckColor());
        for (AgentProposalMarkerGeometry.Marker marker : markers) {
            if (!pages.contains(marker.pageIndex)) continue;
            RectF bounds = new RectF(
                    marker.bounds.left, marker.bounds.top, marker.bounds.right, marker.bounds.bottom);
            canvas.drawRoundRect(
                    bounds,
                    COMMENT_MARKER_CORNER_RADIUS_DP * density,
                    COMMENT_MARKER_CORNER_RADIUS_DP * density,
                    agentProposalMarkerFillPaint);
            canvas.drawRoundRect(
                    bounds,
                    COMMENT_MARKER_CORNER_RADIUS_DP * density,
                    COMMENT_MARKER_CORNER_RADIUS_DP * density,
                    agentProposalMarkerStrokePaint);
            drawCheckGlyph(canvas, bounds);
        }
    }

    /**
     * The desktop's check mark, scaled from its 20-point marker box, in the green of Accept: a tap
     * on it accepts the proposal, a long press shows its options.
     */
    private void drawCheckGlyph(Canvas canvas, RectF bounds) {
        float scale = bounds.width() / COMMENT_MARKER_SIZE_DP;
        agentProposalMarkerGlyphPaint.setStrokeWidth(2.4f * scale);
        Path path = agentProposalMarkerPath;
        path.rewind();
        path.moveTo(bounds.left + 5f * scale, bounds.top + 10.5f * scale);
        path.lineTo(bounds.left + 8.5f * scale, bounds.top + 14f * scale);
        path.lineTo(bounds.left + 15f * scale, bounds.top + 6.5f * scale);
        canvas.drawPath(path, agentProposalMarkerGlyphPaint);
    }

    private int agentProposalCheckColor() {
        return nightMode ? 0xFF66BB6A : 0xFF239B4B;
    }

    private int commentMarkerOutlineColor() {
        return nightMode ? 0xFF9E9E9E : 0xFF616161;
    }

    private int commentMarkerSurfaceColor() {
        return nightMode ? 0xFF424242 : 0xFFFFFFFF;
    }

    /**
     * One marker per proposal, reusing the same rectangles for drawing and taps.
     *
     * <p>The layout only depends on the overlays, the document, and the zoom,
     * never on the scroll offset, so panning cannot rebuild it or change anchors.
     */
    private List<AgentProposalMarkerGeometry.Marker> agentProposalMarkers() {
        if (pdfFile == null || agentProposalOverlays.isEmpty()) {
            return Collections.emptyList();
        }
        if (agentProposalMarkerLayoutSource == agentProposalOverlays
                && agentProposalMarkerLayoutPdf == pdfFile
                && agentProposalMarkerLayoutZoom == zoom
                && agentProposalMarkerLayoutWidth == getWidth()
                && agentProposalMarkerLayoutHeight == getHeight()) {
            return agentProposalMarkerLayout;
        }
        agentProposalMarkerLayoutSource = agentProposalOverlays;
        agentProposalMarkerLayoutPdf = pdfFile;
        agentProposalMarkerLayoutZoom = zoom;
        agentProposalMarkerLayoutWidth = getWidth();
        agentProposalMarkerLayoutHeight = getHeight();

        List<AgentProposalMarkerGeometry.Fragment> fragments = new ArrayList<>();
        Map<Integer, AgentProposalMarkerGeometry.Bounds> pageBounds = new LinkedHashMap<>();
        Map<Integer, Float> pageMarkerSizes = new LinkedHashMap<>();
        for (AgentProposalOverlay overlay : agentProposalOverlays) {
            if (overlay.pageIndex < 0 || overlay.pageIndex >= pdfFile.getPagesCount()) continue;
            SizeF pageSize = pdfFile.getPagePointSize(overlay.pageIndex);
            if (pageSize == null || pageSize.getWidth() <= 0 || pageSize.getHeight() <= 0) continue;
            // Overlays use top-origin page points. Use the same crop-aware transform
            // as the highlights, without clipping: clipping could change anchor order.
            RectF anchor = pdfFile.pdfRectToDocument(overlay.pageIndex, zoom,
                    overlay.left, pageSize.getHeight() - overlay.bottom,
                    overlay.right, pageSize.getHeight() - overlay.top, false);
            if (anchor == null) continue;
            fragments.add(new AgentProposalMarkerGeometry.Fragment(
                    overlay.proposalId, overlay.pageIndex, anchor.left, anchor.top, overlay.duplicate));
            if (!pageBounds.containsKey(overlay.pageIndex)) {
                RectF bounds = pageDocumentBounds(overlay.pageIndex);
                if (bounds != null) {
                    pageBounds.put(overlay.pageIndex, new AgentProposalMarkerGeometry.Bounds(
                            bounds.left, bounds.top, bounds.right, bounds.bottom));
                    pageMarkerSizes.put(overlay.pageIndex, agentProposalMarkerSize(overlay.pageIndex));
                }
            }
        }
        agentProposalMarkerLayout = AgentProposalMarkerGeometry.layout(
                fragments, pageBounds, null, pageMarkerSizes);
        return agentProposalMarkerLayout;
    }

    /** Return the proposal whose marker (a check mark) is under a view-coordinate tap, if any. */
    public String agentProposalMarkerAt(float viewX, float viewY) {
        if (!highlightAgentEdits) return null;
        List<AgentProposalMarkerGeometry.Marker> markers = agentProposalMarkers();
        if (markers.isEmpty()) {
            return null;
        }
        float touchTarget = COMMENT_MARKER_TOUCH_TARGET_DP * getResources().getDisplayMetrics().density;
        return AgentProposalMarkerGeometry.hitTest(
                markers,
                -getCurrentXOffset() + viewX,
                -getCurrentYOffset() + viewY,
                touchTarget);
    }

    public static final class AgentProposalOverlay {
        public final String proposalId;
        public final String role;
        public final int pageIndex;
        public final float left;
        public final float top;
        public final float right;
        public final float bottom;
        public final boolean selected;
        /**
         * A LaTeX-made copy (contents entry, running head) of the edited text.
         * It is highlighted like any fragment but does not anchor the proposal's marker.
         */
        public final boolean duplicate;

        public AgentProposalOverlay(
                String proposalId,
                String role,
                int pageIndex,
                float left,
                float top,
                float right,
                float bottom,
                boolean selected) {
            this(proposalId, role, pageIndex, left, top, right, bottom, selected, false);
        }

        public AgentProposalOverlay(
                String proposalId,
                String role,
                int pageIndex,
                float left,
                float top,
                float right,
                float bottom,
                boolean selected,
                boolean duplicate) {
            this.proposalId = proposalId == null ? "" : proposalId;
            this.role = role == null ? "before" : role;
            this.pageIndex = pageIndex;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.selected = selected;
            this.duplicate = duplicate;
        }
    }

    private static boolean isStandaloneComment(PdfDocument.HighlightAnnotation annotation) {
        return annotation.isAppOwned()
                && annotation.getQuote().isEmpty()
                && !annotation.getNote().isEmpty();
    }

    private void drawCommentMarker(Canvas canvas, int page, PdfDocument.HighlightAnnotation annotation) {
        RectF bounds = annotation.getBounds();
        RectF marker = pdfFile.pdfRectToDocument(
                page,
                zoom,
                bounds.left,
                bounds.bottom,
                bounds.right,
                bounds.top
        );
        if (marker == null || marker.width() <= 0 || marker.height() <= 0) {
            return;
        }

        drawTextCommentMarker(
                canvas,
                textCommentMarker(page, marker),
                (COMMENT_MARKER_UNSELECTED_ALPHA << 24)
                        | (annotation.getColor() & 0x00FFFFFF),
                false
        );
    }

    private TextCommentMarkerGeometry.Bubble textCommentMarker(int page, RectF bounds) {
        float size = commentMarkerSize(page, COMMENT_MARKER_SIZE_DP);
        return TextCommentMarkerGeometry.centeredBubble(
                bounds.left,
                bounds.top,
                bounds.right,
                bounds.bottom,
                size
        );
    }

    private void drawTextCommentMarker(
            Canvas canvas,
            TextCommentMarkerGeometry.Bubble marker,
            int color,
            boolean sourceAnchorUnresolved) {
        float density = (marker.right - marker.left) / COMMENT_MARKER_SIZE_DP;
        float shadowSpread = COMMENT_MARKER_SHADOW_SPREAD_DP * density;
        float shadowOffsetY = COMMENT_MARKER_SHADOW_OFFSET_Y_DP * density;
        float cornerRadius = COMMENT_MARKER_CORNER_RADIUS_DP * density;
        TextCommentMarkerGeometry.Bubble shadow = marker.offset(0f, shadowOffsetY);

        buildCommentMarkerPath(commentMarkerShadowPath, shadow, cornerRadius);
        commentMarkerShadowPaint.setStrokeWidth(shadowSpread * 2f);
        canvas.drawPath(commentMarkerShadowPath, commentMarkerShadowPaint);

        buildCommentMarkerPath(commentMarkerPath, marker, cornerRadius);
        commentMarkerFillPaint.setColor(color);
        canvas.drawPath(commentMarkerPath, commentMarkerFillPaint);
        if (sourceAnchorUnresolved) {
            drawUnresolvedAnchorBadge(canvas, marker, color, density);
        }
    }

    private void drawUnresolvedAnchorBadge(
            Canvas canvas,
            TextCommentMarkerGeometry.Bubble marker,
            int markerColor,
            float density) {
        TextCommentMarkerGeometry.Badge badge = TextCommentMarkerGeometry.upperRightBadge(
                marker,
                COMMENT_MARKER_BADGE_DIAMETER_DP * density,
                COMMENT_MARKER_BADGE_INSET_DP * density
        );
        int alpha = Color.alpha(markerColor);
        commentMarkerBadgePaint.setColor((alpha << 24)
                | (COMMENT_MARKER_BADGE_COLOR & 0x00FFFFFF));
        canvas.drawCircle(badge.centerX, badge.centerY, badge.radius, commentMarkerBadgePaint);

        commentMarkerBadgeGlyphPaint.setColor((alpha << 24)
                | (COMMENT_MARKER_BADGE_GLYPH_COLOR & 0x00FFFFFF));
        commentMarkerBadgeGlyphPaint.setStrokeWidth(1f * density);
        canvas.drawLine(
                badge.centerX,
                badge.centerY - 1.7f * density,
                badge.centerX,
                badge.centerY + 0.35f * density,
                commentMarkerBadgeGlyphPaint
        );
        canvas.drawPoint(
                badge.centerX,
                badge.centerY + 1.8f * density,
                commentMarkerBadgeGlyphPaint
        );
    }

    private static void buildCommentMarkerPath(
            Path path,
            TextCommentMarkerGeometry.Bubble bubble,
            float cornerRadius) {
        float radius = Math.max(0f, Math.min(
                cornerRadius,
                Math.min(
                        (bubble.right - bubble.left) / 2f,
                        (bubble.bodyBottom - bubble.bodyTop) / 2f
                )
        ));
        path.rewind();
        path.moveTo(bubble.left + radius, bubble.bodyTop);
        if (bubble.tailOnTop) {
            path.lineTo(bubble.tailShoulderLeft, bubble.bodyTop);
            path.lineTo(bubble.tailTipX, bubble.top);
            path.lineTo(bubble.tailShoulderRight, bubble.bodyTop);
        }
        path.lineTo(bubble.right - radius, bubble.bodyTop);
        path.quadTo(
                bubble.right,
                bubble.bodyTop,
                bubble.right,
                bubble.bodyTop + radius
        );
        path.lineTo(bubble.right, bubble.bodyBottom - radius);
        path.quadTo(
                bubble.right,
                bubble.bodyBottom,
                bubble.right - radius,
                bubble.bodyBottom
        );
        if (!bubble.tailOnTop) {
            path.lineTo(bubble.tailShoulderRight, bubble.bodyBottom);
            path.lineTo(bubble.tailTipX, bubble.bottom);
            path.lineTo(bubble.tailShoulderLeft, bubble.bodyBottom);
        }
        path.quadTo(
                bubble.left,
                bubble.bodyBottom,
                bubble.left,
                bubble.bodyBottom - radius
        );
        path.lineTo(bubble.left, bubble.bodyTop + radius);
        path.quadTo(
                bubble.left,
                bubble.bodyTop,
                bubble.left + radius,
                bubble.bodyTop
        );
        path.close();
    }

    private Paint highlightOverlayPaint(PdfDocument.HighlightAnnotation annotation) {
        if (nightMode) {
            highlightAnnotationOverlayPaint.setColor((0x66 << 24) | (annotation.getColor() & 0x00FFFFFF));
            return highlightAnnotationOverlayPaint;
        }
        if (!annotation.isAppOwned()) {
            return null;
        }
        highlightAnnotationMultiplyPaint.setColor(0xFF000000 | (annotation.getColor() & 0x00FFFFFF));
        return highlightAnnotationMultiplyPaint;
    }

    private void drawWithListener(Canvas canvas, int page, OnDrawListener listener) {
        if (listener != null) {
            float translateX, translateY;
            if (swipeVertical) {
                translateX = pdfFile.getSecondaryPageOffset(page, zoom);
                translateY = pdfFile.getPageOffset(page, zoom);
            } else {
                translateY = pdfFile.getSecondaryPageOffset(page, zoom);
                translateX = pdfFile.getPageOffset(page, zoom);
            }

            canvas.translate(translateX, translateY);
            SizeF size = pdfFile.getPageSize(page);
            listener.onLayerDrawn(canvas,
                toCurrentScale(size.getWidth()),
                toCurrentScale(size.getHeight()),
                page);

            canvas.translate(-translateX, -translateY);
        }
    }

    private void drawPreviews(Canvas canvas) {
        PreviewStore<Bitmap> store = previewStore;
        if (store == null || pdfFile == null) {
            return;
        }
        previewVisiblePages.clear();
        collectVisiblePages(previewVisiblePages);
        for (Integer page : previewVisiblePages) {
            Bitmap preview = store.peek(page);
            if (preview == null) {
                requestPreview(page);
            } else {
                drawPreview(canvas, page, preview);
            }
        }
    }

    private void collectVisiblePages(Set<Integer> out) {
        long range = visiblePageRange();
        if (range < 0L) {
            return;
        }
        int to = (int) range;
        for (int page = (int) (range >>> 32); page <= to; page++) {
            out.add(page);
        }
    }

    private boolean isPageInPreviewRange(int page) {
        long range = visiblePageRange();
        if (range < 0L) {
            return false;
        }
        return page >= (int) (range >>> 32) && page <= (int) range;
    }

    private long visiblePageRange() {
        if (pdfFile == null) {
            return -1L;
        }
        int count = pdfFile.getPagesCount();
        if (count <= 0) {
            return -1L;
        }
        float offset = swipeVertical ? currentYOffset : currentXOffset;
        float viewSize = swipeVertical ? getHeight() : getWidth();
        float startDist = Math.max(0f, -offset);
        float endDist = Math.max(0f, -offset + viewSize);
        int first = pdfFile.getPageAtOffset(startDist, zoom);
        int last = pdfFile.getPageAtOffset(endDist, zoom);
        int from = Math.max(0, Math.min(first, last) - 1);
        int to = Math.min(count - 1, Math.max(first, last) + 1);
        return ((long) from << 32) | (to & 0xFFFFFFFFL);
    }

    private void drawPreview(Canvas canvas, int page, Bitmap bitmap) {
        if (bitmap == null) {
            return;
        }
        synchronized (bitmap) {
            if (bitmap.isRecycled()) {
                return;
            }
            int bitmapWidth = bitmap.getWidth();
            int bitmapHeight = bitmap.getHeight();
            if (bitmapWidth <= 0 || bitmapHeight <= 0) {
                return;
            }

            SizeF size = pdfFile.getPageSize(page);
            float localTranslationX;
            float localTranslationY;
            if (swipeVertical) {
                localTranslationY = pdfFile.getPageOffset(page, zoom);
                localTranslationX = pdfFile.getSecondaryPageOffset(page, zoom);
            } else {
                localTranslationX = pdfFile.getPageOffset(page, zoom);
                localTranslationY = pdfFile.getSecondaryPageOffset(page, zoom);
            }
            canvas.translate(localTranslationX, localTranslationY);

            float width = toCurrentScale(size.getWidth());
            float height = toCurrentScale(size.getHeight());
            RectF dstRect = new RectF(0, 0, (int) width, (int) height);

            float translationX = currentXOffset + localTranslationX;
            float translationY = currentYOffset + localTranslationY;
            if (translationX + dstRect.left >= getWidth() || translationX + dstRect.right <= 0
                    || translationY + dstRect.top >= getHeight() || translationY + dstRect.bottom <= 0) {
                canvas.translate(-localTranslationX, -localTranslationY);
                return;
            }

            CropBounds crop = pdfFile.getPageCropBounds(page);
            int srcLeft = (int) (crop.getLeft() * bitmapWidth);
            int srcTop = (int) (crop.getTop() * bitmapHeight);
            int srcRight = (int) (crop.getRight() * bitmapWidth);
            int srcBottom = (int) (crop.getBottom() * bitmapHeight);
            if (srcRight <= srcLeft || srcBottom <= srcTop) {
                canvas.translate(-localTranslationX, -localTranslationY);
                return;
            }

            Rect srcRect = new Rect(srcLeft, srcTop, srcRight, srcBottom);
            canvas.drawBitmap(bitmap, srcRect, dstRect, paint);

            canvas.translate(-localTranslationX, -localTranslationY);
        }
    }

    /**
     * Draw a given PagePart on the canvas
     */
    private void drawPart(Canvas canvas, PagePart part) {
        // Can seem strange, but avoid lot of calls
        RectF pageRelativeBounds = part.getPageRelativeBounds();
        Bitmap renderedBitmap = part.getRenderedBitmap();

        if (renderedBitmap == null) {
            return;
        }

        synchronized (renderedBitmap) {
            if (renderedBitmap.isRecycled()) {
                return;
            }

            // Move to the target page
            float localTranslationX = 0;
            float localTranslationY = 0;
            SizeF size = pdfFile.getPageSize(part.getPage());

            if (swipeVertical) {
                localTranslationY = pdfFile.getPageOffset(part.getPage(), zoom);
                localTranslationX = pdfFile.getSecondaryPageOffset(part.getPage(), zoom);
            } else {
                localTranslationX = pdfFile.getPageOffset(part.getPage(), zoom);
                localTranslationY = pdfFile.getSecondaryPageOffset(part.getPage(), zoom);
            }
            canvas.translate(localTranslationX, localTranslationY);

            Rect srcRect = new Rect(0, 0, renderedBitmap.getWidth(),
                renderedBitmap.getHeight());

            float offsetX = toCurrentScale(pageRelativeBounds.left * size.getWidth());
            float offsetY = toCurrentScale(pageRelativeBounds.top * size.getHeight());
            float width = toCurrentScale(pageRelativeBounds.width() * size.getWidth());
            float height = toCurrentScale(pageRelativeBounds.height() * size.getHeight());

            // If we use float values for this rectangle, there will be a possible gap between
            // page parts, especially when the zoom level is high.
            RectF dstRect = new RectF((int) offsetX, (int) offsetY,
                (int) (offsetX + width),
                (int) (offsetY + height));

            // Check if bitmap is in the screen
            float translationX = currentXOffset + localTranslationX;
            float translationY = currentYOffset + localTranslationY;
            if (translationX + dstRect.left >= getWidth() || translationX + dstRect.right <= 0 ||
                translationY + dstRect.top >= getHeight() || translationY + dstRect.bottom <= 0) {
                canvas.translate(-localTranslationX, -localTranslationY);
                return;
            }

            canvas.drawBitmap(renderedBitmap, srcRect, dstRect, paint);

            if (Constants.DEBUG_MODE) {
                debugPaint.setColor(part.getPage() % 2 == 0 ? Color.RED : Color.BLUE);
                canvas.drawRect(dstRect, debugPaint);
            }

            // Restore the canvas position
            canvas.translate(-localTranslationX, -localTranslationY);
        }
    }

    /**
     * Load all the parts around the center of the screen,
     * taking into account X and Y offsets, zoom level, and
     * the current page displayed
     */
    public void loadPages() {
        if (pdfFile == null || renderScheduler == null) {
            return;
        }
        // The page moved: the markers on screen are others, or elsewhere.
        if (markerAccessibility != null) markerAccessibility.invalidateRoot();

        synchronized (previewPending) {
            previewPending.clear();
        }

        renderScheduler.beginWave(RenderQueue.WaveKind.LOAD);
        cacheManager.makeANewSet();

        pagesLoader.loadPages();
        renderScheduler.endWave();
        redraw();
    }

    /**
     * Render the visible region of each visible page as a single part,
     * replacing any queued render tasks. Used for cheap whole-viewport
     * refreshes during a pinch gesture.
     */
    void loadViewportSnapshot() {
        if (pdfFile == null || renderScheduler == null) {
            return;
        }

        renderScheduler.beginWave(RenderQueue.WaveKind.SNAPSHOT);
        pagesLoader.loadViewportSnapshot();
        renderScheduler.endWave();
        redraw();
    }

    /**
     * Called when the PDF is loaded
     */
    void loadComplete(PdfFile pdfFile) {
        state = State.LOADED;

        this.pdfFile = pdfFile;
        if (pdfFile == null) {
            Log.e(TAG, "loadComplete: pdfFile is nul!!");
            return;
        }

        pageGenerations = new AtomicIntegerArray(pdfFile.getPagesCount());

        currentRenderEpoch++;
        int epoch = currentRenderEpoch;
        renderScheduler = new RenderScheduler(this, new RenderScheduler.PdfExecutor(this, epoch), epoch);
        renderScheduler.start();

        initPreviewStore(pdfFile);

        if (scrollHandle != null) {
            scrollHandle.setupLayout(this);
            isScrollHandleInit = true;
        }
        dragPinchManager.enable();
        callbacks.callOnLoadComplete(pdfFile.getPagesCount());
        if (!restoreDefaultViewState()) {
            jumpTo(defaultPage, false);
        }
    }

    private boolean restoreDefaultViewState() {
        ViewState viewState = defaultViewState;
        defaultViewState = null;
        return applyViewState(viewState);
    }

    public boolean applyViewState(ViewState viewState) {
        if (viewState == null || pdfFile == null || pdfFile.getPagesCount() == 0) {
            return false;
        }

        int pageIndex = pdfFile.determineValidPageNumberFrom(viewState.pageIndex);
        zoomTo(validZoom(viewState.zoom));
        if (viewState.swipeVertical != swipeVertical
                || viewState.horizontalReadingDirectionRtl != isHorizontalReadingDirectionRtl()
                || viewState.pagesPerRow != pdfFile.getPagesPerRow()
                || viewState.firstPageAlone != pdfFile.isFirstPageAlone()
                || getWidth() <= 0
                || getHeight() <= 0) {
            jumpTo(pageIndex, false);
            return true;
        }

        float pageLength = pdfFile.getPageLength(pageIndex, zoom);
        if (!isUsable(pageLength)) {
            jumpTo(pageIndex, false);
            return true;
        }

        float pageCenterOffsetRatio = isValidNumber(viewState.pageCenterOffsetRatio)
                ? MathUtils.limit(viewState.pageCenterOffsetRatio, 0f, 1f)
                : 0.5f;
        float relativeCrossAxisCenter = isValidNumber(viewState.relativeCrossAxisCenter)
                ? viewState.relativeCrossAxisCenter
                : 0.5f;
        float pageCenterOffset = pdfFile.getPageOffset(pageIndex, zoom) + pageLength * pageCenterOffsetRatio;

        float offsetX;
        float offsetY;
        if (swipeVertical) {
            float crossAxisSize = pdfFile.getMaxPageWidth();
            if (!isUsable(crossAxisSize)) {
                jumpTo(pageIndex, false);
                return true;
            }
            offsetX = -(relativeCrossAxisCenter * crossAxisSize) + getWidth() * 0.5f;
            offsetY = -pageCenterOffset + getHeight() * 0.5f;
        } else {
            float crossAxisSize = pdfFile.getMaxPageHeight();
            if (!isUsable(crossAxisSize)) {
                jumpTo(pageIndex, false);
                return true;
            }
            offsetX = -pageCenterOffset + getWidth() * 0.5f;
            offsetY = -(relativeCrossAxisCenter * crossAxisSize) + getHeight() * 0.5f;
        }

        moveTo(offsetX, offsetY);
        showPage(pageIndex);
        return true;
    }

    void loadError(Throwable t) {
        state = State.ERROR;
        // store reference, because callbacks will be cleared in recycle() method
        OnErrorListener onErrorListener = callbacks.getOnError();
        recycle();
        invalidate();
        if (onErrorListener != null) {
            onErrorListener.onError(t);
        } else {
            Log.e("PDFView", "load pdf error", t);
        }
    }

    void redraw() {
        invalidate();
    }

    /**
     * Called when a rendering task is over and
     * a PagePart has been freshly created.
     *
     * @param part The created PagePart.
     */
    public void onBitmapRendered(PagePart part) {
        // when it is first rendered part
        if (state == State.LOADED) {
            state = State.SHOWN;
            callbacks.callOnRender(pdfFile.getPagesCount());
        }

        if (part.getGeneration() != getPageGeneration(part.getPage())) {
            Bitmap bitmap = part.getRenderedBitmap();
            if (bitmap != null) {
                synchronized (bitmap) {
                    bitmap.recycle();
                }
            }
            return;
        }

        cacheManager.cachePart(part);
        redraw();
    }

    /*
     * I have added this function to tap the PDF to show / hide the page scroll handler.
     * I think I could've done it using the toggle functions in MainActivity
     * */

    public void performTap() {
        // get the coordinates of the view
        int[] coordinates = new int[2];
        getRootView().getLocationOnScreen(coordinates);

        MotionEvent event = MotionEvent.obtain(
            SystemClock.uptimeMillis(),
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_DOWN,
            coordinates[0], coordinates[1],
            0);
        getRootView().dispatchTouchEvent(event);
        callbacks.callOnTap(event);
        ScrollHandle handle = getScrollHandle();
    }

    public void moveTo(float offsetX, float offsetY) {
        /*
         * Changing the 3rd argument (moveHandle) to false solved the issue with animation when
         * scrolling file with the handler invisible.
         * I have spent at least 6 hours trying to find why the flinging animation gets so bad
         * when I hide the scrollHandler view.
         * I turned that off when the scroll isn't shown.
         * And to move the handle, I added few lines to the onSingleTapConfirmed() function in
         * DragPinchManager, that will move the handle based on pdfFile.getPositionOffset value.
         * */
        boolean shouldMove = scrollHandle != null && scrollHandle.customShown();
        moveTo(offsetX, offsetY, shouldMove);
    }

    /**
     * Move to the given X and Y offsets, but check them ahead of time
     * to be sure not to go outside the the big strip.
     *
     * @param offsetX    The big strip X offset to use as the left border of the screen.
     * @param offsetY    The big strip Y offset to use as the right border of the screen.
     * @param moveHandle whether to move scroll handle or not
     */
    public void moveTo(float offsetX, float offsetY, boolean moveHandle) {
        if (pdfFile == null) {
            return;
        }

        float viewportLeft = getContentViewportLeft();
        float viewportTop = getContentViewportTop();
        float viewportRight = getContentViewportRight();
        float viewportBottom = getContentViewportBottom();

        if (swipeVertical) {
            float scaledPageWidth = toCurrentScale(pdfFile.getMaxPageWidth());
            offsetX = ContentViewportMath.clampStripOffset(
                    offsetX, scaledPageWidth, viewportLeft, viewportRight);
            float contentHeight = pdfFile.getDocLen(zoom);
            offsetY = ContentViewportMath.clampStripOffset(
                    offsetY, contentHeight, viewportTop, viewportBottom);

            if (offsetY < currentYOffset) {
                scrollDir = ScrollDir.END;
            } else if (offsetY > currentYOffset) {
                scrollDir = ScrollDir.START;
            } else {
                scrollDir = ScrollDir.NONE;
            }
        } else {
            float scaledPageHeight = toCurrentScale(pdfFile.getMaxPageHeight());
            offsetY = ContentViewportMath.clampStripOffset(
                    offsetY, scaledPageHeight, viewportTop, viewportBottom);
            float contentWidth = pdfFile.getDocLen(zoom);
            offsetX = ContentViewportMath.clampStripOffset(
                    offsetX, contentWidth, viewportLeft, viewportRight);

            if (offsetX < currentXOffset) {
                scrollDir = ScrollDir.END;
            } else if (offsetX > currentXOffset) {
                scrollDir = ScrollDir.START;
            } else {
                scrollDir = ScrollDir.NONE;
            }
        }

        if (scrollDir == ScrollDir.END) {
            lastScrollDir = 1;
        } else if (scrollDir == ScrollDir.START) {
            lastScrollDir = -1;
        }

        currentXOffset = offsetX;
        currentYOffset = offsetY;
        float positionOffset = getPositionOffset();

        if (moveHandle && scrollHandle != null && !documentFitsView()) {
            scrollHandle.setScroll(positionOffset);
        }

        callbacks.callOnPageScroll(getCurrentPage(), positionOffset);

        redraw();
    }

    void loadPageByOffset() {
        if (pdfFile == null || 0 == pdfFile.getPagesCount()) {
            return;
        }

        float offset, screenCenter;
        if (swipeVertical) {
            offset = currentYOffset;
            screenCenter = ((float) getHeight()) / 2;
        } else {
            offset = currentXOffset;
            screenCenter = ((float) getWidth()) / 2;
        }

        int page = pdfFile.getPageAtOffset(-(offset - screenCenter), zoom);

        if (page >= 0 && page <= pdfFile.getPagesCount() - 1 && page != getCurrentPage()) {
            showPage(page);
        } else {
            loadPages();
        }
    }

    /**
     * Animate to the nearest snapping position for the current SnapPolicy
     */
    public void performPageSnap() {
        if (!pageSnap || pdfFile == null || pdfFile.getPagesCount() == 0) {
            return;
        }
        int centerPage = findFocusPage(currentXOffset, currentYOffset);
        SnapEdge edge = findSnapEdge(centerPage);
        if (edge == SnapEdge.NONE) {
            return;
        }

        float offset = snapOffsetForPage(centerPage, edge);
        if (swipeVertical) {
            animationManager.startYAnimation(currentYOffset, -offset);
        } else {
            animationManager.startXAnimation(currentXOffset, -offset);
        }
    }

    /**
     * Find the edge to snap to when showing the specified page
     */
    SnapEdge findSnapEdge(int page) {
        if (!pageSnap || page < 0) {
            return SnapEdge.NONE;
        }
        int row = pdfFile.getRowOfPage(page);
        float currentOffset = swipeVertical ? currentYOffset : currentXOffset;
        float offset = -pdfFile.getRowOffset(row, zoom);
        int length = swipeVertical ? getHeight() : getWidth();
        float pageLength = pdfFile.getRowLength(row, zoom);

        if (length >= pageLength) {
            return SnapEdge.CENTER;
        }
        else if (currentOffset >= offset) {
            return SnapEdge.START;
        }
        else if (offset - pageLength > currentOffset - length) {
            return SnapEdge.END;
        }
        else {
            return SnapEdge.NONE;
        }
    }

    /**
     * Get the offset to move to in order to snap to the page
     */
    float snapOffsetForPage(int pageIndex, SnapEdge edge) {
        int row = pdfFile.getRowOfPage(pageIndex);
        float offset = pdfFile.getRowOffset(row, zoom);

        float length = swipeVertical ? getHeight() : getWidth();
        float pageLength = pdfFile.getRowLength(row, zoom);

        if (edge == SnapEdge.CENTER) {
            offset = offset - length / 2f + pageLength / 2f;
        } else if (edge == SnapEdge.END) {
            offset = offset - length + pageLength;
        }
        return offset;
    }

    int findFocusPage(float xOffset, float yOffset) {
        float currOffset = swipeVertical ? yOffset : xOffset;
        float length = swipeVertical ? getHeight() : getWidth();
        // make sure first and last page can be found
        if (currOffset > -1) {
            return 0;
        } else if (currOffset < -pdfFile.getDocLen(zoom) + length + 1) {
            return pdfFile.getPagesCount() - 1;
        }
        // else find page in center
        float center = currOffset - length / 2f;
        return pdfFile.getPageAtOffset(-center, zoom);
    }

    public int getPageAfterRowStep(int fromPage, int direction) {
        if (pdfFile == null || pdfFile.getPagesCount() == 0) {
            return fromPage;
        }
        int row = pdfFile.getRowOfPage(pdfFile.determineValidPageNumberFrom(fromPage));
        int targetRow = Math.max(0, Math.min(pdfFile.getRowCount() - 1, row + direction));
        return pdfFile.getRowFirstPage(targetRow);
    }

    public int getRowFirstPage(int pageIndex) {
        if (pdfFile == null) {
            return pageIndex;
        }
        return pdfFile.getRowFirstPage(pdfFile.getRowOfPage(pageIndex));
    }

    public int getRowLastPage(int pageIndex) {
        if (pdfFile == null) {
            return pageIndex;
        }
        int row = pdfFile.getRowOfPage(pageIndex);
        return pdfFile.getRowFirstPage(row) + pdfFile.getPagesInRow(row) - 1;
    }

    /**
     * @return true if single page fills the entire screen in the scrolling direction
     */
    public boolean pageFillsScreen() {
        if (pdfFile == null) {
            return false;
        }
        int row = pdfFile.getRowOfPage(currentPage);
        float start = -pdfFile.getRowOffset(row, zoom);
        float end = start - pdfFile.getRowLength(row, zoom);
        if (isSwipeVertical()) {
            return start > currentYOffset && end < currentYOffset - getHeight();
        } else {
            return start > currentXOffset && end < currentXOffset - getWidth();
        }
    }

    /**
     * Move relatively to the current position.
     *
     * @param dx The X difference you want to apply.
     * @param dy The Y difference you want to apply.
     * @see #moveTo(float, float)
     */
    public void moveRelativeTo(float dx, float dy) {
        moveTo(currentXOffset + dx, currentYOffset + dy);
    }

    /**
     * Change the zoom level
     */
    public void zoomTo(float zoom) {
        this.zoom = zoom;
    }

    /**
     * Change the zoom level, relatively to a pivot point.
     * It will call moveTo() to make sure the given point stays
     * in the middle of the screen.
     *
     * @param zoom  The zoom level.
     * @param pivot The point on the screen that should stays.
     */
    public void zoomCenteredTo(float zoom, PointF pivot) {
        float dzoom = zoom / this.zoom;
        zoomTo(zoom);
        float baseX = currentXOffset * dzoom;
        float baseY = currentYOffset * dzoom;
        baseX += (pivot.x - pivot.x * dzoom);
        baseY += (pivot.y - pivot.y * dzoom);
        moveTo(baseX, baseY);
    }

    /**
     * @see #zoomCenteredTo(float, PointF)
     */
    public void zoomCenteredRelativeTo(float dzoom, PointF pivot) {
        zoomCenteredTo(zoom * dzoom, pivot);
    }

    /**
     * Checks if whole document can be displayed on screen, doesn't include zoom
     *
     * @return true if whole document can displayed at once, false otherwise
     */
    public boolean documentFitsView() {
        if (pdfFile == null) {
            return true;
        }
        float len = pdfFile.getDocLen(1);
        if (swipeVertical) {
            return len < getHeight();
        } else {
            return len < getWidth();
        }
    }

    public void fitToWidth(int page) {
        if (state != State.SHOWN) {
            Log.e(TAG, "Cannot fit, document not rendered yet");
            return;
        }
        zoomTo(getWidth() / pdfFile.getPageSize(page).getWidth());
        jumpTo(page);
    }

    public SizeF getPageSize(int pageIndex) {
        if (pdfFile == null) {
            return new SizeF(0, 0);
        }
        return pdfFile.getPageSize(pageIndex);
    }

    public int getCurrentPage() {
        return currentPage;
    }

    public float getCurrentXOffset() {
        return currentXOffset;
    }

    public float getCurrentYOffset() {
        return currentYOffset;
    }

    public void setContentViewport(RectF viewport) {
        if (viewport == null) {
            clearContentViewport();
            return;
        }
        float left = Math.max(0f, Math.min(viewport.left, getWidth()));
        float top = Math.max(0f, Math.min(viewport.top, getHeight()));
        float right = Math.max(left, Math.min(viewport.right, getWidth()));
        float bottom = Math.max(top, Math.min(viewport.bottom, getHeight()));
        if (right - left < 1f || bottom - top < 1f) {
            return;
        }
        if (hasContentViewport
                && contentViewport.left == left
                && contentViewport.top == top
                && contentViewport.right == right
                && contentViewport.bottom == bottom) {
            return;
        }
        contentViewport.set(left, top, right, bottom);
        hasContentViewport = true;
        normalizePositionForContentViewport();
    }

    public void clearContentViewport() {
        if (!hasContentViewport) {
            return;
        }
        hasContentViewport = false;
        normalizePositionForContentViewport();
    }

    private void normalizePositionForContentViewport() {
        if (pdfFile == null) {
            return;
        }
        moveTo(currentXOffset, currentYOffset);
        loadPages();
    }

    private float getContentViewportLeft() {
        return hasContentViewport ? contentViewport.left : 0f;
    }

    private float getContentViewportTop() {
        return hasContentViewport ? contentViewport.top : 0f;
    }

    private float getContentViewportRight() {
        return hasContentViewport ? contentViewport.right : getWidth();
    }

    public void setSearchBottomInset(float inset) {
        searchBottomInset = Math.max(0f, inset);
    }

    /** Lets the end of the document scroll above a card covering the bottom {@code inset} pixels. */
    public void setOverlayBottomInset(float inset) {
        float previous = overlayBottomInset;
        overlayBottomInset = Math.max(0f, inset);
        if (overlayBottomInset < previous && pdfFile != null) {
            // The card shrank or closed: don't leave empty space below the document's end.
            moveTo(currentXOffset, currentYOffset, false);
        }
    }

    float getContentViewportBottom() {
        float bottom = hasContentViewport ? contentViewport.bottom : getHeight();
        float inset = Math.max(searchBottomInset, overlayBottomInset);
        return Math.min(bottom, Math.max(1f, getHeight() - inset));
    }

    public float toRealScale(float size) {
        return size / zoom;
    }

    public float toCurrentScale(float size) {
        return size * zoom;
    }

    public float getZoom() {
        return zoom;
    }

    private float validZoom(float value) {
        if (!isValidNumber(value)) {
            return NORMAL_SCALE;
        }
        return MathUtils.limit(value, minZoom, maxZoom);
    }

    private static float ratioOrDefault(float numerator, float denominator, float fallback) {
        if (!isUsable(denominator)) {
            return fallback;
        }
        float ratio = numerator / denominator;
        return isValidNumber(ratio) ? ratio : fallback;
    }

    private static boolean isUsable(float value) {
        return isValidNumber(value) && value > 0f;
    }

    private static boolean isValidNumber(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    TextSelectionManager getTextSelectionManager() {
        return textSelectionManager;
    }

    StampPlacementManager getStampPlacementManager() {
        return stampPlacementManager;
    }

    ReviewRegionSelectionManager getReviewRegionSelectionManager() {
        return reviewRegionSelectionManager;
    }

    PdfCommentMoveManager getPdfCommentMoveManager() {
        return pdfCommentMoveManager;
    }

    boolean isNightModeEnabled() {
        return nightMode;
    }

    boolean startTextSelectionAt(float viewX, float viewY) {
        return textSelectionManager != null && textSelectionManager.startWordSelectionAt(viewX, viewY);
    }

    /**
     * Selects the text of a saved passage with handles, as a long press and drag would, and tells
     * the selection listener. [pages] are 0-based, [normalizedRects] top-origin page fractions in
     * reading order. False when no text lies there.
     */
    public boolean startTextSelectionCovering(List<Integer> pages, List<RectF> normalizedRects) {
        return textSelectionManager != null && textSelectionManager.startSelectionCovering(pages, normalizedRects);
    }

    public boolean isTextSelectionEnabled() {
        return textSelectionManager != null && textSelectionManager.isEnabled();
    }

    public void setTextSelectionEnabled(boolean enabled) {
        if (textSelectionManager != null) {
            textSelectionManager.setEnabled(enabled);
        }
    }

    public void setTextSelectionColor(int color) {
        if (textSelectionManager != null) {
            textSelectionManager.setSelectionColor(color);
        }
        setSelectedHighlightColor(color);
    }

    private void setSelectedHighlightColor(int color) {
        if (selectedHighlightFillPaint == null || selectedHighlightStrokePaint == null) {
            return;
        }
        selectedHighlightFillPaint.setColor((0x33 << 24) | (color & 0x00FFFFFF));
        selectedHighlightStrokePaint.setColor((0xCC << 24) | (color & 0x00FFFFFF));
    }

    public void setSelectedHighlightAnnotation(HighlightAnnotation annotation) {
        selectedHighlightAnnotation = annotation;
        invalidate();
    }

    public void clearSelectedHighlightAnnotation() {
        if (selectedHighlightAnnotation == null) {
            return;
        }
        selectedHighlightAnnotation = null;
        invalidate();
    }

    public void setSelectedPdfComment(PdfComment comment) {
        selectedPdfComment = comment;
        invalidate();
    }

    public void clearSelectedPdfComment() {
        if (selectedPdfComment == null) {
            return;
        }
        selectedPdfComment = null;
        invalidate();
    }

    public void setSourceAnchorUnresolvedCommentNames(Collection<String> names) {
        LinkedHashSet<String> safeNames = new LinkedHashSet<>();
        if (names != null) {
            for (String name : names) {
                String logicalName = logicalCommentName(name);
                if (!logicalName.isEmpty()) {
                    safeNames.add(logicalName);
                }
            }
        }
        sourceAnchorUnresolvedCommentNames = safeNames.isEmpty()
                ? Collections.<String>emptySet()
                : Collections.unmodifiableSet(safeNames);
        invalidate();
    }

    /** Install the complete authoritative sidecar projection without touching Pdfium. */
    public void setManagedPdfComments(Collection<ManagedPdfComment> comments) {
        pointMarkerLayoutSource = null;
        synchronized (managedPdfCommentLock) {
            List<ManagedPdfComment> records = new ArrayList<>();
            if (comments != null) {
                for (ManagedPdfComment comment : comments) {
                    if (comment != null && comment.pageIndex >= 0 && !comment.name.isEmpty()
                            && !comment.pdfRects.isEmpty()) {
                        records.add(copyManagedPdfComment(comment));
                    }
                }
            }
            managedPdfCommentRecords = records;
            rebuildManagedPdfCommentIndexesLocked();
        }
        invalidate();
    }

    public void clearManagedPdfComments() {
        synchronized (managedPdfCommentLock) {
            managedPdfCommentRecords = null;
            managedPdfCommentSnapshot = Collections.emptyList();
            managedPdfCommentsByPage = Collections.emptyMap();
            managedPdfCommentRecordsByName = Collections.emptyMap();
        }
        invalidate();
    }

    public boolean isManagedPdfCommentsEnabled() {
        synchronized (managedPdfCommentLock) {
            return managedPdfCommentRecords != null;
        }
    }

    private static ManagedPdfComment copyManagedPdfComment(ManagedPdfComment source) {
        return new ManagedPdfComment(
                source.pageIndex,
                source.subtype,
                source.name,
                source.pdfRects,
                source.color,
                source.contents,
                source.author,
                source.creationDate,
                source.modifiedDate,
                source.inReplyToName,
                source.anchorStatus
        );
    }

    private static RectF managedPdfCommentBounds(ManagedPdfComment record) {
        RectF result = null;
        for (RectF rect : record.pdfRects) {
            if (result == null) {
                result = new RectF(rect);
            } else {
                result.union(rect);
            }
        }
        return result;
    }

    private void rebuildManagedPdfCommentIndexesLocked() {
        if (managedPdfCommentRecords == null) {
            managedPdfCommentSnapshot = Collections.emptyList();
            managedPdfCommentsByPage = Collections.emptyMap();
            managedPdfCommentRecordsByName = Collections.emptyMap();
            return;
        }
        Map<Integer, Integer> nextPageIndex = new HashMap<>();
        Map<Integer, List<PdfComment>> byPage = new LinkedHashMap<>();
        Map<String, ManagedPdfComment> byName = new LinkedHashMap<>();
        List<PdfComment> snapshot = new ArrayList<>();
        for (ManagedPdfComment record : managedPdfCommentRecords) {
            int annotationIndex = nextPageIndex.containsKey(record.pageIndex)
                    ? nextPageIndex.get(record.pageIndex)
                    : 0;
            nextPageIndex.put(record.pageIndex, annotationIndex + 1);
            PdfComment comment = new PdfComment(
                    record.pageIndex,
                    annotationIndex,
                    record.subtype,
                    record.name,
                    null,
                    managedPdfCommentBounds(record),
                    record.color,
                    record.contents,
                    record.author,
                    record.creationDate,
                    record.modifiedDate,
                    record.inReplyToName,
                    record.anchorStatus
            ).withPdfRects(record.pdfRects);
            snapshot.add(comment);
            List<PdfComment> pageComments = byPage.get(record.pageIndex);
            if (pageComments == null) {
                pageComments = new ArrayList<>();
                byPage.put(record.pageIndex, pageComments);
            }
            pageComments.add(comment);
            byName.put(record.name, record);
        }
        for (Map.Entry<Integer, List<PdfComment>> entry : byPage.entrySet()) {
            entry.setValue(Collections.unmodifiableList(entry.getValue()));
        }
        managedPdfCommentSnapshot = Collections.unmodifiableList(snapshot);
        managedPdfCommentsByPage = Collections.unmodifiableMap(byPage);
        managedPdfCommentRecordsByName = Collections.unmodifiableMap(byName);
    }

    private List<PdfComment> managedPdfCommentsSnapshotOrNull() {
        synchronized (managedPdfCommentLock) {
            return managedPdfCommentRecords == null ? null : managedPdfCommentSnapshot;
        }
    }

    private List<PdfComment> managedPdfCommentsForPage(int page) {
        synchronized (managedPdfCommentLock) {
            if (managedPdfCommentRecords == null) {
                return null;
            }
            List<PdfComment> comments = managedPdfCommentsByPage.get(page);
            return comments == null ? Collections.<PdfComment>emptyList() : comments;
        }
    }

    private ManagedPdfComment managedPdfCommentRecord(String physicalName) {
        synchronized (managedPdfCommentLock) {
            return managedPdfCommentRecords == null
                    ? null
                    : managedPdfCommentRecordsByName.get(physicalName);
        }
    }

    public boolean startPdfCommentMove(PdfComment comment, OnPdfCommentMoveListener listener) {
        if (pdfCommentMoveManager == null) {
            return false;
        }
        setSelectedPdfComment(comment);
        return pdfCommentMoveManager.start(comment, listener);
    }

    public boolean isPdfCommentMoveActive() {
        return pdfCommentMoveManager != null && pdfCommentMoveManager.isActive();
    }

    public void cancelPdfCommentMove() {
        if (pdfCommentMoveManager != null) {
            pdfCommentMoveManager.cancel();
        }
    }

    public boolean hasTextSelection() {
        return textSelectionManager != null && textSelectionManager.hasSelection();
    }

    public String getSelectedText() {
        if (textSelectionManager == null) {
            return "";
        }
        return textSelectionManager.getSelectedText();
    }

    public HighlightRequest getHighlightRequest() {
        if (textSelectionManager == null) {
            return null;
        }
        return textSelectionManager.getHighlightRequest();
    }

    public boolean addHighlight(HighlightRequest request, int color, String groupKey) {
        return addHighlight(request, color, groupKey, null);
    }

    public boolean addHighlight(HighlightRequest request, int color, String groupKey, String creationDate) {
        if (request == null || request.fragments.isEmpty()) {
            return false;
        }
        List<Integer> createdPages = new ArrayList<>();
        for (HighlightFragment fragment : request.fragments) {
            if (!addHighlightAnnotation(
                    fragment.pageIndex,
                    fragment.pdfRects,
                    color,
                    request.selectedText,
                    groupKey,
                    creationDate
            )) {
                rollbackHighlightGroup(createdPages, groupKey);
                return false;
            }
            createdPages.add(fragment.pageIndex);
        }
        return true;
    }

    private void rollbackHighlightGroup(List<Integer> pages, String groupKey) {
        if (pdfFile == null || groupKey == null || groupKey.isEmpty()) {
            return;
        }
        for (int index = pages.size() - 1; index >= 0; --index) {
            int page = pages.get(index);
            try {
                pdfFile.removeHighlightAnnotation(page, -1, groupKey);
                overlayContentChanged(page);
            } catch (Throwable throwable) {
                Log.e(TAG, "rollbackHighlightGroup: failed to remove fragment", throwable);
            }
        }
    }

    public boolean addHighlightAnnotation(int pageIndex, List<RectF> pdfRects, int color,
                                          String contents, String groupKey) {
        return addHighlightAnnotation(pageIndex, pdfRects, color, contents, groupKey, null);
    }

    public boolean addHighlightAnnotation(int pageIndex, List<RectF> pdfRects, int color,
                                          String contents, String groupKey, String creationDate) {
        if (pdfFile == null || pdfRects == null || pdfRects.isEmpty()) {
            return false;
        }
        try {
            boolean created = pdfFile.createHighlightAnnotation(pageIndex, pdfRects, color, contents,
                    groupKey, creationDate);
            if (created) {
                overlayContentChanged(pageIndex);
            }
            return created;
        } catch (Throwable throwable) {
            Log.e(TAG, "addHighlightAnnotation: failed to create highlight", throwable);
            return false;
        }
    }

    public HighlightRequest createCommentRequestAt(float viewX, float viewY, float markerSizeDp) {
        if (pdfFile == null || markerSizeDp <= 0) {
            return null;
        }
        float docX = -getCurrentXOffset() + viewX;
        float docY = -getCurrentYOffset() + viewY;
        int page = pdfFile.getPageAtOffset(isSwipeVertical() ? docY : docX,
                isSwipeVertical() ? docX : docY, getZoom());
        if (page < 0 || page >= pdfFile.getPagesCount()) {
            return null;
        }

        SizeF pageSize = pdfFile.getPagePointSize(page);
        RectF documentPageBounds = pdfFile.pdfRectToDocument(
                page,
                getZoom(),
                0,
                0,
                pageSize.getWidth(),
                pageSize.getHeight()
        );
        if (documentPageBounds == null || !documentPageBounds.contains(docX, docY)) {
            return null;
        }

        PointF center = pdfFile.documentToPdf(page, getZoom(), docX, docY);
        float markerSizePx = markerSizeDp * getResources().getDisplayMetrics().density;
        PointF extent = pdfFile.documentToPdf(
                page,
                getZoom(),
                docX + markerSizePx,
                docY + markerSizePx
        );
        float width = Math.min(pageSize.getWidth(), Math.abs(extent.x - center.x));
        float height = Math.min(pageSize.getHeight(), Math.abs(extent.y - center.y));
        if (width <= 0 || height <= 0) {
            return null;
        }

        float left = Math.max(0, Math.min(center.x - width / 2f, pageSize.getWidth() - width));
        float bottom = Math.max(0, Math.min(center.y - height / 2f, pageSize.getHeight() - height));
        RectF marker = new RectF(left, bottom + height, left + width, bottom);
        return new HighlightRequest(page, Collections.singletonList(marker), "");
    }

    public static class PendingStamp {
        public final int pageIndex;
        public final RectF pdfRect;
        public final float[][] strokes;
        public final int color;
        public final float normalizedStrokeWidth;

        PendingStamp(int pageIndex, RectF pdfRect, float[][] strokes, int color, float normalizedStrokeWidth) {
            this.pageIndex = pageIndex;
            this.pdfRect = pdfRect;
            this.strokes = strokes;
            this.color = color;
            this.normalizedStrokeWidth = normalizedStrokeWidth;
        }
    }

    /** One transient review-card region in normalized, top-left page coordinates. */
    public static class ReviewRegionSelection {
        public final int pageIndex;
        public final RectF normalizedRect;

        public ReviewRegionSelection(int pageIndex, RectF normalizedRect) {
            this.pageIndex = pageIndex;
            this.normalizedRect = new RectF(normalizedRect);
        }
    }

    public boolean startReviewRegionSelectionAt(float viewX, float viewY) {
        return reviewRegionSelectionManager != null
                && reviewRegionSelectionManager.startAt(viewX, viewY);
    }

    /** Edit an existing answer region without changing the PDF or the saved card. */
    public boolean startReviewRegionSelection(int pageIndex, RectF normalizedRect) {
        return reviewRegionSelectionManager != null
                && reviewRegionSelectionManager.start(pageIndex, normalizedRect);
    }

    public boolean hasReviewRegionSelection() {
        return reviewRegionSelectionManager != null
                && reviewRegionSelectionManager.hasSelection();
    }

    public ReviewRegionSelection getReviewRegionSelection() {
        return reviewRegionSelectionManager == null
                ? null
                : reviewRegionSelectionManager.selection();
    }

    public void clearReviewRegionSelection() {
        if (reviewRegionSelectionManager != null) {
            reviewRegionSelectionManager.cancel();
        }
    }

    /** Told when the selected answer box or the number of boxes changes. */
    public interface OnReviewRegionsChangeListener {
        void onReviewRegionsChanged(int selectedIndex, int count);
    }

    public void setOnReviewRegionsChangeListener(OnReviewRegionsChangeListener listener) {
        if (reviewRegionSelectionManager != null) {
            reviewRegionSelectionManager.setListener(listener == null ? null : listener::onReviewRegionsChanged);
        }
    }

    /** Edit several answer boxes, possibly on different pages, without scrolling. */
    public boolean startReviewRegionSelections(java.util.List<ReviewRegionSelection> regions, int selectedIndex) {
        return reviewRegionSelectionManager != null
                && reviewRegionSelectionManager.startAll(regions, selectedIndex);
    }

    /**
     * Add a default-size answer box centred on the view point (clamped onto the page there) and
     * select it. Returns its index, or -1.
     */
    public int addReviewRegionSelectionAt(float viewX, float viewY) {
        return reviewRegionSelectionManager == null ? -1 : reviewRegionSelectionManager.addAt(viewX, viewY);
    }

    /** Remove the selected answer box; the last one is never removed. */
    public boolean removeSelectedReviewRegion() {
        return reviewRegionSelectionManager != null && reviewRegionSelectionManager.removeSelected();
    }

    public boolean selectReviewRegion(int index) {
        return reviewRegionSelectionManager != null && reviewRegionSelectionManager.select(index);
    }

    public int getSelectedReviewRegionIndex() {
        return reviewRegionSelectionManager == null ? -1 : reviewRegionSelectionManager.selectedIndex();
    }

    /** Every answer box being edited, in order; empty if none, null if one cannot be measured. */
    public java.util.List<ReviewRegionSelection> getReviewRegionSelections() {
        return reviewRegionSelectionManager == null
                ? java.util.Collections.emptyList()
                : reviewRegionSelectionManager.selections();
    }

    public void startStampPlacement(int pageIndex, RectF pdfRect, float[][] strokes, int color,
                                    float normalizedStrokeWidth) {
        if (stampPlacementManager == null) {
            return;
        }
        stampPlacementManager.start(pageIndex, pdfRect, strokes, color, normalizedStrokeWidth);
        invalidate();
    }

    public boolean startStampPlacementAtViewCenter(float[][] strokes, int color,
                                                   float normalizedStrokeWidth, float aspect,
                                                   float pageWidthFraction) {
        if (stampPlacementManager == null || pdfFile == null || aspect <= 0 || pageWidthFraction <= 0) {
            return false;
        }
        float docX = -currentXOffset + getWidth() / 2f;
        float docY = -currentYOffset + getHeight() / 2f;
        int page = pdfFile.getPageAtOffset(isSwipeVertical() ? docY : docX,
                isSwipeVertical() ? docX : docY, zoom);
        if (page < 0 || page >= pdfFile.getPagesCount()) {
            return false;
        }
        PointF center = pdfFile.documentToPdf(page, zoom, docX, docY);
        if (center == null) {
            return false;
        }
        SizeF pageSize = pdfFile.getPagePointSize(page);
        float pageWidth = pageSize.getWidth();
        float pageHeight = pageSize.getHeight();
        if (pageWidth <= 0 || pageHeight <= 0) {
            return false;
        }
        float width = pageWidthFraction * pageWidth;
        float height = width * aspect;
        if (height > pageHeight) {
            height = pageHeight;
            width = height / aspect;
        }
        float left = center.x - width / 2f;
        float bottom = center.y - height / 2f;
        left = Math.max(0, Math.min(left, pageWidth - width));
        bottom = Math.max(0, Math.min(bottom, pageHeight - height));
        RectF rect = new RectF(left, bottom + height, left + width, bottom);
        startStampPlacement(page, rect, strokes, color, normalizedStrokeWidth);
        return true;
    }

    public void cancelStampPlacement() {
        if (stampPlacementManager == null) {
            return;
        }
        stampPlacementManager.cancel();
        invalidate();
    }

    public void setOnStampPlacementDiscardListener(Runnable listener) {
        this.onStampPlacementDiscardListener = listener;
    }

    void notifyStampPlacementDiscard() {
        if (onStampPlacementDiscardListener != null) {
            onStampPlacementDiscardListener.run();
        }
    }

    public boolean hasPendingStampPlacement() {
        return stampPlacementManager != null && stampPlacementManager.hasPending();
    }

    public PendingStamp getPendingStampPlacement() {
        if (stampPlacementManager == null || !stampPlacementManager.hasPending()) {
            return null;
        }
        return new PendingStamp(stampPlacementManager.getPendingPageIndex(),
                stampPlacementManager.getPendingRect(),
                stampPlacementManager.getPendingStrokes(),
                stampPlacementManager.getPendingColor(),
                stampPlacementManager.getPendingNormalizedStrokeWidth());
    }

    public boolean commitPendingStampPlacement() {
        PendingStamp pending = getPendingStampPlacement();
        if (pending == null) {
            return false;
        }
        boolean added = addSignature(pending.pageIndex, pending.pdfRect, pending.strokes,
                pending.color, pending.normalizedStrokeWidth);
        if (added) {
            stampPlacementManager.cancel();
            invalidate();
        }
        return added;
    }

    public boolean addSignature(int pageIndex, RectF pdfRect, float[][] strokes, int color,
                                float normalizedStrokeWidth) {
        if (pdfFile == null) {
            return false;
        }
        try {
            boolean created = pdfFile.addSignature(pageIndex, pdfRect, strokes, color,
                    normalizedStrokeWidth);
            if (created) {
                pageContentChanged(pageIndex);
            }
            return created;
        } catch (Throwable throwable) {
            Log.e(TAG, "addSignature: failed to add signature", throwable);
            return false;
        }
    }

    public HighlightAnnotation findHighlightAnnotationAt(float viewX, float viewY) {
        if (!highlightComments) return null;
        if (pdfFile == null) {
            return null;
        }
        float docX = -getCurrentXOffset() + viewX;
        float docY = -getCurrentYOffset() + viewY;
        int page = pdfFile.getPageAtOffset(isSwipeVertical() ? docY : docX,
                isSwipeVertical() ? docX : docY, getZoom());
        if (page < 0 || page >= pdfFile.getPagesCount()) {
            return null;
        }

        PointF point = pdfFile.documentToPdf(page, getZoom(), docX, docY);
        List<PdfDocument.HighlightAnnotation> annotations;
        try {
            annotations = pdfFile.getHighlightAnnotations(page);
        } catch (Throwable throwable) {
            Log.e(TAG, "findHighlightAnnotationAt: failed to read highlights", throwable);
            return null;
        }
        if (annotations == null || annotations.isEmpty()) {
            return null;
        }

        PdfDocument.HighlightAnnotation hit = null;
        for (int i = annotations.size() - 1; i >= 0; i--) {
            PdfDocument.HighlightAnnotation candidate = annotations.get(i);
            String group = candidate.getGroupKey();
            boolean standaloneComment = isStandaloneComment(candidate);
            if (!standaloneComment && (group == null || group.isEmpty())) {
                continue;
            }
            RectF bounds = candidate.getBounds();
            boolean containsTap = standaloneComment
                    ? commentMarkerContainsViewPoint(page, bounds, viewX, viewY)
                    : bounds != null
                            && pdfRectContainsPoint(bounds, point.x, point.y, HIGHLIGHT_HIT_TOLERANCE);
            if (containsTap) {
                hit = candidate;
                break;
            }
        }
        if (hit == null) {
            return null;
        }

        String hitGroup = hit.getGroupKey();
        RectF pdfBounds = unionGroupBounds(annotations, hitGroup, hit.getBounds());
        RectF viewBounds = null;
        if (pdfBounds != null) {
            viewBounds = pdfFile.pdfRectToDocument(
                    page,
                    getZoom(),
                    pdfBounds.left,
                    pdfBounds.bottom,
                    pdfBounds.right,
                    pdfBounds.top
            );
            if (viewBounds != null) {
                viewBounds.offset(getCurrentXOffset(), getCurrentYOffset());
            }
        }
        return new HighlightAnnotation(
                page,
                hit.getAnnotationIndex(),
                hitGroup,
                viewBounds,
                pdfBounds,
                hit.getQuote(),
                hit.getNote()
        );
    }

    public PdfComment findPdfCommentAt(float viewX, float viewY) {
        if (!highlightComments) return null;
        if (pdfFile == null) {
            return null;
        }
        float docX = -getCurrentXOffset() + viewX;
        float docY = -getCurrentYOffset() + viewY;
        int page = pdfFile.getPageAtOffset(isSwipeVertical() ? docY : docX,
                isSwipeVertical() ? docX : docY, getZoom());
        if (page < 0 || page >= pdfFile.getPagesCount()) {
            return null;
        }

        List<PdfComment> managed = managedPdfCommentsForPage(page);
        if (managed != null) {
            return findManagedPdfCommentAt(page, managed, viewX, viewY, docX, docY);
        }

        PointF point = pdfFile.documentToPdf(page, getZoom(), docX, docY);
        List<PdfDocument.PdfComment> comments;
        try {
            comments = pdfFile.getPdfComments(page);
        } catch (Throwable throwable) {
            Log.e(TAG, "findPdfCommentAt: failed to read comments", throwable);
            return null;
        }
        if (comments == null || comments.isEmpty()) {
            return null;
        }

        PdfDocument.PdfComment best = null;
        RectF bestViewBounds = null;
        boolean bestContainsVisibleBounds = false;
        float bestDistanceSquared = Float.MAX_VALUE;
        List<PdfDocument.HighlightAnnotation> highlightAnnotations = null;
        boolean highlightAnnotationsLoaded = false;
        for (int i = comments.size() - 1; i >= 0; i--) {
            PdfDocument.PdfComment candidate = comments.get(i);
            if (candidate.isReply()) {
                continue;
            }
            RectF pdfBounds = candidate.getBounds();
            if (pdfBounds == null) {
                continue;
            }
            RectF viewBounds = pdfCommentViewBounds(page, pdfBounds);
            if (viewBounds == null) {
                continue;
            }

            boolean containsMarker = false;
            TextCommentMarkerGeometry.Bubble highlightMarker = null;
            boolean containsTap;
            if (candidate.getSubtype() == PdfDocument.PdfComment.SUBTYPE_TEXT) {
                RectF touchBounds = new RectF(viewBounds);
                float minimumSize = COMMENT_MARKER_TOUCH_TARGET_DP
                        * getResources().getDisplayMetrics().density;
                float horizontalExpansion = Math.max(0, (minimumSize - touchBounds.width()) / 2f);
                float verticalExpansion = Math.max(0, (minimumSize - touchBounds.height()) / 2f);
                touchBounds.inset(-horizontalExpansion, -verticalExpansion);
                containsTap = touchBounds.contains(viewX, viewY);
            } else {
                if (PdfComment.isTextMarkup(candidate.getSubtype())
                        && isLogicalRootPdfComment(candidate)) {
                    if (!highlightAnnotationsLoaded) {
                        try {
                            highlightAnnotations = pdfFile.getHighlightAnnotations(page);
                        } catch (Throwable throwable) {
                            Log.e(TAG, "findPdfCommentAt: failed to read highlight geometry",
                                    throwable);
                            highlightAnnotations = Collections.emptyList();
                        }
                        highlightAnnotationsLoaded = true;
                    }
                    highlightMarker = highlightCommentMarker(
                            page, candidate, highlightAnnotations);
                    containsMarker = commentMarkerContainsViewPoint(
                            highlightMarker, viewX, viewY);
                }
                containsTap = containsMarker || pdfRectContainsPoint(
                        pdfBounds, point.x, point.y, HIGHLIGHT_HIT_TOLERANCE);
            }
            if (!containsTap) {
                continue;
            }

            boolean containsVisibleBounds = containsMarker || viewBounds.contains(viewX, viewY);
            float centerX = containsMarker && highlightMarker != null
                    ? (highlightMarker.left + highlightMarker.right) / 2f + getCurrentXOffset()
                    : viewBounds.centerX();
            float centerY = containsMarker && highlightMarker != null
                    ? (highlightMarker.top + highlightMarker.bottom) / 2f + getCurrentYOffset()
                    : viewBounds.centerY();
            float dx = viewX - centerX;
            float dy = viewY - centerY;
            float distanceSquared = dx * dx + dy * dy;
            if (best == null
                    || (containsVisibleBounds && !bestContainsVisibleBounds)
                    || (containsVisibleBounds == bestContainsVisibleBounds
                            && distanceSquared < bestDistanceSquared)) {
                best = candidate;
                bestViewBounds = viewBounds;
                bestContainsVisibleBounds = containsVisibleBounds;
                bestDistanceSquared = distanceSquared;
            }
        }
        return best == null ? null : logicalizePdfComment(
                toPdfComment(page, best, bestViewBounds));
    }

    private PdfComment findManagedPdfCommentAt(
            int page,
            List<PdfComment> comments,
            float viewX,
            float viewY,
            float docX,
            float docY) {
        PointF point = pdfFile.documentToPdf(page, getZoom(), docX, docY);
        PdfComment best = null;
        RectF bestViewBounds = null;
        boolean bestContainsVisibleBounds = false;
        float bestDistanceSquared = Float.MAX_VALUE;
        for (int index = comments.size() - 1; index >= 0; --index) {
            PdfComment candidate = comments.get(index);
            if (candidate.isReply() || candidate.pdfBounds == null) {
                continue;
            }
            ManagedPdfComment record = managedPdfCommentRecord(candidate.name);
            if (record == null) {
                continue;
            }
            RectF viewBounds = pdfCommentViewBounds(page, candidate.pdfBounds);
            if (viewBounds == null) {
                continue;
            }
            TextCommentMarkerGeometry.Bubble marker = PdfComment.isTextMarkup(candidate.subtype)
                    ? managedHighlightCommentMarker(record)
                    : null;
            boolean containsMarker = commentMarkerContainsViewPoint(marker, viewX, viewY);
            boolean containsTap = containsMarker;
            if (candidate.subtype == PdfComment.SUBTYPE_TEXT) {
                RectF touchBounds = new RectF(viewBounds);
                float minimumSize = COMMENT_MARKER_TOUCH_TARGET_DP
                        * getResources().getDisplayMetrics().density;
                float horizontalExpansion = Math.max(0, (minimumSize - touchBounds.width()) / 2f);
                float verticalExpansion = Math.max(0, (minimumSize - touchBounds.height()) / 2f);
                touchBounds.inset(-horizontalExpansion, -verticalExpansion);
                containsTap = touchBounds.contains(viewX, viewY);
            } else if (!containsTap) {
                for (RectF rect : record.pdfRects) {
                    if (pdfRectContainsPoint(rect, point.x, point.y, HIGHLIGHT_HIT_TOLERANCE)) {
                        containsTap = true;
                        break;
                    }
                }
            }
            if (!containsTap) {
                continue;
            }
            boolean containsVisibleBounds = containsMarker || viewBounds.contains(viewX, viewY);
            float centerX = containsMarker && marker != null
                    ? (marker.left + marker.right) / 2f + getCurrentXOffset()
                    : viewBounds.centerX();
            float centerY = containsMarker && marker != null
                    ? (marker.top + marker.bottom) / 2f + getCurrentYOffset()
                    : viewBounds.centerY();
            float dx = viewX - centerX;
            float dy = viewY - centerY;
            float distanceSquared = dx * dx + dy * dy;
            if (best == null
                    || (containsVisibleBounds && !bestContainsVisibleBounds)
                    || (containsVisibleBounds == bestContainsVisibleBounds
                            && distanceSquared < bestDistanceSquared)) {
                best = candidate;
                bestViewBounds = viewBounds;
                bestContainsVisibleBounds = containsVisibleBounds;
                bestDistanceSquared = distanceSquared;
            }
        }
        if (best == null) {
            return null;
        }
        PdfComment located = new PdfComment(
                best.pageIndex,
                best.annotationIndex,
                best.subtype,
                best.name,
                bestViewBounds,
                best.pdfBounds,
                best.color,
                best.contents,
                best.author,
                best.creationDate,
                best.modifiedDate,
                best.inReplyToName,
                best.anchorStatus
        );
        return logicalizePdfComment(located);
    }

    public PdfComment findPdfComment(int pageIndex, String name, int annotationIndex, int subtype) {
        if (pdfFile == null || pageIndex < 0 || pageIndex >= pdfFile.getPagesCount()) {
            return null;
        }
        List<PdfComment> managed = managedPdfCommentsSnapshotOrNull();
        if (managed != null) {
            String requestedName = name == null ? "" : name;
            String safeName = logicalCommentName(requestedName);
            PdfComment fallback = null;
            for (PdfComment candidate : managed) {
                if (candidate.subtype != subtype) {
                    continue;
                }
                if (!safeName.isEmpty() && safeName.equals(candidate.name)) {
                    return candidate;
                }
                if (candidate.pageIndex == pageIndex
                        && ((requestedName.isEmpty() && candidate.annotationIndex == annotationIndex)
                                || requestedName.equals(candidate.name))) {
                    fallback = candidate;
                }
            }
            return logicalizePdfComment(fallback);
        }
        List<PdfDocument.PdfComment> comments;
        try {
            comments = pdfFile.getPdfComments(pageIndex);
        } catch (Throwable throwable) {
            Log.e(TAG, "findPdfComment: failed to read comments", throwable);
            return null;
        }
        String requestedName = name == null ? "" : name;
        String safeName = logicalCommentName(requestedName);
        for (PdfDocument.PdfComment candidate : comments) {
            boolean matches = !safeName.isEmpty()
                    ? safeName.equals(candidate.getName())
                            && candidate.getSubtype() == subtype
                    : candidate.getAnnotationIndex() == annotationIndex
                            && candidate.getSubtype() == subtype;
            if (matches) {
                return toPdfComment(
                        pageIndex,
                        candidate,
                        pdfCommentViewBounds(pageIndex, candidate.getBounds()));
            }
        }
        if (!requestedName.equals(safeName)) {
            for (PdfDocument.PdfComment candidate : comments) {
                if (requestedName.equals(candidate.getName())
                        && candidate.getSubtype() == subtype) {
                    return logicalizePdfComment(toPdfComment(
                            pageIndex,
                            candidate,
                            pdfCommentViewBounds(pageIndex, candidate.getBounds())));
                }
            }
        }
        PdfComment root = findPhysicalPdfCommentByName(safeName, subtype);
        if (root != null) {
            return root;
        }
        return null;
    }

    public List<PdfComment> getAllPdfCommentsIncludingFragments() {
        List<PdfComment> managed = managedPdfCommentsSnapshotOrNull();
        if (managed != null) {
            return managed;
        }
        if (pdfFile == null) {
            return Collections.emptyList();
        }
        List<PdfComment> result = new ArrayList<>();
        int pageCount = pdfFile.getPagesCount();
        for (int page = 0; page < pageCount; page++) {
            List<PdfDocument.PdfComment> comments;
            try {
                comments = pdfFile.getPdfComments(page);
            } catch (Throwable throwable) {
                Log.e(TAG, "getAllPdfComments: failed on page " + page, throwable);
                continue;
            }
            if (comments == null) {
                continue;
            }
            for (PdfDocument.PdfComment comment : comments) {
                if (comment != null) {
                    result.add(toPdfComment(page, comment, null));
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    public List<PdfComment> getAllPdfComments() {
        List<PdfComment> physical = getAllPdfCommentsIncludingFragments();
        if (physical.isEmpty()) {
            return physical;
        }
        Set<String> rootNames = new LinkedHashSet<>();
        for (PdfComment comment : physical) {
            if (!comment.name.isEmpty() && logicalCommentName(comment.name).equals(comment.name)) {
                rootNames.add(comment.name);
            }
        }
        List<PdfComment> logical = new ArrayList<>();
        for (PdfComment comment : physical) {
            String rootName = logicalCommentName(comment.name);
            if (!rootName.equals(comment.name) && rootNames.contains(rootName)) {
                continue;
            }
            String replyTarget = logicalCommentName(comment.inReplyToName);
            if (!replyTarget.equals(comment.inReplyToName) && rootNames.contains(replyTarget)) {
                logical.add(copyPdfComment(comment, comment.name, replyTarget));
            } else {
                logical.add(comment);
            }
        }
        return Collections.unmodifiableList(logical);
    }

    public void focusOnPdfComment(PdfComment comment) {
        if (comment == null) {
            return;
        }
        setSelectedPdfComment(comment);
        if (comment.pdfBounds != null) {
            focusOnPdfRect(comment.pageIndex, comment.pdfBounds, getZoom());
        } else {
            jumpTo(comment.pageIndex);
        }
    }

    private RectF pdfCommentViewBounds(int page, RectF pdfBounds) {
        if (pdfBounds == null) {
            return null;
        }
        RectF viewBounds = pdfFile.pdfRectToDocument(
                page,
                getZoom(),
                pdfBounds.left,
                pdfBounds.bottom,
                pdfBounds.right,
                pdfBounds.top
        );
        if (viewBounds != null) {
            viewBounds.offset(getCurrentXOffset(), getCurrentYOffset());
            viewBounds.sort();
        }
        return viewBounds;
    }

    private PdfComment toPdfComment(int page, PdfDocument.PdfComment comment, RectF viewBounds) {
        return new PdfComment(
                page,
                comment.getAnnotationIndex(),
                comment.getSubtype(),
                comment.getName(),
                viewBounds,
                comment.getBounds(),
                comment.getColor(),
                comment.getContents(),
                comment.getAuthor(),
                comment.getCreationDate(),
                comment.getModifiedDate(),
                comment.getInReplyToName(),
                comment.getAnchorStatus()
        ).withPdfRects(comment.getMarkupRects());
    }

    private static String logicalCommentName(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        int marker = name.lastIndexOf(COMMENT_FRAGMENT_MARKER);
        if (marker <= 0) {
            return name;
        }
        String suffix = name.substring(marker + COMMENT_FRAGMENT_MARKER.length());
        try {
            if (Integer.parseInt(suffix) > 0) {
                return name.substring(0, marker);
            }
        } catch (NumberFormatException ignored) {
            // Ordinary annotation name containing the marker text.
        }
        return name;
    }

    private static PdfComment copyPdfComment(
            PdfComment source, String name, String inReplyToName) {
        return new PdfComment(
                source.pageIndex,
                source.annotationIndex,
                source.subtype,
                name,
                source.viewBounds,
                source.pdfBounds,
                source.color,
                source.contents,
                source.author,
                source.creationDate,
                source.modifiedDate,
                inReplyToName,
                source.anchorStatus
        ).withPdfRects(source.pdfRects);
    }

    private PdfComment findPhysicalPdfCommentByName(String name, int subtype) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        for (PdfComment candidate : getAllPdfCommentsIncludingFragments()) {
            if (name.equals(candidate.name) && subtype == candidate.subtype) {
                return candidate;
            }
        }
        return null;
    }

    private PdfComment logicalizePdfComment(PdfComment physical) {
        if (physical == null) {
            return null;
        }
        String logicalName = logicalCommentName(physical.name);
        if (logicalName.equals(physical.name)) {
            return physical;
        }
        PdfComment root = findPhysicalPdfCommentByName(logicalName, physical.subtype);
        if (root == null) {
            return physical;
        }
        // Keep the tapped fragment's location for initial focus, but expose
        // the root identity and text to every editing/threading action.
        return new PdfComment(
                physical.pageIndex,
                physical.annotationIndex,
                physical.subtype,
                logicalName,
                physical.viewBounds,
                physical.pdfBounds,
                root.color,
                root.contents,
                root.author,
                root.creationDate,
                root.modifiedDate,
                root.inReplyToName,
                root.anchorStatus
        ).withPdfRects(physical.pdfRects);
    }

    private boolean commentMarkerContainsViewPoint(int page, RectF pdfBounds, float viewX, float viewY) {
        if (pdfBounds == null) {
            return false;
        }
        RectF viewBounds = pdfFile.pdfRectToDocument(
                page,
                getZoom(),
                pdfBounds.left,
                pdfBounds.bottom,
                pdfBounds.right,
                pdfBounds.top
        );
        if (viewBounds == null) {
            return false;
        }
        viewBounds.offset(getCurrentXOffset(), getCurrentYOffset());
        float minimumSize = COMMENT_MARKER_TOUCH_TARGET_DP
                * getResources().getDisplayMetrics().density;
        float horizontalExpansion = Math.max(0, (minimumSize - viewBounds.width()) / 2f);
        float verticalExpansion = Math.max(0, (minimumSize - viewBounds.height()) / 2f);
        viewBounds.inset(-horizontalExpansion, -verticalExpansion);
        return viewBounds.contains(viewX, viewY);
    }

    private boolean commentMarkerContainsViewPoint(
            TextCommentMarkerGeometry.Bubble marker,
            float viewX,
            float viewY) {
        if (marker == null) {
            return false;
        }
        RectF touchBounds = new RectF(
                marker.left + getCurrentXOffset(),
                marker.top + getCurrentYOffset(),
                marker.right + getCurrentXOffset(),
                marker.bottom + getCurrentYOffset()
        );
        float minimumSize = COMMENT_MARKER_TOUCH_TARGET_DP
                * getResources().getDisplayMetrics().density;
        float horizontalExpansion = Math.max(0, (minimumSize - touchBounds.width()) / 2f);
        float verticalExpansion = Math.max(0, (minimumSize - touchBounds.height()) / 2f);
        touchBounds.inset(-horizontalExpansion, -verticalExpansion);
        return touchBounds.contains(viewX, viewY);
    }

    public RectF findHighlightPdfBounds(int pageIndex, String groupKey, int annotationIndex) {
        if (pdfFile == null || pageIndex < 0 || pageIndex >= pdfFile.getPagesCount()) {
            return null;
        }
        List<PdfDocument.HighlightAnnotation> annotations;
        try {
            pdfFile.invalidateHighlightAnnotationCache(pageIndex);
            annotations = pdfFile.getHighlightAnnotations(pageIndex);
        } catch (Throwable throwable) {
            Log.e(TAG, "findHighlightPdfBounds: failed to read highlights", throwable);
            return null;
        }
        if (annotations == null || annotations.isEmpty()) {
            return null;
        }
        if (groupKey != null && !groupKey.isEmpty()) {
            for (PdfDocument.HighlightAnnotation candidate : annotations) {
                if (groupKey.equals(candidate.getGroupKey())) {
                    return candidate.getBounds();
                }
            }
        }
        for (PdfDocument.HighlightAnnotation candidate : annotations) {
            if (candidate.getAnnotationIndex() == annotationIndex) {
                return candidate.getBounds();
            }
        }
        return null;
    }

    public HighlightAnnotation findHighlightAnnotationMatching(HighlightRequest request) {
        if (pdfFile == null || request == null || request.pdfRects.isEmpty()) {
            return null;
        }
        int page = request.pageIndex;
        if (page < 0 || page >= pdfFile.getPagesCount()) {
            return null;
        }
        List<PdfDocument.HighlightAnnotation> annotations;
        try {
            annotations = pdfFile.getHighlightAnnotations(page);
        } catch (Throwable throwable) {
            Log.e(TAG, "findHighlightAnnotationMatching: failed to read highlights", throwable);
            return null;
        }
        if (annotations == null || annotations.isEmpty()) {
            return null;
        }

        Map<String, List<PdfDocument.HighlightAnnotation>> groups = new HashMap<>();
        for (PdfDocument.HighlightAnnotation candidate : annotations) {
            String group = candidate.getGroupKey();
            if (group == null || group.isEmpty()) {
                continue;
            }
            List<PdfDocument.HighlightAnnotation> members = groups.get(group);
            if (members == null) {
                members = new ArrayList<>();
                groups.put(group, members);
            }
            members.add(candidate);
        }

        for (Map.Entry<String, List<PdfDocument.HighlightAnnotation>> entry : groups.entrySet()) {
            if (!groupMatchesRects(entry.getValue(), request.pdfRects)) {
                continue;
            }
            if (!highlightGroupMatchesRequest(entry.getKey(), request)) {
                continue;
            }
            PdfDocument.HighlightAnnotation first = entry.getValue().get(0);
            String group = entry.getKey();
            RectF pdfBounds = unionGroupBounds(annotations, group, first.getBounds());
            RectF viewBounds = null;
            if (pdfBounds != null) {
                viewBounds = pdfFile.pdfRectToDocument(
                        page,
                        getZoom(),
                        pdfBounds.left,
                        pdfBounds.bottom,
                        pdfBounds.right,
                        pdfBounds.top
                );
                if (viewBounds != null) {
                    viewBounds.offset(getCurrentXOffset(), getCurrentYOffset());
                }
            }
            return new HighlightAnnotation(
                    page,
                    first.getAnnotationIndex(),
                    group,
                    viewBounds,
                    pdfBounds,
                    first.getQuote(),
                    first.getNote()
            );
        }
        return null;
    }

    private boolean highlightGroupMatchesRequest(String group, HighlightRequest request) {
        if (pdfFile == null || group == null || group.isEmpty()) {
            return false;
        }
        for (HighlightFragment fragment : request.fragments) {
            List<PdfDocument.HighlightAnnotation> members = new ArrayList<>();
            for (PdfDocument.HighlightAnnotation candidate :
                    pdfFile.getHighlightAnnotations(fragment.pageIndex)) {
                if (group.equals(candidate.getGroupKey())) {
                    members.add(candidate);
                }
            }
            if (!groupMatchesRects(members, fragment.pdfRects)) {
                return false;
            }
        }
        return true;
    }

    private static boolean groupMatchesRects(List<PdfDocument.HighlightAnnotation> members, List<RectF> rects) {
        if (members.size() != rects.size()) {
            return false;
        }
        boolean[] used = new boolean[members.size()];
        for (RectF rect : rects) {
            boolean matched = false;
            for (int i = 0; i < members.size(); i++) {
                if (used[i]) {
                    continue;
                }
                RectF bounds = members.get(i).getBounds();
                if (bounds != null && pdfRectsAlmostEqual(rect, bounds, HIGHLIGHT_MATCH_TOLERANCE)) {
                    used[i] = true;
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private static boolean pdfRectsAlmostEqual(RectF a, RectF b, float tolerance) {
        return Math.abs(Math.min(a.left, a.right) - Math.min(b.left, b.right)) <= tolerance
                && Math.abs(Math.max(a.left, a.right) - Math.max(b.left, b.right)) <= tolerance
                && Math.abs(Math.min(a.top, a.bottom) - Math.min(b.top, b.bottom)) <= tolerance
                && Math.abs(Math.max(a.top, a.bottom) - Math.max(b.top, b.bottom)) <= tolerance;
    }

    private static boolean pdfRectContainsPoint(RectF rect, float x, float y, float tolerance) {
        float left = Math.min(rect.left, rect.right);
        float right = Math.max(rect.left, rect.right);
        float bottom = Math.min(rect.top, rect.bottom);
        float top = Math.max(rect.top, rect.bottom);
        return x >= left - tolerance && x <= right + tolerance
                && y >= bottom - tolerance && y <= top + tolerance;
    }

    private static RectF unionGroupBounds(List<PdfDocument.HighlightAnnotation> annotations,
                                          String group, RectF fallback) {
        if (group == null || group.isEmpty()) {
            return fallback;
        }
        float left = Float.MAX_VALUE;
        float right = -Float.MAX_VALUE;
        float top = -Float.MAX_VALUE;
        float bottom = Float.MAX_VALUE;
        boolean found = false;
        for (PdfDocument.HighlightAnnotation candidate : annotations) {
            if (!group.equals(candidate.getGroupKey())) {
                continue;
            }
            RectF b = candidate.getBounds();
            if (b == null) {
                continue;
            }
            left = Math.min(left, Math.min(b.left, b.right));
            right = Math.max(right, Math.max(b.left, b.right));
            top = Math.max(top, Math.max(b.top, b.bottom));
            bottom = Math.min(bottom, Math.min(b.top, b.bottom));
            found = true;
        }
        if (!found) {
            return fallback;
        }
        return new RectF(left, top, right, bottom);
    }

    public boolean setHighlightAnnotationColor(HighlightAnnotation annotation, int color) {
        if (pdfFile == null || annotation == null) {
            return false;
        }
        try {
            List<Integer> pages = pagesForHighlightGroup(annotation);
            boolean updated = false;
            for (Integer page : pages) {
                boolean fragmentUpdated = pdfFile.setHighlightAnnotationColor(
                        page,
                        page == annotation.pageIndex ? annotation.annotationIndex : -1,
                        annotation.groupKey,
                        color
                );
                updated |= fragmentUpdated;
                if (fragmentUpdated) {
                    overlayContentChanged(page);
                }
            }
            return updated;
        } catch (Throwable throwable) {
            Log.e(TAG, "setHighlightAnnotationColor: failed to update highlight", throwable);
            return false;
        }
    }

    public boolean setHighlightAnnotationNote(HighlightAnnotation annotation, String note, String modifiedDate) {
        if (pdfFile == null || annotation == null) {
            return false;
        }
        try {
            List<Integer> pages = pagesForHighlightGroup(annotation);
            boolean updated = false;
            for (Integer page : pages) {
                boolean fragmentUpdated = pdfFile.setHighlightAnnotationNote(
                        page,
                        page == annotation.pageIndex ? annotation.annotationIndex : -1,
                        annotation.groupKey,
                        note,
                        modifiedDate
                );
                updated |= fragmentUpdated;
                if (fragmentUpdated) {
                    overlayContentChanged(page);
                }
            }
            return updated;
        } catch (Throwable throwable) {
            Log.e(TAG, "setHighlightAnnotationNote: failed to update highlight", throwable);
            return false;
        }
    }

    public boolean removeHighlightAnnotation(HighlightAnnotation annotation) {
        if (pdfFile == null || annotation == null) {
            return false;
        }
        try {
            List<Integer> pages = pagesForHighlightGroup(annotation);
            boolean removed = false;
            for (int index = pages.size() - 1; index >= 0; --index) {
                int page = pages.get(index);
                boolean fragmentRemoved = pdfFile.removeHighlightAnnotation(
                        page,
                        page == annotation.pageIndex ? annotation.annotationIndex : -1,
                        annotation.groupKey
                );
                removed |= fragmentRemoved;
                if (fragmentRemoved) {
                    overlayContentChanged(page);
                }
            }
            return removed;
        } catch (Throwable throwable) {
            Log.e(TAG, "removeHighlightAnnotation: failed to remove highlight", throwable);
            return false;
        }
    }

    private List<Integer> pagesForHighlightGroup(HighlightAnnotation annotation) {
        if (pdfFile == null || annotation == null
                || annotation.groupKey == null || annotation.groupKey.isEmpty()) {
            return Collections.singletonList(annotation == null ? -1 : annotation.pageIndex);
        }
        List<Integer> pages = new ArrayList<>();
        for (int page = 0; page < pdfFile.getPagesCount(); ++page) {
            List<PdfDocument.HighlightAnnotation> annotations = pdfFile.getHighlightAnnotations(page);
            for (PdfDocument.HighlightAnnotation candidate : annotations) {
                if (annotation.groupKey.equals(candidate.getGroupKey())) {
                    pages.add(page);
                    break;
                }
            }
        }
        if (pages.isEmpty()) {
            pages.add(annotation.pageIndex);
        }
        return pages;
    }

    public boolean addPdfComment(int pageIndex, RectF pdfRect, String name, String contents,
                                 String author, String creationDate, String modifiedDate) {
        return addPdfComment(pageIndex, pdfRect, COMMENT_MARKER_FILL_COLOR, name, contents,
                author, creationDate, modifiedDate);
    }

    public boolean addPdfComment(int pageIndex, RectF pdfRect, int color, String name,
                                 String contents, String author, String creationDate,
                                 String modifiedDate) {
        if (pdfFile == null || pdfRect == null) {
            return false;
        }
        if (isManagedPdfCommentsEnabled()) {
            return addManagedPdfComment(new ManagedPdfComment(
                    pageIndex,
                    PdfComment.SUBTYPE_TEXT,
                    name,
                    Collections.singletonList(pdfRect),
                    color,
                    contents,
                    author,
                    creationDate,
                    modifiedDate,
                    "",
                    ""
            ));
        }
        try {
            boolean created = pdfFile.createPdfComment(
                    pageIndex, pdfRect, color, name, contents, author, creationDate, modifiedDate);
            if (created) {
                pageContentChanged(pageIndex);
            }
            return created;
        } catch (Throwable throwable) {
            Log.e(TAG, "addPdfComment: failed to create comment", throwable);
            return false;
        }
    }

    public boolean addPdfHighlightComment(int pageIndex, List<RectF> pdfRects, int color,
            String name, String contents, String author, String creationDate, String modifiedDate) {
        return addPdfMarkupComment(pageIndex, pdfRects, color, name, contents, author, creationDate, modifiedDate, PdfComment.SUBTYPE_HIGHLIGHT);
    }

    public boolean addPdfHighlightComment(HighlightRequest request, int color,
            String name, String contents, String author, String creationDate, String modifiedDate) {
        return addPdfMarkupComment(request, color, name, contents, author, creationDate, modifiedDate, PdfComment.SUBTYPE_HIGHLIGHT);
    }

    public boolean addPdfMarkupComment(int pageIndex, List<RectF> pdfRects, int color,
                                          String name, String contents, String author,
                                          String creationDate, String modifiedDate, int subtype) {
        if (pdfFile == null || pdfRects == null || pdfRects.isEmpty()) {
            return false;
        }
        try {
            boolean created = pdfFile.createPdfMarkupComment(
                    pageIndex, pdfRects, color, name, contents, author,
                    creationDate, modifiedDate, subtype);
            if (created) {
                pageContentChanged(pageIndex);
            }
            return created;
        } catch (Throwable throwable) {
            Log.e(TAG, "addPdfHighlightComment: failed to create comment", throwable);
            return false;
        }
    }

    public boolean addPdfMarkupComment(HighlightRequest request, int color,
                                          String name, String contents, String author,
                                          String creationDate, String modifiedDate, int subtype) {
        if (!PdfComment.isTextMarkup(subtype)) return false;
        if (request == null || request.fragments.isEmpty() || name == null || name.isEmpty()) {
            return false;
        }
        if (isManagedPdfCommentsEnabled()) {
            List<ManagedPdfComment> records = new ArrayList<>();
            for (int index = 0; index < request.fragments.size(); ++index) {
                HighlightFragment fragment = request.fragments.get(index);
                records.add(new ManagedPdfComment(
                        fragment.pageIndex,
                        subtype,
                        managedCommentFragmentName(name, index),
                        fragment.pdfRects,
                        color,
                        index == 0 ? contents : "",
                        author,
                        creationDate,
                        modifiedDate,
                        "",
                        ""
                ));
            }
            return addManagedPdfComments(records);
        }
        List<PdfComment> created = new ArrayList<>();
        for (int index = 0; index < request.fragments.size(); ++index) {
            HighlightFragment fragment = request.fragments.get(index);
            String fragmentName = index == 0 ? name : name + COMMENT_FRAGMENT_MARKER + index;
            String fragmentContents = index == 0 ? contents : "";
            if (!addPdfMarkupComment(
                    fragment.pageIndex,
                    fragment.pdfRects,
                    color,
                    fragmentName,
                    fragmentContents,
                    author,
                    creationDate,
                    modifiedDate, subtype
            )) {
                for (int rollback = created.size() - 1; rollback >= 0; --rollback) {
                    removePdfComment(created.get(rollback));
                }
                return false;
            }
            PdfComment physical = findPhysicalPdfCommentByName(
                    fragmentName,
                    subtype
            );
            if (physical != null) {
                created.add(physical);
            }
        }
        return true;
    }

    public boolean addPdfReply(int pageIndex, RectF pdfRect, String parentName,
                               int parentAnnotationIndex, int parentSubtype, String name,
                               String contents, String author, String creationDate,
                               String modifiedDate) {
        if (pdfFile == null || pdfRect == null) {
            return false;
        }
        if (isManagedPdfCommentsEnabled()) {
            return addManagedPdfComment(new ManagedPdfComment(
                    pageIndex,
                    PdfComment.SUBTYPE_TEXT,
                    name,
                    Collections.singletonList(pdfRect),
                    COMMENT_MARKER_FILL_COLOR,
                    contents,
                    author,
                    creationDate,
                    modifiedDate,
                    logicalCommentName(parentName),
                    ""
            ));
        }
        try {
            boolean created = pdfFile.createPdfReply(
                    pageIndex, pdfRect, parentAnnotationIndex, parentSubtype, parentName,
                    name, contents, author,
                    creationDate, modifiedDate);
            if (created) {
                overlayContentChanged(pageIndex);
            }
            return created;
        } catch (Throwable throwable) {
            Log.e(TAG, "addPdfReply: failed to create reply", throwable);
            return false;
        }
    }

    public boolean setPdfCommentContents(PdfComment comment, String contents, String modifiedDate) {
        if (pdfFile == null || comment == null) {
            return false;
        }
        if (isManagedPdfCommentsEnabled()) {
            return updateManagedPdfCommentContents(comment, contents, modifiedDate);
        }
        String logicalName = logicalCommentName(comment.name);
        PdfComment root = findPhysicalPdfCommentByName(logicalName, comment.subtype);
        PdfComment target = root == null ? comment : root;
        try {
            boolean updated = pdfFile.setPdfCommentContents(
                    target.pageIndex,
                    target.annotationIndex,
                    target.subtype,
                    target.name,
                    contents,
                    modifiedDate
            );
            if (updated) {
                overlayContentChanged(target.pageIndex);
            }
            return updated;
        } catch (Throwable throwable) {
            Log.e(TAG, "setPdfCommentContents: failed to update comment", throwable);
            return false;
        }
    }

    public boolean setPdfCommentColor(PdfComment comment, int color) {
        if (pdfFile == null || comment == null || comment.isReply()) {
            return false;
        }
        if (isManagedPdfCommentsEnabled()) {
            return updateManagedPdfCommentColor(comment, color);
        }
        String logicalName = logicalCommentName(comment.name);
        List<PdfComment> physical = getAllPdfCommentsIncludingFragments();
        boolean updated = true;
        boolean matched = false;
        Set<Integer> changedPages = new LinkedHashSet<>();
        try {
            for (PdfComment candidate : physical) {
                if (candidate.subtype != comment.subtype
                        || !logicalName.equals(logicalCommentName(candidate.name))) {
                    continue;
                }
                matched = true;
                boolean fragmentUpdated = pdfFile.setPdfCommentColor(
                        candidate.pageIndex, candidate.annotationIndex, candidate.subtype,
                        candidate.name, color);
                updated &= fragmentUpdated;
                if (fragmentUpdated) {
                    changedPages.add(candidate.pageIndex);
                }
            }
            if (!matched) {
                updated = pdfFile.setPdfCommentColor(comment.pageIndex, comment.annotationIndex,
                        comment.subtype, comment.name, color);
                if (updated) {
                    changedPages.add(comment.pageIndex);
                }
            }
            for (Integer page : changedPages) {
                overlayContentChanged(page);
            }
            if (updated && selectedPdfComment != null
                    && logicalCommentName(selectedPdfComment.name).equals(logicalName)) {
                selectedPdfComment = new PdfComment(
                        selectedPdfComment.pageIndex,
                        selectedPdfComment.annotationIndex,
                        selectedPdfComment.subtype,
                        selectedPdfComment.name,
                        selectedPdfComment.viewBounds,
                        selectedPdfComment.pdfBounds,
                        color,
                        selectedPdfComment.contents,
                        selectedPdfComment.author,
                        selectedPdfComment.creationDate,
                        selectedPdfComment.modifiedDate,
                        selectedPdfComment.inReplyToName,
                        selectedPdfComment.anchorStatus);
                invalidate();
            }
            return updated;
        } catch (Throwable throwable) {
            Log.e(TAG, "setPdfCommentColor: failed to update comment", throwable);
            return false;
        }
    }

    public boolean setPdfCommentBounds(PdfComment comment, RectF pdfBounds) {
        if (pdfFile == null || comment == null || pdfBounds == null || comment.isReply()
                || comment.subtype != PdfDocument.PdfComment.SUBTYPE_TEXT) {
            return false;
        }
        if (isManagedPdfCommentsEnabled()) {
            return updateManagedPdfCommentBounds(comment, pdfBounds);
        }
        String logicalName = logicalCommentName(comment.name);
        PdfComment root = findPhysicalPdfCommentByName(logicalName, comment.subtype);
        PdfComment target = root == null ? comment : root;
        try {
            boolean updated = pdfFile.setPdfCommentBounds(
                    target.pageIndex, target.annotationIndex, target.subtype,
                    target.name, pdfBounds);
            if (updated) {
                overlayContentChanged(target.pageIndex);
                if (selectedPdfComment != null
                        && logicalCommentName(selectedPdfComment.name).equals(logicalName)) {
                    selectedPdfComment = new PdfComment(
                            target.pageIndex,
                            target.annotationIndex,
                            target.subtype,
                            logicalName,
                            pdfCommentViewBounds(target.pageIndex, pdfBounds),
                            pdfBounds,
                            selectedPdfComment.color,
                            selectedPdfComment.contents,
                            selectedPdfComment.author,
                            selectedPdfComment.creationDate,
                            selectedPdfComment.modifiedDate,
                            selectedPdfComment.inReplyToName,
                            selectedPdfComment.anchorStatus);
                    invalidate();
                }
            }
            return updated;
        } catch (Throwable throwable) {
            Log.e(TAG, "setPdfCommentBounds: failed to move comment", throwable);
            return false;
        }
    }

    public boolean removePdfComment(PdfComment comment) {
        if (pdfFile == null || comment == null) {
            return false;
        }
        if (isManagedPdfCommentsEnabled()) {
            return removeManagedPdfComment(comment);
        }
        String logicalName = logicalCommentName(comment.name);
        List<PdfComment> physical = getAllPdfCommentsIncludingFragments();
        boolean hasRoot = false;
        for (PdfComment candidate : physical) {
            if (logicalName.equals(candidate.name) && candidate.subtype == comment.subtype) {
                hasRoot = true;
                break;
            }
        }
        List<PdfComment> targets = new ArrayList<>();
        if (hasRoot) {
            for (PdfComment candidate : physical) {
                if (candidate.subtype == comment.subtype
                        && logicalName.equals(logicalCommentName(candidate.name))) {
                    targets.add(candidate);
                }
            }
        }
        if (targets.isEmpty()) {
            targets.add(comment);
        }
        targets.sort((left, right) -> {
            int pageOrder = Integer.compare(right.pageIndex, left.pageIndex);
            return pageOrder != 0
                    ? pageOrder
                    : Integer.compare(right.annotationIndex, left.annotationIndex);
        });
        try {
            boolean removed = true;
            Set<Integer> changedPages = new LinkedHashSet<>();
            for (PdfComment target : targets) {
                boolean fragmentRemoved = pdfFile.removePdfComment(
                        target.pageIndex,
                        target.annotationIndex,
                        target.subtype,
                        target.name
                );
                removed &= fragmentRemoved;
                if (fragmentRemoved) {
                    changedPages.add(target.pageIndex);
                }
            }
            for (Integer page : changedPages) {
                pageContentChanged(page);
            }
            return removed;
        } catch (Throwable throwable) {
            Log.e(TAG, "removePdfComment: failed to remove comment", throwable);
            return false;
        }
    }

    private boolean addManagedPdfComment(ManagedPdfComment comment) {
        return addManagedPdfComments(Collections.singletonList(comment));
    }

    private boolean addManagedPdfComments(List<ManagedPdfComment> comments) {
        synchronized (managedPdfCommentLock) {
            if (managedPdfCommentRecords == null || comments == null || comments.isEmpty()) {
                return false;
            }
            Set<String> names = new LinkedHashSet<>(managedPdfCommentRecordsByName.keySet());
            for (ManagedPdfComment comment : comments) {
                if (comment == null || comment.name.isEmpty() || comment.pdfRects.isEmpty()
                        || comment.pageIndex < 0
                        || (pdfFile != null && comment.pageIndex >= pdfFile.getPagesCount())
                        || !names.add(comment.name)) {
                    return false;
                }
            }
            for (ManagedPdfComment comment : comments) {
                managedPdfCommentRecords.add(copyManagedPdfComment(comment));
            }
            rebuildManagedPdfCommentIndexesLocked();
        }
        invalidate();
        return true;
    }

    private boolean updateManagedPdfCommentContents(
            PdfComment comment,
            String contents,
            String modifiedDate) {
        String targetName = comment.isReply() ? comment.name : logicalCommentName(comment.name);
        boolean updated = false;
        synchronized (managedPdfCommentLock) {
            if (managedPdfCommentRecords == null) {
                return false;
            }
            for (int index = 0; index < managedPdfCommentRecords.size(); ++index) {
                ManagedPdfComment record = managedPdfCommentRecords.get(index);
                if (!record.name.equals(targetName)) {
                    continue;
                }
                managedPdfCommentRecords.set(index, new ManagedPdfComment(
                        record.pageIndex,
                        record.subtype,
                        record.name,
                        record.pdfRects,
                        record.color,
                        contents,
                        record.author,
                        record.creationDate,
                        modifiedDate,
                        record.inReplyToName,
                        record.anchorStatus
                ));
                updated = true;
                break;
            }
            if (updated) {
                rebuildManagedPdfCommentIndexesLocked();
            }
        }
        if (updated) {
            selectedPdfComment = findPdfComment(
                    comment.pageIndex, targetName, comment.annotationIndex, comment.subtype);
            invalidate();
        }
        return updated;
    }

    private boolean updateManagedPdfCommentColor(PdfComment comment, int color) {
        String logicalName = logicalCommentName(comment.name);
        boolean updated = false;
        synchronized (managedPdfCommentLock) {
            if (managedPdfCommentRecords == null) {
                return false;
            }
            for (int index = 0; index < managedPdfCommentRecords.size(); ++index) {
                ManagedPdfComment record = managedPdfCommentRecords.get(index);
                if (record.subtype != comment.subtype
                        || !logicalName.equals(logicalCommentName(record.name))) {
                    continue;
                }
                managedPdfCommentRecords.set(index, new ManagedPdfComment(
                        record.pageIndex,
                        record.subtype,
                        record.name,
                        record.pdfRects,
                        color,
                        record.contents,
                        record.author,
                        record.creationDate,
                        record.modifiedDate,
                        record.inReplyToName,
                        record.anchorStatus
                ));
                updated = true;
            }
            if (updated) {
                rebuildManagedPdfCommentIndexesLocked();
            }
        }
        if (updated) {
            selectedPdfComment = findPdfComment(
                    comment.pageIndex, logicalName, comment.annotationIndex, comment.subtype);
            invalidate();
        }
        return updated;
    }

    private boolean updateManagedPdfCommentBounds(PdfComment comment, RectF bounds) {
        String logicalName = logicalCommentName(comment.name);
        boolean updated = false;
        synchronized (managedPdfCommentLock) {
            if (managedPdfCommentRecords == null) {
                return false;
            }
            for (int index = 0; index < managedPdfCommentRecords.size(); ++index) {
                ManagedPdfComment record = managedPdfCommentRecords.get(index);
                if (!record.name.equals(logicalName) || record.subtype != PdfComment.SUBTYPE_TEXT) {
                    continue;
                }
                managedPdfCommentRecords.set(index, new ManagedPdfComment(
                        record.pageIndex,
                        record.subtype,
                        record.name,
                        Collections.singletonList(bounds),
                        record.color,
                        record.contents,
                        record.author,
                        record.creationDate,
                        record.modifiedDate,
                        record.inReplyToName,
                        record.anchorStatus
                ));
                updated = true;
                break;
            }
            if (updated) {
                rebuildManagedPdfCommentIndexesLocked();
            }
        }
        if (updated) {
            selectedPdfComment = findPdfComment(
                    comment.pageIndex, logicalName, comment.annotationIndex, comment.subtype);
            invalidate();
        }
        return updated;
    }

    private boolean removeManagedPdfComment(PdfComment comment) {
        String logicalName = logicalCommentName(comment.name);
        boolean removed = false;
        synchronized (managedPdfCommentLock) {
            if (managedPdfCommentRecords == null) {
                return false;
            }
            for (int index = managedPdfCommentRecords.size() - 1; index >= 0; --index) {
                ManagedPdfComment record = managedPdfCommentRecords.get(index);
                if (record.subtype == comment.subtype
                        && logicalName.equals(logicalCommentName(record.name))) {
                    managedPdfCommentRecords.remove(index);
                    removed = true;
                }
            }
            if (removed) {
                rebuildManagedPdfCommentIndexesLocked();
            }
        }
        if (removed) {
            if (selectedPdfComment != null
                    && logicalName.equals(logicalCommentName(selectedPdfComment.name))) {
                selectedPdfComment = null;
            }
            invalidate();
        }
        return removed;
    }

    public FormField findFormFieldAt(float viewX, float viewY) {
        if (pdfFile == null) {
            return null;
        }
        float docX = -getCurrentXOffset() + viewX;
        float docY = -getCurrentYOffset() + viewY;
        int page = pdfFile.getPageAtOffset(isSwipeVertical() ? docY : docX,
                isSwipeVertical() ? docX : docY, getZoom());
        if (page < 0 || page >= pdfFile.getPagesCount()) {
            return null;
        }

        PointF point = pdfFile.documentToPdf(page, getZoom(), docX, docY);
        try {
            float tolerance = formFieldTouchTolerance(page, docX, docY, point);
            PdfDocument.FormField field = pdfFile.getFormFieldAtPoint(page, point.x, point.y, tolerance);
            return field == null ? null : new FormField(page, field);
        } catch (Throwable throwable) {
            Log.e(TAG, "findFormFieldAt: failed to hit-test form field", throwable);
            return null;
        }
    }

    private float formFieldTouchTolerance(int page, float docX, float docY, PointF pdfPoint) {
        float tolerancePx = FORM_FIELD_TOUCH_TOLERANCE_DP * getResources().getDisplayMetrics().density;
        PointF shifted = pdfFile.documentToPdf(page, getZoom(), docX + tolerancePx, docY);
        return (float) Math.hypot(shifted.x - pdfPoint.x, shifted.y - pdfPoint.y);
    }

    public boolean setFormFieldText(int pageIndex, int annotationIndex, String text) {
        if (pdfFile == null) {
            return false;
        }
        try {
            boolean updated = pdfFile.setFormFieldText(pageIndex, annotationIndex, text);
            if (updated) {
                pageContentChanged(pageIndex);
            }
            return updated;
        } catch (Throwable throwable) {
            Log.e(TAG, "setFormFieldText: failed to update form field", throwable);
            return false;
        }
    }

    public boolean setFormFieldChecked(int pageIndex, int annotationIndex, boolean checked) {
        if (pdfFile == null) {
            return false;
        }
        try {
            boolean updated = pdfFile.setFormFieldChecked(pageIndex, annotationIndex, checked);
            if (updated) {
                pageContentChanged(pageIndex);
            }
            return updated;
        } catch (Throwable throwable) {
            Log.e(TAG, "setFormFieldChecked: failed to update form field", throwable);
            return false;
        }
    }

    public boolean saveAsCopy(File outputFile) throws IOException {
        if (pdfFile == null || outputFile == null) {
            return false;
        }
        List<ManagedPdfComment> records = managedPdfCommentRecordsSnapshotOrNull();
        if (records == null) {
            return pdfFile.saveAsCopy(outputFile);
        }
        File temporary = File.createTempFile("thoughts-export-", ".pdf", getContext().getCacheDir());
        PdfFile exportPdf = null;
        try {
            exportPdf = pdfFile.detachedExportCopy(temporary, loadedDocumentPassword);
            return exportPdf != null && materializeManagedPdfCommentsForSave(exportPdf, records)
                    && exportPdf.saveAsCopy(outputFile);
        } finally {
            if (exportPdf != null) exportPdf.dispose();
            if (!temporary.delete()) temporary.deleteOnExit();
        }
    }

    /**
     * Explicit PDF export is the only point at which a managed sidecar is copied into Pdfium.
     * Normal reading and synchronization never call this path.
     */
    private boolean materializeManagedPdfCommentsForSave(PdfFile exportPdf, List<ManagedPdfComment> records) {
        List<PdfComment> embedded = new ArrayList<>();
        for (int page = 0; page < exportPdf.getPagesCount(); ++page) {
            List<PdfDocument.PdfComment> comments;
            try {
                comments = exportPdf.getPdfComments(page);
            } catch (Throwable throwable) {
                Log.e(TAG, "materializeManagedPdfCommentsForSave: failed to read page " + page,
                        throwable);
                return false;
            }
            for (PdfDocument.PdfComment comment : comments) {
                embedded.add(toPdfComment(page, comment, null));
            }
        }
        embedded.sort((left, right) -> {
            int pageOrder = Integer.compare(right.pageIndex, left.pageIndex);
            return pageOrder != 0
                    ? pageOrder
                    : Integer.compare(right.annotationIndex, left.annotationIndex);
        });
        for (PdfComment comment : embedded) {
            if (!exportPdf.removePdfComment(
                    comment.pageIndex,
                    comment.annotationIndex,
                    comment.subtype,
                    comment.name)) {
                Log.e(TAG, "materializeManagedPdfCommentsForSave: could not remove " + comment.name);
                return false;
            }
        }

        List<ManagedPdfComment> replies = new ArrayList<>();
        for (ManagedPdfComment record : records) {
            if (!record.inReplyToName.isEmpty()) {
                replies.add(record);
                continue;
            }
            boolean created;
            if (PdfComment.isTextMarkup(record.subtype)) {
                created = exportPdf.createPdfMarkupComment(
                        record.pageIndex,
                        record.pdfRects,
                        record.color,
                        record.name,
                        record.contents,
                        record.author,
                        record.creationDate,
                        record.modifiedDate, record.subtype
                );
            } else {
                created = exportPdf.createPdfComment(
                        record.pageIndex,
                        record.pdfRects.get(0),
                        record.color,
                        record.name,
                        record.contents,
                        record.author,
                        record.creationDate,
                        record.modifiedDate
                );
            }
            if (!created) {
                Log.e(TAG, "materializeManagedPdfCommentsForSave: could not create " + record.name);
                return false;
            }
        }
        for (ManagedPdfComment reply : replies) {
            PdfDocument.PdfComment parent = null;
            for (PdfDocument.PdfComment candidate : exportPdf.getPdfComments(reply.pageIndex)) {
                if (logicalCommentName(reply.inReplyToName).equals(
                        logicalCommentName(candidate.getName()))) {
                    parent = candidate;
                    break;
                }
            }
            if (parent == null || !exportPdf.createPdfReply(
                    reply.pageIndex,
                    reply.pdfRects.get(0),
                    parent.getAnnotationIndex(),
                    parent.getSubtype(),
                    parent.getName(),
                    reply.name,
                    reply.contents,
                    reply.author,
                    reply.creationDate,
                    reply.modifiedDate)) {
                Log.e(TAG, "materializeManagedPdfCommentsForSave: could not create reply "
                        + reply.name);
                return false;
            }
        }
        return true;
    }

    public boolean saveDecryptedCopy(File outputFile) throws IOException {
        if (pdfFile == null || outputFile == null) {
            return false;
        }
        return pdfFile.saveDecryptedCopy(outputFile);
    }

    /**
     * The link under view point (x, y), mapped exactly as a tap there would find it, or null.
     * Used by long-press menus that act on links.
     */
    public com.github.barteksc.pdfviewer.model.LinkTapEvent findLinkAt(float viewX, float viewY) {
        return dragPinchManager == null ? null : dragPinchManager.linkAt(viewX, viewY);
    }

    public void clearTextSelection() {
        if (textSelectionManager != null) {
            textSelectionManager.clear();
        }
    }

    public boolean isZooming() {
        return zoom != minZoom;
    }

    public void setDefaultPage(int defaultPage) {   // changed by User
        this.defaultPage = defaultPage;
    }

    private void setDefaultViewState(ViewState defaultViewState) {
        this.defaultViewState = defaultViewState;
    }

    public void resetZoom() {
        zoomTo(NORMAL_SCALE);
    }

    public void resetZoomWithAnimation() {
        zoomWithAnimation(NORMAL_SCALE);   // mudlej: I think double tap should always reset to 1f rather than the min zoom
    }

    public void resetZoomToFitPageWithAnimation() {
        if (pdfFile == null || swipeVertical || getHeight() <= 0) {
            resetZoomWithAnimation();
            return;
        }
        float pageHeight = pdfFile.getPageSize(currentPage).getHeight();
        if (pageHeight <= 0) {
            resetZoomWithAnimation();
            return;
        }
        float fitHeightZoom = getHeight() / pageHeight;
        zoomWithAnimation(MathUtils.limit(fitHeightZoom, minZoom, NORMAL_SCALE));
    }

    public void zoomWithAnimation(float centerX, float centerY, float scaleTo) {
        animationManager.startZoomAnimation(centerX, centerY, zoom, scaleTo);
    }

    /** Focus an internal PDF link at its encoded target and show a transient target pulse. */
    public boolean focusOnNamedDestination(String name) {
        if (pdfFile == null) return false;
        PdfDocument.Link link = pdfFile.getNamedDestination(name);
        if (link == null) return false;
        if (!focusOnLinkDestination(link)) jumpTo(link.getDestPageIdx());
        return true;
    }

    public boolean focusOnLinkDestination(PdfDocument.Link link) {
        if (pdfFile == null || link == null || link.getDestPageIdx() == null) {
            return false;
        }
        int pageIndex = link.getDestPageIdx();
        PointF destination = link.getDestPoint();
        SizeF pageSize = pdfFile.getPagePointSize(pageIndex);
        if (destination == null || pageSize == null) {
            return false;
        }
        LinkDestinationGeometry.Anchor anchor = LinkDestinationGeometry.resolve(
                destination.x,
                destination.y,
                pageSize.getWidth(),
                pageSize.getHeight()
        );
        if (anchor == null) {
            return false;
        }

        RectF focusRect = new RectF(
                anchor.x - 1f,
                anchor.y + 1f,
                anchor.x + 1f,
                anchor.y - 1f
        );
        if (focusOnPdfRect(pageIndex, focusRect, getZoom()) == null) {
            return false;
        }
        showPage(pageIndex);
        linkDestinationPage = pageIndex;
        linkDestinationPdfPoint = new PointF(anchor.x, anchor.y);
        linkDestinationMarkerStartedAt = SystemClock.uptimeMillis();
        invalidate();
        return true;
    }

    /** Centre an answer region in the space above the review card, preserving zoom and masks. */
    public boolean focusOnReviewRegion(int pageIndex, RectF normalizedRect, float visibleBottom) {
        if (normalizedRect == null || getWidth() == 0 || getHeight() == 0) return false;
        RectF rect = normalizedTopLeftRectToDocument(pageIndex, normalizedRect);
        if (rect == null) return false;
        stopFling();
        float bottom = Math.min(getContentViewportBottom(), visibleBottom);
        float x = ContentViewportMath.centerOffset(rect.centerX(),
                getContentViewportLeft(), getContentViewportRight());
        float y = ContentViewportMath.centerOffset(rect.centerY(), getContentViewportTop(), bottom);
        moveTo(clampTargetXOffset(x, zoom), clampTargetYOffset(y, zoom));
        loadPageByOffset();
        loadPages();
        return true;
    }

    /** Whether part of a page region (top-origin fractions) is in the view at the current zoom and offset. */
    public boolean isReviewRegionOnScreen(int pageIndex, RectF normalizedRect) {
        RectF rect = normalizedRect == null ? null : normalizedTopLeftRectToDocument(pageIndex, normalizedRect);
        if (rect == null) return false;
        rect.offset(getCurrentXOffset(), getCurrentYOffset());
        return RectF.intersects(rect, new RectF(0f, 0f, getWidth(), getHeight()));
    }

    public RectF focusOnSearchResult(int pageIndex, RectF pdfRect, float targetZoom) {
        RectF screenRect = focusOnPdfRect(pageIndex, pdfRect, targetZoom);
        if (screenRect != null) showPage(pageIndex);
        return screenRect;
    }

    public RectF focusOnPdfRect(int pageIndex, RectF pdfRect, float targetZoom) {
        if (pdfFile == null || pdfRect == null || getWidth() == 0 || getHeight() == 0) {
            return null;
        }
        if (targetZoom < zoom) {
            targetZoom = zoom;
        }
        RectF docRect = pdfFile.pdfRectToDocument(pageIndex, targetZoom,
                pdfRect.left, pdfRect.bottom, pdfRect.right, pdfRect.top, false);
        if (docRect == null) {
            return null;
        }
        float targetXOffset = clampTargetXOffset(ContentViewportMath.centerOffset(
                docRect.centerX(), getContentViewportLeft(), getContentViewportRight()), targetZoom);
        float targetYOffset = clampTargetYOffset(ContentViewportMath.centerOffset(
                docRect.centerY(), getContentViewportTop(), getContentViewportBottom()), targetZoom);
        float scale = targetZoom / zoom;
        if (scale > 1.001f) {
            float pivotX = (targetXOffset - currentXOffset * scale) / (1f - scale);
            float pivotY = (targetYOffset - currentYOffset * scale) / (1f - scale);
            animationManager.startZoomAnimation(pivotX, pivotY, zoom, targetZoom,
                    new PointF(targetXOffset, targetYOffset));
        } else {
            moveTo(targetXOffset, targetYOffset);
            loadPages();
        }
        return new RectF(
                docRect.left + targetXOffset,
                docRect.top + targetYOffset,
                docRect.right + targetXOffset,
                docRect.bottom + targetYOffset
        );
    }

    private float clampTargetXOffset(float offsetX, float targetZoom) {
        float viewportLeft = getContentViewportLeft();
        float viewportRight = getContentViewportRight();
        if (swipeVertical) {
            float scaledPageWidth = pdfFile.getMaxPageWidth() * targetZoom;
            return ContentViewportMath.clampStripOffset(
                    offsetX, scaledPageWidth, viewportLeft, viewportRight);
        }
        float contentWidth = pdfFile.getDocLen(targetZoom);
        return ContentViewportMath.clampStripOffset(
                offsetX, contentWidth, viewportLeft, viewportRight);
    }

    private float clampTargetYOffset(float offsetY, float targetZoom) {
        float viewportTop = getContentViewportTop();
        float viewportBottom = getContentViewportBottom();
        if (swipeVertical) {
            float contentHeight = pdfFile.getDocLen(targetZoom);
            return ContentViewportMath.clampStripOffset(
                    offsetY, contentHeight, viewportTop, viewportBottom);
        }
        float scaledPageHeight = pdfFile.getMaxPageHeight() * targetZoom;
        return ContentViewportMath.clampStripOffset(
                offsetY, scaledPageHeight, viewportTop, viewportBottom);
    }

    public void zoomWithAnimation(float scale) {
        animationManager.startZoomAnimation((float) getWidth() / 2, (float) getHeight() / 2, zoom, scale);
    }

    private void setScrollHandle(ScrollHandle scrollHandle) {
        this.scrollHandle = scrollHandle;
    }

    /**
     * Get page number at given offset
     *
     * @param positionOffset scroll offset between 0 and 1
     * @return page number at given offset, starting from 0
     */
    public int getPageAtPositionOffset(float positionOffset) {
        if (pdfFile == null) {
            return 0;
        }
        return pdfFile.getPageAtOffset(pdfFile.getDocLen(zoom) * positionOffset, zoom);
    }

    public float getMinZoom() {
        return minZoom;
    }

    public void setMinZoom(float minZoom) {
        this.minZoom = minZoom;
    }

    public float getMidZoom() {
        return midZoom;
    }

    public void setMidZoom(float midZoom) {
        this.midZoom = midZoom;
    }

    public float getMaxZoom() {
        return maxZoom;
    }

    public void setMaxZoom(float maxZoom) {
        this.maxZoom = maxZoom;
    }

    public void useBestQuality(boolean bestQuality) {
        this.bestQuality = bestQuality;
    }

    public boolean isBestQuality() {
        return bestQuality;
    }

    public boolean isSwipeVertical() {
        return swipeVertical;
    }

    public boolean isSwipeEnabled() {
        return enableSwipe;
    }

    public boolean isHorizontalSwipeDisabled() {
        return horizontalSwipeDisabled;
    }

    public boolean isZoomDisabled() {
        return zoomDisabled;
    }

    private void setSwipeVertical(boolean swipeVertical) {
        this.swipeVertical = swipeVertical;
    }

    private void setHorizontalReadingDirectionRtl(boolean horizontalReadingDirectionRtl) {
        this.horizontalReadingDirectionRtl = horizontalReadingDirectionRtl;
    }

    public boolean isHorizontalReadingDirectionRtl() {
        return !swipeVertical && horizontalReadingDirectionRtl;
    }

    public void enableAnnotationRendering(boolean annotationRendering) {
        this.annotationRendering = annotationRendering;
    }

    public boolean isAnnotationRendering() {
        return annotationRendering;
    }

    public void enableRenderDuringScale(boolean renderDuringScale) {
        this.renderDuringScale = renderDuringScale;
    }

    public boolean isAntialiasing() {
        return enableAntialiasing;
    }

    public void enableAntialiasing(boolean enableAntialiasing) {
        this.enableAntialiasing = enableAntialiasing;
    }

    public int getSpacingPx() {
        return spacingPx;
    }

    public boolean isAutoSpacingEnabled() {
        return autoSpacing;
    }

    public void setPageFling(boolean pageFling) {
        this.pageFling = pageFling;
    }

    public boolean isPageFlingEnabled() {
        return pageFling;
    }

    private void setSpacing(int spacingDp) {
        this.spacingPx = Util.getDP(getContext(), spacingDp);
    }

    private void setAutoSpacing(boolean autoSpacing) {
        this.autoSpacing = autoSpacing;
    }

    private void setPagesPerRow(int pagesPerRow) {
        this.pagesPerRow = pagesPerRow;
    }

    public int getPagesPerRow() {
        return pagesPerRow;
    }

    private void setFirstPageAlone(boolean firstPageAlone) {
        this.firstPageAlone = firstPageAlone;
    }

    public boolean isFirstPageAlone() {
        return firstPageAlone;
    }

    private void setAutoReleasingWhenDetachedFromWindow(boolean autoReleasing) {
        this.autoReleasingWhenDetachedFromWindow = autoReleasing;
    }

    private void setPageFitPolicy(FitPolicy pageFitPolicy) {
        this.pageFitPolicy = pageFitPolicy;
    }

    public FitPolicy getPageFitPolicy() {
        return pageFitPolicy;
    }

    private void setFitEachPage(boolean fitEachPage) {
        this.fitEachPage = fitEachPage;
    }

    public boolean isFitEachPage() {
        return fitEachPage;
    }

    private void setCropMargins(boolean cropMargins) {
        this.cropMargins = cropMargins;
    }

    public boolean isCropMarginsEnabled() {
        return cropMargins;
    }

    private void setCachedCropMargins(CropMargins cachedCropMargins) {
        this.cachedCropMargins = cachedCropMargins;
    }

    CropMargins getCachedCropMargins() {
        return cachedCropMargins;
    }

    public boolean isPageSnap() {
        return pageSnap;
    }

    public void setPageSnap(boolean pageSnap) {
        this.pageSnap = pageSnap;
    }

    public boolean isFreeScrollMode() {
        return freeScrollMode;
    }

    private void setFreeScrollMode(boolean freeScrollMode) {
        this.freeScrollMode = freeScrollMode;
    }

    public boolean doRenderDuringScale() {
        return renderDuringScale;
    }

    /**
     * Returns null if document is not loaded
     */
    public PdfDocument.Meta getDocumentMeta() {
        if (pdfFile == null) {
            Log.e(TAG, "getDocumentMeta: pdfFile is null!");
            return null;
        }
        return pdfFile.getMetaData();
    }

    /**
     * Will be empty until document is loaded
     */
    public List<PdfDocument.Bookmark> getTableOfContents() {
        if (pdfFile == null) {
            return Collections.emptyList();
        }
        return pdfFile.getBookmarks();
    }

    public float[] getSourcePoint(int pageIndex, RectF bounds) {
        return pdfFile == null ? null : pdfFile.sourcePoint(pageIndex, bounds.centerX(), bounds.centerY());
    }

    /** Text of the PDF line containing this geometric selection, not a global word search. */
    public String getSourceContext(int pageIndex, RectF bounds) {
        if (pdfFile == null) return "";
        pdfFile.ensureTextPage(pageIndex);
        int at = pdfFile.charIndexAtPagePoint(pageIndex, bounds.centerX(), bounds.centerY(), 8f);
        if (at < 0) return "";
        int start = at, end = at, count = pdfFile.pageCharCount(pageIndex);
        while (start > 0 && at - start < 240) {
            int c = pdfFile.charUnicode(pageIndex, start - 1);
            if (c == '\n' || c == '\r') break;
            --start;
        }
        while (end < count && end - at < 240) {
            int c = pdfFile.charUnicode(pageIndex, end);
            if (c == '\n' || c == '\r') break;
            ++end;
        }
        return pdfFile.textRange(pageIndex, start, end - start).trim();
    }

    public SizeF getPagePointSize(int pageIndex) {
        if (pdfFile == null) {
            return null;
        }
        return pdfFile.getPagePointSize(pageIndex);
    }

    public List<PdfDocument.FontInfo> getAllFonts(int maxPages) {
        if (pdfFile == null) {
            return Collections.emptyList();
        }
        return pdfFile.getAllFonts(maxPages);
    }

    /**
     * Will be empty until document is loaded
     */
    public List<PdfDocument.Link> getLinks(int page) {
        if (pdfFile == null) {
            return Collections.emptyList();
        }
        return pdfFile.getPageLinks(page);
    }


    /**
     * Get the text of page
     */     // added by Mudlej
    public String getPageText(int pagNumber) {
        if (pdfFile == null) {
            return "";
        }
        return pdfFile.getPageText(pagNumber);
    }

    public String getPageRawText(int pageNumber) {
        if (pdfFile == null) {
            return "";
        }
        return pdfFile.getPageRawText(pageNumber - 1);
    }

    public List<RectF> getSearchTextBounds(int pageIndex, int start, int count) {
        if (pdfFile == null || count <= 0) return Collections.emptyList();
        pdfFile.ensureTextPage(pageIndex);
        float[] values = pdfFile.textRects(pageIndex, start, count);
        List<RectF> result = new ArrayList<>();
        for (int i = 0; i + 3 < values.length; i += 4) {
            float left = Math.min(values[i], values[i + 2]);
            float right = Math.max(values[i], values[i + 2]);
            float bottom = Math.min(values[i + 1], values[i + 3]);
            float top = Math.max(values[i + 1], values[i + 3]);
            if (right <= left || top <= bottom) continue;
            result.add(new RectF(left, top, right, bottom));
        }
        List<RectF> lines = TextLineRects.merge(result);
        // Search bounds use PDF bottom-up coordinates (top > bottom).
        for (RectF line : lines) {
            float bottom = line.top;
            line.top = line.bottom;
            line.bottom = bottom;
        }
        return lines;
    }

    public void setSearchHighlights(int pageIndex, List<RectF> matches, List<RectF> activeMatch) {
        searchHighlightPage = pageIndex;
        searchHighlightRects = new ArrayList<>(matches);
        activeSearchHighlightRects = new ArrayList<>(activeMatch);
        invalidate();
    }

    public void clearSearchHighlights() {
        searchHighlightPage = -1;
        searchHighlightRects = Collections.emptyList();
        activeSearchHighlightRects = Collections.emptyList();
        invalidate();
    }

    private void drawSearchHighlights(Canvas canvas, Set<Integer> visiblePages) {
        if (pdfFile == null || !visiblePages.contains(searchHighlightPage)) return;
        drawSearchRects(canvas, searchHighlightRects, 0x55FFDC35, false);
        drawSearchRects(canvas, activeSearchHighlightRects, 0x88FF9800, false);
        drawSearchRects(canvas, activeSearchHighlightRects, 0xFFE07800, true);
    }

    private void drawSearchRects(Canvas canvas, List<RectF> rects, int color, boolean outline) {
        searchHighlightPaint.setColor(color);
        searchHighlightPaint.setStyle(outline ? Paint.Style.STROKE : Paint.Style.FILL);
        searchHighlightPaint.setStrokeWidth(getResources().getDisplayMetrics().density * 1.5f);
        for (RectF rect : rects) {
            RectF bounds = pdfFile.pdfRectToDocument(searchHighlightPage, zoom,
                    rect.left, rect.bottom, rect.right, rect.top, false);
            if (bounds != null) canvas.drawRect(bounds, searchHighlightPaint);
        }
    }

    public Rect[] createHighlightText(int pageNumber, int start, int end, Boolean padding) {
        Rect[] emptyArray = new Rect[0];
        if (pdfFile == null) {
            return emptyArray;
        }
        try {
            int pageIndex = pageNumber - 1;
            Rect[] result = pdfFile.createHighlightText(pageIndex, start, end, padding);
            if (result != null && result.length > 0) {
                synchronized (searchMarkerPages) {
                    searchMarkerPages.add(pageIndex);
                }
                pageContentChanged(pageIndex);
            }
            return result == null ? emptyArray : result;
        } catch (Throwable throwable) {
            Log.e(TAG, "createHighlightText: An error occurred while highlight search result", throwable);
            return emptyArray;
        }
    }

    public void clearSearchResultsHighlight(int pageNumber) {
        if (pdfFile == null) {
            return;
        }
        List<Integer> pages;
        synchronized (searchMarkerPages) {
            if (pageNumber - 1 >= 0) {
                searchMarkerPages.add(pageNumber - 1);
            }
            if (searchMarkerPages.isEmpty()) {
                return;
            }
            pages = new ArrayList<>(searchMarkerPages);
        }
        for (Integer page : pages) {
            pdfFile.clearSearchResultsAnnot(page);
            invalidatePageContent(page);
        }
        synchronized (searchMarkerPages) {
            searchMarkerPages.removeAll(pages);
        }
        loadPages();
    }

    /**
     * Get the text of page
     */     // added by Mudlej
    public Map<Integer, String> getPagesText(int start, int end) {
        if (pdfFile == null) {
            return new HashMap<>();
        }

        return pdfFile.getPagesText(start, end);
    }

    /**
     * Use an asset file as the pdf source
     */
    public Configurator fromAsset(String assetName) {
        return new Configurator(new AssetSource(assetName));
    }

    /**
     * Use a file as the pdf source
     */
    public Configurator fromFile(File file) {
        return new Configurator(new FileSource(file));
    }

    /**
     * Use URI as the pdf source, for use with content providers
     */
    public Configurator fromUri(Uri uri) {
        return new Configurator(new UriSource(uri));
    }

    /**
     * Use bytearray as the pdf source, documents is not saved
     */
    public Configurator fromBytes(byte[] bytes) {
        return new Configurator(new ByteArraySource(bytes));
    }

    /**
     * Use stream as the pdf source. Stream will be written to bytearray, because native code does not support Java Streams
     */
    public Configurator fromStream(InputStream stream) {
        return new Configurator(new InputStreamSource(stream));
    }

    /**
     * Use custom source as pdf source
     */
    public Configurator fromSource(DocumentSource docSource) {
        return new Configurator(docSource);
    }

    private enum State {DEFAULT, LOADED, SHOWN, ERROR}

    public class Configurator {

        private final DocumentSource documentSource;

        private int[] pageNumbers = null;

        private boolean enableSwipe = true;

        private boolean horizontalSwipeDisabled = false;

        private boolean zoomDisabled = false;

        private boolean enableDoubleTap = true;

        private OnDrawListener onDrawListener;

        private OnDrawListener onDrawAllListener;

        private OnLoadCompleteListener onLoadCompleteListener;

        private OnErrorListener onErrorListener;

        private OnPageChangeListener onPageChangeListener;

        private OnPageScrollListener onPageScrollListener;

        private OnDocumentInteractionListener onDocumentInteractionListener;

        private OnRenderListener onRenderListener;

        private OnTapListener onTapListener;

        private OnTapListener onTapUpListener;

        private OnLongPressListener onLongPressListener;

        private OnTapListener onLongPressFirstListener;

        private OnTextSelectionChangeListener onTextSelectionChangeListener;

        private OnPageErrorListener onPageErrorListener;

        private LinkHandler linkHandler = new DefaultLinkHandler(PDFView.this);

        private int defaultPage = 0;

        private ViewState defaultViewState = null;

        private boolean swipeHorizontal = false;

        private boolean horizontalReadingDirectionRtl = false;

        private boolean annotationRendering = false;

        private String password = null;

        private ScrollHandle scrollHandle = null;

        private boolean antialiasing = true;

        private int spacing = 0;

        private boolean autoSpacing = false;

        private int pagesPerRow = 1;

        private boolean firstPageAlone = false;

        private boolean autoReleasingWhenDetachedFromWindow = true;

        private FitPolicy pageFitPolicy = FitPolicy.WIDTH;

        private boolean fitEachPage = false;

        private boolean cropMargins = false;

        private CropMargins cachedCropMargins = null;

        private boolean pageFling = false;

        private boolean pageSnap = false;

        private boolean freeScrollMode = false;

        private boolean renderDuringScale = false;

        private boolean nightMode = false;

        private boolean textSelectionEnabled = false;

        private int textSelectionColor = 0xFF3F51B5;

        private boolean debugChecks = false;

        private File previewDiskDir = null;

        private String previewDocKey = null;

        private float[] pageGeometry = null;

        private Configurator(DocumentSource documentSource) {
            this.documentSource = documentSource;
        }

        public Configurator pages(int... pageNumbers) {
            this.pageNumbers = pageNumbers;
            return this;
        }

        public Configurator enableSwipe(boolean enableSwipe) {
            this.enableSwipe = enableSwipe;
            return this;
        }

        public Configurator disableHorizontalSwipe(boolean disableHorizontalSwipe) {
            this.horizontalSwipeDisabled = disableHorizontalSwipe;
            return this;
        }

        public Configurator zoomDisabled(boolean zoomDisabled) {
            this.zoomDisabled = zoomDisabled;
            return this;
        }

        public Configurator enableDoubleTap(boolean enableDoubleTap) {
            this.enableDoubleTap = enableDoubleTap;
            return this;
        }

        public Configurator enableAnnotationRendering(boolean annotationRendering) {
            this.annotationRendering = annotationRendering;
            return this;
        }

        public Configurator onDraw(OnDrawListener onDrawListener) {
            this.onDrawListener = onDrawListener;
            return this;
        }

        public Configurator onDrawAll(OnDrawListener onDrawAllListener) {
            this.onDrawAllListener = onDrawAllListener;
            return this;
        }

        public Configurator onLoad(OnLoadCompleteListener onLoadCompleteListener) {
            this.onLoadCompleteListener = onLoadCompleteListener;
            return this;
        }

        public Configurator onPageScroll(OnPageScrollListener onPageScrollListener) {
            this.onPageScrollListener = onPageScrollListener;
            return this;
        }

        public Configurator onDocumentInteraction(OnDocumentInteractionListener onDocumentInteractionListener) {
            this.onDocumentInteractionListener = onDocumentInteractionListener;
            return this;
        }

        public Configurator onError(OnErrorListener onErrorListener) {
            this.onErrorListener = onErrorListener;
            return this;
        }

        public Configurator onPageError(OnPageErrorListener onPageErrorListener) {
            this.onPageErrorListener = onPageErrorListener;
            return this;
        }

        public Configurator onPageChange(OnPageChangeListener onPageChangeListener) {
            this.onPageChangeListener = onPageChangeListener;
            return this;
        }

        public Configurator onRender(OnRenderListener onRenderListener) {
            this.onRenderListener = onRenderListener;
            return this;
        }

        public Configurator onTap(OnTapListener onTapListener) {
            this.onTapListener = onTapListener;
            return this;
        }

        public Configurator onTapUp(OnTapListener onTapUpListener) {
            this.onTapUpListener = onTapUpListener;
            return this;
        }

        public Configurator onLongPress(OnLongPressListener onLongPressListener) {
            this.onLongPressListener = onLongPressListener;
            return this;
        }

        /** Asked first on a long press; true when it was a marker's own, so no text selection starts. */
        public Configurator onLongPressFirst(OnTapListener onLongPressFirstListener) {
            this.onLongPressFirstListener = onLongPressFirstListener;
            return this;
        }

        public Configurator enableTextSelection(boolean enabled) {
            this.textSelectionEnabled = enabled;
            return this;
        }

        public Configurator textSelectionColor(int color) {
            this.textSelectionColor = color;
            return this;
        }

        public Configurator onTextSelectionChange(OnTextSelectionChangeListener onTextSelectionChangeListener) {
            this.onTextSelectionChangeListener = onTextSelectionChangeListener;
            return this;
        }

        public Configurator linkHandler(LinkHandler linkHandler) {
            this.linkHandler = linkHandler;
            return this;
        }

        public Configurator defaultPage(int defaultPage) {
            this.defaultPage = defaultPage;
            return this;
        }

        public Configurator defaultViewState(ViewState defaultViewState) {
            this.defaultViewState = defaultViewState;
            return this;
        }

        public Configurator swipeHorizontal(boolean swipeHorizontal) {
            this.swipeHorizontal = swipeHorizontal;
            return this;
        }

        public Configurator horizontalReadingDirectionRtl(boolean horizontalReadingDirectionRtl) {
            this.horizontalReadingDirectionRtl = horizontalReadingDirectionRtl;
            return this;
        }

        public Configurator password(String password) {
            this.password = password;
            return this;
        }

        public Configurator scrollHandle(ScrollHandle scrollHandle) {
            this.scrollHandle = scrollHandle;
            return this;
        }

        public Configurator enableAntialiasing(boolean antialiasing) {
            this.antialiasing = antialiasing;
            return this;
        }

        public Configurator spacing(int spacing) {
            this.spacing = spacing;
            return this;
        }

        public Configurator autoSpacing(boolean autoSpacing) {
            this.autoSpacing = autoSpacing;
            return this;
        }

        public Configurator pagesPerRow(int pagesPerRow) {
            this.pagesPerRow = pagesPerRow;
            return this;
        }

        public Configurator firstPageAlone(boolean firstPageAlone) {
            this.firstPageAlone = firstPageAlone;
            return this;
        }

        public Configurator autoReleasingWhenDetachedFromWindow(boolean autoReleasing) {
            this.autoReleasingWhenDetachedFromWindow = autoReleasing;
            return this;
        }

        public Configurator pageFitPolicy(FitPolicy pageFitPolicy) {
            this.pageFitPolicy = pageFitPolicy;
            return this;
        }

        public Configurator fitEachPage(boolean fitEachPage) {
            this.fitEachPage = fitEachPage;
            return this;
        }

        public Configurator cropMargins(boolean cropMargins) {
            this.cropMargins = cropMargins;
            return this;
        }

        public Configurator cachedCropMargins(CropMargins cachedCropMargins) {
            this.cachedCropMargins = cachedCropMargins;
            return this;
        }

        public Configurator pageSnap(boolean pageSnap) {
            this.pageSnap = pageSnap;
            return this;
        }

        public Configurator renderDuringScale(boolean renderDuringScale) {
            this.renderDuringScale = renderDuringScale;
            return this;
        }

        public Configurator pageFling(boolean pageFling) {
            this.pageFling = pageFling;
            return this;
        }

        public Configurator freeScrollMode(boolean freeScrollMode) {
            this.freeScrollMode = freeScrollMode;
            return this;
        }

        public Configurator nightMode(boolean nightMode) {
            this.nightMode = nightMode;
            return this;
        }

        public Configurator debugChecks(boolean debugChecks) {
            this.debugChecks = debugChecks;
            return this;
        }

        public Configurator previewDisk(File directory, String documentKey) {
            this.previewDiskDir = directory;
            this.previewDocKey = documentKey;
            return this;
        }

        public Configurator pageGeometry(float[] geometry) {
            this.pageGeometry = geometry == null ? null : geometry.clone();
            return this;
        }

        public Configurator disableLongpress() {
            PDFView.this.dragPinchManager.disableLongpress();
            return this;
        }

        public void load() {
            if (!hasSize) {
                waitingDocumentConfigurator = this;
                return;
            }
            PDFView.this.recycle();
            PDFView.this.pendingPreviewDiskDir = previewDiskDir;
            PDFView.this.pendingPreviewDocKey = previewDocKey;
            PDFView.this.cachedPageGeometry = pageGeometry;
            PdfFile.setDebugChecksEnabled(debugChecks);
            PdfiumCore.setTimingLogsEnabled(debugChecks);
            PDFView.this.callbacks.setOnLoadComplete(onLoadCompleteListener);
            PDFView.this.callbacks.setOnError(onErrorListener);
            PDFView.this.callbacks.setOnDraw(onDrawListener);
            PDFView.this.callbacks.setOnDrawAll(onDrawAllListener);
            PDFView.this.callbacks.setOnPageChange(onPageChangeListener);
            PDFView.this.callbacks.setOnPageScroll(onPageScrollListener);
            PDFView.this.callbacks.setOnDocumentInteraction(onDocumentInteractionListener);
            PDFView.this.callbacks.setOnRender(onRenderListener);
            PDFView.this.callbacks.setOnTap(onTapListener);
            PDFView.this.callbacks.setOnTapUp(onTapUpListener);
            PDFView.this.callbacks.setOnLongPress(onLongPressListener);
            PDFView.this.callbacks.setOnLongPressFirst(onLongPressFirstListener);
            PDFView.this.callbacks.setOnPageError(onPageErrorListener);
            PDFView.this.callbacks.setOnTextSelectionChange(onTextSelectionChangeListener);
            PDFView.this.callbacks.setLinkHandler(linkHandler);
            PDFView.this.setSwipeEnabled(enableSwipe);
            PDFView.this.setHorizontalSwipeDisabled(horizontalSwipeDisabled);
            PDFView.this.setZoomDisabled(zoomDisabled);
            PDFView.this.setNightMode(nightMode);
            PDFView.this.enableDoubleTap(enableDoubleTap);
            PDFView.this.setDefaultPage(defaultPage);
            PDFView.this.setDefaultViewState(defaultViewState);
            PDFView.this.setSwipeVertical(!swipeHorizontal);
            PDFView.this.setHorizontalReadingDirectionRtl(horizontalReadingDirectionRtl);
            PDFView.this.enableAnnotationRendering(annotationRendering);
            PDFView.this.setScrollHandle(scrollHandle);
            PDFView.this.enableAntialiasing(antialiasing);
            PDFView.this.enableRenderDuringScale(renderDuringScale);
            PDFView.this.setSpacing(spacing);
            PDFView.this.setAutoSpacing(autoSpacing);
            PDFView.this.setPagesPerRow(pagesPerRow);
            PDFView.this.setFirstPageAlone(firstPageAlone);
            PDFView.this.setAutoReleasingWhenDetachedFromWindow(autoReleasingWhenDetachedFromWindow);
            PDFView.this.setPageFitPolicy(pageFitPolicy);
            PDFView.this.setFitEachPage(fitEachPage);
            PDFView.this.setCropMargins(cropMargins);
            PDFView.this.setCachedCropMargins(cachedCropMargins);
            PDFView.this.setPageSnap(pageSnap);
            PDFView.this.setPageFling(pageFling);
            PDFView.this.setFreeScrollMode(freeScrollMode);
            PDFView.this.setTextSelectionColor(textSelectionColor);
            PDFView.this.setTextSelectionEnabled(textSelectionEnabled);

            if (pageNumbers != null) {
                PDFView.this.load(documentSource, password, pageNumbers);
            } else {
                PDFView.this.load(documentSource, password);
            }
        }
    }
}
