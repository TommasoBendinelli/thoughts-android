#!/usr/bin/env python3
# Written for Thoughts. License is GPLv3.
"""Publish exact rendered LaTeX sources and SyncTeX data for offline Thoughts use."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
import os
import re
import subprocess
import tempfile
import uuid
import zipfile
from contextlib import nullcontext
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from typing import Any


SCHEMA_VERSION = 3
INDEX_FILENAME = "index.json"
MAX_SOURCE_BYTES = 2_000_000
AGENT_REMOVE_NAME = r"\agentRemove"
FANCYVRB_AGENT_REMOVE_NAME = "|agentRemove"
AGENT_EDIT_NAME = r"\agentProposedEdit"
AGENT_REMOVE_ID = re.compile(
    r"^agent-remove-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
)
AGENT_PROPOSAL_ID = re.compile(r"^[a-z0-9][a-z0-9._:-]{0,127}$")
SYNCTEX_INPUT = re.compile(r"^Input:(\d+):(.*)$")
SYNCTEX_PAGE = re.compile(r"^\{(\d+)$")
SYNCTEX_NODE = re.compile(
    r"^([\[\(hvrx])(-?\d+),(-?\d+):(-?\d+),(-?\d+)(?::(-?\d+),(-?\d+),(-?\d+))?.*$"
)
SYNCTEX_SP_PER_BIG_POINT = 65_781.76
DOCUMENT_ARTIFACT_FORMAT = "thoughts-document-source-artifact-v2"
REVIEW_ANCHOR_NAME = r"\CommentAnchor"
COMMENT_ANCHOR_RE = re.compile(r"\\(?:CommentHighlight|CommentStrikethrough|CommentAnchor|ReviewAnchor)(?![A-Za-z@])")
REVIEW_CARD_MASK_START_NAME = r"\ReviewCardMaskStart"
REVIEW_CARD_MASK_END_NAME = r"\ReviewCardMaskEnd"
REVIEW_CARD_SEGMENT_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")


class BundleError(ValueError):
    pass


@dataclass(frozen=True)
class TexGroup:
    start: int
    content_start: int
    content_end: int
    end: int


def _skip_tex_space(value: str, offset: int) -> int:
    while offset < len(value):
        if value[offset].isspace():
            offset += 1
            continue
        if value[offset] == "%" and (offset == 0 or value[offset - 1] != "\\"):
            newline = value.find("\n", offset)
            return len(value) if newline < 0 else _skip_tex_space(value, newline + 1)
        return offset
    return offset


def _tex_group(value: str, offset: int) -> TexGroup | None:
    offset = _skip_tex_space(value, offset)
    if offset >= len(value) or value[offset] != "{":
        return None
    depth = 1
    cursor = offset + 1
    while cursor < len(value):
        character = value[cursor]
        if character == "%" and (cursor == 0 or value[cursor - 1] != "\\"):
            newline = value.find("\n", cursor)
            if newline < 0:
                return None
            cursor = newline + 1
            continue
        if character == "\\":
            cursor += 2
            continue
        if character == "{":
            depth += 1
        elif character == "}":
            depth -= 1
            if depth == 0:
                return TexGroup(offset, offset + 1, cursor, cursor + 1)
        cursor += 1
    return None


def _parenthesis_group(value: str, offset: int) -> TexGroup | None:
    offset = _skip_tex_space(value, offset)
    if offset >= len(value) or value[offset] != "(":
        return None
    depth = 1
    cursor = offset + 1
    while cursor < len(value):
        if value[cursor] == "(":
            depth += 1
        elif value[cursor] == ")":
            depth -= 1
            if depth == 0:
                return TexGroup(offset, offset + 1, cursor, cursor + 1)
        cursor += 1
    return None


def _suppressed_body(value: str, body: TexGroup) -> tuple[bool, int, int]:
    cursor = _skip_tex_space(value, body.content_start)
    marker = r"\agentRemovedBlock"
    if not value.startswith(marker, cursor):
        return False, body.content_start, body.content_end
    inner = _tex_group(value, cursor + len(marker))
    if inner is None or _skip_tex_space(value, inner.end) != body.content_end:
        return False, body.content_start, body.content_end
    return True, inner.content_start, inner.content_end


def _preview(value: str, limit: int = 500) -> str:
    normalized = " ".join(value.split())
    return normalized if len(normalized) <= limit else normalized[: limit - 1] + "…"


def _byte_offset(value: str, offset: int) -> int:
    return len(value[:offset].encode("utf-8"))


def _content(value: str, group: TexGroup) -> dict[str, Any]:
    raw = value[group.content_start : group.content_end]
    return {
        "byte_start": _byte_offset(value, group.content_start),
        "byte_end": _byte_offset(value, group.content_end),
        "sha256": sha256_bytes(raw.encode("utf-8")),
        "preview": _preview(raw),
        "line_start": value.count("\n", 0, group.content_start) + 1,
        "line_end": value.count("\n", 0, group.content_end) + 1,
    }


def parse_agent_proposals(source_file: str, raw: bytes) -> list[dict[str, Any]]:
    """Parse persisted removal and edit proposals without executing TeX."""

    value = raw.decode("utf-8")
    proposals: list[dict[str, Any]] = []
    cursor = 0
    while True:
        start = value.find(AGENT_REMOVE_NAME, cursor)
        if start < 0:
            break
        cursor = start + len(AGENT_REMOVE_NAME)
        if cursor < len(value) and (value[cursor].isalpha() or value[cursor] == "@"):
            continue
        groups: list[TexGroup] = []
        group_cursor = cursor
        for _ in range(3):
            group = _tex_group(value, group_cursor)
            if group is None:
                break
            groups.append(group)
            group_cursor = group.end
        if len(groups) != 3:
            continue
        proposal_id = value[groups[0].content_start : groups[0].content_end].strip()
        if AGENT_REMOVE_ID.fullmatch(proposal_id) is None:
            continue
        suppressed, visible_start, visible_end = _suppressed_body(value, groups[2])
        prefix = value[:start]
        line = prefix.count("\n") + 1
        last_newline = prefix.rfind("\n")
        proposal = {
            "id": proposal_id,
            "kind": "remove",
            "comment": value[groups[1].content_start : groups[1].content_end].strip(),
            "source_file": source_file,
            "macro_start": len(value[:start].encode("utf-8")),
            "macro_end": len(value[: groups[2].end].encode("utf-8")),
            "body_start": len(value[: groups[2].content_start].encode("utf-8")),
            "body_end": len(value[: groups[2].content_end].encode("utf-8")),
            "visible_body_start": len(value[:visible_start].encode("utf-8")),
            "visible_body_end": len(value[:visible_end].encode("utf-8")),
            "line": line,
            "column": start - last_newline,
            "parent_id": None,
            "suppressed": suppressed,
            "body_sha256": sha256_bytes(
                value[visible_start:visible_end].encode("utf-8")
            ),
            "body_preview": _preview(value[visible_start:visible_end]),
            "before": {
                "byte_start": _byte_offset(value, visible_start),
                "byte_end": _byte_offset(value, visible_end),
                "sha256": sha256_bytes(
                    value[visible_start:visible_end].encode("utf-8")
                ),
                "preview": _preview(value[visible_start:visible_end]),
                "line_start": value.count("\n", 0, visible_start) + 1,
                "line_end": value.count("\n", 0, visible_end) + 1,
            },
            "after": {
                "byte_start": _byte_offset(value, groups[2].content_end),
                "byte_end": _byte_offset(value, groups[2].content_end),
                "sha256": sha256_bytes(b""),
                "preview": "",
                "line_start": value.count("\n", 0, groups[2].content_end) + 1,
                "line_end": value.count("\n", 0, groups[2].content_end) + 1,
            },
            "placement": {
                "status": "source_only" if suppressed else "pending",
                "quad_fragments": [],
                "regions": [],
            },
        }
        proposals.append(proposal)
        cursor = start + len(AGENT_REMOVE_NAME)

    cursor = 0
    while True:
        start = value.find(FANCYVRB_AGENT_REMOVE_NAME, cursor)
        if start < 0:
            break
        cursor = start + len(FANCYVRB_AGENT_REMOVE_NAME)
        groups: list[TexGroup] = []
        group_cursor = cursor
        for _ in range(3):
            group = _parenthesis_group(value, group_cursor)
            if group is None:
                break
            groups.append(group)
            group_cursor = group.end
        if len(groups) != 3:
            continue
        proposal_id = value[groups[0].content_start : groups[0].content_end].strip()
        if AGENT_REMOVE_ID.fullmatch(proposal_id) is None:
            continue
        prefix = value[:start]
        line = prefix.count("\n") + 1
        last_newline = prefix.rfind("\n")
        visible_start = groups[2].content_start
        visible_end = groups[2].content_end
        proposals.append(
            {
                "id": proposal_id,
                "kind": "remove",
                "comment": value[
                    groups[1].content_start : groups[1].content_end
                ].strip(),
                "source_file": source_file,
                "macro_start": len(value[:start].encode("utf-8")),
                "macro_end": len(value[: groups[2].end].encode("utf-8")),
                "body_start": len(value[:visible_start].encode("utf-8")),
                "body_end": len(value[:visible_end].encode("utf-8")),
                "visible_body_start": len(value[:visible_start].encode("utf-8")),
                "visible_body_end": len(value[:visible_end].encode("utf-8")),
                "line": line,
                "column": start - last_newline,
                "parent_id": None,
                "suppressed": False,
                "body_sha256": sha256_bytes(
                    value[visible_start:visible_end].encode("utf-8")
                ),
                "body_preview": _preview(value[visible_start:visible_end]),
                "before": _content(value, groups[2]),
                "after": {
                    "byte_start": _byte_offset(value, groups[2].content_end),
                    "byte_end": _byte_offset(value, groups[2].content_end),
                    "sha256": sha256_bytes(b""),
                    "preview": "",
                    "line_start": value.count("\n", 0, groups[2].content_end) + 1,
                    "line_end": value.count("\n", 0, groups[2].content_end) + 1,
                },
                "placement": {"status": "pending", "quad_fragments": [], "regions": []},
            }
        )

    cursor = 0
    while True:
        start = value.find(AGENT_EDIT_NAME, cursor)
        if start < 0:
            break
        cursor = start + len(AGENT_EDIT_NAME)
        if cursor < len(value) and (value[cursor].isalpha() or value[cursor] == "@"):
            continue
        groups = []
        group_cursor = cursor
        for _ in range(4):
            group = _tex_group(value, group_cursor)
            if group is None:
                break
            groups.append(group)
            group_cursor = group.end
        if len(groups) != 4:
            continue
        proposal_id = value[groups[0].content_start : groups[0].content_end].strip()
        if AGENT_PROPOSAL_ID.fullmatch(proposal_id) is None:
            continue
        prefix = value[:start]
        line = prefix.count("\n") + 1
        last_newline = prefix.rfind("\n")
        before = _content(value, groups[2])
        after = _content(value, groups[3])
        proposals.append(
            {
                "id": proposal_id,
                "kind": "edit",
                "comment": value[
                    groups[1].content_start : groups[1].content_end
                ].strip(),
                "source_file": source_file,
                "macro_start": _byte_offset(value, start),
                "macro_end": _byte_offset(value, groups[3].end),
                "body_start": before["byte_start"],
                "body_end": before["byte_end"],
                "visible_body_start": before["byte_start"],
                "visible_body_end": before["byte_end"],
                "line": line,
                "column": start - last_newline,
                "parent_id": None,
                "suppressed": False,
                "body_sha256": before["sha256"],
                "body_preview": before["preview"],
                "before": before,
                "after": after,
                "placement": {"status": "pending", "quad_fragments": [], "regions": []},
            }
        )

    for child in proposals:
        parents = [
            candidate
            for candidate in proposals
            if candidate is not child
            and candidate["macro_start"] < child["macro_start"]
            and child["macro_end"] < candidate["macro_end"]
        ]
        if parents:
            parent = min(
                parents,
                key=lambda candidate: candidate["macro_end"] - candidate["macro_start"],
            )
            raise BundleError(
                "agent proposals cannot be nested; revise the existing pending "
                f"proposal in place: {parent['id']} contains {child['id']}"
            )
    return proposals


def _decode_review_anchor_mirror(value: str) -> str:
    named = {
        r"\textbackslash{}": "\\",
        r"\textasciicircum{}": "^",
        r"\textasciitilde{}": "~",
    }
    escaped = set("{}%#&$_")
    output: list[str] = []
    cursor = 0
    while cursor < len(value):
        match = next(
            ((encoded, decoded) for encoded, decoded in named.items() if value.startswith(encoded, cursor)),
            None,
        )
        if match is not None:
            encoded, decoded = match
            output.append(decoded)
            cursor += len(encoded)
        elif value[cursor] == "\\" and cursor + 1 < len(value) and value[cursor + 1] in escaped:
            output.append(value[cursor + 1])
            cursor += 2
        else:
            output.append(value[cursor])
            cursor += 1
    return " ".join("".join(output).split())


def _has_unescaped_percent(value: str) -> bool:
    """Return whether a TeX line contains a comment introducer."""

    for cursor, character in enumerate(value):
        if character != "%":
            continue
        backslashes = 0
        previous = cursor - 1
        while previous >= 0 and value[previous] == "\\":
            backslashes += 1
            previous -= 1
        if backslashes % 2 == 0:
            return True
    return False


def parse_review_comments(source_file: str, raw: bytes) -> list[dict[str, Any]]:
    """Read ordinary-comment mirrors from current source without rendering TeX."""

    value = raw.decode("utf-8")
    comments: list[dict[str, Any]] = []
    cursor = 0
    while True:
        match = COMMENT_ANCHOR_RE.search(value, cursor)
        if match is None:
            break
        start = match.start()
        cursor = match.end()
        if cursor < len(value) and (value[cursor].isalpha() or value[cursor] == "@"):
            continue
        line_start = value.rfind("\n", 0, start) + 1
        prefix = value[line_start:start]
        if _has_unescaped_percent(prefix):
            continue
        identifier = _tex_group(value, cursor)
        if identifier is None:
            continue
        comment_id = value[identifier.content_start : identifier.content_end].strip()
        mirror = _tex_group(value, identifier.end)
        if not comment_id or len(comment_id) > 512 or mirror is None:
            continue
        if match.group() in {r"\CommentHighlight", r"\CommentStrikethrough"} and _tex_group(value, mirror.end) is None:
            continue
        comments.append(
            {
                "id": comment_id,
                "contents": _decode_review_anchor_mirror(value[mirror.content_start : mirror.content_end]),
                "source_file": source_file,
                "source_sha256": sha256_bytes(raw),
                "line": value.count("\n", 0, start) + 1,
            }
        )
    duplicate_ids = {comment["id"] for comment in comments if sum(candidate["id"] == comment["id"] for candidate in comments) > 1}
    return [comment for comment in comments if comment["id"] not in duplicate_ids]


def parse_review_card_anchors(source_file: str, raw: bytes) -> list[dict[str, Any]]:
    """Parse paired, render-neutral review-card mask markers."""

    value = raw.decode("utf-8")
    occurrences: dict[tuple[str, str], dict[str, list[tuple[int, int]]]] = {}
    for command, role in (
        (REVIEW_CARD_MASK_START_NAME, "start"),
        (REVIEW_CARD_MASK_END_NAME, "end"),
    ):
        cursor = 0
        while True:
            start = value.find(command, cursor)
            if start < 0:
                break
            cursor = start + len(command)
            if cursor < len(value) and (value[cursor].isalpha() or value[cursor] == "@"):
                continue
            line_start = value.rfind("\n", 0, start) + 1
            if _has_unescaped_percent(value[line_start:start]):
                continue
            card_group = _tex_group(value, cursor)
            segment_group = _tex_group(value, card_group.end) if card_group is not None else None
            if card_group is None or segment_group is None:
                continue
            card_id = value[card_group.content_start : card_group.content_end].strip()
            segment_id = value[segment_group.content_start : segment_group.content_end].strip()
            try:
                uuid.UUID(card_id)
            except ValueError:
                continue
            if REVIEW_CARD_SEGMENT_ID.fullmatch(segment_id) is None:
                continue
            occurrences.setdefault((card_id, segment_id), {"start": [], "end": []})[role].append(
                (start, segment_group.end)
            )

    anchors: list[dict[str, Any]] = []
    for (card_id, segment_id), pair in sorted(occurrences.items()):
        if len(pair["start"]) != 1 or len(pair["end"]) != 1:
            continue
        start_offset, body_start = pair["start"][0]
        body_end, end_offset = pair["end"][0]
        if body_start > body_end:
            continue
        body = value[body_start:body_end]
        if not body.strip(" \t\r\n%"):
            continue
        anchors.append(
            {
                "card_id": card_id,
                "segment_id": segment_id,
                "source_file": source_file,
                "source_sha256": sha256_bytes(raw),
                "marker_start": _byte_offset(value, start_offset),
                "marker_end": _byte_offset(value, end_offset),
                "byte_start": _byte_offset(value, body_start),
                "byte_end": _byte_offset(value, body_end),
                "content_sha256": sha256_bytes(body.encode("utf-8")),
                "content_preview": _preview(body),
                "line_start": value.count("\n", 0, body_start) + 1,
                "line_end": value.count("\n", 0, body_end) + 1,
                "placement": {"status": "pending", "regions": []},
            }
        )
    return anchors


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def reverse_synctex_index(
    synctex_raw: bytes,
    inputs: list[dict[str, str]],
) -> bytes | None:
    """Build a deterministic ZIP whose entries contain one page of reverse SyncTeX nodes."""

    authored = {value["synctex_path"] for value in inputs}
    tags: dict[int, str] = {}
    unit = 1.0
    magnification = 1000.0
    x_offset = 0.0
    y_offset = 0.0
    current_page = 0
    page_lines: list[str] = []
    pages: list[tuple[int, bytes]] = []

    def flush_page() -> None:
        nonlocal page_lines
        if current_page > 0 and page_lines:
            pages.append((current_page, ("".join(page_lines)).encode("utf-8")))
        page_lines = []

    try:
        with gzip.GzipFile(fileobj=io.BytesIO(synctex_raw)) as compressed:
            with io.TextIOWrapper(
                compressed, encoding="utf-8", errors="replace"
            ) as stream:
                for raw_line in stream:
                    line = raw_line.rstrip("\r\n")
                    input_match = SYNCTEX_INPUT.fullmatch(line)
                    if input_match:
                        tags[int(input_match.group(1))] = input_match.group(2)
                        continue
                    if line.startswith("Unit:"):
                        try:
                            unit = float(line.partition(":")[2])
                        except ValueError:
                            pass
                        continue
                    if line.startswith("Magnification:"):
                        try:
                            magnification = float(line.partition(":")[2])
                        except ValueError:
                            pass
                        continue
                    if line.startswith("X Offset:"):
                        try:
                            x_offset = float(line.partition(":")[2])
                        except ValueError:
                            pass
                        continue
                    if line.startswith("Y Offset:"):
                        try:
                            y_offset = float(line.partition(":")[2])
                        except ValueError:
                            pass
                        continue
                    page_match = SYNCTEX_PAGE.fullmatch(line)
                    if page_match:
                        flush_page()
                        current_page = int(page_match.group(1))
                        continue
                    if current_page < 1:
                        continue
                    node = SYNCTEX_NODE.fullmatch(line)
                    if node is None:
                        continue
                    source_path = tags.get(int(node.group(2)))
                    if source_path not in authored:
                        continue
                    source_line = int(node.group(3))
                    if source_line < 1:
                        continue
                    scale = unit * magnification / 1000.0 / SYNCTEX_SP_PER_BIG_POINT
                    values = [
                        float(node.group(index) or 0) * scale for index in range(4, 9)
                    ]
                    x, y, width, height, depth = values
                    x += x_offset * scale
                    y += y_offset * scale
                    page_lines.append(
                        f"{node.group(1)}\t{source_path}\t{source_line}\t{x:.8g}\t{y:.8g}\t{width:.8g}\t{height:.8g}\t{depth:.8g}\n"
                    )
        flush_page()
    except (OSError, EOFError):
        return None
    if not pages:
        return None

    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as archive:
        entries = [
            (
                "format.json",
                b'{"schema_version":1,"format":"thoughts-reverse-synctex"}\n',
            ),
            *((f"pages/{page}.tsv", payload) for page, payload in pages),
        ]
        for name, payload in entries:
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, payload, compresslevel=9)
    return output.getvalue()


def atomic_write_bytes(path: Path, value: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.", suffix=".tmp", dir=path.parent
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(value)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        directory_fd = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
    finally:
        temporary.unlink(missing_ok=True)


def atomic_write_json(path: Path, value: dict[str, Any]) -> None:
    atomic_write_bytes(
        path,
        (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8"),
    )


def atomic_write_if_changed(path: Path, value: bytes) -> bool:
    try:
        if path.read_bytes() == value:
            return False
    except OSError:
        pass
    atomic_write_bytes(path, value)
    return True


def read_object(path: Path, description: str) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise BundleError(f"could not read {description} {path}: {error}") from error
    if not isinstance(value, dict):
        raise BundleError(f"{description} is not a JSON object: {path}")
    return value


def safe_source_id(value: Any) -> str:
    if not isinstance(value, str) or not value.endswith(".tex"):
        raise BundleError("source snapshot has an invalid .tex path")
    path = PurePosixPath(value)
    if path.is_absolute() or ".." in path.parts or not path.parts:
        raise BundleError(f"source snapshot escapes the documentation tree: {value}")
    return path.as_posix()


@dataclass(frozen=True)
class BuildRecord:
    directory: Path
    mapping: dict[str, Any]


class PhoneSourceBundlePublisher:
    def __init__(self, docs_root: Path, output: Path, ledger: Path) -> None:
        self.docs_root = docs_root.resolve()
        self.output = output.resolve()
        self.ledger = ledger.resolve()
        self.objects = self.output / "objects"
        self.source_objects: dict[str, dict[str, Any]] = {}
        self.proposals: dict[str, dict[str, Any]] = {}
        self.proposal_pdfs: dict[str, set[str]] = {}
        self.review_comments: dict[str, dict[str, Any]] = {}
        self.review_comment_pdfs: dict[str, set[str]] = {}
        self.review_card_anchors: dict[tuple[str, str], dict[str, Any]] = {}
        self.review_card_anchor_pdfs: dict[tuple[str, str], set[str]] = {}
        self.builds: dict[tuple[str, str], BuildRecord] | None = None
        configured_worker = os.environ.get("THOUGHTS_SOURCE_ARTIFACT_WORKER")
        worker = (
            Path(configured_worker)
            if configured_worker
            else self.docs_root / ".runtime" / "bin" / "thoughts-source-artifact-worker"
        )
        self.native_worker = (
            worker.resolve()
            if worker.is_file() and os.access(worker, os.X_OK)
            else None
        )

    def _index_builds(self) -> dict[tuple[str, str], BuildRecord]:
        result: dict[tuple[str, str], BuildRecord] = {}
        root = self.docs_root / ".runtime" / "comment_anchors" / "builds"
        for mapping_path in sorted(root.glob("*/mapping.json")):
            try:
                mapping = read_object(mapping_path, "cached build mapping")
            except BundleError:
                continue
            source_id = mapping.get("source_id")
            fingerprint = mapping.get("source_fingerprint")
            if isinstance(source_id, str) and isinstance(fingerprint, str):
                result[(source_id, fingerprint)] = BuildRecord(
                    mapping_path.parent, mapping
                )
        return result

    def _build(self, source_id: str, fingerprint: str) -> BuildRecord | None:
        if self.builds is None:
            self.builds = self._index_builds()
        return self.builds.get((source_id, fingerprint))

    def _store_object(self, value: bytes) -> str:
        object_id = sha256_bytes(value)
        destination = self.objects / object_id
        if not destination.is_file():
            atomic_write_bytes(destination, value)
        elif sha256_file(destination) != object_id:
            raise BundleError(f"bundle object is corrupt: {destination}")
        return object_id

    def _source_inputs(self, build: BuildRecord) -> list[dict[str, str]]:
        inputs: list[dict[str, str]] = []
        snapshots = build.mapping.get("source_snapshots")
        if not isinstance(snapshots, list):
            raise BundleError(
                f"cached build has no source snapshots: {build.directory}"
            )
        for snapshot in snapshots:
            if (
                not isinstance(snapshot, dict)
                or snapshot.get("classification") != "authored"
            ):
                continue
            source_file = safe_source_id(snapshot.get("source_id"))
            synctex_path = snapshot.get("source_tex_absolute")
            snapshot_name = snapshot.get("snapshot")
            declared_sha = snapshot.get("sha256")
            if not isinstance(synctex_path, str) or not synctex_path:
                raise BundleError(
                    f"authored source has no SyncTeX input path: {source_file}"
                )
            if not isinstance(snapshot_name, str) or not snapshot_name:
                raise BundleError(f"authored source has no snapshot: {source_file}")
            source_path = (build.directory / snapshot_name).resolve()
            try:
                source_path.relative_to(build.directory)
            except ValueError as error:
                raise BundleError(
                    f"source snapshot escapes its cached build: {source_file}"
                ) from error
            raw = source_path.read_bytes()
            if len(raw) > MAX_SOURCE_BYTES:
                raise BundleError(
                    f"source is larger than {MAX_SOURCE_BYTES} bytes: {source_file}"
                )
            try:
                raw.decode("utf-8")
            except UnicodeDecodeError as error:
                raise BundleError(f"source is not UTF-8: {source_file}") from error
            source_sha = sha256_bytes(raw)
            if declared_sha != source_sha:
                raise BundleError(f"source snapshot hash does not match: {source_file}")
            previous = self.source_objects.get(source_file)
            if previous is not None and previous["sha256"] != source_sha:
                raise BundleError(
                    f"current rendered PDFs disagree about the source version: {source_file}"
                )
            object_id = self._store_object(raw)
            proposals = parse_agent_proposals(source_file, raw)
            for proposal in proposals:
                proposal["source_sha256"] = source_sha
                previous_proposal = self.proposals.get(proposal["id"])
                if previous_proposal is not None and (
                    previous_proposal["source_file"] != proposal["source_file"]
                    or previous_proposal["kind"] != proposal["kind"]
                    or previous_proposal["before"]["sha256"]
                    != proposal["before"]["sha256"]
                    or previous_proposal["after"]["sha256"]
                    != proposal["after"]["sha256"]
                    or previous_proposal["comment"] != proposal["comment"]
                ):
                    raise BundleError(
                        f"agent proposal ID is duplicated: {proposal['id']}"
                    )
                if previous_proposal is None:
                    self.proposals[proposal["id"]] = proposal
            review_comments = parse_review_comments(source_file, raw)
            review_card_anchors = parse_review_card_anchors(source_file, raw)
            self.source_objects[source_file] = {
                "path": source_file,
                "sha256": source_sha,
                "object": object_id,
                "bytes": len(raw),
                "proposal_ids": [proposal["id"] for proposal in proposals],
                "review_comment_ids": [comment["id"] for comment in review_comments],
                "review_card_anchor_ids": [
                    f"{anchor['card_id']}:{anchor['segment_id']}"
                    for anchor in review_card_anchors
                ],
            }
            for comment in review_comments:
                previous_comment = self.review_comments.get(comment["id"])
                if previous_comment is not None and previous_comment != comment:
                    raise BundleError(f"review comment ID is duplicated: {comment['id']}")
                self.review_comments[comment["id"]] = comment
            for anchor in review_card_anchors:
                key = (anchor["card_id"], anchor["segment_id"])
                previous_anchor = self.review_card_anchors.get(key)
                if previous_anchor is not None and previous_anchor != anchor:
                    raise BundleError(
                        "review-card marker pair is duplicated: "
                        f"{anchor['card_id']}:{anchor['segment_id']}"
                    )
                self.review_card_anchors[key] = anchor
            inputs.append(
                {
                    "synctex_path": synctex_path,
                    "source_file": source_file,
                    "source_sha256": source_sha,
                }
            )
        return sorted(
            inputs, key=lambda value: (value["source_file"], value["synctex_path"])
        )

    @staticmethod
    def _destination(reader: Any, name: str) -> tuple[int, float, float] | None:
        destination = reader.named_destinations.get(name)
        if destination is None:
            return None
        try:
            page = reader.get_destination_page_number(destination) + 1
            pdf_page = reader.pages[page - 1]
            x = float(getattr(destination, "left", None) or pdf_page.cropbox.left)
            y = float(getattr(destination, "top", None) or pdf_page.cropbox.top)
            return page, x, y
        except (IndexError, TypeError, ValueError):
            return None

    @staticmethod
    def _synctex_line_boxes(
        pdf_path: Path,
        synctex_path: str,
        content: dict[str, Any],
    ) -> dict[int, list[tuple[float, float, float, float]]]:
        result: dict[int, list[tuple[float, float, float, float]]] = {}
        line_start = content.get("line_start")
        line_end = content.get("line_end")
        if not isinstance(line_start, int) or not isinstance(line_end, int):
            return result
        for line in range(line_start, line_end + 1):
            try:
                completed = subprocess.run(
                    [
                        "synctex",
                        "view",
                        "-i",
                        f"{line}:1:{synctex_path}",
                        "-o",
                        str(pdf_path),
                    ],
                    capture_output=True,
                    text=True,
                    timeout=10,
                    check=False,
                )
            except (OSError, subprocess.TimeoutExpired):
                return {}
            record: dict[str, str] = {}

            def flush_record() -> None:
                nonlocal record
                try:
                    page = int(record["Page"])
                    left = float(record["h"])
                    bottom = float(record["v"])
                    width = float(record["W"])
                    height = float(record["H"])
                except (KeyError, ValueError):
                    record = {}
                    return
                result.setdefault(page, []).append(
                    (left, bottom - height - 2.0, left + width, bottom + 2.0)
                )
                record = {}

            for raw_line in completed.stdout.splitlines():
                if raw_line == "SyncTeX result begin":
                    record = {}
                    continue
                if raw_line.startswith("Output:"):
                    flush_record()
                    continue
                if raw_line == "SyncTeX result end":
                    flush_record()
                    continue
                key, separator, value = raw_line.partition(":")
                if separator and key in {"Page", "h", "v", "W", "H"}:
                    record[key] = value
        return result

    def _native_synctex_line_boxes(
        self,
        pdf_path: Path,
        queries: list[tuple[str, str, dict[str, str], dict[str, Any]]],
    ) -> (
        dict[tuple[str, str], dict[int, list[tuple[float, float, float, float]]]] | None
    ):
        if self.native_worker is None or not queries:
            return None
        request_keys: dict[str, tuple[str, str]] = {}
        request_lines: list[str] = []
        for offset, (proposal_id, role, source_input, content) in enumerate(queries):
            first_line = content.get("line_start")
            last_line = content.get("line_end")
            if not isinstance(first_line, int) or not isinstance(last_line, int):
                continue
            request_id = str(offset)
            request_keys[request_id] = (proposal_id, role)
            request_lines.append(
                f"{request_id}\t{source_input['synctex_path']}\t{first_line}\t{last_line}"
            )
        if not request_lines:
            return {}
        try:
            completed = subprocess.run(
                [str(self.native_worker), "synctex-boxes", str(pdf_path)],
                input="\n".join(request_lines) + "\n",
                capture_output=True,
                text=True,
                timeout=30,
                check=False,
            )
        except (OSError, subprocess.TimeoutExpired):
            return None
        if completed.returncode != 0:
            return None
        result: dict[
            tuple[str, str], dict[int, list[tuple[float, float, float, float]]]
        ] = {}
        try:
            for raw_line in completed.stdout.splitlines():
                request_id, page, left, top, right, bottom = raw_line.split("\t")
                key = request_keys[request_id]
                result.setdefault(key, {}).setdefault(int(page), []).append(
                    (float(left), float(top), float(right), float(bottom))
                )
        except (KeyError, ValueError):
            return None
        return result

    def _native_reverse_synctex_index(
        self,
        synctex_path: Path,
        inputs: list[dict[str, str]],
    ) -> bytes | None:
        if self.native_worker is None or not inputs:
            return None
        descriptor, temporary_name = tempfile.mkstemp(
            prefix="thoughts-reverse-", suffix=".zip"
        )
        os.close(descriptor)
        temporary = Path(temporary_name)
        try:
            completed = subprocess.run(
                [
                    str(self.native_worker),
                    "reverse-index",
                    str(synctex_path),
                    str(temporary),
                ],
                input="\n".join(value["synctex_path"] for value in inputs) + "\n",
                capture_output=True,
                text=True,
                timeout=30,
                check=False,
            )
            if completed.returncode != 0 or not temporary.is_file():
                return None
            return temporary.read_bytes()
        except (OSError, subprocess.TimeoutExpired):
            return None
        finally:
            temporary.unlink(missing_ok=True)

    def _proposal_queries(
        self,
        inputs: list[dict[str, str]],
    ) -> list[tuple[str, str, dict[str, str], dict[str, Any]]]:
        queries = []
        for source_input in inputs:
            proposal_ids = self.source_objects.get(source_input["source_file"], {}).get(
                "proposal_ids", []
            )
            for proposal_id in proposal_ids:
                proposal = self.proposals[proposal_id]
                if proposal["suppressed"]:
                    continue
                roles = (
                    ("before",) if proposal["kind"] == "remove" else ("before", "after")
                )
                for role in roles:
                    queries.append((proposal_id, role, source_input, proposal[role]))
        return sorted(queries, key=lambda value: (value[0], value[1]))

    def _review_card_queries(
        self,
        inputs: list[dict[str, str]],
    ) -> list[tuple[str, str, dict[str, str], dict[str, Any]]]:
        queries = []
        for source_input in inputs:
            source_file = source_input["source_file"]
            for anchor in self.review_card_anchors.values():
                if anchor["source_file"] != source_file:
                    continue
                query_id = f"{anchor['card_id']}:{anchor['segment_id']}"
                queries.append((query_id, "mask", source_input, anchor))
        return sorted(queries, key=lambda value: value[0])

    def _build_review_card_fragments(
        self,
        build: BuildRecord,
        inputs: list[dict[str, str]],
        analyzed_line_boxes: dict[
            tuple[str, str], dict[int, list[tuple[float, float, float, float]]]
        ]
        | None,
    ) -> dict[tuple[str, str], list[dict[str, float | int]]]:
        try:
            from pypdf import PdfReader
        except ImportError:
            return {}
        pdf_path = build.directory / build.mapping.get("pdf_name", "document.pdf")
        if not pdf_path.is_file():
            return {}
        try:
            reader = PdfReader(str(pdf_path))
        except Exception:
            return {}
        result: dict[tuple[str, str], list[dict[str, float | int]]] = {}
        for query_id, role, source_input, content in self._review_card_queries(inputs):
            card_id, segment_id = query_id.split(":", 1)
            boxes_by_page = (
                analyzed_line_boxes.get((query_id, role), {})
                if analyzed_line_boxes is not None
                else {}
            )
            candidates = self._review_card_region_candidates(reader, boxes_by_page)
            if candidates:
                _score, selected_region = max(
                    candidates,
                    key=lambda value: (value[0], -int(value[1]["page"])),
                )
                selected_area = (
                    float(selected_region["right"]) - float(selected_region["left"])
                ) * (
                    float(selected_region["bottom"]) - float(selected_region["top"])
                )
            else:
                selected_region = None
                selected_area = 0.0
            if selected_area < 0.001:
                fallback = self._synctex_line_boxes(
                    pdf_path,
                    source_input["synctex_path"],
                    content,
                )
                if fallback:
                    fallback_candidates = self._review_card_region_candidates(
                        reader,
                        fallback,
                    )
                    if fallback_candidates:
                        _score, selected_region = max(
                            fallback_candidates,
                            key=lambda value: (value[0], -int(value[1]["page"])),
                        )
            if selected_region is not None:
                result[(card_id, segment_id)] = [selected_region]
        return result

    def _review_card_region_candidates(
        self,
        reader: Any,
        boxes_by_page: dict[int, list[tuple[float, float, float, float]]],
    ) -> list[tuple[float, dict[str, float | int]]]:
        candidates: list[tuple[float, dict[str, float | int]]] = []
        for page_number, boxes in sorted(boxes_by_page.items()):
            if page_number < 1 or page_number > len(reader.pages):
                continue
            page = reader.pages[page_number - 1]
            width = float(page.cropbox.width)
            height = float(page.cropbox.height)
            if width <= 0 or height <= 0:
                continue
            clipped = [
                (
                    max(0.0, min(width, min(left, right))),
                    max(0.0, min(height, min(top, bottom))),
                    max(0.0, min(width, max(left, right))),
                    max(0.0, min(height, max(top, bottom))),
                )
                for left, top, right, bottom in boxes
            ]
            clipped = [box for box in clipped if box[0] < box[2] and box[1] < box[3]]
            if not clipped:
                continue
            region: dict[str, float | int] = {
                "page": page_number,
                "left": min(box[0] for box in clipped) / width,
                "top": min(box[1] for box in clipped) / height,
                "right": max(box[2] for box in clipped) / width,
                "bottom": max(box[3] for box in clipped) / height,
            }
            area = (float(region["right"]) - float(region["left"])) * (
                float(region["bottom"]) - float(region["top"])
            )
            selected = clipped
            if area > 0.5:
                compact = self._compact_review_region(clipped, width, height)
                if compact is None:
                    continue
                selected = compact
                region = {
                    "page": page_number,
                    "left": min(box[0] for box in selected) / width,
                    "top": min(box[1] for box in selected) / height,
                    "right": max(box[2] for box in selected) / width,
                    "bottom": max(box[3] for box in selected) / height,
                }
            score = sum(
                min((box[2] - box[0]) * (box[3] - box[1]) / (width * height), 0.02)
                for box in selected
            )
            candidates.append((score, region))
        return candidates

    @staticmethod
    def _compact_review_region(
        boxes: list[tuple[float, float, float, float]],
        width: float,
        height: float,
    ) -> list[tuple[float, float, float, float]] | None:
        meaningful = [
            box
            for box in boxes
            if box[2] - box[0] >= 1.0
            and box[3] - box[1] >= 1.0
            and (box[2] - box[0]) * (box[3] - box[1]) <= width * height * 0.5
            and box[3] - box[1] <= height * 0.45
        ]
        if not meaningful:
            return None
        text_boxes = [
            box
            for box in meaningful
            if box[2] - box[0] >= width * 0.05 and box[3] - box[1] <= height * 0.08
        ]
        selected = text_boxes or meaningful
        groups: dict[int, list[tuple[float, float, float, float]]] = {}
        for box in selected:
            box_width = box[2] - box[0]
            if box_width >= width * 0.55:
                column = 2
            else:
                column = 0 if (box[0] + box[2]) / 2 < width / 2 else 1
            groups.setdefault(column, []).append(box)
        candidates: list[tuple[float, list[tuple[float, float, float, float]]]] = []
        for group in groups.values():
            area = (
                (max(box[2] for box in group) - min(box[0] for box in group))
                * (max(box[3] for box in group) - min(box[1] for box in group))
                / (width * height)
            )
            if area > 0.5:
                continue
            score = sum(
                min((box[2] - box[0]) * (box[3] - box[1]) / (width * height), 0.02)
                for box in group
            )
            candidates.append((score, group))
        return max(candidates, key=lambda value: value[0])[1] if candidates else None

    def _native_analyze_synctex(
        self,
        synctex_path: Path,
        inputs: list[dict[str, str]],
        queries: list[tuple[str, str, dict[str, str], dict[str, Any]]],
    ) -> (
        tuple[
            bytes,
            dict[tuple[str, str], dict[int, list[tuple[float, float, float, float]]]],
        ]
        | None
    ):
        if self.native_worker is None or not inputs:
            return None
        request_keys: dict[str, tuple[str, str]] = {}
        request_lines = [f"A\t{value['synctex_path']}" for value in inputs]
        for offset, (proposal_id, role, source_input, content) in enumerate(queries):
            first_line = content.get("line_start")
            last_line = content.get("line_end")
            if not isinstance(first_line, int) or not isinstance(last_line, int):
                continue
            request_id = str(offset)
            request_keys[request_id] = (proposal_id, role)
            request_lines.append(
                f"Q\t{request_id}\t{source_input['synctex_path']}\t{first_line}\t{last_line}"
            )
        descriptor, temporary_name = tempfile.mkstemp(
            prefix="thoughts-analysis-", suffix=".zip"
        )
        os.close(descriptor)
        temporary = Path(temporary_name)
        try:
            completed = subprocess.run(
                [str(self.native_worker), "analyze", str(synctex_path), str(temporary)],
                input="\n".join(request_lines) + "\n",
                capture_output=True,
                text=True,
                timeout=30,
                check=False,
            )
            if completed.returncode != 0 or not temporary.is_file():
                return None
            boxes: dict[
                tuple[str, str], dict[int, list[tuple[float, float, float, float]]]
            ] = {}
            for raw_line in completed.stdout.splitlines():
                request_id, page, left, top, right, bottom = raw_line.split("\t")
                key = request_keys[request_id]
                boxes.setdefault(key, {}).setdefault(int(page), []).append(
                    (float(left), float(top), float(right), float(bottom))
                )
            return temporary.read_bytes(), boxes
        except (KeyError, OSError, ValueError, subprocess.TimeoutExpired):
            return None
        finally:
            temporary.unlink(missing_ok=True)

    @staticmethod
    def _proposal_link_fragments(
        reader: Any,
    ) -> dict[str, dict[str, list[dict[str, float | int]]]]:
        result: dict[str, dict[str, list[dict[str, float | int]]]] = {}
        prefix = "agent-proposal:"
        for page_number, page in enumerate(reader.pages, start=1):
            height = float(page.cropbox.height)
            for reference in page.get("/Annots", []):
                try:
                    annotation = reference.get_object()
                    action = annotation.get("/A")
                    uri = str(action.get("/URI")) if action is not None else ""
                    if not uri.startswith(prefix):
                        continue
                    identity, separator, role = uri[len(prefix) :].rpartition(":")
                    if (
                        not separator
                        or role not in {"before", "after"}
                        or AGENT_PROPOSAL_ID.fullmatch(identity) is None
                    ):
                        continue
                    coordinates = [float(value) for value in annotation["/Rect"]]
                    left, right = sorted((coordinates[0], coordinates[2]))
                    top, bottom = sorted(
                        (height - coordinates[1], height - coordinates[3])
                    )
                    if right <= left or bottom <= top:
                        continue
                    result.setdefault(identity, {}).setdefault(role, []).append(
                        {
                            "page": page_number,
                            "left": left,
                            "top": top,
                            "right": right,
                            "bottom": bottom,
                        }
                    )
                except (AttributeError, IndexError, KeyError, TypeError, ValueError):
                    continue
        return result

    def _build_proposal_fragments(
        self,
        build: BuildRecord,
        inputs: list[dict[str, str]],
        analyzed_line_boxes: dict[
            tuple[str, str], dict[int, list[tuple[float, float, float, float]]]
        ]
        | None = None,
    ) -> dict[str, dict[str, list[dict[str, float | int]]]]:
        proposal_ids = {
            proposal_id
            for source_input in inputs
            for proposal_id in self.source_objects.get(
                source_input["source_file"], {}
            ).get("proposal_ids", [])
            if not self.proposals[proposal_id]["suppressed"]
        }
        if not proposal_ids:
            return {}
        try:
            from pypdf import PdfReader
        except ImportError:
            return {}
        pdf_name = build.mapping.get("pdf_name", "document.pdf")
        pdf_path = build.directory / pdf_name
        if not pdf_path.is_file():
            return {}
        try:
            reader = PdfReader(str(pdf_path))
            try:
                import pdfplumber
            except ImportError:
                pdfplumber = None
            document_context = (
                pdfplumber.open(str(pdf_path))
                if pdfplumber is not None
                else nullcontext(None)
            )
            with document_context as document:
                result: dict[str, dict[str, list[dict[str, float | int]]]] = {}
                link_fragments = self._proposal_link_fragments(reader)
                query_inputs: dict[tuple[str, str], dict[str, str]] = {}
                queries = self._proposal_queries(inputs)
                for proposal_id in sorted(proposal_ids):
                    proposal = self.proposals[proposal_id]
                    roles = (
                        ("before",)
                        if proposal["kind"] == "remove"
                        else ("before", "after")
                    )
                    source_input = next(
                        (
                            item
                            for item in inputs
                            if item["source_file"] == proposal["source_file"]
                        ),
                        None,
                    )
                    if source_input is None:
                        continue
                    for role in roles:
                        query_inputs[(proposal_id, role)] = source_input
                native_line_boxes = (
                    analyzed_line_boxes
                    if analyzed_line_boxes is not None
                    else self._native_synctex_line_boxes(pdf_path, queries)
                )
                for proposal_id in sorted(proposal_ids):
                    proposal = self.proposals[proposal_id]
                    anchor_regions = (
                        [("before", "agent-remove", "start", "end")]
                        if proposal["kind"] == "remove"
                        else [
                            ("before", "agent-edit", "before-start", "before-end"),
                            ("after", "agent-edit", "after-start", "after-end"),
                        ]
                    )
                    for role, prefix, start_edge, end_edge in anchor_regions:
                        source_input = query_inputs.get((proposal_id, role))
                        line_boxes = (
                            native_line_boxes.get((proposal_id, role), {})
                            if native_line_boxes is not None
                            else self._synctex_line_boxes(
                                pdf_path,
                                source_input["synctex_path"],
                                proposal[role],
                            )
                            if source_input is not None
                            else {}
                        )
                        linked = [
                            fragment
                            for fragment in link_fragments.get(proposal_id, {}).get(
                                role, []
                            )
                            if not line_boxes
                            or any(
                                box[0]
                                <= (float(fragment["left"]) + float(fragment["right"]))
                                / 2
                                <= box[2]
                                and box[1]
                                <= (float(fragment["top"]) + float(fragment["bottom"]))
                                / 2
                                <= box[3]
                                for box in line_boxes.get(int(fragment["page"]), [])
                            )
                        ]
                        if linked:
                            result.setdefault(proposal_id, {})[role] = linked
                            continue
                        if document is None:
                            continue
                        start = self._destination(
                            reader, f"{prefix}:{proposal_id}:{start_edge}"
                        )
                        end = self._destination(
                            reader, f"{prefix}:{proposal_id}:{end_edge}"
                        )
                        if start is None or end is None or start[0] > end[0]:
                            continue
                        fragments: list[dict[str, float | int]] = []
                        for page_number in range(start[0], end[0] + 1):
                            page = document.pages[page_number - 1]
                            height = float(page.height)
                            start_key = (
                                (height - start[2], start[1])
                                if page_number == start[0]
                                else (-float("inf"), -float("inf"))
                            )
                            end_key = (
                                (height - end[2], end[1])
                                if page_number == end[0]
                                else (float("inf"), float("inf"))
                            )
                            if end_key < start_key:
                                start_key, end_key = end_key, start_key
                            selected = []
                            for character in page.chars:
                                key = (
                                    (
                                        float(character["top"])
                                        + float(character["bottom"])
                                    )
                                    / 2,
                                    (float(character["x0"]) + float(character["x1"]))
                                    / 2,
                                )
                                in_source_line = not line_boxes or any(
                                    box[0] <= key[1] <= box[2]
                                    and box[1] <= key[0] <= box[3]
                                    for box in line_boxes.get(page_number, [])
                                )
                                if start_key <= key <= end_key and in_source_line:
                                    selected.append(character)
                            lines: dict[tuple[float, float], list[dict[str, Any]]] = {}
                            for character in selected:
                                key = (
                                    round(float(character["top"]), 1),
                                    round(float(character["bottom"]), 1),
                                )
                                lines.setdefault(key, []).append(character)
                            for characters in sorted(
                                lines.values(),
                                key=lambda values: float(values[0]["top"]),
                            ):
                                fragments.append(
                                    {
                                        "page": page_number,
                                        "left": min(
                                            float(value["x0"]) for value in characters
                                        ),
                                        "top": min(
                                            float(value["top"]) for value in characters
                                        ),
                                        "right": max(
                                            float(value["x1"]) for value in characters
                                        ),
                                        "bottom": max(
                                            float(value["bottom"])
                                            for value in characters
                                        ),
                                    }
                                )
                        if fragments:
                            result.setdefault(proposal_id, {})[role] = fragments
                return result
        except Exception:
            return {}

    def _segment(self, value: dict[str, Any]) -> dict[str, Any]:
        start_page = value.get("start_page")
        page_count = value.get("page_count")
        source_id = value.get("source_id")
        fingerprint = value.get("fingerprint")
        if not isinstance(start_page, int) or start_page < 1:
            raise BundleError("layout segment has an invalid start_page")
        if not isinstance(page_count, int) or page_count < 1:
            raise BundleError("layout segment has an invalid page_count")
        base = {
            "start_page": start_page,
            "page_count": page_count,
            "source_id": source_id if isinstance(source_id, str) else "",
            "fingerprint": fingerprint if isinstance(fingerprint, str) else "",
        }
        if isinstance(source_id, str) and source_id.endswith(".pdf"):
            embedded = self._embedded_tex_segment(
                source_id,
                base["fingerprint"],
                page_count,
            )
            if embedded is not None:
                return {
                    **self._segment({**embedded, "start_page": start_page}),
                    "source_pdf": source_id,
                }
        if not isinstance(source_id, str) or not source_id.endswith(".tex"):
            return {**base, "source_available": False}
        if not isinstance(fingerprint, str) or len(fingerprint) != 64:
            raise BundleError(
                f"source-linked layout segment has no fingerprint: {source_id}"
            )
        build = self._build(source_id, fingerprint)
        if build is None:
            raise BundleError(
                f"exact rendered SyncTeX build is unavailable: {source_id}"
            )
        synctex_name = build.mapping.get("synctex_name", "document.synctex.gz")
        if not isinstance(synctex_name, str) or not synctex_name:
            raise BundleError(f"cached build has an invalid SyncTeX name: {source_id}")
        synctex_path = build.directory / synctex_name
        synctex_raw = synctex_path.read_bytes()
        inputs = self._source_inputs(build)
        all_queries = self._proposal_queries(inputs) + self._review_card_queries(inputs)
        analysis = self._native_analyze_synctex(
            synctex_path,
            inputs,
            all_queries,
        )
        if analysis is None:
            lookup_raw = self._native_reverse_synctex_index(synctex_path, inputs)
            analyzed_line_boxes = None
        else:
            lookup_raw, analyzed_line_boxes = analysis
        if lookup_raw is None:
            lookup_raw = reverse_synctex_index(synctex_raw, inputs)
        proposal_fragments = self._build_proposal_fragments(
            build,
            inputs,
            analyzed_line_boxes,
        )
        review_card_fragments = self._build_review_card_fragments(
            build,
            inputs,
            analyzed_line_boxes,
        )
        primary = safe_source_id(build.mapping.get("source_tex"))
        primary_source = self.source_objects.get(primary)
        if primary_source is None:
            raise BundleError(
                f"primary rendered source is missing from snapshots: {primary}"
            )
        if not inputs:
            raise BundleError(
                f"cached build has no authored source inputs: {source_id}"
            )
        result = {
            **base,
            "source_available": True,
            "primary_source_file": primary,
            "primary_source_sha256": primary_source["sha256"],
            "synctex_object": self._store_object(synctex_raw),
            "synctex_sha256": sha256_bytes(synctex_raw),
            "inputs": inputs,
            "_proposal_fragments": proposal_fragments,
            "_review_card_fragments": review_card_fragments,
        }
        if lookup_raw is not None:
            result.update(
                {
                    "lookup_format": "thoughts-reverse-synctex-v1",
                    "lookup_object": self._store_object(lookup_raw),
                    "lookup_sha256": sha256_bytes(lookup_raw),
                }
            )
        return result

    def _embedded_tex_segment(
        self,
        source_pdf: str,
        fingerprint: str,
        page_count: int,
    ) -> dict[str, Any] | None:
        """Resolve an exact embedded PDF back to its authored TeX build."""

        matches: dict[tuple[str, str, str], dict[str, Any]] = {}
        layout_directory = self.docs_root / "comments" / ".layout"
        for layout_path in sorted(layout_directory.glob("*.json")):
            try:
                layout = read_object(layout_path, "source-link layout")
            except BundleError:
                continue
            for candidate in layout.get("sources", []):
                if not isinstance(candidate, dict):
                    continue
                candidate_source = candidate.get("source_id")
                if (
                    not isinstance(candidate_source, str)
                    or not candidate_source.endswith(".tex")
                    or candidate.get("source_pdf") != source_pdf
                    or candidate.get("page_count") != page_count
                ):
                    continue
                build = self._build(candidate_source, fingerprint)
                if build is None:
                    continue
                build_id = build.mapping.get("build_id")
                key = (
                    candidate_source,
                    fingerprint,
                    build_id if isinstance(build_id, str) else "",
                )
                matches[key] = {
                    **candidate,
                    "fingerprint": fingerprint,
                    "build_id": build_id,
                }
        if not matches:
            return None
        if len(matches) > 1:
            raise BundleError(
                f"embedded PDF maps to multiple exact TeX builds: {source_pdf}"
            )
        return next(iter(matches.values()))

    def _pdf_entry(self, value: dict[str, Any]) -> dict[str, Any]:
        filename = value.get("filename")
        project = value.get("project")
        if (
            not isinstance(filename, str)
            or Path(filename).name != filename
            or not filename.endswith(".pdf")
        ):
            raise BundleError("source PDF index contains an invalid filename")
        pdf = self.docs_root / "current_pdfs" / filename
        if not pdf.is_file():
            raise BundleError(f"source PDF index references a missing PDF: {filename}")
        layout = read_object(
            self.docs_root / "comments" / ".layout" / f"{Path(filename).stem}.json",
            "source-link layout",
        )
        if layout.get("version") != 1 or not isinstance(layout.get("sources"), list):
            raise BundleError(f"source-link layout is invalid: {filename}")
        segments = [
            self._segment(segment)
            for segment in layout["sources"]
            if isinstance(segment, dict)
        ]
        if not segments:
            raise BundleError(f"source-link layout is empty: {filename}")
        page_count = layout.get("page_count")
        if not isinstance(page_count, int) or page_count < 1:
            page_count = max(
                segment["start_page"] + segment["page_count"] - 1
                for segment in segments
            )
        proposal_ids = sorted(
            {
                proposal_id
                for segment in segments
                for source_input in segment.get("inputs", [])
                for proposal_id in self.source_objects.get(
                    source_input.get("source_file", ""), {}
                ).get("proposal_ids", [])
            }
        )
        review_comment_ids = sorted(
            {
                comment_id
                for segment in segments
                for source_input in segment.get("inputs", [])
                for comment_id in self.source_objects.get(
                    source_input.get("source_file", ""), {}
                ).get("review_comment_ids", [])
            }
        )
        review_card_anchor_ids = sorted(
            {
                anchor_id
                for segment in segments
                for source_input in segment.get("inputs", [])
                for anchor_id in self.source_objects.get(
                    source_input.get("source_file", ""), {}
                ).get("review_card_anchor_ids", [])
            }
        )
        for proposal_id in proposal_ids:
            self.proposal_pdfs.setdefault(proposal_id, set()).add(filename)
        for comment_id in review_comment_ids:
            self.review_comment_pdfs.setdefault(comment_id, set()).add(filename)
        for anchor_id in review_card_anchor_ids:
            card_id, segment_id = anchor_id.split(":", 1)
            self.review_card_anchor_pdfs.setdefault((card_id, segment_id), set()).add(filename)
        for segment in segments:
            for proposal_id, regions in segment.pop("_proposal_fragments", {}).items():
                proposal = self.proposals[proposal_id]
                proposal["placement"]["status"] = "anchored"
                for role, fragments in sorted(regions.items()):
                    adjusted = [
                        {
                            **fragment,
                            "page": fragment["page"] + segment["start_page"] - 1,
                            "pdf_filename": filename,
                        }
                        for fragment in fragments
                    ]
                    proposal["placement"]["regions"].append(
                        {
                            "role": role,
                            "quad_fragments": adjusted,
                        }
                    )
                    if role == "before":
                        proposal["placement"]["quad_fragments"].extend(adjusted)
            for key, fragments in segment.pop("_review_card_fragments", {}).items():
                anchor = self.review_card_anchors[key]
                anchor["placement"]["status"] = "anchored"
                anchor["placement"]["regions"].extend(
                    {
                        **fragment,
                        "page": fragment["page"] + segment["start_page"] - 1,
                        "pdf_filename": filename,
                    }
                    for fragment in fragments
                )
        return {
            "filename": filename,
            "project": project if isinstance(project, str) else "",
            "pdf_sha256": sha256_file(pdf),
            "page_count": page_count,
            "segments": segments,
            "proposal_ids": proposal_ids,
            "review_comment_ids": review_comment_ids,
            "review_card_anchor_ids": review_card_anchor_ids,
        }

    def publish(self) -> tuple[str, int, int, bool]:
        index = read_object(
            self.docs_root / "current_pdfs" / ".thoughts-source-index.json",
            "source PDF index",
        )
        if index.get("schema_version") != 1 or not isinstance(index.get("pdfs"), list):
            raise BundleError("source PDF index has an unsupported schema")
        pdfs = [
            self._pdf_entry(value) for value in index["pdfs"] if isinstance(value, dict)
        ]
        proposals = []
        for proposal_id, proposal in sorted(self.proposals.items()):
            proposals.append(
                {
                    **proposal,
                    "pdf_filenames": sorted(self.proposal_pdfs.get(proposal_id, set())),
                }
            )
        review_comments = [
            {
                **comment,
                "pdf_filenames": sorted(self.review_comment_pdfs.get(comment_id, set())),
            }
            for comment_id, comment in sorted(self.review_comments.items())
        ]
        review_card_anchors = [
            {
                **anchor,
                "pdf_filenames": sorted(self.review_card_anchor_pdfs.get(key, set())),
            }
            for key, anchor in sorted(self.review_card_anchors.items())
        ]
        manifest = {
            "schema_version": SCHEMA_VERSION,
            "pdfs": sorted(pdfs, key=lambda value: value["filename"]),
            "sources": sorted(
                self.source_objects.values(), key=lambda value: value["path"]
            ),
            "agent_proposals": proposals,
            "review_comments": review_comments,
            "review_card_anchors_schema_version": 1,
            "review_card_anchors": review_card_anchors,
        }
        manifest_raw = (
            json.dumps(
                manifest, ensure_ascii=False, sort_keys=True, separators=(",", ":")
            )
            + "\n"
        ).encode("utf-8")
        bundle_id = self._store_object(manifest_raw)
        atomic_write_json(
            self.ledger / f"{bundle_id}.json",
            {
                "schema_version": SCHEMA_VERSION,
                "bundle_id": bundle_id,
                "manifest_object": bundle_id,
                "manifest": manifest,
            },
        )
        index_payload = {
            "schema_version": SCHEMA_VERSION,
            "bundle_id": bundle_id,
            "manifest_object": bundle_id,
            "published_at": utc_now(),
            "pdf_count": len(manifest["pdfs"]),
            "source_count": len(manifest["sources"]),
            "proposal_count": len(manifest["agent_proposals"]),
            "review_comment_count": len(manifest["review_comments"]),
            "review_card_anchor_count": len(manifest["review_card_anchors"]),
        }
        index_path = self.output / INDEX_FILENAME
        try:
            previous = read_object(index_path, "published bundle index")
        except BundleError:
            previous = {}
        if previous.get("bundle_id") == bundle_id:
            changed = False
        else:
            atomic_write_json(index_path, index_payload)
            changed = True
        return bundle_id, len(manifest["pdfs"]), len(manifest["sources"]), changed

    def publish_document(self, filename: str) -> tuple[str, int, int, bool]:
        """Publish one immutable source artifact without rebuilding the global bundle."""

        if Path(filename).name != filename or not filename.endswith(".pdf"):
            raise BundleError(
                f"document source artifact has an invalid filename: {filename}"
            )
        stem = Path(filename).stem
        project = f"docs-{stem}"
        if not (self.docs_root / project / "tex" / "source").is_dir():
            raise BundleError(f"document has no renderable source project: {filename}")
        pdf_path = self.docs_root / "current_pdfs" / filename
        layout_path = self.docs_root / "comments" / ".layout" / f"{stem}.json"
        if not pdf_path.is_file() or not layout_path.is_file():
            raise BundleError(f"document source inputs are unavailable: {filename}")
        input_sha256 = sha256_bytes(
            (
                DOCUMENT_ARTIFACT_FORMAT
                + "\n"
                + sha256_file(pdf_path)
                + "\n"
                + sha256_file(layout_path)
                + "\n"
                + sha256_file(Path(__file__).resolve())
                + "\n"
            ).encode("utf-8")
        )
        pointer_path = self.output / "documents" / f"{filename}.json"
        try:
            previous = read_object(pointer_path, "document source artifact pointer")
        except BundleError:
            previous = {}
        previous_artifact = previous.get("artifact_sha256")
        if (
            previous.get("document_input_sha256") == input_sha256
            and isinstance(previous_artifact, str)
            and len(previous_artifact) == 64
            and (self.objects / previous_artifact).is_file()
            and sha256_file(self.objects / previous_artifact) == previous_artifact
        ):
            return (
                previous_artifact,
                int(previous.get("source_count", 0)),
                int(previous.get("proposal_count", 0)),
                False,
            )
        pdf = self._pdf_entry({"filename": filename, "project": project})
        proposals = [
            {
                **proposal,
                "pdf_filenames": sorted(self.proposal_pdfs.get(proposal_id, set())),
            }
            for proposal_id, proposal in sorted(self.proposals.items())
        ]
        review_comments = [
            {
                **self.review_comments[comment_id],
                "pdf_filenames": sorted(self.review_comment_pdfs.get(comment_id, set())),
            }
            for comment_id in pdf.get("review_comment_ids", [])
            if comment_id in self.review_comments
        ]
        anchor_ids = {
            tuple(anchor_id.split(":", 1))
            for anchor_id in pdf.get("review_card_anchor_ids", [])
        }
        review_card_anchors = [
            {
                **self.review_card_anchors[key],
                "pdf_filenames": sorted(self.review_card_anchor_pdfs.get(key, set())),
            }
            for key in sorted(anchor_ids)
            if key in self.review_card_anchors
        ]
        proposal_objects = []
        for proposal in proposals:
            proposal_raw = (
                json.dumps(
                    {
                        "schema_version": 1,
                        "kind": "thoughts-agent-proposal",
                        **proposal,
                    },
                    ensure_ascii=False,
                    sort_keys=True,
                    separators=(",", ":"),
                )
                + "\n"
            ).encode("utf-8")
            proposal_objects.append(
                {"id": proposal["id"], "object": self._store_object(proposal_raw)}
            )
        manifest = {
            "schema_version": 1,
            "kind": "thoughts-document-source-artifact",
            "filename": filename,
            "pdf": pdf,
            "sources": sorted(
                self.source_objects.values(), key=lambda value: value["path"]
            ),
            "agent_proposals": proposals,
            "proposal_objects": proposal_objects,
            "review_comments_schema_version": 1,
            "review_comments": review_comments,
            "review_card_anchors_schema_version": 1,
            "review_card_anchors": review_card_anchors,
        }
        manifest_raw = (
            json.dumps(
                manifest, ensure_ascii=False, sort_keys=True, separators=(",", ":")
            )
            + "\n"
        ).encode("utf-8")
        artifact_id = self._store_object(manifest_raw)
        pointer = {
            "schema_version": 1,
            "kind": "thoughts-document-source-artifact-pointer",
            "filename": filename,
            "artifact_sha256": artifact_id,
            "manifest_object": artifact_id,
            "pdf_sha256": pdf["pdf_sha256"],
            "document_input_sha256": input_sha256,
            "source_count": len(manifest["sources"]),
            "proposal_count": len(manifest["agent_proposals"]),
            "review_comment_count": len(manifest["review_comments"]),
            "review_card_anchor_count": len(manifest["review_card_anchors"]),
        }
        changed = atomic_write_if_changed(
            pointer_path,
            (
                json.dumps(
                    pointer, ensure_ascii=False, sort_keys=True, separators=(",", ":")
                )
                + "\n"
            ).encode("utf-8"),
        )
        atomic_write_json(
            self.ledger / "documents" / filename / f"{artifact_id}.json",
            {
                "schema_version": 1,
                "artifact_sha256": artifact_id,
                "manifest": manifest,
            },
        )
        return (
            artifact_id,
            len(manifest["sources"]),
            len(manifest["agent_proposals"]),
            changed,
        )

    def migrate_document_from_global_bundle(
        self,
        filename: str,
        expected_pdf_sha256: str | None = None,
    ) -> tuple[str, int, int, bool]:
        """Convert one matching legacy bundle entry into a v2 document artifact."""
        if Path(filename).name != filename or not filename.endswith(".pdf"):
            raise BundleError(f"document source artifact has an invalid filename: {filename}")
        index = read_object(self.output / INDEX_FILENAME, "published bundle index")
        bundle_id = index.get("bundle_id")
        if not isinstance(bundle_id, str) or len(bundle_id) != 64:
            raise BundleError("published bundle index has no valid bundle identity")
        manifest_path = self.objects / bundle_id
        if not manifest_path.is_file() or sha256_file(manifest_path) != bundle_id:
            raise BundleError("published bundle manifest failed verification")
        legacy = read_object(manifest_path, "published bundle manifest")
        pdf = next(
            (
                value
                for value in legacy.get("pdfs", [])
                if isinstance(value, dict) and value.get("filename") == filename
            ),
            None,
        )
        if pdf is None:
            raise BundleError(f"published bundle has no document entry: {filename}")
        pdf_sha256 = pdf.get("pdf_sha256")
        if (
            not isinstance(pdf_sha256, str)
            or len(pdf_sha256) != 64
            or expected_pdf_sha256 is not None
            and pdf_sha256 != expected_pdf_sha256
        ):
            raise BundleError(f"published bundle has a stale PDF identity: {filename}")
        proposal_ids = {
            value
            for value in pdf.get("proposal_ids", [])
            if isinstance(value, str)
        }
        proposals = [
            value
            for value in legacy.get("agent_proposals", [])
            if isinstance(value, dict)
            and (
                value.get("id") in proposal_ids
                or filename in value.get("pdf_filenames", [])
            )
        ]
        source_paths = {
            value.get("source_file")
            for value in proposals
            if isinstance(value.get("source_file"), str)
        }
        for segment in pdf.get("segments", []):
            if not isinstance(segment, dict):
                continue
            if isinstance(segment.get("source_id"), str):
                source_paths.add(segment["source_id"])
            for value in segment.get("inputs", []):
                if isinstance(value, dict) and isinstance(value.get("source_file"), str):
                    source_paths.add(value["source_file"])
        sources = sorted(
            (
                value
                for value in legacy.get("sources", [])
                if isinstance(value, dict) and value.get("path") in source_paths
            ),
            key=lambda value: value["path"],
        )
        proposal_objects = []
        for proposal in sorted(proposals, key=lambda value: value["id"]):
            proposal_raw = (
                json.dumps(
                    {
                        "schema_version": 1,
                        "kind": "thoughts-agent-proposal",
                        **proposal,
                    },
                    ensure_ascii=False,
                    sort_keys=True,
                    separators=(",", ":"),
                )
                + "\n"
            ).encode("utf-8")
            proposal_objects.append(
                {"id": proposal["id"], "object": self._store_object(proposal_raw)}
            )
        manifest = {
            "schema_version": 1,
            "kind": "thoughts-document-source-artifact",
            "filename": filename,
            "pdf": pdf,
            "sources": sources,
            "agent_proposals": sorted(proposals, key=lambda value: value["id"]),
            "proposal_objects": proposal_objects,
        }
        manifest_raw = (
            json.dumps(
                manifest, ensure_ascii=False, sort_keys=True, separators=(",", ":")
            )
            + "\n"
        ).encode("utf-8")
        artifact_id = self._store_object(manifest_raw)
        pointer_path = self.output / "documents" / f"{filename}.json"
        pointer = {
            "schema_version": 1,
            "kind": "thoughts-document-source-artifact-pointer",
            "filename": filename,
            "artifact_sha256": artifact_id,
            "manifest_object": artifact_id,
            "pdf_sha256": pdf_sha256,
            "source_count": len(sources),
            "proposal_count": len(proposals),
            "migrated_from_bundle_sha256": bundle_id,
        }
        changed = atomic_write_if_changed(
            pointer_path,
            (
                json.dumps(
                    pointer, ensure_ascii=False, sort_keys=True, separators=(",", ":")
                )
                + "\n"
            ).encode("utf-8"),
        )
        atomic_write_json(
            self.ledger / "documents" / filename / f"{artifact_id}.json",
            {
                "schema_version": 1,
                "artifact_sha256": artifact_id,
                "manifest": manifest,
            },
        )
        return artifact_id, len(sources), len(proposals), changed


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--docs-root", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--ledger", type=Path)
    parser.add_argument(
        "--pdf", help="Publish only this PDF's immutable source artifact"
    )
    parser.add_argument(
        "--migrate-global-pdf",
        help="Migrate one last-good PDF entry from the legacy global bundle",
    )
    parser.add_argument("--expected-pdf-sha256")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    docs_root = args.docs_root.resolve()
    configured_output = os.environ.get("THOUGHTS_SOURCE_BUNDLE_OUTPUT")
    output = args.output or (
        Path(configured_output)
        if configured_output
        else docs_root / ".runtime" / "target" / "source-bundle"
    )
    ledger = (
        args.ledger
        or docs_root / ".runtime" / "target" / "phone-source" / "bundle-ledger"
    )
    try:
        publisher = PhoneSourceBundlePublisher(
            docs_root,
            output,
            ledger,
        )
        if args.pdf and args.migrate_global_pdf:
            raise BundleError("--pdf and --migrate-global-pdf are mutually exclusive")
        if args.migrate_global_pdf:
            artifact_id, source_count, proposal_count, changed = (
                publisher.migrate_document_from_global_bundle(
                    args.migrate_global_pdf,
                    args.expected_pdf_sha256,
                )
            )
        elif args.pdf:
            artifact_id, source_count, proposal_count, changed = (
                publisher.publish_document(args.pdf)
            )
        else:
            bundle_id, pdf_count, source_count, changed = publisher.publish()
    except (BundleError, OSError) as error:
        print(f"Error: {error}", file=os.sys.stderr)
        return 1
    action = "Published" if changed else "Unchanged"
    artifact_filename = args.migrate_global_pdf or args.pdf
    if artifact_filename:
        print(
            f"{action}: document source artifact {artifact_id} "
            f"({artifact_filename}, {source_count} sources, {proposal_count} proposals)"
        )
        pointer = json.loads(
            (output / "documents" / f"{artifact_filename}.json").read_text(
                encoding="utf-8"
            )
        )
        print(
            f"THOUGHTS_ARTIFACT\t{artifact_filename}\t{artifact_id}\t"
            f"{pointer['pdf_sha256']}"
        )
    else:
        print(
            f"{action}: offline source bundle {bundle_id} ({pdf_count} PDFs, {source_count} sources)"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
