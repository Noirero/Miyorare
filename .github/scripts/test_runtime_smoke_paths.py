#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location(
    "runtime_smoke_paths",
    ROOT / "runtime_smoke_paths.py",
)
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


class RuntimeSmokePathsTest(unittest.TestCase):
    def test_readme_only_skips(self) -> None:
        self.assertFalse(module.requires_runtime_smoke(["README.md"]))

    def test_docs_tree_skips(self) -> None:
        self.assertFalse(
            module.requires_runtime_smoke(
                ["docs/release-process.md", "docs/images/release-flow.png"]
            )
        )

    def test_release_notes_only_skips(self) -> None:
        self.assertFalse(module.requires_runtime_smoke([".github/release-notes.md"]))

    def test_app_runtime_change_runs(self) -> None:
        self.assertTrue(
            module.requires_runtime_smoke(
                ["app/src/main/kotlin/org/koitharu/kotatsu/reader/ReaderActivity.kt"]
            )
        )

    def test_gradle_change_runs(self) -> None:
        self.assertTrue(module.requires_runtime_smoke(["app/build.gradle"]))
        self.assertTrue(module.requires_runtime_smoke(["gradle.properties"]))
        self.assertTrue(module.requires_runtime_smoke(["gradle/libs.versions.toml"]))

    def test_manifest_change_runs(self) -> None:
        self.assertTrue(
            module.requires_runtime_smoke(["app/src/main/AndroidManifest.xml"])
        )

    def test_workflow_change_defaults_to_run(self) -> None:
        self.assertTrue(
            module.requires_runtime_smoke(
                [".github/workflows/beta-to-main-release-gate.yml"]
            )
        )

    def test_mixed_docs_and_runtime_runs(self) -> None:
        self.assertTrue(
            module.requires_runtime_smoke(
                ["README.md", "app/src/main/res/values/strings.xml"]
            )
        )

    def test_nested_runtime_readme_is_not_globally_exempt(self) -> None:
        self.assertTrue(
            module.requires_runtime_smoke(["app/src/main/assets/README.md"])
        )

    def test_unknown_path_runs(self) -> None:
        self.assertTrue(module.requires_runtime_smoke(["tools/new-check.txt"]))

    def test_empty_diff_runs(self) -> None:
        self.assertTrue(module.requires_runtime_smoke([]))


if __name__ == "__main__":
    unittest.main()
