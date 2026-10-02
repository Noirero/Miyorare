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

DB = "app/src/main/kotlin/org/koitharu/kotatsu/core/db/MangaDatabase.kt"
PLAIN_UI = "app/src/main/kotlin/org/koitharu/kotatsu/download/ui/list/DownloadsFragment.kt"
THEME = "app/src/main/kotlin/org/koitharu/kotatsu/readerjourney/theme/RankTheme.kt"
NAV_THEME = "app/src/main/assets/navigation/themes/11_Eternal_Library_Prism.webp"

class AndroidRuntimePathsTest(unittest.TestCase):
    def test_docs_and_plain_ui_skip_legacy_runtime(self):
        self.assertFalse(module.requires_android_runtime(["README.md"]))
        self.assertFalse(module.requires_android_runtime([PLAIN_UI]))
        self.assertFalse(module.requires_android_runtime(["app/src/main/res/values/strings.xml"]))

    def test_database_backup_migration_and_instrumentation_run(self):
        for path in [
            DB,
            "app/src/main/kotlin/org/koitharu/kotatsu/settings/backup/BackupSettings.kt",
            "app/src/main/kotlin/org/koitharu/kotatsu/alternatives/domain/ProfileMigration.kt",
            "app/src/androidTest/kotlin/org/koitharu/kotatsu/core/db/ChapterPersistenceRegressionTest.kt",
        ]:
            with self.subTest(path=path):
                self.assertTrue(module.requires_android_runtime([path]))

    def test_existing_persistence_regression_risk_paths_run(self):
        for path in [
            "app/src/main/kotlin/org/koitharu/kotatsu/kotatsumigration/KotatsuMangaMigrator.kt",
            "app/src/main/kotlin/org/koitharu/kotatsu/sync/domain/SyncMerger.kt",
            "app/src/main/kotlin/org/koitharu/kotatsu/core/parser/MangaDataRepository.kt",
            "app/src/main/kotlin/org/koitharu/kotatsu/details/domain/DetailsLoadUseCase.kt",
            "app/src/main/kotlin/org/koitharu/kotatsu/local/domain/DownloadedMangaResolver.kt",
            "app/src/main/kotlin/org/koitharu/kotatsu/reader/domain/PageLoader.kt",
            "app/src/main/kotlin/org/koitharu/kotatsu/history/data/HistoryRepository.kt",
            "app/src/main/kotlin/org/koitharu/kotatsu/favourites/data/FavouritesDao.kt",
            "app/src/main/kotlin/org/koitharu/kotatsu/bookmarks/data/BookmarksDao.kt",
            "app/src/main/kotlin/org/koitharu/kotatsu/reader/ui/ReaderViewModel.kt",
            "app/src/main/kotlin/org/koitharu/kotatsu/tracker/work/TrackWorker.kt",
            "app/src/main/kotlin/org/koitharu/kotatsu/core/network/UserAgentManager.kt",
            "app/src/main/kotlin/org/koitharu/kotatsu/core/prefs/AppSettings.kt",
            "app/src/main/kotlin/org/koitharu/kotatsu/favourites/ui/list/FavouritesListViewModel.kt",
            "app/src/main/kotlin/org/koitharu/kotatsu/list/ui/MangaListFragment.kt",
            "app/src/main/kotlin/org/koitharu/kotatsu/settings/override/OverrideConfigViewModel.kt",
        ]:
            with self.subTest(path=path):
                self.assertTrue(module.requires_android_runtime([path]))

    def test_owner_request_skips_normal_runtime_sensitive_change(self):
        required, _ = module.policy_requires_android_runtime(
            ["app/src/main/kotlin/org/koitharu/kotatsu/reader/ui/ReaderViewModel.kt"],
            {module.OWNER_REQUEST},
        )
        self.assertFalse(required)

    def test_owner_request_cannot_skip_critical_persistence_change(self):
        required, _ = module.policy_requires_android_runtime([DB], {module.OWNER_REQUEST})
        self.assertTrue(required)

    def test_user_issue_always_runs_even_for_plain_ui(self):
        required, _ = module.policy_requires_android_runtime([PLAIN_UI], {module.USER_ISSUE})
        self.assertTrue(required)

    def test_theme_always_runs_for_owner_request(self):
        for path in [THEME, NAV_THEME]:
            with self.subTest(path=path):
                required, _ = module.policy_requires_android_runtime([path], {module.OWNER_REQUEST})
                self.assertTrue(required)

    def test_explicit_theme_label_runs_for_other_visual_file(self):
        required, _ = module.policy_requires_android_runtime(
            ["app/src/main/kotlin/org/koitharu/kotatsu/readerjourney/ui/ExclusiveProfileFrame.kt"],
            {module.OWNER_REQUEST, module.THEME},
        )
        self.assertTrue(required)

    def test_runtime_required_override_wins(self):
        required, _ = module.policy_requires_android_runtime([PLAIN_UI], {module.OWNER_REQUEST, module.FORCE_RUNTIME})
        self.assertTrue(required)

    def test_conflicting_origin_labels_fail_closed(self):
        required, _ = module.policy_requires_android_runtime([PLAIN_UI], {module.OWNER_REQUEST, module.USER_ISSUE})
        self.assertTrue(required)

    def test_unclassified_pr_uses_legacy_path_classifier(self):
        self.assertFalse(module.policy_requires_android_runtime([PLAIN_UI], set())[0])
        self.assertTrue(module.policy_requires_android_runtime([DB], set())[0])

    def test_empty_diff_fails_closed(self):
        self.assertTrue(module.policy_requires_android_runtime([], set())[0])

if __name__ == "__main__":
    unittest.main()
