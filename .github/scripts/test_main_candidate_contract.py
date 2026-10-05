#!/usr/bin/env python3
"""Execute the main gate's real shell policy against local Git candidates."""
from __future__ import annotations

import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / '.github/workflows/beta-to-main-release-gate.yml'


def step(name, workflow=WORKFLOW):
    blocks = re.split(r'(?=^      - name: )', workflow.read_text(), flags=re.M)
    matches = [b for b in blocks if b.startswith('      - name: ' + name + '\n')]
    if len(matches) != 1:
        raise AssertionError(f'Expected one step: {name}')
    return matches[0]


def script(name, workflow=WORKFLOW):
    block = step(name, workflow).split('        run: |\n', 1)[1]
    lines = []
    for line in block.splitlines():
        if line and not line.startswith('          '):
            break
        lines.append(line)
    return textwrap.dedent('\n'.join(lines))


class MainCandidateContractTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo = Path(self.tmp.name) / 'candidate'
        self.origin = Path(self.tmp.name) / 'origin'
        self.repo.mkdir()
        self.git('init', '-q')
        self.git('config', 'user.name', 'CI fixture')
        self.git('config', 'user.email', 'fixture@example.invalid')
        (self.repo / 'input').write_text('base\n')
        (self.repo / 'README.md').write_text('original README\n')
        self.git('add', '.')
        self.git('commit', '-qm', 'protected main base')
        self.base = self.git('rev-parse', 'HEAD')
        subprocess.run(['git', 'init', '--bare', '-q', str(self.origin)], check=True)
        self.git('remote', 'add', 'origin', str(self.origin))
        self.git('push', '-q', 'origin', 'HEAD:refs/heads/beta', 'HEAD:refs/heads/main')
        self.git('fetch', '-q', 'origin', 'beta')

    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.repo, text=True).strip()

    def classify(self, ref, event='pull_request', head=None, base=None, context_sha=None,
                 base_ref='main', pr_data=None, requested_sha=None, pr_number=None):
        output = Path(self.tmp.name) / 'outputs'
        output.write_text('')
        head = self.git('rev-parse', 'HEAD') if head is None else head
        base = self.base if base is None else base
        maintenance = ref == 'automation/readme-release-stats' or ref.startswith('automation/release-readme-')
        pr = Path(self.tmp.name) / 'pr.json'
        pr.write_text(json.dumps(pr_data or {
            'state': 'open', 'base': {'ref': base_ref, 'sha': base, 'repo': {'full_name': 'Noirero/Miyorare'}},
            'head': {'ref': ref, 'sha': head, 'repo': {'full_name': 'Noirero/Miyorare'}},
        }))
        bin_dir = Path(self.tmp.name) / 'bin'
        bin_dir.mkdir(exist_ok=True)
        gh = bin_dir / 'gh'
        gh.write_text('#!/bin/sh\n[ "$1" = api ] || exit 1\ncat "$FIXTURE_PR"\n')
        gh.chmod(0o755)
        command = script('Classify source').replace('${{ github.event_name }}', event)
        result = subprocess.run(['bash', '-c', command], cwd=self.repo, text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                env={**os.environ, 'HEAD_REF': ref, 'HEAD_SHA': head,
                                     'BASE_SHA': base, 'BASE_REF': base_ref, 'GITHUB_SHA': context_sha or head,
                                     'PR_NUMBER': pr_number if pr_number is not None else ('1' if event == 'pull_request' or maintenance else ''),
                                     'REQUESTED_SHA': requested_sha if requested_sha is not None else (head if event == 'workflow_dispatch' and maintenance else ''),
                                     'GITHUB_REPOSITORY': 'Noirero/Miyorare', 'FIXTURE_PR': str(pr),
                                     'PATH': str(bin_dir) + ':' + os.environ['PATH'], 'GITHUB_OUTPUT': str(output)})
        return result, output.read_text()

    def test_beta_and_frozen_release_candidates_have_no_heavy_promotion(self):
        for ref in ('beta', 'release/main-1.0'):
            result, output = self.classify(ref)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn('mode=promotion', output)
        workflow = WORKFLOW.read_text()
        self.assertIn("if: needs.classify.outputs.mode == 'hotfix'", workflow)
        self.assertIn('name: Verify beta is safe to promote', workflow)

    def test_manual_dispatch_also_requires_beta_ancestry_and_valid_ref(self):
        result, _ = self.classify('beta', 'workflow_dispatch')
        self.assertEqual(0, result.returncode, result.stderr)
        for ref in ('feature/unvalidated', 'main', 'hotfix/unvalidated'):
            result, output = self.classify(ref, 'workflow_dispatch')
            self.assertNotEqual(0, result.returncode, ref)
            self.assertNotIn('mode=promotion', output)
        (self.repo / 'input').write_text('unvalidated branch\n')
        self.git('commit', '-qam', 'not contained in beta')
        result, output = self.classify('release/main-unvalidated', 'workflow_dispatch')
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn('mode=promotion', output)

    def test_exact_head_and_rejected_branch_fail_closed(self):
        for ref, head in [('beta', 'f' * 40), ('beta', 'HEAD'), ('feature/new', None),
                          ('automation/readme-release-stats', None)]:
            result, output = self.classify(ref, head=head)
            self.assertNotEqual(0, result.returncode, ref)
            self.assertEqual('', output)

    def test_hotfix_requires_protected_main_base_ancestry(self):
        result, output = self.classify('hotfix/fix')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('mode=hotfix', output)
        result, output = self.classify('hotfix/fix', base='e' * 40)
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn('mode=hotfix', output)

    def readme_commit(self):
        (self.repo / 'README.md').write_text('updated automation README\n')
        self.git('commit', '-qam', 'README only')

    def test_allowed_readme_branches_use_maintenance_for_pr_and_dispatch(self):
        self.readme_commit()
        for ref in ('automation/release-readme-v1.0-123-a1', 'automation/readme-release-stats'):
            for event in ('pull_request', 'workflow_dispatch'):
                result, output = self.classify(ref, event)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual('mode=maintenance\n', output)

    def test_arbitrary_automation_and_docs_branches_cannot_enter_main(self):
        self.readme_commit()
        for ref in ('automation/foo', 'automation/readme-release-stats-other', 'docs/maintenance', 'feature/readme'):
            result, output = self.classify(ref)
            self.assertNotEqual(0, result.returncode, ref)
            self.assertEqual('', output)

    def test_readme_lane_rejects_entire_mixed_or_non_readme_diff(self):
        self.readme_commit()
        for path in ('App.kt', 'app/build.gradle', '.github/workflows/evil.yml', 'assets/image', 'README.other'):
            target = self.repo / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text('not owned by README automation')
            self.git('add', '.')
            self.git('commit', '-qm', 'earlier non README change')
            (self.repo / 'README.md').write_text('last commit README only\n' + path)
            self.git('commit', '-qam', 'last commit README')
            result, output = self.classify('automation/readme-release-stats')
            self.assertNotEqual(0, result.returncode, path)
            self.assertEqual('', output)
            self.git('reset', '--hard', self.base)
            self.readme_commit()

    def test_stale_or_diverged_main_base_and_wrong_target_fail(self):
        self.readme_commit()
        for base, base_ref in [(self.git('rev-parse', 'HEAD'), 'main'), ('e' * 40, 'main'), (self.base, 'beta')]:
            result, _ = self.classify('automation/readme-release-stats', base=base, base_ref=base_ref)
            self.assertNotEqual(0, result.returncode)
        old_head = self.git('rev-parse', 'HEAD')
        self.git('checkout', '-q', self.base)
        (self.repo / 'input').write_text('main advanced\n')
        self.git('commit', '-qam', 'new protected main')
        self.git('push', '-q', 'origin', 'HEAD:refs/heads/main')
        self.git('checkout', '-q', old_head)
        result, _ = self.classify('automation/readme-release-stats')
        self.assertNotEqual(0, result.returncode)

    def test_dispatch_requires_explicit_sha_pr_and_run_context_match(self):
        self.readme_commit()
        for args in ({'pr_number': ''}, {'requested_sha': ''}, {'context_sha': 'f' * 40}, {'head': 'f' * 40}, {'base': ''}):
            result, output = self.classify('automation/readme-release-stats', 'workflow_dispatch', **args)
            self.assertNotEqual(0, result.returncode, args)
            self.assertEqual('', output)

    def test_live_pr_metadata_must_match_open_same_repo_exact_head_and_base(self):
        self.readme_commit()
        good = {'state': 'open', 'base': {'ref': 'main', 'sha': self.base, 'repo': {'full_name': 'Noirero/Miyorare'}},
                'head': {'ref': 'automation/readme-release-stats', 'sha': self.git('rev-parse', 'HEAD'),
                         'repo': {'full_name': 'Noirero/Miyorare'}}}
        for field, value in [('state', 'closed'), ('head.sha', 'f' * 40), ('head.ref', 'automation/foo'),
                             ('base.sha', 'f' * 40), ('base.ref', 'beta'), ('head.repo.full_name', 'fork/Miyorare')]:
            candidate = json.loads(json.dumps(good))
            target = candidate
            parts = field.split('.')
            for part in parts[:-1]: target = target[part]
            target[parts[-1]] = value
            result, output = self.classify('automation/readme-release-stats', pr_data=candidate)
            self.assertNotEqual(0, result.returncode, field)
            self.assertEqual('', output)

    def test_readme_delete_mode_change_and_empty_diff_are_not_maintenance(self):
        result, _ = self.classify('automation/readme-release-stats')
        self.assertNotEqual(0, result.returncode)
        (self.repo / 'README.md').unlink()
        self.git('commit', '-qam', 'delete README')
        result, _ = self.classify('automation/readme-release-stats')
        self.assertNotEqual(0, result.returncode)
        self.git('reset', '--hard', self.base)
        (self.repo / 'README.md').chmod(0o755)
        self.git('commit', '-qam', 'change mode')
        result, _ = self.classify('automation/readme-release-stats')
        self.assertNotEqual(0, result.returncode)

    def test_hotfix_compiles_instrumentation_using_existing_deep_policy(self):
        workflow = WORKFLOW.read_text()
        self.assertIn('Classify hotfix instrumentation compile risk', workflow)
        self.assertIn('for gate in android-test-compile preview-android-test-compile', workflow)
        self.assertIn('--gate "$gate"', workflow)
        self.assertIn('git diff --name-only "$BASE_SHA" "$HEAD_SHA"', workflow)
        self.assertIn(':app:compileDebugAndroidTestKotlin', workflow)
        self.assertIn(':app:compileDebugAndroidTestJavaWithJavac', workflow)
        self.assertIn(':app:compilePreviewAndroidTestKotlin', workflow)
        self.assertIn(':app:compilePreviewAndroidTestJavaWithJavac -PMIYORARE_ANDROID_TEST_BUILD_TYPE=preview', workflow)
        self.assertIn('run_android_test_compile', workflow)
        self.assertIn('run_preview_android_test_compile', workflow)
        from ci_deep_paths import requires_android_test_compile, requires_preview_android_test_compile
        from android_runtime_paths import requires_android_runtime
        paths = ['app/src/androidTest/java/UncompilableTest.java']
        self.assertFalse(requires_android_runtime(paths))
        self.assertTrue(requires_android_test_compile(paths))
        self.assertFalse(requires_preview_android_test_compile(paths))
        self.assertTrue(requires_preview_android_test_compile(['unknown/new-input']))
        self.assertFalse(requires_android_test_compile(['docs/example.md']))

    def test_compile_router_evaluates_whole_candidate_and_mixed_inputs(self):
        command = script('Classify hotfix instrumentation compile risk')
        command = command.replace('python3 .github/scripts/', 'python3 ' + str(ROOT / '.github/scripts') + '/')
        path = self.repo / 'app/src/androidTest/java/Broken.java'
        path.parent.mkdir(parents=True)
        path.write_text('class Broken {}')
        self.git('add', '.')
        self.git('commit', '-qm', 'instrumentation input')
        (self.repo / 'README.md').write_text('last commit is docs only\n')
        self.git('add', '.')
        self.git('commit', '-qm', 'docs')
        output = Path(self.tmp.name) / 'compile-outputs'
        result = subprocess.run(['bash', '-c', command], cwd=self.repo,
                                env={**os.environ, 'BASE_SHA': self.base, 'HEAD_SHA': self.git('rev-parse', 'HEAD'),
                                     'GITHUB_OUTPUT': str(output)}, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('run_android_test_compile=true', output.read_text())
        self.assertIn('run_preview_android_test_compile=false', output.read_text())

    def test_required_check_rejects_missing_or_failed_hotfix_evidence(self):
        command = script('Verify candidate validation result')
        for mode, classify, jvm, runtime, required, succeeds in [
            ('promotion', 'success', 'skipped', 'skipped', '', True),
            ('hotfix', 'success', 'success', 'skipped', 'false', True),
            ('hotfix', 'success', 'success', 'success', 'true', True),
            ('maintenance', 'success', 'skipped', 'skipped', '', True),
            ('maintenance', 'success', 'success', 'skipped', '', False),
            ('maintenance', 'failure', 'skipped', 'skipped', '', False),
            ('hotfix', 'success', 'failure', 'skipped', 'false', False),
            ('hotfix', 'success', 'success', 'skipped', 'true', False),
            ('hotfix', 'failure', 'success', 'success', 'true', False),
            ('unknown', 'success', 'skipped', 'skipped', '', False),
        ]:
            result = subprocess.run(['bash', '-c', command], cwd=self.repo,
                                    env={**os.environ, 'MODE': mode, 'CLASSIFY_RESULT': classify,
                                         'JVM_RESULT': jvm, 'RUNTIME_RESULT': runtime, 'RUN_RUNTIME': required},
                                    capture_output=True, text=True)
            self.assertEqual(succeeds, result.returncode == 0, (mode, classify, jvm, runtime, required))

    def test_hotfix_has_fast_launcher_isolation_with_identical_assertions(self):
        self.assertEqual(script('Verify launcher isolation', ROOT / '.github/workflows/ci-fast.yml'),
                         script('Verify launcher isolation'))

    def test_promotion_summary_records_literal_exact_sha_and_branch(self):
        command = script('Record promotion evidence')
        command = command.replace('${{ github.head_ref || github.ref_name }}', 'beta')
        summary = Path(self.tmp.name) / 'summary'
        result = subprocess.run(['bash', '-c', command], cwd=self.repo,
                                env={**os.environ, 'GITHUB_STEP_SUMMARY': str(summary), 'HEAD_REF': 'beta'},
                                capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('', result.stderr)
        self.assertIn(self.base, summary.read_text())
        self.assertIn('beta', summary.read_text())

    def test_fast_runs_contract_without_gradle_or_emulator(self):
        workflow = (ROOT / '.github/workflows/ci-fast.yml').read_text()
        self.assertIn('python3 .github/scripts/test_main_candidate_contract.py', workflow)
        self.assertNotIn('./gradlew', workflow)
        self.assertNotIn('android-emulator-runner', workflow)


if __name__ == '__main__':
    # Existing Fast invocation also owns the cheap closure orchestration suite;
    # Fast/Deep workflow definitions and their dispatch semantics stay unchanged.
    import sys
    import test_automation_dispatch
    loader = unittest.defaultTestLoader
    suite = loader.loadTestsFromModule(sys.modules[__name__])
    suite.addTests(loader.loadTestsFromModule(test_automation_dispatch))
    raise SystemExit(not unittest.TextTestRunner().run(suite).wasSuccessful())
