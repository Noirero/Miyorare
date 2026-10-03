#!/usr/bin/env python3
"""Regression and workflow contract tests; stdlib only, no Gradle or emulator."""
import hashlib
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import phase10_paths as policy

ROOT = policy.ROOT
JVM = [
    'readerjourney.theme.ReaderJourneyPhase10ValidationTest',
    'readerjourney.theme.ReaderJourneyPhase10AccessibilityRegressionTest',
    'readerjourney.theme.RankThemeFoundationTest',
    'readerjourney.theme.RankThemeVisualRegistryTest',
    'readerjourney.theme.RankThemeVisualsRegressionTest',
    'readerjourney.theme.ReaderContentIsolationRegressionTest',
    'readerjourney.theme.ReaderJourneyThemeActivationRegressionTest',
    'readerjourney.theme.ReaderJourneyThemePresentationResolverTest',
    'readerjourney.domain.ReaderJourneyCosmeticSnapshotCodecTest',
    'readerjourney.domain.ReaderJourneyCosmeticPolicyTest',
    'readerjourney.domain.ReaderJourneyThemeCollectionRegressionTest',
    'settings.developer.RankThemeGalleryRegressionTest',
    'core.ui.RankThemeMiyorareBridgeTest',
]


class RoutingTest(unittest.TestCase):
    def test_original_jvm_tests_delegate_without_gradle_or_emulator(self):
        from ci_deep_paths import requires_deep
        for name in JVM:
            path = 'app/src/test/kotlin/org/koitharu/kotatsu/' + name.replace('.', '/') + '.kt'
            with self.subTest(path=path):
                self.assertTrue((ROOT / path).is_file())
                self.assertTrue(requires_deep([path]))
                self.assertEqual(policy.Route(), policy.route_paths([path]))

    def test_ordinary_unvisited_queue_body_edit(self):
        before = (ROOT / policy.QUEUE).read_text()
        after = before.replace('if (modeProvider() == ReaderJourneyCelebrationMode.OFF) return',
                               'if (modeProvider() != ReaderJourneyCelebrationMode.FULL) return')
        self.assertNotEqual(before, after)
        self.assertEqual(policy.Route(), policy.route_paths([policy.QUEUE], {policy.QUEUE: (before, after)}))

    def test_queue_new_dependency_and_initialization_fail_closed(self):
        before = (ROOT / policy.QUEUE).read_text()
        for after in (before.replace('events.trySend(event)', 'updateCosmetics(event)'),
                      before.replace('private val events', 'init { error("new state") }\n private val events'),
                      before.replace('fun enqueue', 'fun renamedEnqueue'), before + '\n /* unterminated'):
            with self.subTest(after=after[-50:]):
                route = policy.route_paths([policy.QUEUE], {policy.QUEUE: (before, after)})
                self.assertTrue(route.state and route.render)

    def test_kotlin_format_only_does_not_need_unique_evidence(self):
        path = policy.PREFIX + 'readerjourney/domain/ReaderJourney.kt'
        before = (ROOT / path).read_text()
        self.assertEqual(policy.Route(), policy.route_paths([path], {path: (before, '// comment\n' + before)}))

    def test_state_and_render_persistence_dependencies_still_run(self):
        for path in ('readerjourney/domain/ReaderProfileStore.kt',
                     'readerjourney/domain/ReaderJourneyCosmeticSnapshotCodec.kt',
                     'readerjourney/domain/ReaderJourneyCosmeticPolicy.kt',
                     'readerjourney/data/ReaderJourneyDao.kt', 'core/db/MangaDatabase.kt',
                     'core/prefs/AppSettings.kt'):
            route = policy.route_paths([policy.PREFIX + path])
            self.assertTrue(route.state and route.render and route.backup, path)
            self.assertFalse(route.jvm, path)

    def test_render_host_and_assets_keep_all_matrix_cases(self):
        for path in ('stats/ui/StatsScreen.kt', 'stats/ui/StatsActivity.kt', 'stats/ui/ReaderJourneyFragment.kt'):
            route = policy.route_paths([policy.PREFIX + path])
            self.assertEqual(policy.Route(render=True), route)
        for path in ('app/src/main/assets/navigation/themes/new.webp', 'app/src/main/res/values/strings.xml'):
            self.assertEqual(policy.Route(render=True), policy.route_paths([path]))

    def test_unvisited_novel_plugin_assets_do_not_start_phase10(self):
        for path in ('app/src/main/assets/lnreader-host.js', 'app/src/main/assets/lnreader-libs.js'):
            self.assertEqual(policy.Route(), policy.route_paths([path]))
        route = policy.route_paths([policy.PREFIX + 'core/BaseApp.kt'])
        self.assertTrue(route.state and route.render)

    def test_phase10_tests_keep_corresponding_unique_coverage(self):
        self.assertEqual(policy.Route(state=True, backup=True), policy.route_paths([policy.STATE_TEST]))
        self.assertEqual(policy.Route(render=True), policy.route_paths([policy.RENDER_TEST]))
        path = policy.ANDROID + 'core/db/ChapterPersistenceRegressionTest.kt'
        self.assertTrue(policy.route_paths([path]).state)
        self.assertEqual(policy.FULL, policy.route_paths([policy.BACKUP_TEST]))

    def test_backup_only_delegates_with_proven_durable_runtime_coverage(self):
        path = policy.PREFIX + 'readerjourney/theme/RankTheme.kt'
        delegated = policy.route_paths([path], runtime_covers_backup=True)
        self.assertTrue(delegated.state and delegated.render)
        self.assertFalse(delegated.backup)
        self.assertIn('ChapterPersistenceRegressionTest', delegated.state_classes)
        self.assertNotIn('LocalBackupIdentityTest', delegated.state_classes)
        fallback = policy.route_paths([path], runtime_covers_backup=False)
        self.assertIn('LocalBackupIdentityTest', fallback.state_classes)

    def test_runtime_policy_covers_delegated_class_for_every_origin(self):
        from android_runtime_paths import policy_requires_android_runtime
        path = policy.PREFIX + 'readerjourney/theme/RankTheme.kt'
        for labels in (set(), {'ci:owner-request'}, {'ci:user-issue'}, {'ci:theme'},
                       {'ci:runtime-required'}, {'ci:owner-request', 'ci:user-issue'}):
            self.assertTrue(policy_requires_android_runtime([path], labels)[0])
        path = policy.PREFIX + 'readerjourney/domain/ReaderProfileStore.kt'
        self.assertFalse(policy_requires_android_runtime([path], {'ci:owner-request'})[0])
        self.assertTrue(policy.route_paths([path]).backup)

    def test_build_and_dependency_edits_run_full(self):
        for path in policy.BUILD_EXACT | {'gradle/libs.versions.toml', 'buildSrc/plugin.gradle', 'build-logic/new.gradle'}:
            self.assertEqual(policy.FULL, policy.route_paths([path]), path)

    def test_self_and_changed_delegate_contracts_run_full(self):
        for path in policy.SELF | policy.DEEP_CONTRACT.keys() | policy.RUNTIME_CONTRACT.keys():
            self.assertEqual(policy.FULL, policy.route_paths([path]), path)

    def test_mixed_pr_cannot_hide_relevant_input(self):
        paths = ['docs/audit.md', 'app/src/test/kotlin/org/koitharu/kotatsu/readerjourney/GenericTest.kt',
                 policy.PREFIX + 'readerjourney/domain/ReaderProfileStore.kt']
        for ordered in (paths, list(reversed(paths))):
            route = policy.route_paths(ordered)
            self.assertTrue(route.state and route.render)

    def test_unknown_relevant_and_unreadable_source_run_validation(self):
        for path in (policy.PREFIX + 'readerjourney/new/Unknown.java',
                     policy.PREFIX + 'settings/new/Input.kt', policy.QUEUE):
            route = policy.route_paths([path])
            self.assertTrue(route.state and route.render, path)
        for paths in ([], ['../bad'], [''], ['bad\npath'], ['a\\b']):
            self.assertEqual(policy.FULL, policy.route_paths(paths))

    def test_missing_deep_coverage_falls_back_to_full(self):
        self.assertEqual(policy.FULL, policy.route_paths([
            'app/src/test/kotlin/org/koitharu/kotatsu/readerjourney/GenericTest.kt'], deep_covers=False))

    def test_docs_metadata_unrelated_inputs_add_no_heavy_work(self):
        for path in ('docs/reader-journey-phase10-validation.md', 'README.md', 'LICENSE', '.gitignore',
                     'docs/ci-phase10-routing.md', '.github/ISSUE_TEMPLATE/bug.yml'):
            self.assertEqual(policy.Route(), policy.route_paths([path], deep_covers=False))

    def test_manual_always_forces_original_full_validation(self):
        self.assertEqual(policy.FULL, policy.classify('', '', manual=True))
        self.assertIn('run_jvm=true', policy.FULL.outputs())
        self.assertIn('run_state=true', policy.FULL.outputs())
        self.assertIn('run_render=true', policy.FULL.outputs())
        self.assertEqual(3, len(policy.FULL.state_classes.split(',')))


class ExactCandidateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        files = set(policy.DEEP_CONTRACT) | set(policy.RUNTIME_CONTRACT) | {policy.WORKFLOW, policy.QUEUE}
        for path in files:
            target = self.root / path
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / path, target)
        for path in ('reader/ui/ReaderActivity.kt', 'settings/developer/DeveloperToolsFragment.kt',
                     'readerjourney/ui/ReaderJourneyCelebrationDialog.kt'):
            target = self.root / (policy.PREFIX + path)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text('val marker: CelebrationQueueItem\n')
        self.git('init', '-q')
        self.git('config', 'user.email', 'routing-test@example.invalid')
        self.git('config', 'user.name', 'Routing test')
        self.base = self.commit()
        self.root_patch = patch.object(policy, 'ROOT', self.root)
        self.root_patch.start()

    def tearDown(self):
        self.root_patch.stop()
        self.temp.cleanup()

    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.root, text=True, stderr=subprocess.PIPE).strip()

    def commit(self):
        self.git('add', '.')
        self.git('commit', '-qm', 'fixture')
        return self.git('rev-parse', 'HEAD')

    def write(self, path, text):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)

    def test_entire_base_to_head_not_last_commit(self):
        self.write(policy.STATE_TEST, 'class ChangedStateTest')
        first = self.commit()
        self.write('docs/audit.md', 'docs only last commit')
        head = self.commit()
        self.assertEqual(['docs/audit.md'], policy.git_paths(first, head))
        self.assertTrue(policy.classify(self.base, head).state)

    def test_correct_queue_candidate_can_skip(self):
        before = (self.root / policy.QUEUE).read_text()
        self.write(policy.QUEUE, before.replace('if (modeProvider() == ReaderJourneyCelebrationMode.OFF) return',
                                               'if (modeProvider() != ReaderJourneyCelebrationMode.FULL) return'))
        self.assertEqual(policy.Route(), policy.classify(self.base, self.commit()))

    def test_new_queue_consumer_invalidates_exception(self):
        before = (self.root / policy.QUEUE).read_text()
        self.write(policy.QUEUE, before.replace('events.trySend(event)', 'events.trySend(event)\n return'))
        self.write('app/src/main/kotlin/new/Host.kt', 'val queue: CelebrationQueue')
        route = policy.classify(self.base, self.commit())
        self.assertTrue(route.state and route.render)

    def test_wrong_checkout_missing_history_empty_diff_fail_closed(self):
        self.write('docs/audit.md', 'new doc')
        head = self.commit()
        self.git('checkout', '--detach', self.base)
        self.assertEqual(policy.FULL, policy.classify(self.base, head))
        self.assertEqual(policy.FULL, policy.classify('f' * 40, self.base))
        self.assertEqual(policy.FULL, policy.classify(self.base, self.base))
        self.assertEqual(policy.FULL, policy.classify('beta', self.base))

    def test_unexpected_delegate_contract_or_parser_failure_falls_back(self):
        self.write(policy.PREFIX + 'readerjourney/theme/RankTheme.kt', '// change')
        head = self.commit()
        with patch.object(policy, 'contract_matches', return_value=False):
            self.assertEqual(policy.FULL, policy.classify(self.base, head))
        with patch.object(policy, 'workflow_paths', side_effect=ValueError('parser')):
            self.assertEqual(policy.FULL, policy.classify(self.base, head))
        with patch.object(policy, 'git_paths', side_effect=OSError('history')):
            self.assertEqual(policy.FULL, policy.classify(self.base, head))

    def test_runtime_uses_its_actual_merge_base_policy(self):
        self.git('checkout', '-qb', 'advanced-base')
        critical = policy.PREFIX + 'core/db/NewDependency.kt'
        self.write(critical, 'class NewDependency')
        advanced_base = self.commit()
        self.git('checkout', '--detach', self.base)
        self.write(policy.PREFIX + 'readerjourney/domain/ReaderProfileStore.kt', '// state change')
        head = self.commit()
        self.assertIn(critical, policy.git_paths(advanced_base, head))
        self.assertNotIn(critical, policy.git_paths(advanced_base, head, merge_base=True))
        # Direct-tree critical deletion must not incorrectly predict a Runtime run:
        # its real merge-base diff is owner-skippable, so Phase 10 retains backup.
        route = policy.classify(advanced_base, head)
        self.assertTrue(route.state and route.backup)

    def test_docs_only_even_when_delegate_contract_is_unrecognized(self):
        self.write('docs/audit.md', 'new doc')
        head = self.commit()
        with patch.object(policy, 'contract_matches', return_value=False):
            self.assertEqual(policy.Route(), policy.classify(self.base, head))


class WorkflowContractTest(unittest.TestCase):
    def test_exact_head_and_no_legacy_last_commit_or_owner_bypass(self):
        source = (ROOT / policy.WORKFLOW).read_text()
        self.assertEqual(3, source.count("ref: ${{ github.event_name == 'pull_request' && github.event.pull_request.head.sha || github.sha }}"))
        self.assertEqual(3, source.count('test "$actual" = "$EXPECTED_HEAD"'))
        self.assertIn('BASE_SHA: ${{ github.event.pull_request.base.sha }}', source)
        self.assertIn('--base "$BASE_SHA" --head "$HEAD_SHA"', source)
        self.assertNotIn('BEFORE_SHA', source)
        self.assertNotIn('ci:owner-request', source)
        self.assertIn('--manual > "$route_file"', source)
        self.assertIn('run_jvm=true\n          run_state=true\n          run_render=true', source)

    def test_cheap_job_runs_regressions_and_gates_every_jvm_setup_step(self):
        source = (ROOT / policy.WORKFLOW).read_text()
        deterministic = source.split('  deterministic-validation:', 1)[1].split('  android-state-restoration:', 1)[0]
        self.assertIn('python3 .github/scripts/test_phase10_paths.py', deterministic)
        self.assertEqual(6, deterministic.count("steps.phase10_scope.outputs.run_jvm == 'true'"))
        self.assertNotIn('emulator-runner', deterministic)
        self.assertIn("needs.deterministic-validation.outputs.run_state == 'true'", source)
        self.assertIn("needs.deterministic-validation.outputs.run_render == 'true'", source)

    def test_jvm_delegation_is_the_same_unfiltered_task_and_configuration(self):
        source = (ROOT / policy.WORKFLOW).read_text()
        self.assertEqual(['org.koitharu.kotatsu.' + x for x in JVM], re.findall(r'--tests (\S+)', source))
        deep = (ROOT / '.github/workflows/ci-deep.yml').read_text()
        self.assertIn('./gradlew :app:testDebugUnitTest --no-daemon --stacktrace', deep)
        self.assertNotIn('--tests ', deep)
        self.assertIn('java-version: \'17\'', source)
        self.assertIn('java-version: \'17\'', deep)
        self.assertIn('ci-deep-unit-test-reports', deep)
        self.assertIn('app/build/reports/tests/', source)
        self.assertIn('app/build/reports/tests/', deep)

    def test_state_preserves_measured_config_and_full_manual_classes(self):
        source = (ROOT / policy.WORKFLOW).read_text().split('  android-state-restoration:', 1)[1].split('  rendered-device-matrix:', 1)[0]
        self.assertIn('cores: 4', source)
        self.assertIn('-memory 4096', source)
        for value in ('api-level: 35', 'emulator-build: 13823996', 'target: google_apis', 'arch: x86_64', 'profile: pixel_2'):
            self.assertIn(value, source)
        self.assertIn(':app:connectedDebugAndroidTest', source)
        self.assertIn('class=${{ needs.deterministic-validation.outputs.state_classes }}', source)
        self.assertIn('path: |\n            app/build/reports/androidTests/\n            app/build/outputs/androidTest-results/', source)

    def test_entire_render_matrix_configuration_assertions_and_evidence_stay_intact(self):
        source = (ROOT / policy.WORKFLOW).read_text().split('  rendered-device-matrix:', 1)[1]
        source = source.replace("needs.deterministic-validation.outputs.run_render == 'true'", '<route>')
        # All three original scenarios, device settings, Preview properties,
        # instrumentation arguments, mandatory PNG/JSON and startup/memory/gfx
        # collection remain identical. Only their routing condition changes.
        self.assertEqual('41aab96399807a781420e218011bf0a6c7ad13cba4c2cc1940cfae66bdf81401',
                         hashlib.sha256(source.encode()).hexdigest())

    def test_preserved_scope_and_new_dependency_events(self):
        rules = policy.workflow_paths()
        for path in (policy.STATE_TEST, policy.RENDER_TEST, policy.QUEUE,
                     policy.PREFIX + 'core/ui/New.kt', policy.PREFIX + 'settings/New.kt',
                     policy.PREFIX + 'stats/ui/StatsScreen.kt', policy.PREFIX + 'core/prefs/AppSettings.kt',
                     'app/src/main/res/values/strings.xml', 'app/src/main/assets/navigation/themes/new.webp',
                     *policy.SELF, *policy.DEEP_CONTRACT, *policy.RUNTIME_CONTRACT):
            self.assertTrue(any(policy.kotlin.matches(path, rule) for rule in rules), path)
        self.assertFalse(any(policy.kotlin.matches('docs/unrelated.md', rule) for rule in rules))


if __name__ == '__main__':
    unittest.main()
