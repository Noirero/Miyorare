#!/usr/bin/env python3
from __future__ import annotations
import importlib.util
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("ci_deep_paths", ROOT / "ci_deep_paths.py")
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)

class CiDeepPathsTest(unittest.TestCase):
    def test_docs_only_skips_full_regression(self):
        for paths in [
            ["README.md"],
            ["docs/CI_AUDIT.md"],
            ["README.md", "docs/DEVELOPMENT_PIPELINE.md"],
        ]:
            with self.subTest(paths=paths):
                self.assertFalse(module.requires_deep(paths))

    def test_source_tests_resources_and_build_config_run(self):
        for path in [
            "app/src/main/kotlin/org/koitharu/kotatsu/main/MainActivity.kt",
            "app/src/test/kotlin/org/koitharu/kotatsu/history/data/TrackingProgressPolicyTest.kt",
            "app/src/androidTest/kotlin/org/koitharu/kotatsu/core/db/ChapterPersistenceRegressionTest.kt",
            "app/src/main/res/values/strings.xml",
            "app/src/main/AndroidManifest.xml",
            "app/build.gradle",
            "gradle/libs.versions.toml",
            "settings.gradle",
        ]:
            with self.subTest(path=path):
                self.assertTrue(module.requires_deep([path]))

    def test_unknown_changes_fail_closed(self):
        self.assertTrue(module.requires_deep(["tools/new_release_helper.py"]))
        self.assertTrue(module.requires_deep([".github/workflows/other-workflow.yml"]))

    def test_classifier_and_workflow_changes_force_deep(self):
        for path in [
            ".github/workflows/ci-deep.yml",
            ".github/scripts/ci_deep_paths.py",
            ".github/scripts/test_ci_deep_paths.py",
        ]:
            with self.subTest(path=path):
                self.assertTrue(module.requires_deep([path]))

    def test_mixed_docs_and_source_runs(self):
        self.assertTrue(module.requires_deep([
            "docs/CI_AUDIT.md",
            "app/src/main/kotlin/org/koitharu/kotatsu/core/db/MangaDatabase.kt",
        ]))

    def test_empty_diff_fails_closed(self):
        self.assertTrue(module.requires_deep([]))

if __name__ == "__main__":
    unittest.main()
