// Written for Thoughts. License is GPLv3.

package com.github.barteksc.pdfviewer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class LinkDestinationGeometryTest {

    @Test
    public void resolve_preservesExplicitDestinationPoint() {
        LinkDestinationGeometry.Anchor anchor = LinkDestinationGeometry.resolve(
                70.866f, 437.086f, 612f, 792f);

        assertNotNull(anchor);
        assertEquals(70.866f, anchor.x, 0.0001f);
        assertEquals(437.086f, anchor.y, 0.0001f);
    }

    @Test
    public void resolve_centersMissingAxisForFitDestinations() {
        LinkDestinationGeometry.Anchor horizontal = LinkDestinationGeometry.resolve(
                Float.NaN, 640f, 612f, 792f);
        LinkDestinationGeometry.Anchor vertical = LinkDestinationGeometry.resolve(
                120f, Float.NaN, 612f, 792f);

        assertNotNull(horizontal);
        assertEquals(306f, horizontal.x, 0.0001f);
        assertEquals(640f, horizontal.y, 0.0001f);
        assertNotNull(vertical);
        assertEquals(120f, vertical.x, 0.0001f);
        assertEquals(396f, vertical.y, 0.0001f);
    }

    @Test
    public void resolve_clampsCoordinatesToPage() {
        LinkDestinationGeometry.Anchor anchor = LinkDestinationGeometry.resolve(
                -20f, 900f, 612f, 792f);

        assertNotNull(anchor);
        assertEquals(0f, anchor.x, 0.0001f);
        assertEquals(792f, anchor.y, 0.0001f);
    }

    @Test
    public void resolve_rejectsPageOnlyAndInvalidDestinations() {
        assertNull(LinkDestinationGeometry.resolve(
                Float.NaN, Float.NaN, 612f, 792f));
        assertNull(LinkDestinationGeometry.resolve(
                72f, 500f, 0f, 792f));
    }
}
