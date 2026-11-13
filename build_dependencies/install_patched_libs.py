#!/usr/bin/env python3
# Written for Thoughts. License is GPLv3.
"""Install the committed patched PDFium libraries and rebuild the JNI bridge.

The app's JNI bridge calls functions that only our patched PDFium has (see
patches/annotation_reply.patch), so the official MJ PDF release libraries
cannot link it. Building PDFium itself needs a multi-gigabyte Chromium
checkout, so the built libraries are kept in PdfiumAndroid/prebuilt with
their SHA-256 sums, and only the small JNI bridge is compiled here.

    python3 build_dependencies/install_patched_libs.py           # CI and new checkouts
    python3 build_dependencies/install_patched_libs.py --update  # after pdfium_source.py
"""

from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO_ROOT))

from build_dependencies.bootstrap_release_libs import (  # noqa: E402
    DEFAULT_OUTPUT_DIR,
    EXPECTED_ABIS,
    find_ndk_build,
    rebuild_jni_bridge,
)

PREBUILT_DIR = REPO_ROOT / "PdfiumAndroid" / "prebuilt"
SUMS = PREBUILT_DIR / "SHA256SUMS"
LIBRARIES = ("libc++_shared.so", "libmodft2.so", "libmodpdfium.so", "libmodpng.so")
# The JNI bridge must link against these; an unpatched PDFium lacks them.
REQUIRED_SYMBOL = b"MJPDFAnnot_SetIconName"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def entries() -> list[str]:
    return [f"{abi}/{name}" for abi in EXPECTED_ABIS for name in LIBRARIES]


def check_library(path: Path) -> None:
    data = path.read_bytes()
    if data[:4] != b"\x7fELF":
        raise RuntimeError(f"Not an ELF library: {path}")
    if path.name == "libmodpdfium.so" and REQUIRED_SYMBOL not in data:
        raise RuntimeError(f"{path} is not the patched PDFium (no {REQUIRED_SYMBOL.decode()}).")


def update(source: Path) -> None:
    lines = []
    for entry in entries():
        origin = source / entry
        check_library(origin)
        destination = PREBUILT_DIR / entry
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(origin, destination)
        lines.append(f"{sha256(destination)}  {entry}")
    SUMS.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"Updated {len(lines)} patched libraries in {PREBUILT_DIR}")


def install(output_dir: Path) -> None:
    expected = dict(
        reversed(line.split(None, 1)) for line in SUMS.read_text(encoding="utf-8").splitlines() if line.strip()
    )
    if sorted(expected) != sorted(entries()):
        raise RuntimeError(f"{SUMS} does not list exactly the expected libraries.")
    for entry in entries():
        origin = PREBUILT_DIR / entry
        if sha256(origin) != expected[entry]:
            raise RuntimeError(f"SHA-256 mismatch for {origin}")
        check_library(origin)
        destination = output_dir / entry
        destination.parent.mkdir(parents=True, exist_ok=True)
        temporary = destination.with_name(destination.name + ".tmp")
        shutil.copyfile(origin, temporary)
        os.replace(temporary, destination)
    print(f"Installed {len(expected)} verified patched libraries into {output_dir}")
    rebuild_jni_bridge(find_ndk_build(None), output_dir)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--update", action="store_true",
                        help="Copy the libraries from PdfiumAndroid/src/main/libs into prebuilt and record their sums.")
    args = parser.parse_args()
    if args.update:
        update(DEFAULT_OUTPUT_DIR)
    else:
        install(DEFAULT_OUTPUT_DIR)


if __name__ == "__main__":
    main()
