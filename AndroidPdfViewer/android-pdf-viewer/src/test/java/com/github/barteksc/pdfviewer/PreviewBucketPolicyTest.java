// Written by Mudlej. License is GPLv3.
package com.github.barteksc.pdfviewer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PreviewBucketPolicyTest {

    @Test
    public void normalTier1080_usesFullFittedWidth() {
        assertEquals(1080, PreviewBucketPolicy.bucketFor(1080f, false));
    }

    @Test
    public void lowTier1080_usesLowRatio() {
        assertEquals(810, PreviewBucketPolicy.bucketFor(1080f, true));
    }

    @Test
    public void tinyPage_clampsBetweenOneAndFitted() {
        int fitted = 3;
        int bucket = PreviewBucketPolicy.bucketFor(fitted, false);
        assertTrue(bucket >= 1);
        assertTrue(bucket <= fitted);
        assertEquals(fitted, bucket);
    }

    @Test
    public void hugeFit_clampsToMax() {
        assertEquals(1440, PreviewBucketPolicy.bucketFor(4000f, false));
    }

    @Test
    public void lowTier600_respectsAbsoluteFloor() {
        assertEquals(450, PreviewBucketPolicy.bucketFor(600f, true));
    }
}
