#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("release_version", ROOT / "release_version.py")
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


class ReleaseVersionTest(unittest.TestCase):
    def test_automatic_continues_from_latest_published_stable(self) -> None:
        self.assertEqual(
            module.resolve_version("1.2.0", ["v1.2.0", "v1.3.5", "v1.3.3"]),
            "1.3.6",
        )

    def test_higher_gradle_base_becomes_next_release(self) -> None:
        self.assertEqual(
            module.resolve_version("1.4.0", ["v1.3.5"]),
            "1.4.0",
        )

    def test_equal_base_is_incremented(self) -> None:
        self.assertEqual(
            module.resolve_version("1.3.5", ["v1.3.5"]),
            "1.3.6",
        )

    def test_ignores_non_stable_tags(self) -> None:
        self.assertEqual(
            module.resolve_version("1.2.0", ["v1.3.5-beta", "miyorare-sources-v0.5.3"]),
            "1.2.0",
        )

    def test_manual_version_must_be_greater_than_latest(self) -> None:
        with self.assertRaisesRegex(ValueError, "must be greater"):
            module.resolve_version("1.2.0", ["v1.3.5"], "1.3.5")
        with self.assertRaisesRegex(ValueError, "must be greater"):
            module.resolve_version("1.2.0", ["v1.3.5"], "1.3.4")

    def test_manual_higher_version_is_accepted(self) -> None:
        self.assertEqual(
            module.resolve_version("1.2.0", ["v1.3.5"], "1.4.0"),
            "1.4.0",
        )

    def test_invalid_versions_fail_closed(self) -> None:
        with self.assertRaises(ValueError):
            module.resolve_version("1.2", [])
        with self.assertRaises(ValueError):
            module.resolve_version("1.2.0", [], "release")


if __name__ == "__main__":
    unittest.main()
