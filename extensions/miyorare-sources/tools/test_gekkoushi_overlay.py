from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

import gekkoushi_overlay


def blob_sha(data: bytes) -> str:
    return hashlib.sha1(f"blob {len(data)}\0".encode("ascii") + data).hexdigest()


class GekkoushiOverlayTest(unittest.TestCase):
    def fixture(self) -> tuple[tempfile.TemporaryDirectory[str], Path, Path]:
        temp = tempfile.TemporaryDirectory()
        root = Path(temp.name)
        miyorare = root / "miyorare-sources"
        upstream = root / "gekkoushi"
        return temp, miyorare, upstream

    def write_replacement(self, miyorare: Path, *, expected: str, target: str = "madara/en/X.kt") -> None:
        overlay = miyorare / "overlays/gekkoushi/en"
        (overlay / "_replacements").mkdir(parents=True, exist_ok=True)
        (overlay / "_replacements/X.kt").write_text("replacement\n", encoding="utf-8")
        (overlay / "_replacements.json").write_text(
            json.dumps({
                "schema": 1,
                "replacements": [{
                    "source": "_replacements/X.kt",
                    "target": target,
                    "expectedBlobSha": expected,
                }],
            }),
            encoding="utf-8",
        )

    def test_direct_and_exact_replacement_overlays_apply(self) -> None:
        temp, miyorare, upstream = self.fixture()
        self.addCleanup(temp.cleanup)
        old = b"upstream\n"
        target = upstream / "src/main/kotlin/tsuki/site/madara/en/X.kt"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(old)
        direct = miyorare / "overlays/gekkoushi/en/Direct.kt"
        direct.parent.mkdir(parents=True, exist_ok=True)
        direct.write_text("direct\n", encoding="utf-8")
        self.write_replacement(miyorare, expected=blob_sha(old))

        applied = gekkoushi_overlay.apply_miyorare_overlays(miyorare, upstream, "en")

        self.assertEqual(target.read_text(encoding="utf-8"), "replacement\n")
        self.assertEqual(
            (upstream / "src/main/kotlin/tsuki/site/en/Direct.kt").read_text(encoding="utf-8"),
            "direct\n",
        )
        self.assertEqual(applied, ["Direct.kt", "replace:madara/en/X.kt"])

    def test_replacement_fails_closed_on_upstream_drift(self) -> None:
        temp, miyorare, upstream = self.fixture()
        self.addCleanup(temp.cleanup)
        target = upstream / "src/main/kotlin/tsuki/site/madara/en/X.kt"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text("drifted\n", encoding="utf-8")
        self.write_replacement(miyorare, expected=blob_sha(b"expected\n"))

        with self.assertRaisesRegex(SystemExit, "target drifted"):
            gekkoushi_overlay.apply_miyorare_overlays(miyorare, upstream, "en")
        self.assertEqual(target.read_text(encoding="utf-8"), "drifted\n")

    def test_orphan_replacement_file_fails_closed(self) -> None:
        temp, miyorare, upstream = self.fixture()
        self.addCleanup(temp.cleanup)
        overlay = miyorare / "overlays/gekkoushi/en/_replacements"
        overlay.mkdir(parents=True, exist_ok=True)
        (overlay / "X.kt").write_text("x\n", encoding="utf-8")

        with self.assertRaisesRegex(SystemExit, "manifest.*missing"):
            gekkoushi_overlay.apply_miyorare_overlays(miyorare, upstream, "en")

    def test_target_path_traversal_is_rejected(self) -> None:
        temp, miyorare, upstream = self.fixture()
        self.addCleanup(temp.cleanup)
        self.write_replacement(miyorare, expected="0" * 40, target="../outside.kt")

        with self.assertRaisesRegex(SystemExit, "must stay inside"):
            gekkoushi_overlay.apply_miyorare_overlays(miyorare, upstream, "en")


if __name__ == "__main__":
    unittest.main()
