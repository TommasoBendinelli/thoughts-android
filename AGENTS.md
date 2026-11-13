# Thoughts for Android: contributor notes

If `AGENTS.local.md` exists at the repository root, read it too: it holds the maintainer's machine-specific instructions.

- This repository is Thoughts for Android, a fork of MJ PDF that started from upstream tag `V3.0.0` (`17b0dd932ab854adf7a09cb297d59cbfda4d2c75`). Compare or merge later upstream releases through a remote pointed at `https://gitlab.com/mudlej_android/mj_pdf_reader.git`.
- The application ID is `org.tbend.thoughts`; automated tests use `org.tbend.thoughts.sandbox` and the note shortcut is `org.tbend.thoughts.shortcut`. Keep the inherited Kotlin source namespace `com.gitlab.mudlej.MjPdfReader` so upstream merges remain reviewable; it is not the application ID.
- The app links the Thoughts server's Kotlin SDK as `:ThoughtsClient`: by default a `thoughts-server` checkout beside this one (`../thoughts-server/sdk/kotlin`), or the directory given with `-PthoughtsClientDir=/path/to/sdk/kotlin`.

## Build

```sh
python3 build_dependencies/install_patched_libs.py
./gradlew :app:assembleDebug :NoteShortcut:assembleDebug --no-daemon --console=plain
```

- The repository intentionally ignores the packaged native `.so` files under `PdfiumAndroid/src/main/libs`. After a fresh clone, `install_patched_libs.py` verifies and installs the committed patched PDFium for all four supported ABIs (`PdfiumAndroid/prebuilt`, with the standard-reply extension) and rebuilds the JNI bridge with the NDK. To rebuild PDFium itself, run `python3 build_dependencies/bootstrap_release_libs.py` (verifies the pinned official MJ PDF v3.0.0 universal APK and restores its libraries) and then `(cd build_dependencies && PYTHONPATH=.. python3 pdfium_source.py)` (a multi-gigabyte Chromium checkout). Gradle must fail the build if any required native library is absent.
- `local.properties` is ignored. It holds `sdk.dir`, the crash-reporting values `AI`, `AK` and `AU` (blank for local builds), and the build options below. A build works without the file when `ANDROID_HOME` names the SDK.
- Do not commit `local.properties`, signing material, APKs, or other build outputs.
- The debug APK is not debuggable, so it runs compiled and Home loads several times faster. When `run-as` access to the phone's data is needed, build with `-PthoughtsDebuggable=true`; data is kept when the default build is installed over it again.
- An installed build is only replaced in place by an APK signed with the same key. A Play Store, IzzyOnDroid or upstream release APK has another signature; installing it would require uninstalling the development build and could erase app data.
- After app changes, build both APKs, confirm the main APK contains the native libraries, then (on a device) open a real PDF and check for startup or native crashes.
- Cold-start and Sync timings are on logcat: tag `ThoughtsStartup` (milestones from process start, including which caller requested each Home refresh) and `ThoughtsSyncTiming` (Sync stages). `tools/thoughts-mobilectl status <operation-id>` prints a finished Sync's log and its slowest stages without a debuggable build.
- Keep the separate `org.tbend.thoughts.shortcut` package: Android SystemUI suppresses the power-button camera gesture when its resolved package is already in the foreground.
- The shortcut package must only open a fresh text note in Thoughts, focus its editor, and show the keyboard without starting voice recording or transcription.

## Build options

Values that belong to one deployment come from `-P<name>=<value>` or the ignored `local.properties` (`-P` wins). Without them a build names no real server or person, and the features that need them stay off.

| Property | Default | Effect |
|---|---|---|
| `thoughtsPublicServerUrl` | `https://thoughts.example.org` | Host of generated citation links and of the app-link intent filter; target of the legacy-address migration. A phone always syncs with the server saved in Settings. |
| `thoughtsLegacyServerHosts` | empty | Former hosts of that server, comma-separated: their links keep opening, and a phone saved with `https://<host>` moves to the public URL once. Empty: no migration. |
| `thoughtsAbTestingUrl` | empty | Card-pilot rating page for Home's A/B testing tab and Review › Mental Model. Empty: both are hidden. |
| `thoughtsTestServerUrl` | `https://thoughts-test.example.org` | The isolated test service Thoughts Test may talk to, or `http://10.0.2.2:PORT` for a hermetic server on the emulator's host. |
| `thoughtsOwnerCommentAuthor` | empty | Author name for comments on a phone saved with this build's own server while none is set in Settings. Empty: "Thoughts user". |
| `thoughtsLegacyMjPdfAuthor` | empty | Author name the old MJ PDF reader build wrote: a plain-UUID yellow note by it is attributed to that reader. Empty: rule off. |
| `thoughtsStudyCaptureFolder` | `~/glasses` | Default of the study-mode capture folder (`{mentra_folder}`). |
| `thoughtsRecallPredictorUrl`, `thoughtsRecallPredictorToken` | empty | The agent recall prediction service; without both the feature is off. |

## Unit tests

```sh
./gradlew :AndroidPdfViewer:android-pdf-viewer:testDebugUnitTest :app:testSandboxUnitTest --no-daemon --console=plain
python3 -m unittest discover -s tools/tests -p 'test_*.py'
```

Unit tests must not depend on the build options above: pass neutral values (`thoughts.example.org`, `server.example.ts.net`, `Alex Example`) to the functions under test instead of asserting the defaults.

## Commit discipline

- Commit completed, coherent changes regularly and before handing off a finished coding task.
- During longer work, create clearly labeled checkpoint commits at useful milestones rather than letting changes accumulate across tasks.
- Before committing, inspect the diff, preserve unrelated work, and exclude credentials, local configuration, signing material, and generated build outputs.
- Run checks appropriate to the change and record any incomplete validation in the commit or handoff.
- Report the commit hash, whether it was pushed, and whether the running application or server uses that code as separate facts.

## Automated test isolation

- Read `TESTING.md` before device tests.
- Instrumentation must only target `org.tbend.thoughts.sandbox` through `tools/run-isolated-tests.py`.
- Never install or execute a production-targeted instrumentation APK against `org.tbend.thoughts`.
- Use `assembleSandbox` and `assembleSandboxAndroidTest` for automated tests; `assembleDebug` remains the normal app build.
- Use run-labelled fixtures, preserve the normal app comparison evidence, and treat incomplete cleanup as a failed test run.
- Sync tests must use the dedicated isolated test service (`thoughtsTestServerUrl`) and its credentials, never production.
