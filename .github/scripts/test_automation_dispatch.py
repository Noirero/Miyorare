#!/usr/bin/env python3
"""Run the real automation shell with local Git origins and a deterministic gh API."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from test_main_candidate_contract import ROOT, script, step

POST = ROOT / '.github/workflows/post-release-readme.yml'
STATS = ROOT / '.github/workflows/readme-release-stats.yml'
FARM = ROOT / '.github/workflows/miyorare-farm-pack-membership-sync.yml'
WORKFLOWS = {'identity-guard.yml': 101, 'ci-fast.yml': 102, 'ci-deep.yml': 103,
             'miyorare-source-pack-check.yml': 104, 'beta-to-main-release-gate.yml': 105}

# This stub applies the actual --jq filters to fixture JSON, rather than simply
# returning whichever success ID the caller expects. No network or remote write.
GH = r'''#!/usr/bin/env python3
import datetime, json, os, subprocess, sys
from pathlib import Path
args = sys.argv[1:]
log = Path(os.environ['GH_LOG'])
prior = [json.loads(line) for line in log.read_text().splitlines()] if log.exists() else []
with log.open('a') as file: file.write(json.dumps(args) + '\n')
def flag(name): return args[args.index(name) + 1]
def head(): return subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
def output(value):
    if '--jq' in args:
        r = subprocess.run(['jq', '-r', flag('--jq')], input=json.dumps(value), text=True)
        raise SystemExit(r.returncode)
    print(json.dumps(value))
workflows = {'identity-guard.yml': 101, 'ci-fast.yml': 102, 'ci-deep.yml': 103,
             'miyorare-source-pack-check.yml': 104, 'beta-to-main-release-gate.yml': 105}
if args[:2] == ['workflow', 'run']:
    if os.environ.get('DISPATCH_FAIL') == args[2]: raise SystemExit(1)
    raise SystemExit(0)
if args[:2] == ['run', 'list']:
    assert flag('--event') == 'workflow_dispatch'
    assert flag('--branch') == os.environ.get('EXPECTED_BRANCH', subprocess.check_output(['git', 'branch', '--show-current'], text=True).strip())
    wf = flag('--workflow')
    now = datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')
    rows = [{'databaseId': 998, 'headSha': 'f' * 40, 'createdAt': now},
            {'databaseId': 999, 'headSha': head(), 'createdAt': '2000-01-01T00:00:00Z'}]
    if os.environ.get('MISSING_RUN') != wf:
        rows.append({'databaseId': workflows[wf], 'headSha': head(), 'createdAt': now})
    output(rows)
elif args[:2] == ['run', 'watch']:
    assert '--exit-status' in args
    if os.environ.get('WATCH_FAIL') == args[2]: raise SystemExit(1)
elif args[:2] == ['run', 'view']:
    conclusion = os.environ.get('CONCLUSION', 'success') if os.environ.get('BAD_RUN') == args[2] else 'success'
    sha = 'f' * 40 if os.environ.get('BAD_RUN_SHA') == args[2] else head()
    output({'status': 'completed', 'conclusion': conclusion, 'headSha': sha})
elif args[:2] == ['pr', 'list']:
    if '--json' in args and flag('--json') == 'number,headRefName': print('')
    elif os.environ.get('EXISTING_PR'): print('42')
elif args[:2] == ['pr', 'create']: print('https://github.com/fixture/repo/pull/42')
elif args[:2] == ['pr', 'view']:
    watched = sum(a[:2] == ['run', 'watch'] for a in prior)
    sha = 'f' * 40 if os.environ.get('CHANGE_HEAD') and watched else head()
    output({'number': 42, 'baseRefOid': os.environ['MAIN_BASE'], 'headRefOid': sha,
            'mergedAt': '2026-10-03T00:00:00Z'})
elif args[:2] == ['pr', 'merge']:
    assert flag('--match-head-commit') == head()
    if os.environ.get('ATOMIC_RACE'): raise SystemExit(1)
    Path(os.environ['MERGED']).write_text(head())
else: raise SystemExit('Unsupported fixture gh invocation: ' + repr(args))
'''


class AutomationDispatchTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo = Path(self.tmp.name) / 'repo'
        self.repo.mkdir()
        self.git('init', '-q')
        self.git('config', 'user.name', 'Fixture')
        self.git('config', 'user.email', 'fixture@example.invalid')
        (self.repo / 'README.md').write_text('<!-- RELEASE_README_START -->\nold\n<!-- RELEASE_README_END -->\n')
        self.git('add', '.')
        self.git('commit', '-qm', 'main')
        self.base = self.git('rev-parse', 'HEAD')
        origin = Path(self.tmp.name) / 'origin'
        subprocess.run(['git', 'init', '--bare', '-q', str(origin)], check=True)
        self.git('remote', 'add', 'origin', str(origin))
        self.git('push', '-q', 'origin', 'HEAD:refs/heads/main')
        self.git('fetch', '-q', 'origin', 'main')
        bin_dir = Path(self.tmp.name) / 'bin'
        bin_dir.mkdir()
        (bin_dir / 'gh').write_text(GH)
        (bin_dir / 'gh').chmod(0o755)
        (bin_dir / 'sleep').write_text('#!/bin/sh\nexit 0\n')
        (bin_dir / 'sleep').chmod(0o755)
        self.log = Path(self.tmp.name) / 'calls'
        self.merged = Path(self.tmp.name) / 'merged'
        self.env = {**os.environ, 'PATH': str(bin_dir) + ':' + os.environ['PATH'],
                    'GH_LOG': str(self.log), 'MERGED': str(self.merged), 'MAIN_BASE': self.base,
                    'GITHUB_REPOSITORY': 'Noirero/Miyorare', 'GH_TOKEN': 'local-fixture',
                    'GITHUB_RUN_ID': '123', 'GITHUB_RUN_ATTEMPT': '1', 'VERSION_NAME': '1.0',
                    'RELEASE_COMMIT': self.base, 'SOURCE_RUN_ID': '456',
                    'GITHUB_STEP_SUMMARY': str(Path(self.tmp.name) / 'summary')}

    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.repo, text=True, stderr=subprocess.DEVNULL).strip()

    def calls(self):
        return [json.loads(line) for line in self.log.read_text().splitlines()]

    def execute(self, body, env=None):
        return subprocess.run(['bash', '-c', body], cwd=self.repo, env={**self.env, **(env or {})},
                              capture_output=True, text=True)

    def readme(self, workflow, env=None):
        if workflow == POST:
            (self.repo / 'release').mkdir(exist_ok=True)
            (self.repo / 'release/app.apk').write_bytes(b'local published artifact fixture')
            name = 'Update README from published APKs'
        else:
            (self.repo / 'README.md').write_text('updated native README stats\n')
            name = 'Open update PR when stats changed'
        return self.execute(script(name, workflow), env)

    def farm(self, env=None):
        head = self.git('rev-parse', 'HEAD')
        output = Path(self.tmp.name) / 'outputs'
        context = {'HEAD_BRANCH': 'automation/farm-pack-sync-fixture', 'EXPECTED_BRANCH': 'automation/farm-pack-sync-fixture',
                   'HEAD_SHA': head, 'PR_NUMBER': '42', 'GITHUB_OUTPUT': str(output), **(env or {})}
        result = self.execute(script('Dispatch required validation on exact PR head', FARM), context)
        if result.returncode: return result
        outputs = dict(line.split('=', 1) for line in output.read_text().splitlines())
        for title, key in [('Identity Guard', 'identity_run'), ('CI Fast', 'fast_run'),
                           ('CI Deep', 'deep_run'), ('Source Pack Check', 'pack_run')]:
            block = step('Wait for ' + title, FARM)
            self.assertIn("if: steps.materialize.outputs.changed == 'true'", block)
            self.assertNotIn('always()', block)
            self.assertNotIn('continue-on-error', block)
            result = self.execute(script('Wait for ' + title, FARM), {**context, 'RUN_ID': outputs[key]})
            if result.returncode: return result
        return self.execute(script('Merge validated beta manifest automatically', FARM), context)

    def assert_readme_dispatch(self):
        calls = self.calls()
        dispatches = [c for c in calls if c[:2] == ['workflow', 'run']]
        self.assertEqual(['identity-guard.yml', 'beta-to-main-release-gate.yml'], [c[2] for c in dispatches])
        fields = [c for c in dispatches[1] if '=' in c]
        self.assertIn('maintenance_pr=42', fields)
        self.assertIn('candidate_sha=' + self.git('rev-parse', 'HEAD'), fields)
        self.assertIn('main_base_sha=' + self.base, fields)
        self.assertEqual(['101', '105'], [c[2] for c in calls if c[:2] == ['run', 'watch']])

    def test_post_release_dispatches_both_required_exact_head_checks_before_merge(self):
        result = self.readme(POST)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assert_readme_dispatch()
        self.assertTrue(self.merged.exists())
        calls = self.calls()
        self.assertLess(max(i for i, c in enumerate(calls) if c[:2] == ['run', 'view']),
                        next(i for i, c in enumerate(calls) if c[:2] == ['pr', 'merge']))
        self.assertIn(hashlib.sha256(b'local published artifact fixture').hexdigest(), (self.repo / 'README.md').read_text())
        self.assertIn('GH_TOKEN: ${{ github.token }}', step('Update README from published APKs', POST))

    def test_stats_validates_and_keeps_existing_open_pr_intent(self):
        result = self.readme(STATS)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assert_readme_dispatch()
        self.assertFalse(self.merged.exists())
        self.assertFalse(any(c[:2] == ['pr', 'merge'] for c in self.calls()))
        # Updating the same automation ref preserves the original lease intent.
        self.git('checkout', '-q', self.base)
        self.git('branch', '-D', 'automation/readme-release-stats')
        (self.repo / 'README.md').write_text('new stats update\n')
        self.log.unlink()
        result = self.execute(script('Open update PR when stats changed', STATS), {'EXISTING_PR': '1'})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse(any(c[:2] == ['pr', 'create'] for c in self.calls()))
        self.assert_readme_dispatch()

    def test_readme_wrong_sha_or_old_run_never_satisfies_resolver(self):
        result = self.readme(POST, {'MISSING_RUN': 'beta-to-main-release-gate.yml'})
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.merged.exists())

    def test_readme_failed_cancelled_or_changed_head_cannot_merge(self):
        for env in ({'WATCH_FAIL': '105'}, {'BAD_RUN': '105', 'CONCLUSION': 'cancelled'},
                    {'BAD_RUN_SHA': '105'}, {'CHANGE_HEAD': '1'}, {'ATOMIC_RACE': '1'}):
            with self.subTest(env=env):
                self.git('checkout', '-q', self.base)
                branch = 'automation/release-readme-v1.0-123-a1'
                if branch in self.git('branch'):
                    self.git('branch', '-D', branch)
                if self.git('ls-remote', '--heads', 'origin', 'refs/heads/' + branch):
                    self.git('push', '-q', 'origin', ':refs/heads/' + branch)
                self.log.unlink(missing_ok=True)
                result = self.readme(POST, env)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(self.merged.exists())

    def test_farm_dispatches_matches_and_waits_all_four_before_merge(self):
        result = self.farm()
        self.assertEqual(0, result.returncode, result.stderr)
        calls = self.calls()
        self.assertEqual(list(WORKFLOWS)[:4], [c[2] for c in calls if c[:2] == ['workflow', 'run']])
        self.assertEqual(['101', '102', '103', '104'], [c[2] for c in calls if c[:2] == ['run', 'watch']])
        self.assertTrue(self.merged.exists())
        for c in calls:
            if c[:2] == ['run', 'list']:
                self.assertIn('--workflow', c)
                self.assertIn('--branch', c)
                self.assertIn('--event', c)
                self.assertIn(self.git('rev-parse', 'HEAD'), c[c.index('--jq') + 1])

    def test_every_missing_farm_run_fails_closed(self):
        for workflow in list(WORKFLOWS)[:4]:
            result = self.farm({'MISSING_RUN': workflow})
            self.assertNotEqual(0, result.returncode, workflow)
            self.assertFalse(self.merged.exists())

    def test_every_failed_cancelled_or_wrong_sha_farm_run_prevents_merge(self):
        for run in ('101', '102', '103', '104'):
            for env in ({'WATCH_FAIL': run}, {'BAD_RUN': run, 'CONCLUSION': 'cancelled'},
                        {'BAD_RUN': run, 'CONCLUSION': 'failure'}, {'BAD_RUN_SHA': run}):
                self.log.unlink(missing_ok=True)
                result = self.farm(env)
                self.assertNotEqual(0, result.returncode, env)
                self.assertFalse(self.merged.exists())

    def test_farm_dispatch_failure_changed_head_and_atomic_race_cannot_merge(self):
        for env in ({'DISPATCH_FAIL': 'ci-deep.yml'}, {'CHANGE_HEAD': '1'}, {'ATOMIC_RACE': '1'}):
            self.log.unlink(missing_ok=True)
            result = self.farm(env)
            self.assertNotEqual(0, result.returncode, env)
            self.assertFalse(self.merged.exists())

    def test_release_provenance_stats_and_farm_semantics_are_preserved(self):
        import re
        baseline = 'b689bbc9cf5a1e55ab45596be04bcaac6fe9bc41'
        for workflow, names in [
            (POST, ['Resolve published release provenance and exact APK assets']),
            (STATS, ['Refresh native README release stats']),
            (FARM, ['Resolve immutable Compatibility Farm snapshot', 'Test pack membership sync engine',
                    'Materialize ACTIVE Farm membership into beta manifest',
                    'Commit synchronized beta manifest to automation branch', 'Open protected beta sync pull request']),
        ]:
            original = subprocess.check_output(['git', 'show', baseline + ':' + str(workflow.relative_to(ROOT))],
                                               cwd=ROOT, text=True)
            for name in names:
                old = next(b for b in re.split(r'(?=^      - name: )', original, flags=re.M)
                           if b.startswith('      - name: ' + name + '\n'))
                self.assertEqual(old, step(name, workflow), name)
        self.assertNotIn('gh pr merge', STATS.read_text())
        self.assertIn('actions: write', STATS.read_text())

    def test_farm_manifest_uses_unchanged_existing_deep_dispatch_contract(self):
        from ci_deep_paths import requires_deep, requires_android_test_compile, requires_preview_android_test_compile
        paths = ['extensions/miyorare-sources/packs.json']
        self.assertTrue(all(classifier(paths) for classifier in
                            (requires_deep, requires_android_test_compile, requires_preview_android_test_compile)))
        # Both providers already support dispatch. Their bytes/fingerprints remain unchanged.
        from p0_p1_contract import verify_contract
        verify_contract()
        self.assertNotIn('android-runtime.yml', script('Dispatch required validation on exact PR head', FARM))


if __name__ == '__main__':
    unittest.main()
