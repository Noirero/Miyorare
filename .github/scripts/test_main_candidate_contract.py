#!/usr/bin/env python3
"""Execute the main gate's real shell policy against local Git candidates."""
from __future__ import annotations

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
        self.git('add', '.')
        self.git('commit', '-qm', 'protected main base')
        self.base = self.git('rev-parse', 'HEAD')
        subprocess.run(['git', 'init', '--bare', '-q', str(self.origin)], check=True)
        self.git('remote', 'add', 'origin', str(self.origin))
        self.git('push', '-q', 'origin', 'HEAD:refs/heads/beta')
        self.git('fetch', '-q', 'origin', 'beta')

    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.repo, text=True).strip()

    def classify(self, ref, event='pull_request', head=None, base=None):
        output = Path(self.tmp.name) / 'outputs'
        output.write_text('')
        command = script('Classify source').replace('${{ github.event_name }}', event)
        result = subprocess.run(['bash', '-c', command], cwd=self.repo, text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                env={**os.environ, 'HEAD_REF': ref, 'HEAD_SHA': head or self.git('rev-parse', 'HEAD'),
                                     'BASE_SHA': base or self.base, 'GITHUB_OUTPUT': str(output)})
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
    unittest.main()
