# Written for Thoughts. License is GPLv3.

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import comment_sidecars
import review_resolutions


class ReviewResolutionsTest(unittest.TestCase):
    def test_resolution_is_idempotent_and_suppresses_every_version(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            comments_dir = Path(temp)
            comment = {
                "id": "shared-id",
                "page": 2,
                "subtype": "Text",
                "rect": [1, 2, 3, 4],
                "contents": "resolve me",
                "revision": 4,
            }

            first = review_resolutions.record_resolutions(
                comments_dir,
                [comment],
                deleted_at="2026-07-25T12:00:00Z",
            )
            second = review_resolutions.record_resolutions(
                comments_dir,
                [comment],
                deleted_at="2026-07-25T13:00:00Z",
            )

            self.assertEqual(first[0]["revision"], 5)
            self.assertEqual(second[0], first[0])
            kept, suppressed = review_resolutions.suppress(
                comments_dir,
                [
                    comment,
                    {
                        **comment,
                        "id": "other-id",
                        "contents": "keep me",
                    },
                ],
            )
            self.assertEqual(suppressed, 1)
            self.assertEqual([record["id"] for record in kept], ["other-id"])

    def test_registry_conflict_uses_shared_revision_order(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            comments_dir = Path(temp)
            path = review_resolutions.registry_path(comments_dir)
            conflict = comments_dir / (
                "review_resolutions.sync-conflict-20260725-phone.jsonl"
            )
            older = {
                "id": "shared-id",
                "deleted": True,
                "source_anchor_deletion": True,
                "revision": 2,
                "updated_at": "2026-07-25T10:00:00Z",
                "origin_id": "mac",
            }
            newer = {
                **older,
                "revision": 3,
                "updated_at": "2026-07-25T11:00:00Z",
                "origin_id": "phone",
            }
            comment_sidecars.atomic_write_jsonl([older], path)
            comment_sidecars.atomic_write_jsonl([newer], conflict)

            records, conflicts = review_resolutions.reconcile_registry(
                comments_dir
            )

            self.assertEqual(conflicts, 1)
            self.assertEqual(records[0]["revision"], 3)
            self.assertFalse(conflict.exists())

    def test_legacy_global_state_is_imported_without_removal(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            comments_dir = Path(temp)
            legacy = comments_dir / review_resolutions.LEGACY_RELATIVE_PATH
            legacy.parent.mkdir(parents=True)
            legacy.write_text(
                json.dumps(
                    {
                        "version": 1,
                        "tombstones": {
                            "id:legacy-id": {
                                "comment_key": "id:legacy-id",
                                "deleted_at": "2026-07-25T09:00:00Z",
                                "previous_annotation": {
                                    "id": "legacy-id",
                                    "revision": 7,
                                },
                            }
                        },
                    }
                ),
                encoding="utf-8",
            )

            self.assertEqual(
                review_resolutions.resolved_ids(comments_dir),
                {"legacy-id"},
            )
            records = comment_sidecars.read_jsonl(
                review_resolutions.registry_path(comments_dir)
            )
            self.assertEqual(records[0]["revision"], 8)
            self.assertTrue(legacy.exists())


if __name__ == "__main__":
    unittest.main()
