// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.html

import android.content.res.AssetManager

/**
 * The shared HTML viewer (server repo `clients/html-viewer/`), vendored into `assets/html-viewer/`
 * by its `install-into.py`. Update by running that script; VendoredHtmlViewerTest checks the copy.
 */
object HtmlViewerAssets {
    const val PINNED_VERSION = "0.2.1"
    const val PROTOCOL = 1
    val INJECT = listOf("geometry.js", "text.js", "viewer.js")
    private const val DIR = "html-viewer"

    @Volatile private var cached: Pair<List<String>, String>? = null

    /** The scripts in inject order and the viewer stylesheet. */
    fun load(assets: AssetManager): Pair<List<String>, String> = cached ?: synchronized(this) {
        cached ?: run {
            fun read(name: String) = assets.open("$DIR/$name").use { it.readBytes().toString(Charsets.UTF_8) }
            (INJECT.map(::read) to read("viewer.css")).also { cached = it }
        }
    }
}
