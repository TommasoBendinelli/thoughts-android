# Isolated Android tests

Automated instrumentation must target `org.tbend.thoughts.sandbox`, labelled **Thoughts Test**.
The normal `org.tbend.thoughts` installation contains personal data and must never be an instrumentation target.
Gradle's instrumentation build type is `sandbox`, and the custom runner refuses another target or an invocation without a run identity.
The test build uses the same application code, its own private storage, a distinct shortcut permission, no backups, and a pinned test server URL (Gradle property `thoughtsTestServerUrl`).
The normal app's package and signing identity are unchanged.

Build the test app and runner with:

```sh
./gradlew :app:assembleSandbox :app:assembleSandboxAndroidTest --no-daemon --console=plain
```

Run local service and UI tests with:

```sh
python3 tools/run-isolated-tests.py --serial DEVICE_SERIAL \
  --class com.gitlab.mudlej.MjPdfReader.data.annotation.CommentServiceInstrumentedTest,com.gitlab.mudlej.MjPdfReader.data.annotation.CommentDeletionUiTest \
  --output /tmp/thoughts-test-results
```

Run the end-to-end deletion and recovery test against the separate test database of the isolated test service with:

```sh
python3 tools/run-isolated-tests.py --serial DEVICE_SERIAL --sync \
  --server USER@TEST_SERVER_HOST --server-url https://TEST_SERVER_HOST:PORT \
  --class com.gitlab.mudlej.MjPdfReader.data.sync.IsolatedReplySyncTest \
  --output /tmp/thoughts-test-sync-results
```

`--server` is the SSH target that runs `sudo -n /usr/local/sbin/thoughts-test-environment reset|credentials|status` on the test service (or `THOUGHTS_TEST_SERVER_SSH`); `--server-url` (or `THOUGHTS_TEST_SERVER_URL`) must equal the `thoughtsTestServerUrl` the sandbox APK was built with.

The wrapper captures hashes of the normal app's outbox and comment projections before and after testing.
It clears only the sandbox app before and after the invocation, including when tests fail or the wrapper receives an interrupt.
The initial reset cleans up remnants from a process kill or a disconnected device that prevented earlier cleanup.
A failed cleanup is reported as a failed run and must not be described as complete.
With `--sync`, the wrapper resets the dedicated test database before and after the run and verifies zero operations and entities afterward.
The test environment is disposable and shared by these runs, so do not run multiple device suites against it concurrently.
A run ID appears in the report and in dummy-comment identities and text.
The test credential is provisioned through a private file and encrypted by the app; it is never passed on the instrumentation command line.

Do not call the stock AndroidJUnitRunner directly, run an old production-targeted test APK, grant the sandbox access to personal document folders, or reuse production server credentials.
Tests needing a PDF must use a generated fixture in sandbox storage.
Manual smoke checks of the normal app are separate from automated tests and must preserve personal data.
