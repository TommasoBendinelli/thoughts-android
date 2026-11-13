# Thoughts Android repository

This repository contains Thoughts on Android and the helper tools in `tools/` that work with a Thoughts Library.

The tree derives from MJ PDF's `V3.0.0` tag (upstream commit `17b0dd932ab854adf7a09cb297d59cbfda4d2c75`). Compare or integrate a later official release through a remote pointed at the official GitLab repository, `https://gitlab.com/mudlej_android/mj_pdf_reader.git`.

## Fresh checkout

Install Android SDK 36, Android build tools 36.0.0, NDK 27.1.12297006, Java 17, and Python 3.

Create `local.properties` with the local Android SDK path, the crash-report values (`AI`, `AK`, `AU`; blank for development builds) and any build options from `AGENTS.md`.

Check out `thoughts-server` beside this repository (the app links its `sdk/kotlin`), or pass `-PthoughtsClientDir=/path/to/thoughts-server/sdk/kotlin`.

Install the patched native libraries and build the JNI bridge before the first Android build:

```bash
python3 build_dependencies/install_patched_libs.py
```

Run the same local verification used by CI:

```bash
python3 -m unittest discover -s tools/tests -p 'test_*.py'
./gradlew :AndroidPdfViewer:android-pdf-viewer:testDebugUnitTest :app:testSandboxUnitTest :app:assembleDebug :NoteShortcut:assembleDebug --no-daemon --console=plain
```

The GitHub Actions workflow (`.github/workflows/verify.yml`) runs the same checks. When the repository's debug-signing secrets and the `EXPECTED_DEBUG_CERT_SHA256` variable are set, it also verifies that the APKs carry that signature and keeps them as workflow artifacts.
