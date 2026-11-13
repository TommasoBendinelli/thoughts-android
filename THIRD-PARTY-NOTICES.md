# Third-party notices

Thoughts for Android is free software under the GNU General Public License v3 (`LICENSE`). It is a fork of
MJ PDF and bundles, or restores at build time, the third-party components below. Their own licence files stay
where upstream put them; licence texts that were missing are in `LICENSES/`.

## Application base

| Component | Version | Licence (SPDX) | Upstream | Path |
|---|---|---|---|---|
| MJ PDF (Mudlej) | `V3.0.0` (`17b0dd93`) | GPL-3.0-or-later | https://gitlab.com/mudlej_android/mj_pdf_reader | the whole tree; `LICENSE` |
| PDF Viewer Plus (Gokul Swaminathan), MJ PDF's v1/v2 base | — | MIT | https://github.com/JavaCafe01 | earlier code in `app/`; `OLD_LICENSE` |
| AndroidPdfViewer (Bartosz Schiller), as modified by MJ PDF and Thoughts | 3.2.0-beta.1 base | Apache-2.0 | https://github.com/barteksc/AndroidPdfViewer | `AndroidPdfViewer/`; `AndroidPdfViewer/LICENSE` |
| PdfiumAndroid (Bekket McClane, Bartosz Schiller), as modified by MJ PDF and Thoughts | 1.9.0 base | Apache-2.0 | https://github.com/barteksc/PdfiumAndroid | `PdfiumAndroid/` (Java and the JNI bridge in `src/main/jni/src`); `PdfiumAndroid/LICENSE` |
| SyncTeX parser (Jérôme Laurens), unmodified | commit `04cf8e3e` | MIT | https://github.com/jlaurens/synctex | `PdfiumAndroid/src/main/jni/synctex/`; its `LICENSE` and `UPSTREAM.md` |

## Prebuilt native libraries

`PdfiumAndroid/prebuilt/{arm64-v8a,armeabi-v7a,x86,x86_64}/` holds prebuilt shared libraries (checked against
`PdfiumAndroid/prebuilt/SHA256SUMS`). `build_dependencies/install_patched_libs.py` copies them to the ignored
`PdfiumAndroid/src/main/libs/` and builds `libjniPdfium.so` from `PdfiumAndroid/src/main/jni` (PdfiumAndroid's
JNI bridge plus SyncTeX). Alternatively `build_dependencies/bootstrap_release_libs.py` downloads the official MJ PDF
v3.0.0 universal release APK from the MJ PDF GitLab project (`https://gitlab.com/-/project/38392439/uploads/…/mj-pdf-v3.0.0-universal-release.apk`,
pinned by SHA-256) and extracts the same five libraries unpatched; `build_dependencies/pdfium_source.py` rebuilds
PDFium from source.

| Library | Component | Version | Licence (SPDX) | Upstream | Licence text |
|---|---|---|---|---|---|
| `libmodpdfium.so` | PDFium, patched (`build_dependencies/patches/*.patch`: comment replies, icon names, shared library) | branch `chromium/7920` | BSD-3-Clause AND Apache-2.0 | https://pdfium.googlesource.com/pdfium | `PdfiumAndroid/src/main/jni/LICENSE` (PDFium section) |
| (inside `libmodpdfium.so`) | Anti-Grain Geometry 2.3 | 2.3 | AGG 2.3 permissive licence (no SPDX id) | PDFium `third_party/agg23` | `PdfiumAndroid/src/main/jni/LICENSE` |
| (inside `libmodpdfium.so`) | Little CMS | 2.x | MIT | PDFium `third_party/lcms` | `PdfiumAndroid/src/main/jni/LICENSE` |
| (inside `libmodpdfium.so`) | OpenJPEG | 2.x | BSD-2-Clause | PDFium `third_party/libopenjpeg` | `LICENSES/OpenJPEG-BSD-2-Clause.txt` |
| (inside `libmodpdfium.so`) | libjpeg-turbo / IJG libjpeg | — | IJG AND BSD-3-Clause AND Zlib | PDFium `third_party/libjpeg_turbo` | `PdfiumAndroid/src/main/jni/LICENSE` |
| (inside `libmodpdfium.so`) | zlib | — | Zlib | Chromium `third_party/zlib` | `PdfiumAndroid/src/main/jni/LICENSE` |
| (inside `libmodpdfium.so`) | ICU | — | Unicode-3.0 (older releases: ICU) | PDFium `third_party/icu` | `PdfiumAndroid/src/main/jni/LICENSE` |
| (inside `libmodpdfium.so`, if linked) | LibTIFF, abseil-cpp, fast_float, simdutf, dragonbox, bigint | as pinned by PDFium's `DEPS` | libtiff; Apache-2.0; Apache-2.0 OR MIT OR BSL-1.0; Apache-2.0 OR MIT; Apache-2.0 WITH LLVM-exception OR BSL-1.0; public domain | PDFium `third_party/` | LibTIFF: `PdfiumAndroid/src/main/jni/LICENSE`; others: their files in PDFium's `third_party/` |
| `libmodft2.so` | FreeType | — | FTL OR GPL-2.0-or-later (used under FTL) | https://freetype.org | `PdfiumAndroid/src/main/jni/LICENSE` (FreeType section) |
| `libmodpng.so` | libpng | 1.6.58 | libpng-2.0 | http://www.libpng.org/pub/png/libpng.html | `PdfiumAndroid/src/main/jni/LICENSE` (libpng section) |
| `libc++_shared.so` | LLVM libc++ (Android NDK) | NDK r27 toolchain | Apache-2.0 WITH LLVM-exception (legacy parts: NCSA) | https://llvm.org | `LICENSES/LLVM-Apache-2.0-WITH-LLVM-exception.txt` |
| `libjniPdfium.so` (built locally, not committed) | PdfiumAndroid JNI bridge + SyncTeX | — | Apache-2.0 AND MIT | see above | `PdfiumAndroid/LICENSE`, `PdfiumAndroid/src/main/jni/synctex/LICENSE` |

`PdfiumAndroid/src/main/jni/include/` holds PDFium's public headers (BSD-3-Clause AND Apache-2.0) and two Android
utility headers, `utils/Errors.h` and `utils/Mutex.h` (Android Open Source Project, Apache-2.0).

## Fonts, icons and images

| Item | Licence (SPDX) | Source | Path |
|---|---|---|---|
| Roboto Regular and Medium 2.001 (Google) | Apache-2.0 | https://github.com/googlefonts/roboto | `app/src/main/res/font/roboto_regular.ttf`, `roboto_medium.ttf` |
| Vector icons in `app/src/main/res/drawable*`: MJ PDF's and Thoughts' own; many follow or copy Google's Material Design icons | Apache-2.0 (Material icons); GPL-3.0-or-later (the rest) | https://github.com/google/material-design-icons | `app/src/main/res/drawable*/` |
| Thoughts icon and launcher images | GPL-3.0-or-later (this project) | `branding/` | `branding/*.svg`, `app/src/main/res/mipmap-*` |
| MJ PDF logo, screenshots and store images | GPL-3.0-or-later (MJ PDF) | MJ PDF | `assets/logo/`, `assets/screenshots/`, `fastlane/metadata/android/en-US/images/` |
| README buttons (IzzyOnDroid, GitLab, GitHub badges) from MJ PDF's README | trademarks of their owners; used only to link to those sites | MJ PDF | `assets/buttons/` |

## JavaScript and web assets

There is no third-party JavaScript in the app. `app/src/main/assets/proposal-review/` is Thoughts' own proposal
reviewer (MIT, its `LICENSE.txt`; origin in its `PROVENANCE.md`) and `app/src/main/assets/html-viewer/` is the HTML
reader shared with the Thoughts desktop app (`html-viewer.json` names its source commit), under this project's licence.

## Build tooling in the tree

| Item | Licence (SPDX) | Upstream | Path |
|---|---|---|---|
| Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle-wrapper.jar`) | Apache-2.0 | https://gradle.org | `gradlew*`, `gradle/wrapper/`, `PdfiumAndroid/gradlew*` and `gradle/wrapper/`, `AndroidPdfViewer/gradlew*` and `gradle/wrapper/` |

## Dependencies fetched at build time (not in this repository)

Gradle downloads these and packages them into the APK; their licences travel in their artifacts. The main ones:
AndroidX libraries, Material Components, Room, WorkManager, Kotlin standard library and coroutines (Apache-2.0);
Gson (Apache-2.0); jsoup (MIT); OkHttp via the Thoughts server SDK (Apache-2.0); zstd-jni (BSD-2-Clause);
ACRA (Apache-2.0); core library desugaring `desugar_jdk_libs` (GPL-2.0-only WITH Classpath-exception-2.0). The
dictionary database is built on demand from WordNet 3.1 (WordNet licence) by `build_dictionary.py` and is not
committed.

Google Play services (Maps, Location) and the Places SDK are no longer used: the place picker for comments was
removed together with those dependencies, the secrets Gradle plugin and the location permissions. Earlier versions
of this file listed them as proprietary libraries linked from a GPL-3.0 app; that concern no longer applies, and
`./gradlew :app:dependencies --configuration releaseRuntimeClasspath` shows no `com.google.android.gms` or
`com.google.android.libraries.places` artifact.
