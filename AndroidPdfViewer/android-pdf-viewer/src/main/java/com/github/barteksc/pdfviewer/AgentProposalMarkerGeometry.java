// Written for Thoughts. License is GPLv3.

package com.github.barteksc.pdfviewer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Places one pencil marker per agent proposal, shared by every before/after
 * fragment of that proposal across the whole document.
 *
 * <p>The anchor is chosen in document order (page, then vertical, then
 * horizontal position) from all fragments, independently of which pages are
 * currently laid out. Scrolling therefore can neither move a marker to a later
 * fragment nor create a second one.
 *
 * <p>Fragments that LaTeX copied elsewhere (table of contents, list of figures
 * or tables, running heads) are marked as duplicates. They keep their
 * highlights but never win the anchor while the proposal has a fragment at the
 * source's own occurrence, so editing a section heading does not put the pencil
 * on the contents page.
 *
 * <p>Plain floats are used instead of {@code android.graphics.RectF} so the
 * placement rules stay unit-testable without a device. All rectangles are
 * page-local document coordinates on the anchor's page, matching the rest of
 * the viewer's marker math.
 */
final class AgentProposalMarkerGeometry {

    private AgentProposalMarkerGeometry() {
    }

    /** One fragment in top-origin PDF points, or document coordinates when pageWidths is null. */
    static final class Fragment {
        final String proposalId;
        final int pageIndex;
        final float left;
        final float top;
        /** A LaTeX-made copy, such as a contents entry or running head, not the source's own occurrence. */
        final boolean duplicate;

        Fragment(String proposalId, int pageIndex, float left, float top) {
            this(proposalId, pageIndex, left, top, false);
        }

        Fragment(String proposalId, int pageIndex, float left, float top, boolean duplicate) {
            this.proposalId = proposalId == null ? "" : proposalId;
            this.pageIndex = pageIndex;
            this.left = left;
            this.top = top;
            this.duplicate = duplicate;
        }
    }

    /** A page-local document rectangle, in the same units as {@code pageBounds}. */
    static final class Bounds {
        final float left;
        final float top;
        final float right;
        final float bottom;

        Bounds(float left, float top, float right, float bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        float width() {
            return right - left;
        }

        float height() {
            return bottom - top;
        }

        float centerX() {
            return (left + right) / 2f;
        }

        float centerY() {
            return (top + bottom) / 2f;
        }

        boolean contains(float x, float y) {
            return x >= left && x <= right && y >= top && y <= bottom;
        }
    }

    static final class Marker {
        final String proposalId;
        final int pageIndex;
        final Bounds bounds;

        Marker(String proposalId, int pageIndex, Bounds bounds) {
            this.proposalId = proposalId;
            this.pageIndex = pageIndex;
            this.bounds = bounds;
        }
    }

    /**
     * Choose the document-wide first non-duplicate fragment of every proposal.
     *
     * <p>Duplicate fragments are LaTeX-made copies (contents entries, running
     * heads) that usually precede the real change, so they are skipped. A
     * proposal made only of duplicates falls back to its first fragment overall
     * rather than losing its marker.
     *
     * <p>Input order carries no meaning: the desktop fixture deliberately feeds
     * a later-page fragment first. Ties on page and vertical position fall back
     * to the horizontal position, so the anchor is deterministic for equal
     * geometry.
     */
    static Map<String, Fragment> anchorFragments(List<Fragment> fragments) {
        Map<String, Fragment> anchors = new LinkedHashMap<>();
        if (fragments == null) {
            return anchors;
        }
        for (Fragment fragment : fragments) {
            if (fragment == null || fragment.pageIndex < 0) continue;
            if (!isFinite(fragment.left) || !isFinite(fragment.top)) continue;
            Fragment previous = anchors.get(fragment.proposalId);
            if (previous == null || isPreferred(fragment, previous)) {
                anchors.put(fragment.proposalId, fragment);
            }
        }
        return anchors;
    }

    /** A source occurrence beats any duplicate; otherwise document order decides. */
    private static boolean isPreferred(Fragment candidate, Fragment current) {
        if (candidate.duplicate != current.duplicate) return current.duplicate;
        return isBefore(candidate, current);
    }

    private static boolean isBefore(Fragment candidate, Fragment current) {
        if (candidate.pageIndex != current.pageIndex) return candidate.pageIndex < current.pageIndex;
        if (candidate.top != current.top) return candidate.top < current.top;
        return candidate.left < current.left;
    }

    /**
     * Build one marker per proposal.
     *
     * <p>The anchor is resolved before {@code pageBounds} is consulted, so the
     * document-wide first non-duplicate fragment keeps winning even when only a
     * later page is laid out. Such a page then simply gets no marker instead of
     * a displaced one.
     *
     * @param fragments  every fragment of every proposal, in any input order
     * @param pageBounds page-local document bounds keyed by page index
     * @param pageWidths page width in PDF points keyed by page index, used to
     *                   scale the point-space anchor into document pixels; null if fragments
     *                   have already been transformed into document coordinates
     * @param pageSizes  marker edge length in document pixels keyed by page index
     */
    static List<Marker> layout(
            List<Fragment> fragments,
            Map<Integer, Bounds> pageBounds,
            Map<Integer, Float> pageWidths,
            Map<Integer, Float> pageSizes) {
        List<Marker> markers = new ArrayList<>();
        if (pageBounds == null || pageSizes == null) {
            return markers;
        }
        final Map<String, Fragment> anchors = anchorFragments(fragments);

        List<String> ids = new ArrayList<>(anchors.keySet());
        // Deterministic placement order keeps the no-overlap nudge stable across
        // frames and independent of the overlay list order.
        Collections.sort(ids, new Comparator<String>() {
            @Override
            public int compare(String first, String second) {
                Fragment a = anchors.get(first);
                Fragment b = anchors.get(second);
                if (a.pageIndex != b.pageIndex) return Integer.compare(a.pageIndex, b.pageIndex);
                if (a.top != b.top) return Float.compare(a.top, b.top);
                if (a.left != b.left) return Float.compare(a.left, b.left);
                return first.compareTo(second);
            }
        });

        Map<Integer, List<Bounds>> placed = new LinkedHashMap<>();
        for (String id : ids) {
            Fragment anchor = anchors.get(id);
            Bounds page = pageBounds.get(anchor.pageIndex);
            Float pageSize = pageSizes.get(anchor.pageIndex);
            if (page == null || pageSize == null || !(pageSize > 0f) || !isFinite(pageSize)) continue;
            float size = pageSize;
            if (page.width() < size || page.height() < size) continue;

            float originX = anchor.left;
            float originY = anchor.top;
            if (pageWidths != null) {
                Float pageWidth = pageWidths.get(anchor.pageIndex);
                // Fragments are PDF points; page bounds are zoomed pixels.
                if (pageWidth != null && pageWidth > 0f) {
                    float scale = page.width() / pageWidth;
                    originX = page.left + anchor.left * scale;
                    originY = page.top + anchor.top * scale;
                }
            }

            float left = clamp(originX, page.left + MARKER_GAP, page.right - size - MARKER_GAP);
            float top = clamp(originY - size - MARKER_GAP, page.top + MARKER_GAP, page.bottom - size - MARKER_GAP);
            Bounds marker = avoidOverlap(new Bounds(left, top, left + size, top + size), page,
                    placed.get(anchor.pageIndex), size);
            placed.computeIfAbsent(anchor.pageIndex, ignored -> new ArrayList<>()).add(marker);
            markers.add(new Marker(id, anchor.pageIndex, marker));
        }
        return markers;
    }

    /**
     * Place a marker so it overlaps nothing already placed, without leaving the page.
     *
     * <p>Sliding right is tried first because it keeps the marker closest to the
     * anchor's line. When the page edge or another marker blocks that, the search
     * falls back to a ring of offsets around the anchor: further right, then left,
     * then above and below. Markers crowding one page edge therefore stay distinct
     * instead of collapsing onto identical bounds, which would leave only one of
     * them reachable.
     */
    private static Bounds avoidOverlap(Bounds marker, Bounds page, List<Bounds> placed, float size) {
        if (placed == null || placed.isEmpty()) {
            return marker;
        }
        Bounds fixed = tryPlace(marker, page, placed, size);
        if (fixed != null) {
            return fixed;
        }
        float step = size + MARKER_GAP;
        // Enough rings to walk past every already-placed marker on this page:
        // a marker may have to travel the full diagonal to escape a dense cluster.
        int maxRing = (int) Math.ceil(Math.hypot(page.width(), page.height()) / step);
        for (int ring = 1; ring <= maxRing; ring++) {
            for (int dy = -ring; dy <= ring; dy++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    if (Math.abs(dx) != ring && Math.abs(dy) != ring) continue;
                    Bounds candidate = new Bounds(
                            marker.left + dx * step, marker.top + dy * step,
                            marker.right + dx * step, marker.bottom + dy * step);
                    if (classify(candidate, page, placed, size) != Placement.FREE) continue;
                    return candidate;
                }
            }
        }
        return marker;
    }

    /**
     * The preferred slot directly right of every neighbour, if one is free.
     *
     * <p>Each shift is fully re-checked rather than only tested for the page
     * edge: moving right of one neighbour can land on another, and accepting
     * that slot is exactly how two markers end up sharing bounds.
     */
    private static Bounds tryPlace(Bounds marker, Bounds page, List<Bounds> placed, float size) {
        Bounds result = marker;
        for (int pass = 0; pass < MAX_OVERLAP_PASSES; pass++) {
            Bounds conflict = null;
            for (Bounds other : placed) {
                if (overlaps(result, other)) {
                    conflict = other;
                    break;
                }
            }
            if (conflict == null) {
                return result;
            }
            Bounds shifted = new Bounds(conflict.right + MARKER_GAP, result.top,
                    conflict.right + MARKER_GAP + size, result.bottom);
            if (classify(shifted, page, placed, size) != Placement.FREE) {
                // Blocked by the page edge or another marker: let the ring
                // search move this marker left, up, or down instead.
                return null;
            }
            result = shifted;
        }
        // Still contested after the pass budget: let the ring search decide.
        return null;
    }

    private enum Placement { FREE, OFF_PAGE, OVERLAPPING }

    private static Placement classify(Bounds candidate, Bounds page, List<Bounds> placed, float size) {
        // Only the page edges decide this. `size` is not re-measured through
        // width(), because a marker built as 20f measures 19.999998 and would be
        // rejected as off-page; EDGE_EPSILON absorbs the same rounding at the edges.
        float epsilon = EDGE_EPSILON;
        if (candidate.left < page.left + MARKER_GAP - epsilon
                || candidate.top < page.top + MARKER_GAP - epsilon
                || candidate.right > page.right - MARKER_GAP + epsilon
                || candidate.bottom > page.bottom - MARKER_GAP + epsilon) {
            return Placement.OFF_PAGE;
        }
        for (Bounds other : placed) {
            if (overlaps(candidate, other)) {
                return Placement.OVERLAPPING;
            }
        }
        return Placement.FREE;
    }

    private static boolean overlaps(Bounds first, Bounds second) {
        return first.left < second.right + MARKER_GAP
                && first.right > second.left - MARKER_GAP
                && first.top < second.bottom + MARKER_GAP
                && first.bottom > second.top - MARKER_GAP;
    }

    /**
     * Hit-test a document-coordinate tap against the markers.
     *
     * <p>A tap inside a drawn marker always wins, so the marker under the finger
     * can never lose to a neighbour's expanded target. Only when the tap misses
     * every drawn box do the {@code touchTarget} expansions apply, and then the
     * nearest target wins rather than the first one in list order.
     */
    static String hitTest(List<Marker> markers, float documentX, float documentY, float touchTarget) {
        if (markers == null || !isFinite(documentX) || !isFinite(documentY)) {
            return null;
        }
        // Drawn boxes first, so a tap on a marker is never stolen by a
        // neighbour whose touch target happens to be wider.
        for (Marker marker : markers) {
            if (marker == null || marker.bounds == null) continue;
            if (marker.bounds.contains(documentX, documentY)) {
                return marker.proposalId;
            }
        }
        if (!(touchTarget > 0f) || !isFinite(touchTarget)) {
            return null;
        }
        String nearest = null;
        float nearestDistance = Float.MAX_VALUE;
        for (Marker marker : markers) {
            if (marker == null || marker.bounds == null) continue;
            Bounds bounds = marker.bounds;
            float horizontal = Math.max(0f, (touchTarget - bounds.width()) / 2f);
            float vertical = Math.max(0f, (touchTarget - bounds.height()) / 2f);
            if (documentX < bounds.left - horizontal || documentX > bounds.right + horizontal
                    || documentY < bounds.top - vertical || documentY > bounds.bottom + vertical) {
                continue;
            }
            // Distance to the rectangle, not its centre, so wide and narrow
            // markers compete on equal terms.
            float dx = Math.max(0f, Math.max(bounds.left - documentX, documentX - bounds.right));
            float dy = Math.max(0f, Math.max(bounds.top - documentY, documentY - bounds.bottom));
            float distance = dx * dx + dy * dy;
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = marker.proposalId;
            }
        }
        return nearest;
    }

    private static float clamp(float value, float minimum, float maximum) {
        if (maximum < minimum) return minimum;
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    /** Gap between a marker and the page edge or a neighbouring marker. */
    static final float MARKER_GAP = 4f;

    /** Absorbs float rounding when comparing a marker edge against the page edge. */
    private static final float EDGE_EPSILON = 0.01f;

    /** Bounded work: each pass slides past at least one neighbour. */
    private static final int MAX_OVERLAP_PASSES = 16;
}