#!/usr/bin/env python3
from __future__ import annotations
import importlib.util
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("android_runtime_paths", ROOT / "android_runtime_paths.py")
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)

class AndroidRuntimePathsTest(unittest.TestCase):
    def test_docs_and_plain_ui_skip_core_runtime(self):
        self.assertFalse(module.requires_android_runtime(["README.md"]))
        self.assertFalse(module.requires_android_runtime(["app/src/main/kotlin/org/koitharu/kotatsu/download/ui/list/DownloadsFragment.kt"]))
        self.assertFalse(module.requires_android_runtime(["app/src/main/res/values/strings.xml"]))

    def test_database_backup_migration_and_instrumentation_run(self):
        for path in [
            "app/src/main/kotlin/org/koitharu/kotatsu/core/db/MangaDatabase.kt",
            "app/src/main/kotlin/org/koitharu/kotatsu/settings/backup/BackupSettings.kt",
            "app/src/main/kotlin/org/koitharu/kotatsu/alternatives/domain/ProfileMigration.kt",
            "app/src/androidTest/kotlin/org/koitharu/kotatsu/core/db/ChapterPersistenceRegressionTest.kt",
        ]:
            with self.subTest(path=path):
                self.assertTrue(module.requires_android_runtime([path]))

    def test_manifest_and_gradle_run(self):
        for path in ["app/src/main/AndroidManifest.xml", "app/build.gradle", "gradle.properties", "gradle/libs.versions.toml"]:
            with self.subTest(path=path):
                self.assertTrue(module.requires_android_runtime([path]))

    def test_mixed_change_runs_if_any_runtime_sensitive(self):
        self.assertTrue(module.requires_android_runtime(["README.md", "app/src/main/kotlin/org/koitharu/kotatsu/core/db/MangaDatabase.kt"]))

    def test_empty_diff_fails_closed(self):
        self.assertTrue(module.requires_android_runtime([]))

if __name__ == "__main__":
    unittest.main()
