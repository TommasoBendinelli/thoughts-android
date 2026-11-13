# Sensitive permissions: code use, Play declarations, recommendations

Paths are relative to `app/src/main/java/com/gitlab/mudlej/MjPdfReader/`. Manifest: `app/src/main/AndroidManifest.xml`.
**Nothing has been dropped yet.** The recommendations are for a later Play-only build, for example a `play` product flavor or a manifest overlay using `tools:node="remove"`. That way the phone's debug build keeps every permission.

---

## MANAGE_EXTERNAL_STORAGE (All files access)

This is the high-risk one: it needs the **Permissions declaration form** in Play Console.

- **Code:**
  - `core/PermissionManager.kt:39-66` shows a rationale ("Allow Access to All Files … Your files never leave your device"), then opens `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`.
  - Its users are `ui/home/StorageManager.kt` (device-wide PDF scan for Library/Recent), `ui/reader/annotation/AnnotationSaveController.kt` (save annotations into the original file) and `ui/home/HomeActivity.kt` (the "Allow Access" button, LOCKED items).
  - READ_EXTERNAL_STORAGE and WRITE_EXTERNAL_STORAGE (maxSdk 29) are the pre-Android-11 equivalents.
- **When it is asked:** never at startup; only when the user taps "Allow Access" or a locked item.
- **Without it:** the app works. PDFs open through the system picker or share/VIEW intents (content URIs), and the Library/server documents live in app storage. What is lost: the automatic list of every PDF on the device, and saving into the original file.
- **Declaration text (core use: document management / on-device file search):**
  > Thoughts is a PDF reader and document manager. With the user's permission it lists every PDF stored on the device in its Library and Recent views, opens them from their original folders, and saves the user's highlights, comments and signatures back into the same file in place. The Storage Access Framework cannot provide a device-wide PDF index or in-place saving to files in arbitrary folders without asking the user to pick each file. The permission is requested only when the user taps "Allow access"; files never leave the device unless the user adds them to their own synced Library.
- **Recommendation: drop it for Play** (high priority).
  - Google routinely rejects PDF readers for this permission, even when internal testing goes through.
  - Replacement for the Play build: open via SAF (`ACTION_OPEN_DOCUMENT` with persisted URI permissions, which the reader already handles) plus the app-private Library.
  - The phone build keeps it.

## FOREGROUND_SERVICE_DATA_SYNC

- **Code:**
  - `data/sync/ThoughtsSyncForegroundService.kt:18-24` and the WorkManager `ForegroundInfo` in `data/sync/ThoughtsSyncWorker.kt:1227,1397` handle a user-started Sync.
  - `data/agentnotes/AgentStudyListenerService.kt:38-42` handles the agent-note long-poll while the user is away (time-limited; unlimited in Study mode).
  - The manifest also merges `androidx.work.impl.foreground.SystemForegroundService` with type dataSync.
- **Play "Foreground service permissions" declaration** (App content; Play may ask for a short video):
  > Data sync: when the user taps Sync, Thoughts uploads and downloads their documents, comments and notes to and from the user's own Thoughts server. Transfers can take minutes for large libraries and must continue if the user switches apps or locks the screen; a notification shows progress and can cancel. Impact if deferred: the user's explicitly requested sync would stop half-way.
- **Recommendation: keep** (core Sync). For the agent-note listener, either keep it under the same declaration or drop the listener service in a Play build together with Study mode.
  - Note that Android 15 caps dataSync foreground services at 6 h per day. `RUN_USER_INITIATED_JOBS` (below) is the modern path on API 34+.

## FOREGROUND_SERVICE_MEDIA_PLAYBACK

- **Code:** `data/speech/ReadAloudService.kt:71-76,134`. Read aloud keeps playing with the screen off; WAKE_LOCK is used in `data/speech/ReadAloudSession.kt:327-342`.
- **Declaration:**
  > Media playback: Read aloud plays a spoken version of the document the user selected, with media controls in a notification, and keeps playing when the screen turns off, like an audiobook player. It starts only when the user taps Read aloud and stops when they stop it.
- **Recommendation: keep.** Low risk; this is the standard use.

## FOREGROUND_SERVICE_SPECIAL_USE

- **Code:** `ui/html/OfflineHtmlService.kt:30`, started from `ui/html/HtmlAttachmentActivity.kt:78`. Subtype (manifest): "User-opened offline HTML attachments served exclusively to the local browser until the user closes them." The service runs a loopback HTTP server (`ui/html/OfflineHtmlServer.kt`, `127.0.0.1` only) so the browser can load an HTML attachment and its assets offline. A "Close pages" action stops it.
- **Declaration:**
  > Special use: when the user opens an HTML attachment of a document (e.g. an interactive figure), Thoughts serves it and its local assets from the phone to the user's browser on 127.0.0.1 only, until the user closes it from the notification. No other foreground service type covers a local web server; the service never starts by itself.
- **Recommendation: moderate risk.** Google scrutinizes specialUse.
  - Option 1: show HTML attachments in an in-app `WebView` with `WebViewAssetLoader`. That needs no service and no FGS; drop this for Play.
  - Option 2: keep it and expect questions.

## REQUEST_IGNORE_BATTERY_OPTIMIZATIONS

- **Code:** `ui/sync/SyncBatterySetup.kt:38-69`.
  - An explanation dialog leads to `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
  - It is offered once on first use. Since this branch, only after a server is configured (commit "Ask for the battery exemption only once a server is set up").
  - It is also offered from Settings → Synchronization → "Background sync and battery".
- **Play policy:** using the direct request intent is restricted to listed use cases. A sync app with user-initiated syncs is not clearly among them, so a rejection or warning is likely.
- **Recommendation: drop the permission for Play** and keep the feature:
  - Open `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` (the list screen), which needs no permission. The fallback `openAndroidSettings` already does exactly that.
  - On API 34+, user-initiated jobs already protect explicit Syncs.

## RUN_USER_INITIATED_JOBS

- **Code:** `data/sync/ThoughtsSyncWorker.kt:577-600` uses `JobInfo.Builder.setUserInitiated(true)` for a Sync the user pressed (API 34+).
- **Play:** a normal permission; no declaration. The job itself shows a notification.
- **Recommendation: keep.**

## Others (no action needed)

- **POST_NOTIFICATIONS:** asked at the first user-started Sync. A denial is tolerated.
- **WAKE_LOCK:** used by Read aloud and WorkManager.
- **INTERNET, ACCESS_NETWORK_STATE:** used for Sync. The merged RECEIVE_BOOT_COMPLETED comes from WorkManager. No Play services library is linked, so nothing merges location or Wi-Fi state permissions.
- **VIBRATE:** declared but **never used**; only `performHapticFeedback` is called, which needs no permission. **Drop it** (safe for both builds).

## Other Play-relevant findings (not permissions)

- `SyncCommandReceiver` (manifest, action `SYNC_NOW` / `SYNC_STATUS`) is exported for adb tooling (`tools/thoughts-mobilectl`) and guarded by `android:permission="android.permission.DUMP"`: the adb shell holds that permission, an installed app cannot be granted it by its user. The agent API's `AgentActivity` has the same guard (see `AGENT-API.md`).
- `<profileable android:shell="true"/>` is declared for debug and sandbox builds only (`app/src/debug`, `app/src/sandbox`); a release build does not carry it.
- App links (`autoVerify`) name the build's public server host and its first legacy host (Gradle properties `thoughtsPublicServerUrl`, `thoughtsLegacyServerHosts`; `thoughts.example.org` by default). Verification succeeds only for a domain that serves the matching `assetlinks.json`; elsewhere it simply fails, which is harmless.
