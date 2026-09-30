#!/usr/bin/env python3
from __future__ import annotations
import importlib.util
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("ci_gradle_paths", ROOT / "ci_gradle_paths.py")
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)

class CiGradlePathsTest(unittest.TestCase):
    def test_docs_only_skips_gradle(self):
        self.assertFalse(module.requires_gradle(["README.md"]))
        self.assertFalse(module.requires_gradle(["docs/CI_AUDIT.md", "docs/DEVELOPMENT_PIPELINE.md"]))

    def test_code_resources_tests_and_build_files_require_gradle(self):
        for path in [
            "app/src/main/kotlin/org/koitharu/kotatsu/main/MainActivity.kt",
            "app/src/main/res/values/strings.xml",
            "app/src/test/kotlin/org/koitharu/kotatsu/Test.kt",
            "app/src/androidTest/kotlin/org/koitharu/kotatsu/Smoke.kt",
            "app/build.gradle",
            "gradle/libs.versions.toml",
            "settings.gradle",
        ]:
            with self.subTest(path=path):
                self.assertTrue(module.requires_gradle([path]))

    def test_ci_routing_changes_force_gradle(self):
        for path in [
            ".github/workflows/ci-fast.yml",
            ".github/workflows/p0-p1-acceptance.yml",
            ".github/scripts/ci_gradle_paths.py",
            ".github/scripts/test_ci_gradle_paths.py",
        ]:
            with self.subTest(path=path):
                self.assertTrue(module.requires_gradle([path]))

    def test_unknown_mixed_and_empty_fail_closed(self):
        self.assertTrue(module.requires_gradle(["tools/new_helper.py"]))
        self.assertTrue(module.requires_gradle(["README.md", "app/src/main/AndroidManifest.xml"]))
        self.assertTrue(module.requires_gradle([]))

if __name__ == "__main__":
    unittest.main()
