# Written for Thoughts. License is GPLv3.

from __future__ import annotations

import hashlib
import gzip
import io
import importlib.util
import json
import os
import sys
import tempfile
import unittest
import zipfile
from unittest import mock
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
TOOLS = ROOT / "tools"
SPEC = importlib.util.spec_from_file_location(
    "publish_phone_source_bundle",
    TOOLS / "publish_phone_source_bundle.py",
)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class PhoneSourceBundlePublisherTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.docs = self.root / "docs"
        self.output = self.root / "server-state" / "source-bundle"
        self.ledger = (
            self.docs / ".runtime" / "target" / "phone-source" / "bundle-ledger"
        )
        current = self.docs / "current_pdfs"
        layout = self.docs / "comments" / ".layout"
        build = self.docs / ".runtime" / "comment_anchors" / "builds" / "build-id"
        snapshot = build / "sources" / "0001-source.tex"
        current.mkdir(parents=True)
        layout.mkdir(parents=True)
        snapshot.parent.mkdir(parents=True)
        (self.docs / "docs-sample/tex/source").mkdir(parents=True)
        (current / "sample.pdf").write_bytes(b"canonical-pdf")
        (current / ".thoughts-source-index.json").write_text(
            json.dumps(
                {
                    "schema_version": 1,
                    "pdfs": [{"filename": "sample.pdf", "project": "docs-sample"}],
                }
            ),
            encoding="utf-8",
        )
        fingerprint = "a" * 64
        (layout / "sample.json").write_text(
            json.dumps(
                {
                    "version": 1,
                    "page_count": 2,
                    "sources": [
                        {
                            "start_page": 1,
                            "page_count": 2,
                            "source_id": "docs-sample/tex/source/main.tex",
                            "fingerprint": fingerprint,
                        }
                    ],
                }
            ),
            encoding="utf-8",
        )
        contents = (
            b"first line\n"
            b"\\agentRemove{agent-remove-11111111-1111-4111-8111-111111111111}"
            b"{outdated}{second line}\n"
        )
        snapshot.write_bytes(contents)
        (build / "document.synctex.gz").write_bytes(b"synctex")
        (build / "mapping.json").write_text(
            json.dumps(
                {
                    "source_id": "docs-sample/tex/source/main.tex",
                    "source_fingerprint": fingerprint,
                    "source_tex": "docs-sample/tex/source/main.tex",
                    "synctex_name": "document.synctex.gz",
                    "source_snapshots": [
                        {
                            "classification": "authored",
                            "source_id": "docs-sample/tex/source/main.tex",
                            "source_tex_absolute": "/workspace/docs-sample/tex/source/main.tex",
                            "snapshot": "sources/0001-source.tex",
                            "sha256": hashlib.sha256(contents).hexdigest(),
                        }
                    ],
                }
            ),
            encoding="utf-8",
        )

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_publishes_hash_verified_manifest_objects_and_ledger(self) -> None:
        bundle_id, pdf_count, source_count, changed = MODULE.PhoneSourceBundlePublisher(
            self.docs,
            self.output,
            self.ledger,
        ).publish()

        self.assertTrue(changed)
        self.assertEqual((pdf_count, source_count), (1, 1))
        index = json.loads((self.output / "index.json").read_text(encoding="utf-8"))
        self.assertEqual(index["bundle_id"], bundle_id)
        manifest_raw = (self.output / "objects" / bundle_id).read_bytes()
        self.assertEqual(hashlib.sha256(manifest_raw).hexdigest(), bundle_id)
        manifest = json.loads(manifest_raw)
        self.assertEqual(manifest["schema_version"], 3)
        self.assertEqual(manifest["pdfs"][0]["segments"][0]["start_page"], 1)
        self.assertEqual(
            manifest["pdfs"][0]["segments"][0]["inputs"][0]["source_file"],
            "docs-sample/tex/source/main.tex",
        )
        self.assertEqual(
            manifest["pdfs"][0]["proposal_ids"],
            ["agent-remove-11111111-1111-4111-8111-111111111111"],
        )
        self.assertEqual(manifest["agent_proposals"][0]["body_preview"], "second line")
        self.assertEqual(manifest["agent_proposals"][0]["kind"], "remove")
        self.assertEqual(
            manifest["agent_proposals"][0]["before"]["preview"], "second line"
        )
        self.assertEqual(manifest["agent_proposals"][0]["after"]["preview"], "")
        self.assertIsNone(manifest["agent_proposals"][0]["parent_id"])
        self.assertEqual(
            manifest["agent_proposals"][0]["placement"]["status"], "pending"
        )
        self.assertTrue((self.ledger / f"{bundle_id}.json").is_file())

        second = MODULE.PhoneSourceBundlePublisher(
            self.docs, self.output, self.ledger
        ).publish()
        self.assertFalse(second[3])

    def test_publishes_one_document_artifact_without_rewriting_global_index(
        self,
    ) -> None:
        global_index = self.output / "index.json"
        global_index.parent.mkdir(parents=True)
        global_index.write_text(
            '{"schema_version":3,"bundle_id":"legacy"}\n', encoding="utf-8"
        )
        (self.docs / "current_pdfs/.thoughts-source-index.json").unlink()

        artifact_id, source_count, proposal_count, changed = (
            MODULE.PhoneSourceBundlePublisher(
                self.docs,
                self.output,
                self.ledger,
            ).publish_document("sample.pdf")
        )

        self.assertTrue(changed)
        self.assertEqual((source_count, proposal_count), (1, 1))
        self.assertEqual(
            global_index.read_text(encoding="utf-8"),
            '{"schema_version":3,"bundle_id":"legacy"}\n',
        )
        pointer = json.loads(
            (self.output / "documents/sample.pdf.json").read_text(encoding="utf-8")
        )
        self.assertEqual(pointer["artifact_sha256"], artifact_id)
        manifest_raw = (self.output / "objects" / artifact_id).read_bytes()
        self.assertEqual(hashlib.sha256(manifest_raw).hexdigest(), artifact_id)
        manifest = json.loads(manifest_raw)
        self.assertEqual(manifest["kind"], "thoughts-document-source-artifact")
        self.assertEqual(manifest["filename"], "sample.pdf")
        self.assertEqual(
            [value["filename"] for value in [manifest["pdf"]]], ["sample.pdf"]
        )

        second = MODULE.PhoneSourceBundlePublisher(
            self.docs,
            self.output,
            self.ledger,
        ).publish_document("sample.pdf")
        self.assertFalse(second[3])

    def test_resolves_exact_embedded_pdf_to_its_authored_tex_build(self) -> None:
        fingerprint = "a" * 64
        layout = self.docs / "comments" / ".layout"
        (layout / "sample.json").write_text(
            json.dumps(
                {
                    "version": 1,
                    "page_count": 2,
                    "sources": [
                        {
                            "start_page": 1,
                            "page_count": 2,
                            "source_id": "docs-child/pdf/child.pdf",
                            "source_pdf": "docs-child/pdf/child.pdf",
                            "fingerprint": fingerprint,
                        }
                    ],
                }
            ),
            encoding="utf-8",
        )
        (layout / "child.json").write_text(
            json.dumps(
                {
                    "version": 1,
                    "page_count": 2,
                    "sources": [
                        {
                            "start_page": 17,
                            "page_count": 2,
                            "source_id": "docs-child/tex/source/main.tex",
                            "source_pdf": "docs-child/pdf/child.pdf",
                            "fingerprint": "b" * 64,
                            "build_id": "build-id",
                        }
                    ],
                }
            ),
            encoding="utf-8",
        )
        build = self.docs / ".runtime" / "comment_anchors" / "builds" / "build-id"
        mapping = json.loads((build / "mapping.json").read_text(encoding="utf-8"))
        mapping["source_id"] = "docs-child/tex/source/main.tex"
        mapping["source_tex"] = "docs-child/tex/source/main.tex"
        mapping["source_snapshots"][0]["source_id"] = "docs-child/tex/source/main.tex"
        mapping["source_snapshots"][0]["source_tex_absolute"] = (
            "/workspace/docs-child/tex/source/main.tex"
        )
        (build / "mapping.json").write_text(json.dumps(mapping), encoding="utf-8")

        bundle_id, _pdf_count, _source_count, _changed = (
            MODULE.PhoneSourceBundlePublisher(
                self.docs,
                self.output,
                self.ledger,
            ).publish()
        )

        manifest = json.loads((self.output / "objects" / bundle_id).read_text())
        segment = manifest["pdfs"][0]["segments"][0]
        self.assertTrue(segment["source_available"])
        self.assertEqual(segment["source_id"], "docs-child/tex/source/main.tex")
        self.assertEqual(segment["source_pdf"], "docs-child/pdf/child.pdf")
        self.assertEqual(segment["start_page"], 1)
        self.assertEqual(
            segment["inputs"][0]["source_file"],
            "docs-child/tex/source/main.tex",
        )

    def test_migrates_matching_document_from_legacy_global_bundle(self) -> None:
        publisher = MODULE.PhoneSourceBundlePublisher(
            self.docs,
            self.output,
            self.ledger,
        )
        publisher.publish()
        expected_pdf_sha256 = hashlib.sha256(b"canonical-pdf").hexdigest()

        artifact_id, source_count, proposal_count, changed = (
            publisher.migrate_document_from_global_bundle(
                "sample.pdf",
                expected_pdf_sha256,
            )
        )

        self.assertTrue(changed)
        self.assertEqual((source_count, proposal_count), (1, 1))
        pointer = json.loads(
            (self.output / "documents/sample.pdf.json").read_text(encoding="utf-8")
        )
        self.assertEqual(pointer["artifact_sha256"], artifact_id)
        self.assertEqual(pointer["pdf_sha256"], expected_pdf_sha256)
        manifest = json.loads((self.output / "objects" / artifact_id).read_text())
        self.assertEqual(manifest["pdf"]["filename"], "sample.pdf")
        self.assertEqual(len(manifest["proposal_objects"]), 1)

    def test_parser_tracks_suppressed_proposals(self) -> None:
        source = (
            r"\agentRemove{agent-remove-cccccccc-cccc-4ccc-8ccc-cccccccccccc}{structural}{"
            r"\agentRemovedBlock{\newcommand{\legacy}{value}}}"
        ).encode("utf-8")

        proposals = MODULE.parse_agent_proposals(
            "docs-sample/tex/source/main.tex", source
        )

        self.assertEqual(len(proposals), 1)
        self.assertIsNone(proposals[0]["parent_id"])
        self.assertTrue(proposals[0]["suppressed"])
        self.assertEqual(proposals[0]["placement"]["status"], "source_only")

    def test_parser_tracks_fancyvrb_command_delimiters(self) -> None:
        proposal_id = "agent-remove-dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        source = (
            f"|agentRemove({proposal_id})(obsolete flag)(  [--old-flag] \\)\n".encode()
        )

        proposals = MODULE.parse_agent_proposals(
            "docs-sample/tex/source/main.tex", source
        )

        self.assertEqual(len(proposals), 1)
        self.assertEqual(proposals[0]["id"], proposal_id)
        self.assertEqual(proposals[0]["body_preview"], "[--old-flag] \\")
        self.assertFalse(proposals[0]["suppressed"])

    def test_parser_tracks_edit_before_and_after_content(self) -> None:
        source = (
            "before\n"
            r"\agentProposedEdit{objective-label-20260820}{The label was stale.}"
            r"{Standard SDPO}{On-policy SDPO}"
            "\nafter\n"
        ).encode("utf-8")

        proposals = MODULE.parse_agent_proposals(
            "docs-sample/tex/source/main.tex", source
        )

        self.assertEqual(len(proposals), 1)
        proposal = proposals[0]
        self.assertEqual(proposal["kind"], "edit")
        self.assertEqual(proposal["before"]["preview"], "Standard SDPO")
        self.assertEqual(proposal["after"]["preview"], "On-policy SDPO")
        self.assertEqual(
            source[proposal["before"]["byte_start"] : proposal["before"]["byte_end"]],
            b"Standard SDPO",
        )
        self.assertEqual(
            source[proposal["after"]["byte_start"] : proposal["after"]["byte_end"]],
            b"On-policy SDPO",
        )

    def test_parser_tracks_multiline_addition(self) -> None:
        source = "\n".join(
            [
                r"\agentProposedEdit",
                "  {multiline-addition}",
                "  {This adds the missing explanation.}",
                "  {}",
                "  {The first sentence is on one line.",
                "   The second sentence is on another line.}",
            ]
        ).encode("utf-8")

        proposals = MODULE.parse_agent_proposals(
            "docs-sample/tex/source/main.tex", source
        )

        self.assertEqual(len(proposals), 1)
        proposal = proposals[0]
        self.assertEqual(proposal["id"], "multiline-addition")
        self.assertEqual(proposal["before"]["preview"], "")
        self.assertIn("The first sentence", proposal["after"]["preview"])

    def test_parser_tracks_paired_review_card_markers_and_ignores_comments(self) -> None:
        card_id = "11111111-2222-4333-8444-555555555555"
        source = (
            f"% \\ReviewCardMaskStart{{{card_id}}}{{ignored}}\n"
            f"\\ReviewCardMaskStart{{{card_id}}}{{answer}}%\n"
            "The durable answer remains rendered.\n"
            f"\\ReviewCardMaskEnd{{{card_id}}}{{answer}}%\n"
        ).encode("utf-8")

        anchors = MODULE.parse_review_card_anchors(
            "docs-sample/tex/source/main.tex", source
        )

        self.assertEqual(len(anchors), 1)
        self.assertEqual(anchors[0]["card_id"], card_id)
        self.assertEqual(anchors[0]["segment_id"], "answer")
        self.assertEqual(anchors[0]["line_start"], 2)
        self.assertEqual(anchors[0]["line_end"], 4)
        self.assertIn("durable answer", anchors[0]["content_preview"])

    def test_review_card_fragments_are_normalized_and_page_scoped(self) -> None:
        from pypdf import PdfWriter

        build = self.root / "review-card-build"
        build.mkdir()
        writer = PdfWriter()
        writer.add_blank_page(width=600, height=800)
        with (build / "document.pdf").open("wb") as stream:
            writer.write(stream)
        card_id = "11111111-2222-4333-8444-555555555555"
        publisher = MODULE.PhoneSourceBundlePublisher(
            self.docs, self.output, self.ledger
        )
        publisher.review_card_anchors[(card_id, "answer")] = {
            "card_id": card_id,
            "segment_id": "answer",
            "source_file": "docs-sample/tex/source/main.tex",
        }
        inputs = [
            {
                "source_file": "docs-sample/tex/source/main.tex",
                "synctex_path": "/workspace/docs-sample/tex/source/main.tex",
            }
        ]

        fragments = publisher._build_review_card_fragments(
            MODULE.BuildRecord(build, {}),
            inputs,
            {(f"{card_id}:answer", "mask"): {1: [(60.0, 80.0, 300.0, 240.0)]}},
        )

        self.assertEqual(
            fragments[(card_id, "answer")],
            [{"page": 1, "left": 0.1, "top": 0.1, "right": 0.5, "bottom": 0.3}],
        )

    def test_review_card_fragments_ignore_page_container_and_choose_dense_column(self) -> None:
        from pypdf import PdfWriter

        build = self.root / "review-card-column-build"
        build.mkdir()
        writer = PdfWriter()
        writer.add_blank_page(width=600, height=800)
        with (build / "document.pdf").open("wb") as stream:
            writer.write(stream)
        card_id = "11111111-2222-4333-8444-555555555555"
        publisher = MODULE.PhoneSourceBundlePublisher(self.docs, self.output, self.ledger)
        publisher.review_card_anchors[(card_id, "answer")] = {
            "card_id": card_id,
            "segment_id": "answer",
            "source_file": "docs-sample/tex/source/main.tex",
        }
        inputs = [{"source_file": "docs-sample/tex/source/main.tex", "synctex_path": "/workspace/main.tex"}]
        boxes = [
            (50.0, 50.0, 300.0, 750.0),
            (50.0, 650.0, 290.0, 665.0),
            (320.0, 100.0, 560.0, 115.0),
            (320.0, 120.0, 560.0, 135.0),
            (320.0, 140.0, 560.0, 155.0),
        ]

        fragments = publisher._build_review_card_fragments(
            MODULE.BuildRecord(build, {}),
            inputs,
            {(f"{card_id}:answer", "mask"): {1: boxes}},
        )

        region = fragments[(card_id, "answer")][0]
        self.assertGreater(region["left"], 0.5)
        self.assertLess(region["bottom"], 0.25)

    def test_review_card_fragments_replace_tiny_native_marker_box(self) -> None:
        from pypdf import PdfWriter

        build = self.root / "review-card-tiny-build"
        build.mkdir()
        writer = PdfWriter()
        writer.add_blank_page(width=600, height=800)
        with (build / "document.pdf").open("wb") as stream:
            writer.write(stream)
        card_id = "11111111-2222-4333-8444-555555555555"
        publisher = MODULE.PhoneSourceBundlePublisher(self.docs, self.output, self.ledger)
        publisher.review_card_anchors[(card_id, "answer")] = {
            "card_id": card_id,
            "segment_id": "answer",
            "source_file": "docs-sample/tex/source/main.tex",
            "line_start": 10,
            "line_end": 12,
        }
        inputs = [
            {
                "source_file": "docs-sample/tex/source/main.tex",
                "synctex_path": "/workspace/main.tex",
            }
        ]
        fallback = {1: [(320.0, 100.0, 560.0, 150.0)]}

        with mock.patch.object(
            publisher,
            "_synctex_line_boxes",
            return_value=fallback,
        ) as line_boxes:
            fragments = publisher._build_review_card_fragments(
                MODULE.BuildRecord(build, {}),
                inputs,
                {(f"{card_id}:answer", "mask"): {1: [(320.0, 100.0, 330.0, 104.0)]}},
            )

        line_boxes.assert_called_once()
        self.assertEqual(
            fragments[(card_id, "answer")],
            [
                {
                    "page": 1,
                    "left": 320.0 / 600.0,
                    "top": 100.0 / 800.0,
                    "right": 560.0 / 600.0,
                    "bottom": 150.0 / 800.0,
                }
            ],
        )

    def test_review_card_fragments_fall_back_without_native_analysis(self) -> None:
        from pypdf import PdfWriter

        build = self.root / "review-card-no-native-build"
        build.mkdir()
        writer = PdfWriter()
        writer.add_blank_page(width=600, height=800)
        with (build / "document.pdf").open("wb") as stream:
            writer.write(stream)
        card_id = "11111111-2222-4333-8444-555555555555"
        publisher = MODULE.PhoneSourceBundlePublisher(
            self.docs,
            self.output,
            self.ledger,
        )
        publisher.review_card_anchors[(card_id, "answer")] = {
            "card_id": card_id,
            "segment_id": "answer",
            "source_file": "docs-sample/tex/source/main.tex",
            "line_start": 10,
            "line_end": 12,
        }
        inputs = [
            {
                "source_file": "docs-sample/tex/source/main.tex",
                "synctex_path": "/workspace/main.tex",
            }
        ]
        fallback = {1: [(60.0, 80.0, 300.0, 240.0)]}

        with mock.patch.object(
            publisher,
            "_synctex_line_boxes",
            return_value=fallback,
        ) as line_boxes:
            fragments = publisher._build_review_card_fragments(
                MODULE.BuildRecord(build, {}),
                inputs,
                None,
            )

        line_boxes.assert_called_once()
        self.assertEqual(
            fragments[(card_id, "answer")],
            [{"page": 1, "left": 0.1, "top": 0.1, "right": 0.5, "bottom": 0.3}],
        )

    def test_parser_rejects_all_nested_proposal_types(self) -> None:
        parent_remove = "agent-remove-aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        child_remove = "agent-remove-bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        cases = [
            (
                rf"\agentRemove{{{parent_remove}}}{{outer}}{{before "
                rf"\agentRemove{{{child_remove}}}{{inner}}{{inside}} after}}",
                parent_remove,
                child_remove,
            ),
            (
                rf"\agentRemove{{{parent_remove}}}{{outer}}{{before "
                r"\agentProposedEdit{inner-edit}{reason}{old}{new} after}",
                parent_remove,
                "inner-edit",
            ),
            (
                r"\agentProposedEdit{outer-edit}{reason}{before "
                rf"\agentRemove{{{child_remove}}}{{inner}}{{inside}} after}}{{new}}",
                "outer-edit",
                child_remove,
            ),
            (
                r"\agentProposedEdit{outer-edit}{reason}{old}{new "
                r"\agentProposedEdit{inner-edit}{reason}{old}{new}}",
                "outer-edit",
                "inner-edit",
            ),
        ]

        for source, parent_id, child_id in cases:
            with self.subTest(parent_id=parent_id, child_id=child_id):
                with self.assertRaisesRegex(
                    MODULE.BundleError,
                    rf"cannot be nested.*{parent_id} contains {child_id}",
                ):
                    MODULE.parse_agent_proposals(
                        "docs-sample/tex/source/main.tex", source.encode("utf-8")
                    )

    def test_link_geometry_does_not_require_pdfplumber(self) -> None:
        from pypdf import PdfWriter

        build = self.root / "link-build"
        build.mkdir()
        writer = PdfWriter()
        writer.add_blank_page(width=600, height=800)
        with (build / "document.pdf").open("wb") as stream:
            writer.write(stream)
        proposal_id = "edit-with-link-geometry"
        publisher = MODULE.PhoneSourceBundlePublisher(
            self.docs, self.output, self.ledger
        )
        publisher.proposals = {
            proposal_id: {
                "id": proposal_id,
                "kind": "edit",
                "source_file": "docs-sample/tex/source/main.tex",
                "suppressed": False,
                "before": {"line_start": 1, "line_end": 1},
                "after": {"line_start": 1, "line_end": 1},
            }
        }
        publisher.source_objects = {
            "docs-sample/tex/source/main.tex": {"proposal_ids": [proposal_id]}
        }
        inputs = [
            {
                "source_file": "docs-sample/tex/source/main.tex",
                "synctex_path": "/workspace/docs-sample/tex/source/main.tex",
            }
        ]
        linked = {
            proposal_id: {
                "before": [
                    {
                        "page": 1,
                        "left": 10.0,
                        "top": 20.0,
                        "right": 30.0,
                        "bottom": 40.0,
                    }
                ],
                "after": [
                    {
                        "page": 1,
                        "left": 50.0,
                        "top": 60.0,
                        "right": 70.0,
                        "bottom": 80.0,
                    }
                ],
            }
        }
        real_import = __import__

        def import_without_pdfplumber(name, *args, **kwargs):
            if name == "pdfplumber":
                raise ImportError("pdfplumber is intentionally unavailable")
            return real_import(name, *args, **kwargs)

        with (
            mock.patch.object(
                publisher, "_proposal_link_fragments", return_value=linked
            ),
            mock.patch("builtins.__import__", side_effect=import_without_pdfplumber),
        ):
            fragments = publisher._build_proposal_fragments(
                MODULE.BuildRecord(build, {}), inputs
            )

        self.assertEqual(fragments, linked)

    def test_native_worker_batches_all_synctex_line_queries(self) -> None:
        worker = self.root / "source-artifact-worker"
        calls = self.root / "worker-calls.txt"
        worker.write_text(
            "#!/usr/bin/env python3\n"
            "import os, sys\n"
            "requests = sys.stdin.read()\n"
            "open(os.environ['WORKER_CALLS'], 'a', encoding='utf-8').write(requests)\n"
            "for line in requests.splitlines():\n"
            "    request_id = line.split('\\t', 1)[0]\n"
            "    print(f'{request_id}\\t2\\t10\\t20\\t30\\t40')\n",
            encoding="utf-8",
        )
        worker.chmod(0o755)
        with mock.patch.dict(
            os.environ,
            {
                "THOUGHTS_SOURCE_ARTIFACT_WORKER": str(worker),
                "WORKER_CALLS": str(calls),
            },
        ):
            publisher = MODULE.PhoneSourceBundlePublisher(
                self.docs, self.output, self.ledger
            )
            boxes = publisher._native_synctex_line_boxes(
                self.root / "document.pdf",
                [
                    (
                        "proposal-one",
                        "before",
                        {"synctex_path": "/workspace/main.tex"},
                        {"line_start": 3, "line_end": 9},
                    ),
                    (
                        "proposal-two",
                        "after",
                        {"synctex_path": "/workspace/appendix.tex"},
                        {"line_start": 40, "line_end": 41},
                    ),
                ],
            )

        self.assertEqual(
            calls.read_text(encoding="utf-8").splitlines(),
            [
                "0\t/workspace/main.tex\t3\t9",
                "1\t/workspace/appendix.tex\t40\t41",
            ],
        )
        self.assertEqual(
            boxes[("proposal-one", "before")][2], [(10.0, 20.0, 30.0, 40.0)]
        )
        self.assertEqual(
            boxes[("proposal-two", "after")][2], [(10.0, 20.0, 30.0, 40.0)]
        )

    def test_reverse_synctex_index_is_page_scoped_and_deterministic(self) -> None:
        raw = io.BytesIO()
        with gzip.GzipFile(fileobj=raw, mode="wb", mtime=0) as stream:
            stream.write(
                b"SyncTeX Version:1\n"
                b"Input:1:/workspace/docs-sample/tex/source/main.tex\n"
                b"Unit:1\nMagnification:1000\nX Offset:0\nY Offset:0\n"
                b"{1\n[1,2:65782,131564:65782,65782,0\n}\n"
                b"{2\nx1,3:197345,263127\n}\n"
            )
        inputs = [
            {
                "synctex_path": "/workspace/docs-sample/tex/source/main.tex",
                "source_file": "docs-sample/tex/source/main.tex",
                "source_sha256": "a" * 64,
            }
        ]

        first = MODULE.reverse_synctex_index(raw.getvalue(), inputs)
        second = MODULE.reverse_synctex_index(raw.getvalue(), inputs)

        self.assertIsNotNone(first)
        self.assertEqual(first, second)
        with zipfile.ZipFile(io.BytesIO(first)) as archive:
            self.assertIn("pages/1.tsv", archive.namelist())
            self.assertIn("pages/2.tsv", archive.namelist())
            self.assertIn(
                "/workspace/docs-sample/tex/source/main.tex",
                archive.read("pages/1.tsv").decode(),
            )


if __name__ == "__main__":
    unittest.main()
