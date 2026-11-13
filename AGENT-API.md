# Agent API

Thoughts exposes a small API so that an agent on the phone, or `adb`, can drive it without tapping
through screens. It lives in one file, `app/src/main/java/org/tbend/agent/AgentApi.kt` (package
`org.tbend.agent`); the app only registers its commands (`agent/AgentCommands.kt`).

## Calling it

Ask or control (answers at once, JSON; the app does not have to be on screen):

    adb shell content call --uri content://<applicationId>.agent --method <command> [--arg '<json>']
    # -> Result: Bundle[{ok=true, result={...json...}}]   or   Bundle[{ok=false, error=...}]

Open something on screen, or start playback while the app is in the background (Android lets only a
foreground caller, such as adb or an agent app on screen, open screens):

    adb shell am start -n <applicationId>/org.tbend.agent.AgentActivity --es command <command> [--es arg '<json>']

Both run the same commands; `help` lists them with their arguments and whether they need the activity:

    adb shell content call --uri content://<applicationId>.agent --method help

From another app: `contentResolver.call(Uri.parse("content://<applicationId>.agent"), command, json, null)`.
To open a screen, another app starts the alias `org.tbend.agent.AgentAppActivity` (not `AgentActivity`,
which only adb can start) in a way that tells Android who it is:

    // Android 14 and later
    val options = ActivityOptions.makeBasic().setShareIdentityEnabled(true).toBundle()
    startActivity(Intent().setClassName(pkg, "org.tbend.agent.AgentAppActivity").putExtra("command", ...), options)
    // any version: a start for a result also names the caller
    startActivityForResult(Intent().setClassName(pkg, "org.tbend.agent.AgentAppActivity").putExtra("command", ...), 0)

A start of the alias that does not name its caller runs nothing.

## Who may call

One rule for every entry: root, `adb shell`, the app itself, apps signed with the same key, and
packages the user allowed. Ordinary installed apps are refused.

- The content API checks the calling uid on every call.
- `AgentActivity` is guarded in the manifest by `android.permission.DUMP`. Android does not tell an
  activity who ran `am start`, so this entry relies on a permission that the adb shell holds and an
  ordinary app cannot be granted by its user. A caller Android does name is held to the rule above as well.
- `AgentAppActivity` has no permission; it accepts only a caller Android names, and that the rule allows.
- The Sync commands of `tools/thoughts-mobilectl` (`org.tbend.thoughts.action.SYNC_NOW` and
  `SYNC_STATUS`, a broadcast receiver) are guarded by the same permission and the same rule.

Allowing a package is done from adb only:

    adb shell content call --uri content://<applicationId>.agent --method allow --arg com.termux
    adb shell content call --uri content://<applicationId>.agent --method deny  --arg com.termux

An allowed package is pinned to its signing certificate, so that another app taking the same package
name later is refused ("not signed with the certificate that was allowed"). `allow` answers with the
list and, for each package, the pinned SHA-256 digest:

- If Thoughts can see the installed app, it is pinned at once.
- If not (Android hides most other apps from each other), the entry is pinned at that package's first
  call. Entries allowed by a version without pins are pinned the same way.
- To pin without waiting, give the digest (as `apksigner verify --print-certs` prints it):

      adb shell content call --uri content://<applicationId>.agent --method allow \
        --arg '{"package": "com.termux", "sha256": "<64 hex digits>"}'

A development grant (`adb shell pm grant <package> android.permission.DUMP`, possible only for an app
that declares that permission) lets an app past the manifest guard; it is still an action taken from adb.

## Rules for commands

- Names: lower_snake_case verbs. Arguments: one JSON object (`--arg '{"query": "pasta", "start_s": 30}'`).
  Results: JSON objects; lists under a plural key (`{"items": [...]}`).
- `help` and `status` (what the app is showing or doing now) always exist.
- Items are found by `id` or by `query` (words of the title, accents and case ignored).
- Nothing destructive or outward: no delete, rename, archive, unpair, sending, publishing or payments.
  Read and navigate; what changes data stays a tap for the user.
- Errors are messages a model can act on ("nothing called 'x'; use list").
