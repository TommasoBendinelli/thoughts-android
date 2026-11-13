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
package com.github.barteksc.pdfviewer.util;

public class Constants {

    public static boolean DEBUG_MODE = false;

    public static final float PREVIEW_BUCKET_RATIO_NORMAL = 1.0f;

    public static final float PREVIEW_BUCKET_RATIO_LOW = 0.75f;

    public static final int PREVIEW_BUCKET_MIN_PX = 240;

    public static final int PREVIEW_BUCKET_MAX_PX = 1440;

    /**
     * Disk budget of the shared preview directory. The reader trims the directory to this size
     * whenever it attaches a document, so background warmers must stay well below it.
     */
    public static final long PREVIEW_DISK_BUDGET_BYTES = 96L * 1024L * 1024L;

    /**
     * WebP "quality" of disk previews. For lossless WebP (API 30+) it is the encoder effort:
     * 100 was ~30x slower than 50 for ~10% smaller files (library sample, 1080 px, 2026-10-21:
     * 66 KB / 4.0 s vs 74 KB / 0.1 s per page on x86; lossy quality 80 gave 101 KB). Before
     * API 30 only lossy WebP exists and this is its quality.
     */
    public static final int PREVIEW_WEBP_QUALITY = 50;

    /**
     * The size of the rendered parts (default 256)
     * Tinier : a little bit slower to have the whole page rendered but more reactive.
     * Bigger : user will have to wait longer to have the first visual results
     */
    public static float PART_SIZE = 256;

    /** Part of document above and below screen that should be preloaded, in dp */
    public static int PRELOAD_OFFSET = 20;

    public static class Cache {

        /** The size of the cache (number of bitmaps kept) */
        public static int CACHE_SIZE = 120;

        /** Viewport snapshots kept at once: one previous per page, this many overall */
        public static int SNAPSHOTS_CACHE_SIZE = 8;
    }

    public static class Pinch {

        public static float MAXIMUM_ZOOM = 100;

        public static float MINIMUM_ZOOM = 0.5f;

        /** Zoom ratio change during a pinch gesture that triggers a re-render of visible parts */
        public static float RENDER_DURING_SCALE_STEP = 1.25f;

    }

}
