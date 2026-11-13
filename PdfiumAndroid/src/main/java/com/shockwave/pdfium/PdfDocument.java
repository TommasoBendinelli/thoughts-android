package com.shockwave.pdfium;

import android.graphics.PointF;
import android.graphics.RectF;
import android.os.ParcelFileDescriptor;
import android.util.ArrayMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PdfDocument {

    public static class Meta {
        String title;
        String author;
        String subject;
        String keywords;
        String creator;
        String producer;
        String creationDate;
        String modDate;
        int totalPages;

        public String getTitle() {
            return title;
        }

        public String getAuthor() {
            return author;
        }

        public String getSubject() {
            return subject;
        }

        public String getKeywords() {
            return keywords;
        }

        public String getCreator() {
            return creator;
        }

        public String getProducer() {
            return producer;
        }

        public String getCreationDate() {
            return creationDate;
        }

        public String getModDate() {
            return modDate;
        }

        public int getTotalPages() {
            return totalPages;
        }
    }

    public static class Bookmark {
        private List<Bookmark> children = new ArrayList<>();
        public String title;
        public long pageIdx;
        public long mNativePtr;

        public List<Bookmark> getChildren() {
            return children;
        }

        public void setChildren(List<Bookmark> newChildren) {
            children.addAll(newChildren);
        }

        public boolean hasChildren() {
            return !children.isEmpty();
        }

        public String getTitle() {
            return title;
        }

        public long getPageIdx() {
            return pageIdx;
        }
    }

    public static class Link {
        private RectF bounds;
        private Integer destPageIdx;
        private String uri;
        private PointF destPoint;

        public Link(RectF bounds, Integer destPageIdx, String uri) {
            this(bounds, destPageIdx, uri, null);
        }

        public Link(RectF bounds, Integer destPageIdx, String uri, PointF destPoint) {
            this.bounds = bounds;
            this.destPageIdx = destPageIdx;
            this.uri = uri;
            this.destPoint = destPoint == null ? null : new PointF(destPoint.x, destPoint.y);
        }

        public Integer getDestPageIdx() {
            return destPageIdx;
        }

        public String getUri() {
            return uri;
        }

        public RectF getBounds() {
            return bounds;
        }

        /**
         * Returns the destination point in PDF page coordinates. Either axis may be NaN when
         * the PDF destination constrains only the other axis.
         */
        public PointF getDestPoint() {
            return destPoint == null ? null : new PointF(destPoint.x, destPoint.y);
        }
    }

    public static class HighlightAnnotation {
        private int annotationIndex;
        private String groupKey;
        private RectF bounds;
        private String quote;
        private int color;
        private boolean appOwned;
        private boolean searchResult;
        private String note;
        private String creationDate;

        public HighlightAnnotation(int annotationIndex, String groupKey, RectF bounds, String quote) {
            this(annotationIndex, groupKey, bounds, quote, 0xFFFFFF00, false);
        }

        public HighlightAnnotation(int annotationIndex, String groupKey, RectF bounds, String quote, int color) {
            this(annotationIndex, groupKey, bounds, quote, color, false);
        }

        public HighlightAnnotation(int annotationIndex, String groupKey, RectF bounds, String quote,
                                   int color, boolean appOwned) {
            this(annotationIndex, groupKey, bounds, quote, color, appOwned, null, null);
        }

        public HighlightAnnotation(int annotationIndex, String groupKey, RectF bounds, String quote,
                                   int color, boolean appOwned, String note, String creationDate) {
            this(annotationIndex, groupKey, bounds, quote, color, appOwned, false, note, creationDate);
        }

        public HighlightAnnotation(int annotationIndex, String groupKey, RectF bounds, String quote,
                                   int color, boolean appOwned, boolean searchResult,
                                   String note, String creationDate) {
            this.annotationIndex = annotationIndex;
            this.groupKey = groupKey == null ? "" : groupKey;
            this.bounds = bounds;
            this.quote = quote == null ? "" : quote;
            this.color = color;
            this.appOwned = appOwned;
            this.searchResult = searchResult;
            this.note = note == null ? "" : note;
            this.creationDate = creationDate == null ? "" : creationDate;
        }

        public int getAnnotationIndex() {
            return annotationIndex;
        }

        public String getGroupKey() {
            return groupKey;
        }

        public RectF getBounds() {
            return bounds;
        }

        public String getQuote() {
            return quote;
        }

        public int getColor() {
            return color;
        }

        public boolean isAppOwned() {
            return appOwned;
        }

        public boolean isSearchResult() {
            return searchResult;
        }

        public String getNote() {
            return note;
        }

        public String getCreationDate() {
            return creationDate;
        }
    }

    public static class PdfComment {
        public static final int SUBTYPE_TEXT = 1;
        public static final int SUBTYPE_HIGHLIGHT = 2;
        public static final int SUBTYPE_STRIKEOUT = 3;

        public static boolean isTextMarkup(int subtype) {
            return subtype == SUBTYPE_HIGHLIGHT || subtype == SUBTYPE_STRIKEOUT;
        }

        public static String subtypeName(int subtype) {
            switch (subtype) {
                case SUBTYPE_TEXT: return "Text";
                case SUBTYPE_HIGHLIGHT: return "Highlight";
                case SUBTYPE_STRIKEOUT: return "StrikeOut";
                default: throw new IllegalArgumentException("Unsupported comment subtype: " + subtype);
            }
        }

        public static int subtypeFromName(String name) {
            if ("Text".equalsIgnoreCase(name)) return SUBTYPE_TEXT;
            if ("Highlight".equalsIgnoreCase(name)) return SUBTYPE_HIGHLIGHT;
            if ("StrikeOut".equalsIgnoreCase(name)) return SUBTYPE_STRIKEOUT;
            return 0;
        }

        private final int annotationIndex;
        private final int subtype;
        private final String name;
        private final RectF bounds;
        private final int color;
        private final String contents;
        private final String author;
        private final String creationDate;
        private final String modifiedDate;
        private final String inReplyToName;
        private final String anchorStatus;
        private final List<RectF> markupRects = new ArrayList<>();

        // Called by the native importer; each rectangle is one original text quad.
        public void setMarkupRects(float[] coordinates) {
            markupRects.clear();
            for (int i = 0; i + 3 < coordinates.length; i += 4) {
                markupRects.add(new RectF(coordinates[i], coordinates[i + 1],
                        coordinates[i + 2], coordinates[i + 3]));
            }
        }

        public List<RectF> getMarkupRects() {
            List<RectF> result = new ArrayList<>();
            for (RectF rect : markupRects) result.add(new RectF(rect));
            if (result.isEmpty() && bounds != null) result.add(new RectF(bounds));
            return result;
        }

        public PdfComment(int annotationIndex, int subtype, String name, RectF bounds, int color,
                          String contents, String author, String creationDate,
                          String modifiedDate, String inReplyToName, String anchorStatus) {
            this.annotationIndex = annotationIndex;
            this.subtype = subtype;
            this.name = name == null ? "" : name;
            this.bounds = bounds;
            this.color = color;
            this.contents = contents == null ? "" : contents;
            this.author = author == null ? "" : author;
            this.creationDate = creationDate == null ? "" : creationDate;
            this.modifiedDate = modifiedDate == null ? "" : modifiedDate;
            this.inReplyToName = inReplyToName == null ? "" : inReplyToName;
            this.anchorStatus = anchorStatus == null ? "" : anchorStatus;
        }

        public int getAnnotationIndex() {
            return annotationIndex;
        }

        public int getSubtype() {
            return subtype;
        }

        public String getName() {
            return name;
        }

        public RectF getBounds() {
            return bounds;
        }

        public int getColor() {
            return color;
        }

        public String getContents() {
            return contents;
        }

        public String getAuthor() {
            return author;
        }

        public String getCreationDate() {
            return creationDate;
        }

        public String getModifiedDate() {
            return modifiedDate;
        }

        public String getInReplyToName() {
            return inReplyToName;
        }

        public String getAnchorStatus() {
            return anchorStatus;
        }

        public boolean isSourceAnchorUnresolved() {
            return "needs_anchor".equalsIgnoreCase(anchorStatus)
                    || "orphaned".equalsIgnoreCase(anchorStatus);
        }

        public boolean isReply() {
            return !inReplyToName.isEmpty();
        }
    }

    public static class FontInfo {
        private final String name;
        private final boolean embedded;

        public FontInfo(String name, boolean embedded) {
            this.name = name == null ? "" : name;
            this.embedded = embedded;
        }

        public String getName() {
            return name;
        }

        public boolean isEmbedded() {
            return embedded;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof FontInfo)) {
                return false;
            }
            FontInfo that = (FontInfo) other;
            return embedded == that.embedded && name.equals(that.name);
        }

        @Override
        public int hashCode() {
            return name.hashCode() * 31 + (embedded ? 1 : 0);
        }
    }

    public static class FormField {
        public static final int TYPE_CHECKBOX = 2;
        public static final int TYPE_RADIO_BUTTON = 3;
        public static final int TYPE_TEXT_FIELD = 6;

        public static final int FLAG_READONLY = 1;
        public static final int FLAG_TEXT_MULTILINE = 1 << 12;

        private final int annotationIndex;
        private final int type;
        private final int flags;
        private final String name;
        private final String alternateName;
        private final String value;
        private final boolean checked;

        public FormField(int annotationIndex, int type, int flags, String name, String alternateName, String value, boolean checked) {
            this.annotationIndex = annotationIndex;
            this.type = type;
            this.flags = flags;
            this.name = name == null ? "" : name;
            this.alternateName = alternateName == null ? "" : alternateName;
            this.value = value == null ? "" : value;
            this.checked = checked;
        }

        public int getAnnotationIndex() {
            return annotationIndex;
        }

        public int getType() {
            return type;
        }

        public String getName() {
            return name;
        }

        public String getAlternateName() {
            return alternateName;
        }

        public String getValue() {
            return value;
        }

        public boolean isChecked() {
            return checked;
        }

        public boolean isReadOnly() {
            return (flags & FLAG_READONLY) != 0;
        }

        public boolean isMultiline() {
            return (flags & FLAG_TEXT_MULTILINE) != 0;
        }
    }

    /*package*/ PdfDocument() {
    }

    // A reading composition owns its children; it has no native merged PDF.
    /*package*/ List<PdfDocument> documents;
    /*package*/ int[] documentStarts;
    /*package*/ String[] documentIds;
    /*package*/ String[] documentFilenames;
    public void setDocumentFilenames(String[] names) {
        if (!isComposition() || names.length != documents.size()) throw new IllegalArgumentException("Invalid composition filenames");
        documentFilenames = names.clone();
    }
    /*package*/ String[] documentTitles;
    /*package*/ String projectTitle;
    /*package*/ int compositionPages;

    public boolean isComposition() { return documents != null; }

    /*package*/ int documentIndex(int page) {
        if (closed || page < 0 || page >= compositionPages) {
            throw new IllegalArgumentException("Page is outside the reading composition");
        }
        int low = 0, high = documentStarts.length - 1;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (documentStarts[middle] <= page) low = middle;
            else high = middle - 1;
        }
        return low;
    }

    public String documentIdForPage(int page) {
        return isComposition() ? documentIds[documentIndex(page)] : null;
    }

    public int documentRelativePage(int page) {
        return isComposition() ? page - documentStarts[documentIndex(page)] : page;
    }

    public int projectPage(String documentId, int localPage) {
        if (!isComposition() || closed || localPage < 0) return -1;
        for (int i = 0; i < documentIds.length; i++) {
            int end = i + 1 < documentStarts.length ? documentStarts[i + 1] : compositionPages;
            if (documentIds[i].equals(documentId) && localPage < end - documentStarts[i])
                return documentStarts[i] + localPage;
        }
        return -1;
    }

    /*package*/ long mNativeDocPtr;
    /*package*/ ParcelFileDescriptor parcelFileDescriptor;
    /*package*/ volatile boolean closed = false;

    /*package*/ final Map<Integer, Long> mNativePagesPtr = new ArrayMap<>();
    /*package*/ final Map<Integer, Long> mNativeTextPagesPtr = new ArrayMap<>();

    public boolean isClosed() {
        return closed;
    }

    public boolean hasPage(int index) {
        if (isComposition()) {
            int child = documentIndex(index);
            return documents.get(child).hasPage(index - documentStarts[child]);
        }
        return mNativePagesPtr.containsKey(index);
    }
}
