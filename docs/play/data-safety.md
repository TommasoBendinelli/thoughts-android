# Play Console — Data safety answers (Thoughts, org.tbend.thoughts)

Draft answers for a Play listing of this app, written for the first internal-testing release (versionCode 26102101).
Replace `[CONTACT EMAIL]` with the publisher's policy contact.
Each answer is grounded in the code. Paths are relative to `app/src/main/java/com/gitlab/mudlej/MjPdfReader/`.

## How to read "collected" for this app

Play counts data as *collected* when the app sends it off the device. Thoughts sends user data only to:

1. **The user's own Thoughts server.** The user enters its address and a device token in
   Settings → Synchronization → "Server" (`ui/settings/SettingsPreferenceFactory.kt`). No address is built in.
   The developer cannot see this data.
2. **OpenRouter.** Used only for Read aloud, with the user's own key (`data/speech/OpenRouterSpeechClient.kt`).
3. **Paper import sites** (arXiv, OpenReview, …). These are downloads the user requests. Only the URL is sent.

The app links no Google Maps, Places or other Play services library and has no location permission (the place picker
for comments was removed).

**Recommendation: answer conservatively.**

- Declare the data types below as *collected* and *not shared*.
- Mark them all *optional*: nothing leaves the phone until the user configures their own server or uses one of the optional features.
- Some developers treat self-hosted sync as "not collected", because the developer has no access. Declaring it is the safer reading, and Play rejects under-declaration, not over-declaration.

## Section 1 — Data collection and security

| Question | Answer | Basis |
|---|---|---|
| Does your app collect or share any of the required user data types? | **Yes** | Sync to the user's server; Read aloud. |
| Is all of the user data collected by your app encrypted in transit? | **Yes** | Server addresses must be `https://` (`data/sync/ThoughtsServerAddress.kt` `normalize`). OpenRouter and import requests use HTTPS. The manifest's `usesCleartextTraffic="true"` is used by the loopback `http://127.0.0.1` server for offline HTML attachments (`ui/html/OfflineHtmlServer.kt`), where nothing leaves the device, and by two optional features that do nothing until the user sets an address: the glasses camera link, which is unauthenticated plain HTTP to a camera on the local network (`data/glasses/GlassesCamera.kt`, see the README), and a recall-prediction service, which is accepted over plain HTTP only on the device itself or through an active VPN (`data/review/ReviewRecallPrediction.kt`). If you ship either feature configured, answer this question for your build accordingly. |
| Do you provide a way for users to request that their data is deleted? | **Yes** | In the app: delete comments, notes, review cards and agent notes, "Delete from Library" (applied at the next Sync), clear reading history, and Settings → "Server" → Disconnect (wipes the token and the Keystore key). The server data belongs to the user's own server, which they control. Policy contact: [CONTACT EMAIL]. |

## Section 2 — Data types

For every type below:

- **Shared:** No. Transfers are user-initiated and go to the user's own server, or to the service the user chose for that feature.
- **Processed ephemerally:** No.
- **Required or optional:** Optional.

| Category → type | Collected? | Purposes to tick | What exactly / where |
|---|---|---|---|
| Location → Approximate / Precise location | No | — | The app has no location permission and never reads the phone's location. A comment made with an earlier version can carry a place (a name and coordinates the user confirmed then, `data/annotation/CommentMetadata.kt` `CommentLocation`); it is kept and synced with the comment as part of its content, and can only be removed, not added or changed. |
| **Personal info → Name** | Yes | App functionality | The "Comment author name" (Settings → Synchronization; default "Thoughts user") is written into comments and notes that are synced (`data/annotation/CommentAuthor.kt`). |
| Personal info → Email, address, phone, IDs, other | No | — | — |
| **Photos and videos → Photos** | Yes | App functionality | Note attachments the user picks (`ui/notes/NoteAttachmentsActivity.kt`), uploaded on Sync. |
| **Photos and videos → Videos** | Yes | App functionality | Same; attachments may be videos (up to 100 MB). |
| Audio → Voice or sound recordings / Music / Other | No | — | There is no microphone permission and no recording. Read aloud *downloads* audio. |
| **Files and docs** | Yes | App functionality | PDFs, HTML and LaTeX/Markdown sources the user adds to their Library, synced to their server (`data/sync/FedoraRepository.kt`). Text the user plays with Read aloud is sent to OpenRouter only with the user's own key. |
| **App activity → Other user-generated content** | Yes | App functionality | Comments, highlights, notes, review cards, proposal decisions and source edits, synced to the user's server. |
| **App activity → Other actions** | Yes | App functionality | Review ratings and review events (`data/review/ReviewStore.kt`), synced to the user's server. |
| App activity → App interactions, in-app search history, installed apps | No | — | The usage tracker (`data/usage/AppUsageTracker.kt`) and search history stay on the device. The `<queries>` entries only check for ChatGPT, text-processing apps and TTS engines. |
| Web browsing | No | — | — |
| App info and performance → Crash logs / Diagnostics / Other | **No** | — | ACRA crash reporting starts only when a crash endpoint is compiled in (`App.kt` `crashReportingConfigured`), and this build has none (`local.properties` AU is blank). Sync diagnostics are only copied to the clipboard when the user asks (`ui/sync/DiagnosticsClipboard.kt`). **If you ever add an ACRA endpoint, change this to Crash logs + Diagnostics, optional (the user confirms each report in a dialog).** |
| Device or other IDs | No | — | No ANDROID_ID, advertising ID or serial is used. The device token is a server credential that the user pastes in, not a device identifier. |
| Financial, Health and fitness, Messages, Contacts, Calendar | No | — | — |

## Section 3 — Security practices / badges

- **Data encrypted in transit:** Yes.
- **Users can request data deletion:** Yes, in-app deletion, and the user controls the server.
- **Independent security review:** No.
- **Committed to Play Families policy:** Not applicable; the app is not for children.

## Notes for later releases

- `allowBackup="true"`. Two items are not excluded from Google backup:
  - `thoughts_server.xml`: the server URL in plain text plus the token ciphertext. The Keystore key does not travel, so the token itself is unusable after a restore.
  - the Room database, which holds PDF passwords in plain text (`data/entity/PdfRecord.kt`).

  This does not change the Data safety answers, since Google backup is not developer collection. Consider excluding `sharedpref:thoughts_server.xml` and the passwords.
