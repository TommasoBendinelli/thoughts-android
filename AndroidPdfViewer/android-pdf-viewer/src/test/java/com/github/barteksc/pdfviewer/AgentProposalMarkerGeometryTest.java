// Written for Thoughts. License is GPLv3.

package com.github.barteksc.pdfviewer;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AgentProposalMarkerGeometryTest {

    private static final float SIZE = 20f;
    private static final AgentProposalMarkerGeometry.Bounds PAGE =
            new AgentProposalMarkerGeometry.Bounds(0f, 0f, 600f, 800f);

    private static AgentProposalMarkerGeometry.Bounds rect(float l, float t, float r, float b) {
        return new AgentProposalMarkerGeometry.Bounds(l, t, r, b);
    }

    private static Map<Integer, AgentProposalMarkerGeometry.Bounds> pageMap(
            AgentProposalMarkerGeometry.Bounds... bounds) {
        Map<Integer, AgentProposalMarkerGeometry.Bounds> result = new LinkedHashMap<>();
        for (int i = 0; i < bounds.length; i++) result.put(i, bounds[i]);
        return result;
    }

    private static Map<Integer, Float> widths(float... widths) {
        Map<Integer, Float> result = new LinkedHashMap<>();
        for (int i = 0; i < widths.length; i++) result.put(i, widths[i]);
        return result;
    }

    private static AgentProposalMarkerGeometry.Fragment fragment(
            String id, int page, float left, float top) {
        return new AgentProposalMarkerGeometry.Fragment(id, page, left, top);
    }

    private static AgentProposalMarkerGeometry.Fragment duplicate(
            String id, int page, float left, float top) {
        return new AgentProposalMarkerGeometry.Fragment(id, page, left, top, true);
    }

    private static Map<Integer, Float> sizes(float... sizes) {
        Map<Integer, Float> result = new LinkedHashMap<>();
        for (int i = 0; i < sizes.length; i++) result.put(i, sizes[i]);
        return result;
    }

    private static List<AgentProposalMarkerGeometry.Marker> layout(
            List<AgentProposalMarkerGeometry.Fragment> fragments, float size) {
        return AgentProposalMarkerGeometry.layout(
                fragments, pageMap(PAGE), widths(600f), sizes(size));
    }

    @Test
    public void choosesTheFirstFragmentInDocumentOrderRegardlessOfInputOrder() {
        // The later page is deliberately first, mirroring the desktop fixture.
        List<AgentProposalMarkerGeometry.Fragment> fragments = Arrays.asList(
                fragment("p", 1, 80f, 80f),
                fragment("p", 0, 300f, 400f),
                fragment("p", 0, 40f, 120f));

        AgentProposalMarkerGeometry.Fragment anchor =
                AgentProposalMarkerGeometry.anchorFragments(fragments).get("p");

        assertNotNull(anchor);
        assertEquals(0, anchor.pageIndex);
        assertEquals(40f, anchor.left, 0f);
        assertEquals(120f, anchor.top, 0f);
    }

    @Test
    public void prefersTheHigherFragmentOnTheSamePageThenTheLeftmost() {
        AgentProposalMarkerGeometry.Fragment anchor = AgentProposalMarkerGeometry.anchorFragments(
                Arrays.asList(
                        fragment("p", 0, 10f, 500f),
                        fragment("p", 0, 400f, 100f),
                        fragment("p", 0, 20f, 100f)))
                .get("p");

        assertEquals(20f, anchor.left, 0f);
        assertEquals(100f, anchor.top, 0f);
    }

    @Test
    public void skipsAnEarlierContentsEntryForTheEditedHeading() {
        // The heading's table-of-contents copy on page 1 precedes the heading itself.
        for (boolean reversed : new boolean[] {false, true}) {
            List<AgentProposalMarkerGeometry.Fragment> fragments = new ArrayList<>(Arrays.asList(
                    duplicate("p", 0, 72f, 140f),
                    duplicate("p", 0, 72f, 160f),
                    fragment("p", 4, 90f, 300f),
                    duplicate("p", 5, 72f, 30f),
                    fragment("p", 4, 300f, 300f)));
            if (reversed) java.util.Collections.reverse(fragments);

            AgentProposalMarkerGeometry.Fragment anchor =
                    AgentProposalMarkerGeometry.anchorFragments(fragments).get("p");

            assertNotNull(anchor);
            assertFalse(anchor.duplicate);
            assertEquals(4, anchor.pageIndex);
            assertEquals(90f, anchor.left, 0f);
            assertEquals(300f, anchor.top, 0f);
        }
    }

    @Test
    public void fallsBackToTheFirstFragmentWhenEveryFragmentIsADuplicate() {
        AgentProposalMarkerGeometry.Fragment anchor = AgentProposalMarkerGeometry.anchorFragments(
                Arrays.asList(
                        duplicate("p", 2, 10f, 10f),
                        duplicate("p", 0, 80f, 200f),
                        duplicate("p", 0, 40f, 200f)))
                .get("p");

        assertNotNull(anchor);
        assertEquals(0, anchor.pageIndex);
        assertEquals(40f, anchor.left, 0f);
        assertEquals(200f, anchor.top, 0f);
    }

    @Test
    public void choosesEachProposalsAnchorIndependentlyOfOthersDuplicates() {
        Map<String, AgentProposalMarkerGeometry.Fragment> anchors = AgentProposalMarkerGeometry.anchorFragments(
                Arrays.asList(
                        duplicate("heading", 0, 72f, 140f),
                        fragment("heading", 3, 72f, 100f),
                        duplicate("contents-only", 0, 72f, 180f),
                        fragment("body", 1, 60f, 400f)));

        assertEquals(3, anchors.get("heading").pageIndex);
        assertEquals(0, anchors.get("contents-only").pageIndex);
        assertEquals(1, anchors.get("body").pageIndex);
    }

    @Test
    public void contentsPageGetsNoMarkerForAHeadingEditedLaterInTheDocument() {
        List<AgentProposalMarkerGeometry.Fragment> fragments = Arrays.asList(
                duplicate("p", 0, 72f, 140f),
                fragment("p", 1, 90f, 300f));

        Map<Integer, AgentProposalMarkerGeometry.Bounds> contentsOnly = new LinkedHashMap<>();
        contentsOnly.put(0, PAGE);
        assertTrue(AgentProposalMarkerGeometry.layout(
                fragments, contentsOnly, widths(600f, 600f), sizes(SIZE, SIZE)).isEmpty());

        List<AgentProposalMarkerGeometry.Marker> markers = AgentProposalMarkerGeometry.layout(
                fragments, pageMap(PAGE, rect(0f, 820f, 600f, 1620f)), widths(600f, 600f), sizes(SIZE, SIZE));
        assertEquals(1, markers.size());
        assertEquals(1, markers.get(0).pageIndex);
    }

    @Test
    public void keepsOneMarkerPerProposalSharingBeforeAndAfterFragments() {
        List<AgentProposalMarkerGeometry.Fragment> fragments = new ArrayList<>();
        fragments.add(fragment("p", 0, 100f, 200f));
        fragments.add(fragment("p", 0, 100f, 210f));
        fragments.add(fragment("p", 1, 100f, 50f));

        List<AgentProposalMarkerGeometry.Marker> markers = AgentProposalMarkerGeometry.layout(
                fragments, pageMap(PAGE, rect(0f, 800f, 600f, 1600f)), widths(600f, 600f), sizes(SIZE, SIZE));

        assertEquals(1, markers.size());
        assertEquals(0, markers.get(0).pageIndex);
    }

    @Test
    public void keepsTheMarkerOnTheAnchorsPageWhenOnlyALaterPageIsLaidOut() {
        // The proposal starts on page 0 but page 1 is the only laid-out page.
        List<AgentProposalMarkerGeometry.Fragment> fragments = Arrays.asList(
                fragment("p", 0, 100f, 200f),
                fragment("p", 1, 100f, 50f));
        Map<Integer, AgentProposalMarkerGeometry.Bounds> laterPageOnly = new LinkedHashMap<>();
        laterPageOnly.put(1, rect(0f, 800f, 600f, 1600f));

        List<AgentProposalMarkerGeometry.Marker> markers = AgentProposalMarkerGeometry.layout(
                fragments, laterPageOnly, widths(600f), sizes(SIZE));

        assertTrue("A later page never acquires the marker", markers.isEmpty());
    }

    @Test
    public void keepsTheMarkerOnItsOwnPageRegardlessOfLaidOutPages() {
        // Same fragments, but now the anchor's page is the laid-out one.
        List<AgentProposalMarkerGeometry.Fragment> fragments = Arrays.asList(
                fragment("p", 1, 100f, 50f),
                fragment("p", 0, 100f, 200f));

        List<AgentProposalMarkerGeometry.Marker> markers = layout(fragments, SIZE);

        assertEquals(1, markers.size());
        assertEquals("Anchored to the first page, not the first input", 0, markers.get(0).pageIndex);
    }

    @Test
    public void choosingTheAnchorDoesNotDependOnWhichPagesAreLaidOut() {
        List<AgentProposalMarkerGeometry.Fragment> fragments = Arrays.asList(
                fragment("p", 0, 100f, 200f),
                fragment("p", 1, 300f, 10f),
                fragment("p", 2, 400f, 20f));

        // The anchor is the page-0 fragment in every case below. A marker exists
        // exactly when page 0 is laid out, and it is always on page 0.
        for (int[] visible : new int[][] {{0}, {1}, {2}, {0, 1}, {1, 2}, {0, 1, 2}}) {
            Map<Integer, AgentProposalMarkerGeometry.Bounds> bounds = new LinkedHashMap<>();
            for (int page : visible) bounds.put(page, rect(0f, page * 800f, 600f, page * 800f + 800f));
            List<AgentProposalMarkerGeometry.Marker> markers = AgentProposalMarkerGeometry.layout(
                    fragments, bounds, widths(600f), sizes(SIZE));
            boolean anchorLaidOut = false;
            for (int page : visible) anchorLaidOut |= page == 0;
            String context = "Visible " + Arrays.toString(visible);
            assertEquals(context, anchorLaidOut ? 1 : 0, markers.size());
            if (anchorLaidOut) assertEquals(context, 0, markers.get(0).pageIndex);
        }
    }

    @Test
    public void anchorsStayWithinThePageEvenWhenEveryProposalSharesOneSpot() {
        // Worst case for the nudge loop: many markers wanting the same place.
        List<AgentProposalMarkerGeometry.Fragment> fragments = new ArrayList<>();
        for (int i = 0; i < 30; i++) fragments.add(fragment("p" + i, 0, 100f, 300f));

        List<AgentProposalMarkerGeometry.Marker> markers = layout(fragments, SIZE);

        assertEquals("Every proposal is still placed", 30, markers.size());
        for (AgentProposalMarkerGeometry.Marker marker : markers) {
            assertTrue("Inside the page: " + marker.bounds,
                    marker.bounds.left >= PAGE.left && marker.bounds.right <= PAGE.right);
        }
    }

    @Test
    public void placesTheMarkerAboveAndLeftOfTheAnchoredFragment() {
        List<AgentProposalMarkerGeometry.Marker> markers =
                layout(Arrays.asList(fragment("p", 0, 200f, 300f)), SIZE);

        AgentProposalMarkerGeometry.Bounds bounds = markers.get(0).bounds;
        assertEquals(200f, bounds.left, 0.001f);
        assertEquals(300f - SIZE - AgentProposalMarkerGeometry.MARKER_GAP, bounds.top, 0.001f);
        assertEquals(SIZE, bounds.width(), 0.001f);
        assertEquals(SIZE, bounds.height(), 0.001f);
    }

    @Test
    public void clampsTheMarkerInsideThePageEdges() {
        List<AgentProposalMarkerGeometry.Marker> markers = layout(Arrays.asList(
                fragment("top", 0, 2f, 3f),
                fragment("bottom", 0, 598f, 799f)), SIZE);

        assertEquals("Each proposal in the document keeps a marker", 2, markers.size());
        for (AgentProposalMarkerGeometry.Marker marker : markers) {
            AgentProposalMarkerGeometry.Bounds bounds = marker.bounds;
            assertTrue("Inside the page: " + marker.proposalId,
                    bounds.left >= PAGE.left && bounds.top >= PAGE.top
                            && bounds.right <= PAGE.right && bounds.bottom <= PAGE.bottom);
        }
    }

    @Test
    public void marksAPageTooSmallOnlyWhenTheAnchorItselfHasNoRoom() {
        // A thin page cannot host the marker, but a normal page still can.
        Map<Integer, AgentProposalMarkerGeometry.Bounds> bounds = new LinkedHashMap<>();
        bounds.put(0, rect(0f, 0f, 10f, 800f));
        bounds.put(1, rect(0f, 900f, 600f, 1700f));

        List<AgentProposalMarkerGeometry.Marker> markers = AgentProposalMarkerGeometry.layout(
                Arrays.asList(fragment("thin", 0, 5f, 100f), fragment("wide", 1, 100f, 100f)),
                bounds, widths(10f, 600f), sizes(SIZE, SIZE));

        assertEquals(1, markers.size());
        assertEquals("wide", markers.get(0).proposalId);
    }

    @Test
    public void eachMarkerUsesItsOwnPagesSize() {
        // Marker size is per page, so a smaller page is not given a larger marker.
        Map<Integer, AgentProposalMarkerGeometry.Bounds> bounds = new LinkedHashMap<>();
        bounds.put(0, rect(0f, 0f, 600f, 800f));
        bounds.put(1, rect(0f, 900f, 300f, 1300f));

        List<AgentProposalMarkerGeometry.Marker> markers = AgentProposalMarkerGeometry.layout(
                Arrays.asList(fragment("big", 0, 100f, 200f), fragment("small", 1, 100f, 100f)),
                bounds, widths(600f, 300f), sizes(SIZE, SIZE / 2f));

        Map<String, Float> widthsById = new LinkedHashMap<>();
        for (AgentProposalMarkerGeometry.Marker marker : markers) {
            widthsById.put(marker.proposalId, marker.bounds.width());
        }
        assertEquals(SIZE, widthsById.get("big"), 0.001f);
        assertEquals(SIZE / 2f, widthsById.get("small"), 0.001f);
    }

    @Test
    public void scalesPointSpaceAnchorsIntoTheZoomedPage() {
        // Zoom 2: the page document bounds double, and so does the marker.
        List<AgentProposalMarkerGeometry.Marker> markers = AgentProposalMarkerGeometry.layout(
                Arrays.asList(fragment("p", 0, 100f, 200f)),
                pageMap(rect(10f, 20f, 1210f, 1620f)), widths(600f), sizes(2f * SIZE));

        AgentProposalMarkerGeometry.Bounds bounds = markers.get(0).bounds;
        assertEquals(10f + 200f, bounds.left, 0.001f);
        assertEquals(20f + 400f - 2f * SIZE - AgentProposalMarkerGeometry.MARKER_GAP, bounds.top, 0.001f);
        assertEquals(2f * SIZE, bounds.width(), 0.001f);
    }

    @Test
    public void nearbyMarkersDoNotOverlap() {
        List<AgentProposalMarkerGeometry.Fragment> fragments = new ArrayList<>();
        for (int i = 0; i < 3; i++) fragments.add(fragment("p" + i, 0, 100f, 300f));

        List<AgentProposalMarkerGeometry.Marker> markers = layout(fragments, SIZE);

        assertEquals(3, markers.size());
        for (int i = 0; i < markers.size(); i++) {
            for (int j = i + 1; j < markers.size(); j++) {
                AgentProposalMarkerGeometry.Bounds a = markers.get(i).bounds;
                AgentProposalMarkerGeometry.Bounds b = markers.get(j).bounds;
                boolean separated = a.right + AgentProposalMarkerGeometry.MARKER_GAP <= b.left
                        || b.right + AgentProposalMarkerGeometry.MARKER_GAP <= a.left
                        || a.bottom + AgentProposalMarkerGeometry.MARKER_GAP <= b.top
                        || b.bottom + AgentProposalMarkerGeometry.MARKER_GAP <= a.top;
                assertTrue("Markers " + i + " and " + j + " overlap", separated);
            }
        }
    }

    @Test
    public void overlapResolutionStaysInsideThePage() {
        // Markers crowded against the right edge cannot be pushed any further.
        List<AgentProposalMarkerGeometry.Fragment> fragments = new ArrayList<>();
        for (int i = 0; i < 4; i++) fragments.add(fragment("p" + i, 0, 595f, 300f));

        List<AgentProposalMarkerGeometry.Marker> markers = layout(fragments, SIZE);

        assertEquals(4, markers.size());
        for (AgentProposalMarkerGeometry.Marker marker : markers) {
            assertTrue("Still inside the page: " + marker.bounds.right,
                    marker.bounds.right <= PAGE.right);
        }
    }

    @Test
    public void placementIsStableAcrossInputOrder() {
        AgentProposalMarkerGeometry.Fragment first = fragment("a", 0, 100f, 300f);
        AgentProposalMarkerGeometry.Fragment second = fragment("b", 0, 100f, 300f);

        List<AgentProposalMarkerGeometry.Marker> forward =
                layout(Arrays.asList(first, second), SIZE);
        List<AgentProposalMarkerGeometry.Marker> reversed =
                layout(Arrays.asList(second, first), SIZE);

        Map<String, AgentProposalMarkerGeometry.Bounds> byId = new LinkedHashMap<>();
        for (AgentProposalMarkerGeometry.Marker marker : forward) byId.put(marker.proposalId, marker.bounds);
        for (AgentProposalMarkerGeometry.Marker marker : reversed) {
            AgentProposalMarkerGeometry.Bounds expected = byId.get(marker.proposalId);
            assertEquals(expected.left, marker.bounds.left, 0f);
            assertEquals(expected.top, marker.bounds.top, 0f);
        }
    }

    @Test
    public void rejectsPagesTooSmallForAMarker() {
        List<AgentProposalMarkerGeometry.Marker> markers = AgentProposalMarkerGeometry.layout(
                Arrays.asList(fragment("p", 0, 5f, 5f)),
                pageMap(rect(0f, 0f, 10f, 10f)), widths(10f), sizes(SIZE));

        assertTrue(markers.isEmpty());
    }

    @Test
    public void hitTestExpandsASmallMarkerToTheTouchTarget() {
        List<AgentProposalMarkerGeometry.Marker> markers =
                layout(Arrays.asList(fragment("p", 0, 300f, 400f)), SIZE);
        AgentProposalMarkerGeometry.Bounds bounds = markers.get(0).bounds;

        // A 48px target around a 20px marker reaches 14px beyond each edge.
        assertEquals("p", AgentProposalMarkerGeometry.hitTest(
                markers, bounds.left - 13f, bounds.centerY(), 48f));
        assertNull("Beyond the touch target", AgentProposalMarkerGeometry.hitTest(
                markers, bounds.left - 15f, bounds.centerY(), 48f));
        assertNull(AgentProposalMarkerGeometry.hitTest(
                markers, bounds.centerX(), bounds.bottom + 15f, 48f));
    }

    @Test
    public void hitTestUsesTheNearerOfTwoCloseMarkers() {
        List<AgentProposalMarkerGeometry.Marker> markers = layout(Arrays.asList(
                fragment("upper", 0, 100f, 300f),
                fragment("lower", 0, 100f, 700f)), SIZE);

        for (AgentProposalMarkerGeometry.Marker marker : markers) {
            assertEquals(marker.proposalId, AgentProposalMarkerGeometry.hitTest(
                    markers, marker.bounds.centerX(), marker.bounds.centerY(), 48f));
        }
    }

    @Test
    public void hitTestNeverShrinksBelowTheDrawnMarkerWhenZoomedIn() {
        List<AgentProposalMarkerGeometry.Marker> markers =
                layout(Arrays.asList(fragment("p", 0, 300f, 400f)), SIZE);
        AgentProposalMarkerGeometry.Bounds bounds = markers.get(0).bounds;

        assertEquals("p", AgentProposalMarkerGeometry.hitTest(
                markers, bounds.right - 0.5f, bounds.centerY(), 4f));
        assertNull(AgentProposalMarkerGeometry.hitTest(
                markers, bounds.right + 1f, bounds.centerY(), 4f));
    }

    @Test
    public void hitTestIgnoresDegenerateInput() {
        List<AgentProposalMarkerGeometry.Marker> markers =
                layout(Arrays.asList(fragment("p", 0, 300f, 400f)), SIZE);

        assertNull(AgentProposalMarkerGeometry.hitTest(markers, Float.NaN, 0f, 48f));
        assertNull(AgentProposalMarkerGeometry.hitTest(null, 0f, 0f, 48f));
    }

    @Test
    public void tappingAMarkerHitsItEvenWhenANeighboursTouchTargetOverlapsIt() {
        // Two markers 4px apart: their 48px touch targets overlap each other, so
        // a first-match search would let the left marker steal taps on the right.
        List<AgentProposalMarkerGeometry.Marker> markers = layout(Arrays.asList(
                fragment("left", 0, 100f, 300f),
                fragment("right", 0, 100f, 300f)), SIZE);
        assertEquals(2, markers.size());
        AgentProposalMarkerGeometry.Bounds right = markers.get(1).bounds;

        // The tap is inside the right marker's drawn box, so it belongs to it.
        assertEquals("right", AgentProposalMarkerGeometry.hitTest(
                markers, right.centerX(), right.centerY(), 48f));
        assertEquals("right", AgentProposalMarkerGeometry.hitTest(
                markers, right.left + 0.5f, right.centerY(), 48f));
    }

    @Test
    public void everyDrawnMarkerStaysTappableWhenTouchTargetsOverlap() {
        // Worst case for ambiguity: markers packed on one row, then a second row.
        List<AgentProposalMarkerGeometry.Fragment> fragments = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            fragments.add(fragment("row0-" + i, 0, 100f + i * 2f, 300f));
            fragments.add(fragment("row1-" + i, 0, 100f + i * 2f, 302f));
        }

        List<AgentProposalMarkerGeometry.Marker> markers = layout(fragments, SIZE);

        assertEquals(16, markers.size());
        for (AgentProposalMarkerGeometry.Marker marker : markers) {
            assertEquals("Tap on " + marker.proposalId + " at " + marker.bounds,
                    marker.proposalId, AgentProposalMarkerGeometry.hitTest(
                            markers, marker.bounds.centerX(), marker.bounds.centerY(), 48f));
        }
    }

    @Test
    public void markersCrowdingTheRightEdgeStayDistinctAndOnThePage() {
        // Regression: moving right only. At the right edge the nudge has nowhere
        // to go, so every marker used to keep identical bounds and only the first
        // stayed reachable.
        List<AgentProposalMarkerGeometry.Fragment> fragments = new ArrayList<>();
        for (int i = 0; i < 4; i++) fragments.add(fragment("p" + i, 0, 595f, 300f));

        List<AgentProposalMarkerGeometry.Marker> markers = layout(fragments, SIZE);

        assertEquals(4, markers.size());
        Set<String> positions = new HashSet<>();
        for (AgentProposalMarkerGeometry.Marker marker : markers) {
            assertTrue("Inside the page: " + marker.bounds,
                    marker.bounds.left >= PAGE.left && marker.bounds.right <= PAGE.right);
            assertTrue("Inside the page: " + marker.bounds,
                    marker.bounds.top >= PAGE.top && marker.bounds.bottom <= PAGE.bottom);
            assertTrue("Distinct slot for " + marker.proposalId,
                    positions.add(marker.bounds.left + "," + marker.bounds.top));
        }
        for (AgentProposalMarkerGeometry.Marker marker : markers) {
            assertEquals("Reachable: " + marker.proposalId, marker.proposalId,
                    AgentProposalMarkerGeometry.hitTest(
                            markers, marker.bounds.centerX(), marker.bounds.centerY(), 48f));
        }
    }

    @Test
    public void crowdedMarkersNeverOverlapOrLeaveThePage() {
        // Many proposals sharing one anchor, on a page of ordinary proportions.
        List<AgentProposalMarkerGeometry.Fragment> fragments = new ArrayList<>();
        for (int i = 0; i < 20; i++) fragments.add(fragment("p" + i, 0, 300f, 300f));

        List<AgentProposalMarkerGeometry.Marker> markers = layout(fragments, SIZE);

        assertEquals(20, markers.size());
        for (int i = 0; i < markers.size(); i++) {
            AgentProposalMarkerGeometry.Bounds first = markers.get(i).bounds;
            assertTrue("Inside the page: " + first,
                    first.left >= PAGE.left && first.right <= PAGE.right
                            && first.top >= PAGE.top && first.bottom <= PAGE.bottom);
            for (int j = i + 1; j < markers.size(); j++) {
                AgentProposalMarkerGeometry.Bounds second = markers.get(j).bounds;
                boolean overlaps = first.left < second.right && first.right > second.left
                        && first.top < second.bottom && first.bottom > second.top;
                assertFalse("No overlap between " + markers.get(i).proposalId + " and "
                        + markers.get(j).proposalId, overlaps);
            }
        }
    }

    @Test
    public void ignoresFragmentsWithoutUsableGeometry() {
        List<AgentProposalMarkerGeometry.Fragment> fragments = Arrays.asList(
                fragment("p", 0, Float.NaN, 100f),
                fragment("p", -1, 50f, 50f),
                fragment("p", 0, 250f, 260f));

        List<AgentProposalMarkerGeometry.Marker> markers = layout(fragments, SIZE);

        assertEquals(1, markers.size());
        assertEquals(250f, markers.get(0).bounds.left, 0.001f);
    }

    @Test
    public void ignoresDegenerateMarkerSizes() {
        assertTrue(layout(Arrays.asList(fragment("p", 0, 100f, 200f)), 0f).isEmpty());
        assertTrue(layout(Arrays.asList(fragment("p", 0, 100f, 200f)), Float.NaN).isEmpty());
    }

    @Test
    public void anchorsAreEmptyForMissingInput() {
        assertTrue(AgentProposalMarkerGeometry.anchorFragments(null).isEmpty());
        assertTrue(AgentProposalMarkerGeometry.anchorFragments(new ArrayList<>()).isEmpty());
        assertFalse(AgentProposalMarkerGeometry.anchorFragments(
                Arrays.asList(fragment("p", 0, 1f, 1f))).isEmpty());
    }
}