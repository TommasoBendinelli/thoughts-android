#!/usr/bin/env python3
# Written for Thoughts. License is GPLv3.
"""Run instrumentation only against the disposable Thoughts Test installation."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import sqlite3
import subprocess
import tempfile
import uuid

PACKAGE = 'org.tbend.thoughts.sandbox'
NORMAL = 'org.tbend.thoughts'
RUNNER = PACKAGE + '.test/com.gitlab.mudlej.MjPdfReader.testing.IsolatedTestRunner'
ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', required=True)
    parser.add_argument('--class', dest='classes', required=True)
    parser.add_argument('--cleanup-probe', action='store_true', help='Intentionally fail after creating a fixture to verify cleanup')
    parser.add_argument('--sync', action='store_true', help='Provision and reset the isolated test server')
    parser.add_argument('--server', default=os.environ.get('THOUGHTS_TEST_SERVER_SSH'),
                        help='SSH target of the isolated test service (USER@HOST; or THOUGHTS_TEST_SERVER_SSH); needed with --sync')
    parser.add_argument('--server-url', default=os.environ.get('THOUGHTS_TEST_SERVER_URL'),
                        help='Its https base URL, as built into the sandbox APK with -PthoughtsTestServerUrl '
                             '(or THOUGHTS_TEST_SERVER_URL); needed with --sync')
    parser.add_argument('--server-config', type=Path,
                        help='JSON {base_url, token} of a hermetic server on the emulator host '
                             '(http://10.0.2.2:PORT; build with -PthoughtsTestServerUrl); never with --sync')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    local_server = None
    if args.sync:
        assert args.server, '--sync needs --server USER@HOST (or THOUGHTS_TEST_SERVER_SSH)'
        assert args.server_url and args.server_url.startswith('https://'), \
            '--sync needs --server-url https://HOST[:PORT] (or THOUGHTS_TEST_SERVER_URL)'
    if args.server_config:
        assert not args.sync, '--server-config replaces the Fedora test environment'
        local_server = json.loads(args.server_config.read_text())
        assert re.fullmatch(r'http://10\.0\.2\.2:\d{2,5}', local_server['base_url']), local_server['base_url']
        assert local_server.get('token'), 'the server config has no token'
    args.output.mkdir(parents=True, exist_ok=True)
    lock = open(Path(tempfile.gettempdir()) / "thoughts-isolated-tests.lock", "a")
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    sdk_adb = Path.home() / 'Library/Android/sdk/platform-tools/adb'
    adb = os.environ.get('ADB') or (str(sdk_adb) if sdk_adb.is_file() else shutil.which('adb') or 'adb')
    prefix = [adb, '-s', args.serial]
    def command(*parts, **kwargs):
        return subprocess.run([*prefix, *parts], check=True, capture_output=True, **kwargs)
    def server(action):
        result = subprocess.run(['ssh', '-o', 'BatchMode=yes', args.server, 'sudo', '-n',
                                 '/usr/local/sbin/thoughts-test-environment', action], check=True, capture_output=True, text=True)
        # The server script prints service status lines before its JSON summary.
        return json.loads(result.stdout.strip().splitlines()[-1])
    def install():
        for apk in ('app/build/outputs/apk/sandbox/app-sandbox.apk',
                    'app/build/outputs/apk/androidTest/sandbox/app-sandbox-androidTest.apk'):
            command('install', '-r', str(ROOT / apk))
    def clear(reinstall=True):
        assert PACKAGE == 'org.tbend.thoughts.sandbox' and PACKAGE != NORMAL
        result = subprocess.run([*prefix, 'shell', 'pm', 'clear', PACKAGE], capture_output=True, text=True)
        if result.stdout.strip() != 'Success':
            # ColorOS refuses pm clear from adb unless "Disable permission monitoring" is on in the
            # developer options; uninstalling the sandbox and its runner removes the same data.
            assert 'CLEAR_APP_USER_DATA' in result.stdout + result.stderr, result.stdout + result.stderr
            for package in (PACKAGE + '.test', PACKAGE):
                subprocess.run([*prefix, 'uninstall', package], capture_output=True, text=True)
            installed = command('shell', 'pm', 'list', 'packages', PACKAGE, text=True).stdout.split()
            assert 'package:' + PACKAGE not in installed, 'Sandbox survived uninstall'
            if not reinstall:
                return
            install()
        names = command('shell', 'run-as', PACKAGE, 'ls', text=True).stdout.split()
        assert 'databases' not in names, 'Sandbox database survived pm clear'
    def production_snapshot():
        # Hash logical records rather than SQLite/WAL byte layouts, which can change on reads.
        with tempfile.TemporaryDirectory() as temporary:
            db = Path(temporary) / 'app.db'
            for suffix in ('', '-wal'):
                result = subprocess.run([*prefix, 'exec-out', 'run-as', NORMAL, 'cat', 'databases/app-db.db' + suffix], capture_output=True)
                if result.returncode == 0:
                    Path(str(db) + suffix).write_bytes(result.stdout)
                elif not suffix:
                    raise RuntimeError('Cannot capture the normal app database')
            with sqlite3.connect(db) as connection:
                digest = {}
                for table in ('ThoughtsOutbox', 'ThoughtsLocalCommentState', 'ThoughtsProjection'):
                    exists = connection.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (table,)).fetchone()
                    if not exists:
                        digest[table] = {'present': False}
                        continue
                    rows = sorted(connection.execute('SELECT * FROM ' + table).fetchall(), key=repr)
                    digest[table] = {'rows': len(rows), 'sha256': hashlib.sha256(repr(rows).encode()).hexdigest()}
                return digest
    run_id = str(uuid.uuid4())
    before = production_snapshot()
    (args.output / 'production-before.json').write_text(json.dumps(before, indent=2))
    report = {'run_id': run_id, 'package': PACKAGE, 'classes': args.classes, 'sync': args.sync,
              'local_server': local_server['base_url'] if local_server else None}
    def interrupted(signum, frame):
        raise KeyboardInterrupt('Test run interrupted')
    signal.signal(signal.SIGTERM, interrupted)
    try:
        install()
        clear()  # Also recovers fixtures from an interrupted earlier invocation.
        if args.sync:
            server('reset')
            config = server('credentials')
            assert config['base_url'] == args.server_url.rstrip('/'), config['base_url']
            command('shell', 'run-as', PACKAGE, 'mkdir', '-p', 'files')
            command('shell', 'run-as', PACKAGE, 'sh', '-c', "'cat > files/test-server.json'", input=json.dumps(config).encode())
        if local_server is not None:
            command('shell', 'run-as', PACKAGE, 'mkdir', '-p', 'files')
            command('shell', 'run-as', PACKAGE, 'sh', '-c', "'cat > files/test-server.json'",
                    input=json.dumps({'base_url': local_server['base_url'], 'token': local_server['token']}).encode())
        result = command('shell', 'am', 'instrument', '-w', '-r', '-e', 'isolation_run_id', run_id,
                         '-e', 'class', args.classes, '-e', 'cleanup_probe', str(args.cleanup_probe).lower(), RUNNER, text=True, timeout=600)
        (args.output / 'instrumentation.txt').write_text(result.stdout + result.stderr)
        report['tests_passed'] = bool(re.search(r'OK \(\d+ tests?\)', result.stdout)) and 'FAILURES!!!' not in result.stdout
        if not report['tests_passed']:
            raise RuntimeError('Instrumentation failed; see instrumentation.txt')
    finally:
        errors = []
        try:
            clear(reinstall=False)
            report['local_cleanup'] = 'verified: sandbox database removed'
        except Exception as error:
            errors.append('Local cleanup: ' + str(error))
        if args.sync:
            try:
                report['server_before_cleanup'] = server('status')
                report['server_after_cleanup'] = server('reset')
                assert report['server_after_cleanup']['operations'] == 0
                assert report['server_after_cleanup']['entities'] == 0
            except Exception as error:
                errors.append('Server cleanup: ' + str(error))
        try:
            after = production_snapshot()
            (args.output / 'production-after.json').write_text(json.dumps(after, indent=2))
            report['production_unchanged'] = before == after
            if before != after:
                errors.append('Normal app logical records changed during the test window')
        except Exception as error:
            errors.append('Production comparison: ' + str(error))
        report['cleanup_errors'] = errors
        (args.output / 'result.json').write_text(json.dumps(report, indent=2))
        print(json.dumps(report, indent=2))
        if errors:
            raise RuntimeError('; '.join(errors))


if __name__ == '__main__':
    main()
