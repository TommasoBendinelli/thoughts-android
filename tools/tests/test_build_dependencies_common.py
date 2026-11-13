# Written for Thoughts. License is GPLv3.

import io
import os
import shutil
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from build_dependencies import common  # noqa: E402


def archive(directory, members):
    """Writes a tar archive; each member is (TarInfo, bytes or None)."""
    path = os.path.join(directory, "fixture.tar")
    with tarfile.open(path, "w") as tar:
        for info, data in members:
            if data is not None:
                info.size = len(data)
            tar.addfile(info, io.BytesIO(data) if data is not None else None)
    return path


def regular(name):
    return tarfile.TarInfo(name)


def link(name, target, kind):
    info = tarfile.TarInfo(name)
    info.type = kind
    info.linkname = target
    return info


class ExtractTarFileTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="extract-tar-")
        self.target = os.path.join(self.root, "target")
        os.makedirs(self.target)
        self.addCleanup(shutil.rmtree, self.root, ignore_errors=True)

    def test_extracts_ordinary_members_into_the_target(self):
        directory = tarfile.TarInfo("lib")
        directory.type = tarfile.DIRTYPE
        path = archive(self.root, [
            (directory, None),
            (regular("lib/libpdfium.so"), b"library"),
            (link("lib/current.so", "libpdfium.so", tarfile.SYMTYPE), None),
        ])
        common.extract_tar_file(path, self.target)
        with open(os.path.join(self.target, "lib/libpdfium.so"), "rb") as extracted:
            self.assertEqual(b"library", extracted.read())

    def test_refuses_members_that_leave_the_target(self):
        outside = os.path.join(self.root, "outside.txt")
        cases = {
            "parent path": [(regular("../outside.txt"), b"x")],
            "nested parent path": [(regular("lib/../../outside.txt"), b"x")],
            "absolute path": [(regular(outside), b"x")],
            "symlink out": [(link("lib", "..", tarfile.SYMTYPE), None), (regular("lib/outside.txt"), b"x")],
            "absolute symlink": [(link("escape", self.root, tarfile.SYMTYPE), None)],
            "hard link out": [(link("escape", "../outside.txt", tarfile.LNKTYPE), None)],
            "device": [(link("device", "", tarfile.CHRTYPE), None)],
        }
        for name, members in cases.items():
            with self.subTest(name):
                path = archive(self.root, [(regular("first.txt"), b"first")] + members)
                with self.assertRaises(ValueError):
                    common.extract_tar_file(path, self.target)
                # Refused as a whole: not even the harmless first member was written.
                self.assertEqual([], os.listdir(self.target))
                self.assertFalse(os.path.exists(outside))


class RunCmdTest(unittest.TestCase):
    def test_arguments_reach_the_program_unchanged_and_no_shell_reads_them(self):
        directory = tempfile.mkdtemp(prefix="run-cmd-")
        self.addCleanup(shutil.rmtree, directory, ignore_errors=True)
        marker = os.path.join(directory, "created by a shell")
        argument = f"one two; touch '{marker}' $(touch '{marker}')"
        with mock.patch.object(common.subprocess, "call", wraps=common.subprocess.call) as call:
            common.run_cmd([sys.executable, "-c", "import sys; sys.exit(0 if sys.argv[1:] == [%r] else 3)" % argument, argument])
        self.assertEqual({}, call.call_args.kwargs)
        self.assertFalse(os.path.exists(marker))

    def test_a_command_line_in_one_string_is_refused(self):
        with self.assertRaises(TypeError):
            common.run_cmd("cmake --build .")

    def test_a_failing_command_stops_the_build(self):
        with self.assertRaises(SystemExit):
            common.run_cmd([sys.executable, "-c", "raise SystemExit(2)"])


if __name__ == "__main__":
    unittest.main()
