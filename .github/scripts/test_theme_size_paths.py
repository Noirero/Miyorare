#!/usr/bin/env python3
"""Regression coverage for the actual size workflow and immutable PR candidates."""
from __future__ import annotations

import contextlib
import io
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import theme_size_paths as router

ROOT = router.ROOT
BEFORE = 'e7ebcd9b30d042e1c5c1d23ee14d22307b02fa11'
AFTER = '48277f835f888ecaf943ed0d2326f0ce2e2efb74'


def source(ref, path):
    return subprocess.check_output(['git', 'show', f'{ref}:{path}'], cwd=ROOT, text=True)


class SizeRoutingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sources = {p: (source(BEFORE, p), source(AFTER, p)) for p in {router.STATS, *router.HOSTS}}
        cls.paths_462 = subprocess.check_output(
            ['git', 'diff', '--name-only', '--no-renames', BEFORE, AFTER], cwd=ROOT, text=True).splitlines()

    def test_real_462_entire_pr_skips_two_apks(self):
        self.assertFalse(router.requires_size(self.paths_462, self.sources))

    def test_each_real_462_shared_host_skips(self):
        for path in self.sources:
            with self.subTest(path=path):
                self.assertFalse(router.requires_size([path], self.sources))

    def test_generic_stats_and_loading_literals_skip(self):
        before = 'fun StatsScreen() {}\nprivate fun ReaderProfileCard() {}\nprivate fun MetricsGrid() { Text("stats"); Spacer(Modifier.height(12.dp)) }\n'
        after = before.replace('"stats"', '"statistics"').replace('12.dp', '14.dp')
        self.assertFalse(router.requires_size([router.STATS], {router.STATS: (before, after)}))
        before = self.sources[router.STATS][1]
        self.assertFalse(router.requires_size([router.STATS], {router.STATS: (before, before.replace('height(214.dp)', 'height(215.dp)'))}))

    def test_theme_profile_import_new_reference_and_unknown_helpers_run(self):
        before = self.sources[router.STATS][1]
        for after in [before.replace('size(34.dp)', 'size(35.dp)', 1),
                      before.replace('width(176.dp)', 'width(180.dp)', 1),
                      before.replace('!hasLoadedStats && isLoading', '!hasLoadedStats || isLoading', 1),
                      before.replace('height(214.dp)', 'height(214.dp).newPayloadHook()', 1),
                      before + '\nprivate fun UnknownFeature() {}\n',
                      before + '\nimport unknown.Package\n']:
            self.assertNotEqual(before, after)
            self.assertTrue(router.requires_size([router.STATS], {router.STATS: (before, after)}))

    def test_other_host_changes_are_conservative(self):
        for path in router.HOSTS:
            before = self.sources[path][1]
            after = before.replace('profile = readerProfile,', 'profile = newProfile(),', 1)
            self.assertNotEqual(before, after)
            self.assertTrue(router.requires_size([path], {path: (before, after)}))

    def test_missing_new_deleted_renamed_binary_malformed_hosts_run(self):
        before = self.sources[router.STATS][1]
        for after in [None, before + '{', before + '\n"unterminated', before.replace('fun ReaderProfileCard(', 'fun UnknownCard(')]:
            self.assertTrue(router.requires_size([router.STATS], {router.STATS: (before, after)}))
        for path in self.sources:
            self.assertTrue(router.requires_size([path]))
            self.assertTrue(router.requires_size([path], {path: (None, self.sources[path][1])}))

    def test_each_payload_build_and_policy_input_runs_even_with_generic_stats(self):
        paths = [
            'app/src/main/res/drawable-nodpi/nameplate_01_newcomer.webp',
            'app/src/main/res/drawable-xhdpi/nameplate_new.png',
            'app/src/main/res/raw-night/nameplate_payload.bin',
            'app/src/main/res/drawable/profile_frame_01_first_page_silver_normal.webp',
            'app/src/main/res/raw/badge_payload.bin',
            'app/src/main/res/values/rank_theme.xml',
            'app/src/main/res/drawable/unfamiliar_payload.xml',
            'app/src/main/assets/new-theme.bin', 'app/src/main/jniLibs/arm64-v8a/library.so',
            'app/src/main/resources/payload.json', 'app/src/main/AndroidManifest.xml',
            'app/src/preview/res/values/strings.xml', 'app/src/release/res/drawable/theme.webp',
            'app/src/main/badge-assets/exclusive_badge_material_payload.b64',
            'app/src/main/badge-assets/v2-source/badge_new.webp',
            'app/src/main/badge-assets/v2-source-manifest.json',
            'tools/regenerate_badge_v2_payload.py',
            'app/build.gradle', 'app/build.gradle.kts', 'build.gradle', 'settings.gradle',
            'gradle.properties', 'gradle/libs.versions.toml', 'gradle/wrapper/gradle-wrapper.jar',
            'gradle/verification-metadata.xml', 'app/gradle.lockfile', 'gradlew', 'gradlew.bat',
            'buildSrc/src/main/kotlin/Packaging.kt', 'build-logic/convention.gradle.kts',
            'app/proguard-rules.pro', router.WORKFLOW,
            '.github/scripts/theme_size_paths.py', '.github/scripts/test_theme_size_paths.py',
            '.github/scripts/exclusive_visual_paths.py',
            router.PREFIX + 'readerjourney/theme/RankTheme.kt',
            router.PREFIX + 'readerjourney/ui/ExclusiveBadge.kt',
            router.PREFIX + 'readerjourney/domain/UnknownDependency.kt',
            router.PREFIX + 'settings/developer/ExclusiveThemeQaFragment.kt',
            router.PREFIX + 'stats/ui/ReaderJourneyExclusiveCollection.kt',
            router.PREFIX + 'stats/domain/ReaderProfileShareModel.kt',
            router.PREFIX + 'stats/share/ReaderProfileShareCard.kt',
            router.PREFIX + 'core/prefs/AppSettings.kt',
        ]
        for path in paths:
            with self.subTest(path=path):
                self.assertTrue(any(router.visual.matches(path, rule) for rule in router.workflow_paths()))
                self.assertTrue(router.requires_size([router.STATS, path], self.sources))

    def test_shared_strings_allow_only_existing_unrelated_text(self):
        path = 'app/src/main/res/values-in/strings.xml'
        before = '<resources><string name="download_label">Download</string><string name="reader_rank_newcomer">Newcomer</string></resources>'
        after = before.replace('Download</string>', 'Unduh</string>')
        self.assertFalse(router.requires_size([path], {path: (before, after)}, set()))
        self.assertTrue(router.requires_size([path], {path: (before, after)}, {'download_label'}))
        for after in [before.replace('Newcomer</string>', 'Reader</string>'),
                      before.replace('</resources>', '<string name="new_label">New</string></resources>'),
                      before.replace('Download</string>', '@string/other</string>'),
                      before.replace('name="download_label"', 'name="download_label" formatted="false"'),
                      before.replace('Download</string>', '<b>Download</b></string>'),
                      '<resources>', None]:
            self.assertTrue(router.requires_size([path], {path: (before, after)}, set()))
        diagnostics = 'app/src/main/res/values/settings_developer_diagnostics.xml'
        old = (ROOT / diagnostics).read_text()
        new = old.replace('Extension loading</string>', 'Load extension</string>')
        self.assertFalse(router.requires_size([diagnostics], {diagnostics: (old, new)}))
        new = old.replace('Rank Theme Gallery</string>', 'Theme Gallery</string>')
        self.assertTrue(router.requires_size([diagnostics], {diagnostics: (old, new)}))

    def test_original_coverage_is_retained_or_replaced_by_audited_gate(self):
        # Every original non-doc path continues to receive an event and defaults
        # to full validation when source/content proof is unavailable.
        original = [router.PREFIX + 'readerjourney/domain/ReaderJourneyCollector.kt',
                    router.PREFIX + 'core/ui/MiyorareColorScheme.kt',
                    router.PREFIX + 'settings/developer/DeveloperExtensionTestRunner.kt',
                    router.PREFIX + 'stats/domain/ReaderProfileShareModel.kt',
                    router.PREFIX + 'stats/share/ReaderProfileShareCard.kt',
                    router.STATS, *router.HOSTS,
                    'app/src/main/res/values/strings.xml', 'app/src/main/res/values-in/strings.xml',
                    'app/src/main/res/values/settings_developer_diagnostics.xml',
                    'app/src/main/res/values-in/settings_developer_diagnostics.xml',
                    'app/src/main/res/drawable-nodpi/nameplate_01_newcomer.webp', router.WORKFLOW]
        for path in original:
            self.assertTrue(any(router.visual.matches(path, rule) for rule in router.workflow_paths()), path)
            self.assertTrue(router.requires_size([path]), path)

    def test_docs_metadata_tests_and_out_of_scope_files_add_no_apk_work(self):
        paths = ['docs/reader-journey-theme-assets.md', 'docs/ci-theme-size-routing.md',
                 'README.md', '.gitignore', '.gitattributes',
                 'app/src/test/kotlin/org/koitharu/kotatsu/readerjourney/StatsTest.kt']
        self.assertFalse(router.requires_size(paths))
        for path in paths:
            self.assertFalse(any(router.visual.matches(path, rule) for rule in router.workflow_paths()))

    def test_unknown_scoped_paths_bad_paths_and_empty_input_fail_closed(self):
        for paths in [[], [''], ['../payload'], ['/app/asset'], ['app\\src\\payload'],
                      [router.PREFIX + 'readerjourney/new/Unknown.kt'],
                      ['app/src/main/res/raw/unknown.bin']]:
            self.assertTrue(router.requires_size(paths))

    def test_actual_workflow_keeps_manual_full_and_cheap_job_without_android_setup(self):
        workflow = (ROOT / router.WORKFLOW).read_text()
        cheap, heavy = workflow.split('  preview-apk-size:', 1)
        for forbidden in ('setup-java', 'setup-gradle', './gradlew', 'emulator-runner'):
            self.assertNotIn(forbidden, cheap)
        self.assertIn("if [ \"$EVENT_NAME\" != \"workflow_dispatch\" ]", cheap)
        self.assertIn('run_size=true', cheap)
        self.assertIn("needs.classify.outputs.run_size != 'false'", heavy)
        self.assertIn('always() && !cancelled()', heavy)
        self.assertNotIn('EVENT_ACTION', workflow)
        self.assertNotIn('BEFORE_SHA', workflow)
        self.assertIn('test "$head_sha" = "$EXPECTED_HEAD"', heavy)
        self.assertIn('echo "BASE_SHA=$base_sha"', heavy)
        self.assertIn('test_theme_size_paths.py', cheap)
        self.assertIn('Audit packaged final rank-title Nameplate resources', heavy)
        self.assertIn('assert actual == expected', heavy)
        self.assertIn('No pass/fail size budget is imposed here', heavy)

    def test_cli_real_tree_diff_keeps_earlier_asset_and_rejects_wrong_head(self):
        with tempfile.TemporaryDirectory() as directory:
            repo = Path(directory)
            def git(*args):
                return subprocess.check_output(['git', *args], cwd=repo, text=True, stderr=subprocess.DEVNULL).strip()
            def commit(message):
                git('add', '.')
                git('commit', '-m', message)
                return git('rev-parse', 'HEAD')
            git('init')
            git('config', 'user.name', 'Routing test')
            git('config', 'user.email', 'routing@example.invalid')
            workflow = repo / router.WORKFLOW
            workflow.parent.mkdir(parents=True)
            workflow.write_text((ROOT / router.WORKFLOW).read_text())
            for path, (before, _) in self.sources.items():
                target = repo / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(before)
            base = commit('Base')
            for path, (_, after) in self.sources.items():
                (repo / path).write_text(after)
            generic = commit('Generic loading')
            with patch.object(router, 'ROOT', repo):
                self.assertFalse(router.classify(base, generic))
            asset = repo / 'app/src/main/res/drawable-nodpi/nameplate_new.webp'
            asset.parent.mkdir(parents=True)
            asset.write_bytes(b'asset')
            asset_head = commit('Theme payload')
            (repo / 'README.md').write_text('follow-up metadata only')
            final = commit('Metadata follow-up')
            with patch.object(router, 'ROOT', repo), contextlib.redirect_stderr(io.StringIO()):
                self.assertTrue(router.classify(base, final))  # still includes the earlier asset
                self.assertFalse(router.classify(asset_head, final))
                self.assertTrue(router.classify(base, generic))  # stale candidate checkout
                self.assertTrue(router.classify('0' * 40, final))  # missing history
                self.assertTrue(router.classify('beta', final))  # moving ref
                with patch('sys.argv', ['router', '--base', base, '--head', final]), contextlib.redirect_stdout(io.StringIO()) as output:
                    self.assertEqual(0, router.main())
                self.assertEqual('true', output.getvalue().strip())


if __name__ == '__main__':
    unittest.main()
