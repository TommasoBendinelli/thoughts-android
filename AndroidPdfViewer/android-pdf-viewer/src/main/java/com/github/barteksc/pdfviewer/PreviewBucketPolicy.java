// Written by Mudlej. License is GPLv3.
package com.github.barteksc.pdfviewer;

import android.app.ActivityManager;
import android.content.Context;

import com.github.barteksc.pdfviewer.util.Constants;

/**
 * Chooses the preview bitmap width. The reader and the background preview warmer
 * (Thoughts' PdfWarmCache) both use this class, so a warmed preview has exactly the
 * width the reader asks for on this device.
 */
public final class PreviewBucketPolicy {

    private PreviewBucketPolicy() {
    }

    public static int bucketFor(float fittedMaxPageWidth, boolean lowTier) {
        int ceiling = Math.round(fittedMaxPageWidth);
        float ratio = lowTier ? Constants.PREVIEW_BUCKET_RATIO_LOW : Constants.PREVIEW_BUCKET_RATIO_NORMAL;
        int bucket = Math.round(ratio * fittedMaxPageWidth);
        if (bucket < Constants.PREVIEW_BUCKET_MIN_PX) {
            bucket = Constants.PREVIEW_BUCKET_MIN_PX;
        }
        if (bucket > Constants.PREVIEW_BUCKET_MAX_PX) {
            bucket = Constants.PREVIEW_BUCKET_MAX_PX;
        }
        if (bucket > ceiling) {
            bucket = ceiling;
        }
        if (bucket < 1) {
            bucket = 1;
        }
        return bucket;
    }

    /** Low-tier devices use a narrower preview bucket and no image smoothing. */
    public static boolean isLowTier(Context context) {
        if (context == null) {
            return false;
        }
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                return false;
            }
            if (am.isLowRamDevice() || am.getMemoryClass() <= 128) {
                return true;
            }
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(info);
            return info.totalMem <= (7L * 1024L * 1024L * 1024L) / 2L;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
