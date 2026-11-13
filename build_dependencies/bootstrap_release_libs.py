#!/usr/bin/env python3
# Written for Thoughts. License is GPLv3.
"""Restore MJ PDF's ignored native libraries from the pinned upstream release."""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import shutil
import subprocess
import tempfile
import urllib.request
import zipfile
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUTPUT_DIR = REPO_ROOT / "PdfiumAndroid" / "src" / "main" / "libs"
JNI_DIR = REPO_ROOT / "PdfiumAndroid" / "src" / "main" / "jni"
JNI_OBJECT_DIR = REPO_ROOT / "PdfiumAndroid" / "src" / "main" / "obj"
JNI_BRIDGE_INPUTS = (
    JNI_DIR / "Android.mk",
    JNI_DIR / "Application.mk",
    JNI_DIR / "src" / "mainJNILib.cpp",
    JNI_DIR / "src" / "sourceSync.cpp",
    *sorted((JNI_DIR / "synctex").glob("*")),
)
JNI_BRIDGE_STAMP = ".jni-bridge.sha256"
RELEASE_APK_URL = (
    "https://gitlab.com/-/project/38392439/uploads/"
    "154ad083e39f05277b19870fef5b812b/"
    "mj-pdf-v3.0.0-universal-release.apk"
)
RELEASE_APK_SHA256 = "24d28ad0436ed383db23b5b9b4627550c2887a4711e12455f3628e4273cb863c"
EXPECTED_PACKAGE = "com.gitlab.mudlej.MjPdfReader"
EXPECTED_VERSION = "3.0.0"
EXPECTED_ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
EXPECTED_LIBRARIES = (
    "libc++_shared.so",
    "libjniPdfium.so",
    "libmodft2.so",
    "libmodpdfium.so",
    "libmodpng.so",
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Verify the pinned official MJ PDF v3.0.0 universal APK and extract "
            "its native libraries for local builds."
        )
    )
    parser.add_argument(
        "--apk",
        type=Path,
        help="Use an existing APK instead of downloading the pinned release asset.",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=DEFAULT_OUTPUT_DIR,
        help=f"Native library destination (default: {DEFAULT_OUTPUT_DIR}).",
    )
    parser.add_argument(
        "--aapt",
        type=Path,
        help="Path to Android SDK aapt; auto-detected when omitted.",
    )
    parser.add_argument(
        "--ndk-build",
        type=Path,
        help="Path to Android NDK ndk-build; auto-detected when omitted.",
    )
    return parser.parse_args()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as file:
        for chunk in iter(lambda: file.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def jni_bridge_fingerprint() -> str:
    digest = hashlib.sha256()
    for path in JNI_BRIDGE_INPUTS:
        relative_path = path.relative_to(REPO_ROOT).as_posix()
        digest.update(relative_path.encode("utf-8"))
        digest.update(b"\0")
        digest.update(path.read_bytes())
        digest.update(b"\0")
    return digest.hexdigest()


def download_release_apk(destination: Path) -> None:
    print(f"Downloading official MJ PDF v{EXPECTED_VERSION} universal APK...")
    curl_path = shutil.which("curl")
    if curl_path:
        subprocess.run(
            [
                curl_path,
                "--fail",
                "--location",
                "--silent",
                "--show-error",
                "--output",
                str(destination),
                RELEASE_APK_URL,
            ],
            check=True,
        )
        return

    request = urllib.request.Request(
        RELEASE_APK_URL,
        headers={"User-Agent": "mj-pdf-native-bootstrap/1.0"},
    )
    with urllib.request.urlopen(request) as response, destination.open("wb") as output:
        shutil.copyfileobj(response, output)


def sdk_dir_from_local_properties() -> Path | None:
    properties_path = REPO_ROOT / "local.properties"
    if not properties_path.is_file():
        return None

    for raw_line in properties_path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if line.startswith("sdk.dir="):
            value = line.split("=", 1)[1].strip()
            value = value.replace("\\:", ":").replace("\\\\", "\\")
            return Path(value).expanduser()
    return None


def build_tools_version_key(path: Path) -> tuple[bool, tuple[int, ...]]:
    version = path.parent.name.lower()
    is_stable = not any(marker in version for marker in ("alpha", "beta", "preview", "rc"))
    return is_stable, tuple(int(value) for value in re.findall(r"\d+", version))


def find_aapt(explicit_path: Path | None) -> Path:
    if explicit_path is not None:
        candidate = explicit_path.expanduser().resolve()
        if candidate.is_file():
            return candidate
        raise RuntimeError(f"aapt does not exist: {candidate}")

    sdk_candidates = [
        sdk_dir_from_local_properties(),
        Path(os.environ["ANDROID_SDK_ROOT"]).expanduser()
        if os.environ.get("ANDROID_SDK_ROOT")
        else None,
        Path(os.environ["ANDROID_HOME"]).expanduser()
        if os.environ.get("ANDROID_HOME")
        else None,
    ]
    for sdk_dir in sdk_candidates:
        if sdk_dir is None:
            continue
        executable_name = "aapt.exe" if os.name == "nt" else "aapt"
        candidates = list((sdk_dir / "build-tools").glob(f"*/{executable_name}"))
        if candidates:
            return max(candidates, key=build_tools_version_key)

    path_candidate = shutil.which("aapt")
    if path_candidate:
        return Path(path_candidate)

    raise RuntimeError(
        "Unable to find aapt. Set sdk.dir in local.properties, set "
        "ANDROID_SDK_ROOT, or pass --aapt."
    )


def find_ndk_build(explicit_path: Path | None) -> Path:
    if explicit_path is not None:
        candidate = explicit_path.expanduser().resolve()
        if candidate.is_file():
            return candidate
        raise RuntimeError(f"ndk-build does not exist: {candidate}")

    executable_name = "ndk-build.cmd" if os.name == "nt" else "ndk-build"
    sdk_candidates = [
        sdk_dir_from_local_properties(),
        Path(os.environ["ANDROID_SDK_ROOT"]).expanduser()
        if os.environ.get("ANDROID_SDK_ROOT")
        else None,
        Path(os.environ["ANDROID_HOME"]).expanduser()
        if os.environ.get("ANDROID_HOME")
        else None,
    ]
    for sdk_dir in sdk_candidates:
        if sdk_dir is None:
            continue
        candidates = list((sdk_dir / "ndk").glob(f"*/{executable_name}"))
        if candidates:
            return max(candidates, key=build_tools_version_key)
        legacy_candidate = sdk_dir / "ndk-bundle" / executable_name
        if legacy_candidate.is_file():
            return legacy_candidate

    path_candidate = shutil.which(executable_name) or shutil.which("ndk-build")
    if path_candidate:
        return Path(path_candidate)

    raise RuntimeError(
        "Unable to find ndk-build. Install an Android NDK, set sdk.dir in "
        "local.properties, set ANDROID_SDK_ROOT, or pass --ndk-build."
    )


def verify_apk_metadata(apk_path: Path, aapt_path: Path) -> None:
    result = subprocess.run(
        [str(aapt_path), "dump", "badging", str(apk_path)],
        check=True,
        capture_output=True,
        text=True,
    )
    package_line = next(
        (line for line in result.stdout.splitlines() if line.startswith("package:")),
        None,
    )
    if package_line is None:
        raise RuntimeError("aapt did not return APK package metadata.")

    attributes = dict(re.findall(r"([A-Za-z]+)='([^']*)'", package_line))
    actual_package = attributes.get("name")
    actual_version = attributes.get("versionName")
    if actual_package != EXPECTED_PACKAGE or actual_version != EXPECTED_VERSION:
        raise RuntimeError(
            "Unexpected APK metadata: "
            f"package={actual_package!r}, version={actual_version!r}; "
            f"expected package={EXPECTED_PACKAGE!r}, version={EXPECTED_VERSION!r}."
        )


def expected_archive_entries() -> set[str]:
    return {
        f"lib/{abi}/{library}"
        for abi in EXPECTED_ABIS
        for library in EXPECTED_LIBRARIES
    }


def extract_native_libraries(apk_path: Path, output_dir: Path) -> None:
    expected_entries = expected_archive_entries()
    with zipfile.ZipFile(apk_path) as archive:
        native_entries = [
            name
            for name in archive.namelist()
            if re.fullmatch(r"lib/[^/]+/[^/]+\.so", name)
        ]
        actual_entries = set(native_entries)
        if len(native_entries) != len(actual_entries):
            raise RuntimeError("The release APK contains duplicate native library entries.")

        missing = sorted(expected_entries - actual_entries)
        unexpected = sorted(actual_entries - expected_entries)
        if missing or unexpected:
            details = []
            if missing:
                details.append("missing: " + ", ".join(missing))
            if unexpected:
                details.append("unexpected: " + ", ".join(unexpected))
            raise RuntimeError("Unexpected native library inventory (" + "; ".join(details) + ").")

        output_dir = output_dir.expanduser().resolve()
        output_dir.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(
            prefix="mj-pdf-native-libs-",
            dir=output_dir.parent,
        ) as stage_directory:
            stage_root = Path(stage_directory)
            for archive_entry in sorted(expected_entries):
                relative_path = Path(archive_entry).relative_to("lib")
                staged_path = stage_root / relative_path
                staged_path.parent.mkdir(parents=True, exist_ok=True)
                data = archive.read(archive_entry)
                if len(data) < 4 or data[:4] != b"\x7fELF":
                    raise RuntimeError(f"Native library is not an ELF binary: {archive_entry}")
                staged_path.write_bytes(data)

            for staged_path in sorted(stage_root.glob("*/*.so")):
                relative_path = staged_path.relative_to(stage_root)
                destination = output_dir / relative_path
                destination.parent.mkdir(parents=True, exist_ok=True)
                temporary_destination = destination.with_name(destination.name + ".tmp")
                shutil.copyfile(staged_path, temporary_destination)
                os.replace(temporary_destination, destination)

    print(
        f"Installed {len(expected_entries)} verified native libraries into {output_dir}"
    )


def rebuild_jni_bridge(ndk_build_path: Path, output_dir: Path) -> None:
    output_dir = output_dir.expanduser().resolve()
    JNI_OBJECT_DIR.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(
        prefix="mj-pdf-jni-build-",
        dir=output_dir.parent,
    ) as stage_directory:
        stage_root = Path(stage_directory)
        command = [
            str(ndk_build_path),
            "-j1",
            f"NDK_PROJECT_PATH={REPO_ROOT}",
            f"APP_BUILD_SCRIPT={JNI_DIR / 'Android.mk'}",
            f"NDK_APPLICATION_MK={JNI_DIR / 'Application.mk'}",
            f"NDK_LIBS_OUT={stage_root}",
            f"NDK_OUT={JNI_OBJECT_DIR}",
            f"PDFIUM_PREBUILT_DIR={output_dir}",
        ]
        print("Rebuilding MJ PDF's JNI bridge for all supported ABIs...")
        subprocess.run(command, check=True, cwd=REPO_ROOT)

        for abi in EXPECTED_ABIS:
            staged_bridge = stage_root / abi / "libjniPdfium.so"
            if not staged_bridge.is_file():
                raise RuntimeError(f"ndk-build did not produce {staged_bridge}")
            with staged_bridge.open("rb") as bridge:
                if bridge.read(4) != b"\x7fELF":
                    raise RuntimeError(f"JNI bridge is not an ELF binary: {staged_bridge}")
            destination = output_dir / abi / "libjniPdfium.so"
            temporary_destination = destination.with_name(destination.name + ".tmp")
            shutil.copyfile(staged_bridge, temporary_destination)
            os.replace(temporary_destination, destination)

    stamp_path = output_dir / JNI_BRIDGE_STAMP
    temporary_stamp = stamp_path.with_name(stamp_path.name + ".tmp")
    temporary_stamp.write_text(jni_bridge_fingerprint() + "\n", encoding="utf-8")
    os.replace(temporary_stamp, stamp_path)
    print(
        "Installed rebuilt libjniPdfium.so for "
        + ", ".join(EXPECTED_ABIS)
        + "; the remaining native dependencies are from the verified release APK."
    )


def main() -> None:
    args = parse_args()
    with tempfile.TemporaryDirectory(prefix="mj-pdf-release-apk-") as temporary_directory:
        if args.apk is None:
            apk_path = Path(temporary_directory) / "mj-pdf-v3.0.0-universal.apk"
            download_release_apk(apk_path)
        else:
            apk_path = args.apk.expanduser().resolve()
            if not apk_path.is_file():
                raise RuntimeError(f"APK does not exist: {apk_path}")

        actual_sha256 = sha256_file(apk_path)
        if actual_sha256 != RELEASE_APK_SHA256:
            raise RuntimeError(
                f"APK SHA-256 mismatch: {actual_sha256}; expected {RELEASE_APK_SHA256}."
            )

        aapt_path = find_aapt(args.aapt)
        verify_apk_metadata(apk_path, aapt_path)
        extract_native_libraries(apk_path, args.output_dir)
        ndk_build_path = find_ndk_build(args.ndk_build)
        rebuild_jni_bridge(ndk_build_path, args.output_dir)
        print(
            f"Verified {EXPECTED_PACKAGE} v{EXPECTED_VERSION} "
            f"({RELEASE_APK_SHA256})"
        )


if __name__ == "__main__":
    main()
