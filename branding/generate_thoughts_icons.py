#!/usr/bin/env python3
# Written for Thoughts. License is GPLv3.
"""Generate the shared Thoughts launcher artwork for Android and macOS."""

from __future__ import annotations

import argparse
import gzip
import shutil
import subprocess
from pathlib import Path

ANDROID_LEGACY_SIZES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}
ANDROID_ADAPTIVE_SIZES = {
    "mdpi": 108,
    "hdpi": 162,
    "xhdpi": 216,
    "xxhdpi": 324,
    "xxxhdpi": 432,
}
DESKTOP_ICON_SIZES = (16, 22, 32, 44, 48, 64, 128, 150, 310, 512)


def render(svg: Path, destination: Path, size: int, renderer: str) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        [renderer, "--width", str(size), "--height", str(size), "--output", str(destination), str(svg)],
        check=True,
    )


def write_svgz(source: Path, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    with source.open("rb") as input_file, destination.open("wb") as output_file:
        with gzip.GzipFile(filename="", mode="wb", fileobj=output_file, mtime=0) as compressed:
            shutil.copyfileobj(input_file, compressed)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--desktop-root",
        type=Path,
        default=Path(__file__).resolve().parents[2] / "thoughts-desktop",
    )
    args = parser.parse_args()

    repository = Path(__file__).resolve().parents[1]
    artwork = Path(__file__).resolve().parent
    full_icon = artwork / "thoughts-icon.svg"
    round_icon = artwork / "thoughts-icon-round.svg"
    foreground = artwork / "thoughts-glyph.svg"
    monochrome = artwork / "thoughts-monochrome.svg"
    renderer = shutil.which("rsvg-convert")
    if renderer is None:
        raise SystemExit("rsvg-convert is required")

    resources = repository / "app/src/main/res"
    for density, size in ANDROID_LEGACY_SIZES.items():
        target = resources / f"mipmap-{density}"
        render(full_icon, target / "ic_launcher.png", size, renderer)
        render(round_icon, target / "ic_launcher_round.png", size, renderer)
    for density, size in ANDROID_ADAPTIVE_SIZES.items():
        target = resources / f"mipmap-{density}"
        render(foreground, target / "ic_launcher_foreground.png", size, renderer)
        render(monochrome, target / "ic_launcher_monochrome.png", size, renderer)

    desktop_icons = args.desktop_root.resolve() / "icons"
    if not desktop_icons.is_dir():
        raise SystemExit(f"Desktop icon directory not found: {desktop_icons}")
    for size in DESKTOP_ICON_SIZES:
        render(full_icon, desktop_icons / f"{size}-apps-okular.png", size, renderer)
    write_svgz(full_icon, desktop_icons / "hisc-apps-okular.svgz")
    write_svgz(full_icon, desktop_icons / "small/hi16-apps-okular.svgz")
    write_svgz(full_icon, desktop_icons / "small/hi22-apps-okular.svgz")


if __name__ == "__main__":
    main()
