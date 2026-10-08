#!/usr/bin/env python3
"""Stdlib regression tests for input ownership and preserved external validation."""
from __future__ import annotations

import ast
import fnmatch
import hashlib
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import textwrap
import unittest
from unittest.mock import patch

import source_pack_paths as router

ROOT = router.ROOT
P = router.PACK_ROOT
# Pre-Stage-5 validation steps: ignores routing `if`, preserves every command,
# checkout ref/configuration, artifact failure/retention rule and environment.
PRESERVED = {('miyorare-global-source-pack-check.yml', 'Apply Miyorare Global parser overlays'): '04c52d75790e8f0b181de149ab2ee58364ed5c7536c5274483aab6e58a3c3ca7',
 ('miyorare-global-source-pack-check.yml', 'Build global Gekkoushi shard'): '616d1996ff918baf36c0930fc349b98b7a1ea90528f1ffd60d30920e24c17ce1',
 ('miyorare-global-source-pack-check.yml', 'Checkout pinned Gekkoushi source'): 'd6277e37f9949beb1434c702720bca3b5632bc18827630bef418c2f465ec6fbe',
 ('miyorare-global-source-pack-check.yml', 'Finalize global Gekkoushi shard'): 'b054a9e6b3514ba731f70bf33ed66fb1d6e2554b66ac7844ed50eb08a7a923e8',
 ('miyorare-global-source-pack-check.yml', 'Finalize logical global pack'): '5c5a175455f5ae9ef91e604f2ed8fcc072e97148307f43ac2745fed7205a3ec8',
 ('miyorare-global-source-pack-check.yml', 'Prepare global Gekkoushi shard'): 'fada907ed4661765b9906b3d385253d1b401a9fcaac37f5707e039e7effc22d3',
 ('miyorare-global-source-pack-check.yml', 'Upload Gradle diagnostics on failure'): 'a055711a72261e67adbfb479f6941ef49b2b43c30edb34196aa6d83c858094a1',
 ('miyorare-global-source-pack-check.yml', 'Upload staging pack'): 'df4d04f860aa3fcc91209f1edb6b29efe06b3044e4130eba275a5da7c08841fb',
 ('miyorare-global-source-pack-check.yml', 'Verify ExHentai pagination regression contract'): 'da0f75786892df82656a5468e1d868917be783baa59a22a5f636e8c45625236a',
 ('miyorare-global-source-pack-check.yml', 'Verify Global ownership and complete E-Hentai family preset'): 'e7e8536c50758a6435fe2b2708332c6c6da6d937b1d750a848796383df11af44',
 ('miyorare-multi-upstream-check.yml', 'Checkout pinned Keiyoushi'): 'a8b009632b524c64cb3f9b56d3eeab911b178d16e5d4e6f466d99d605409ddc0',
 # Sole ref exception: approved Farm pin update in #79; see CI_SOURCE_PACK_STAGE5.md.
 ('miyorare-multi-upstream-check.yml', 'Checkout pinned UMA'): '820963cdd4378e81080946a979bb5ba73114050863c36d57214ecccf403041a7',
 ('miyorare-multi-upstream-check.yml', 'Upload normalized intake metadata'): '7be4116c9df0066814d4036ec5280dd7c6c3f89e829423db49ab5ee6cf0aaae9',
 ('miyorare-multi-upstream-check.yml', 'Verify normalized multi-upstream aliases'): '2bf75438e7ba24ff5749595ca60e940ef7b577fb83e59ee780abec1e212a6436',
 ('miyorare-source-pack-check.yml', 'Apply Miyorare ID parser overlays'): '9bd46ae87d09793f7789754ece59700b0c20151005eb2f595e06f5b0c5ac8d95',
 ('miyorare-source-pack-check.yml', 'Build Gekkoushi shard with Gekkoushi build system'): '19e6d87d2992b890c2e606b4ab55407c3d686d9c522fe72d83a34665d9ebe134',
 ('miyorare-source-pack-check.yml', 'Build UMA shard with UMA build system'): '487714b9e2525a07b5a9f74fcf1d2fb1c30520c3e8e4ca30d841851175bc55e1',
 ('miyorare-source-pack-check.yml', 'Checkout pinned Gekkoushi source'): '51d88cf572f59cbd3a6a42727b593972658fdde3f6207865abc873131f7d0e3b',
 ('miyorare-source-pack-check.yml', 'Checkout pinned UMA source'): '2c15828394baf3b6081bf69358beb92633a29db615990b5c35758385b8f6afd0',
 ('miyorare-source-pack-check.yml', 'Finalize Gekkoushi shard'): 'c83179886c506f1e2c0fbd36c9ca038c7d27202e46eca2e7f545d046186f5aeb',
 ('miyorare-source-pack-check.yml', 'Finalize UMA shard'): '9cc5baf86e804245fbdacbaf79b43c4d2d6c15186c87564a8e18cc51caea60f4',
 ('miyorare-source-pack-check.yml', 'Finalize logical Miyorare pack manifest'): 'c0cccbef0c701eb2d1eb26872e92ec8c5decc7d60ecb547775264a8bae6e7be2',
 ('miyorare-source-pack-check.yml', 'Prepare Gekkoushi shard'): '461ed393141adb08ad046ec7b2c4c16563d4dc3d80247a2335715c9068ebeefb',
 ('miyorare-source-pack-check.yml', 'Prepare curated UMA shard'): '1e21738e3e02771b616a01fb025fd43fd66197cd6d3323fc74ea4454d648ab0a',
 ('miyorare-source-pack-check.yml', 'Resolve provider pins from pack manifest'): '9cc0c340c561516f2ffddbb070335bd7a97e82307b3fc2ce1a92bacdc9dcbc4e',
 ('miyorare-source-pack-check.yml', 'Upload Gradle diagnostics on failure'): 'a233e1edc6eaaabd60fbbe76e8c4100c7facd9b9eb38e3a14c1aec0f0ade083f',
 ('miyorare-source-pack-check.yml', 'Upload staging pack'): 'b17819a673b0bf49dcc9ac7b90d4a00dd466eb30a2596519a2bedfa8ce14de25',
 ('miyorare-source-pack-check.yml', 'Verify HoloToon placement'): '5039cc30ec79a6a798084226ef70b1c3e68f96a6bc9ca6c32a7b7d449aded3c2',
 ('source-pack-contract.yml', 'Checkout Source Packs contract'): '069d3a223328615b77c80a163c670c050accb6eff1f54443c8efc89ba6ba5835',
 ('source-pack-contract.yml', 'Test Compatibility Farm membership sync engine'): 'd9b76e66a0ab91abd06ba1c6e419d1c4b03fb4cd7d2719563a35c167ce1f6519',
 ('source-pack-contract.yml', 'Test stable Source Pack readiness gate'): 'f915ee3227ce1a27fe240c64c25b29930ba8a6508223c17b9784c104f3bd6969',
 ('source-pack-contract.yml', 'Test stable release version resolver'): 'cbe7b3a971e6639ac32759a6f821adc1a8e592cb96b2e0319379107760083471'}
LOCAL_ASSERTIONS_SHA256 = 'a4c239d5a797b0ef9b6b6e32fa0f0509ed3465ea5f7ed9e8a3ac7d9e5cc1fea6'


def read(path):
    return (ROOT / path).read_text()


def step(path, name):
    blocks = re.split(r'(?=      - name: )', read(path))
    return next(b for b in blocks if b.startswith('      - name: ' + name + '\n'))


def run_body(block):
    return textwrap.dedent(block.split('        run: |\n', 1)[1]).strip()


def selected(paths):
    return router.classify(paths)


class RouterTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = Path(self.tmp.name)
        subprocess.run(['git', 'init', '-q', str(self.repo)], check=True)
        self.git('config', 'user.email', 'ci@example.invalid')
        self.git('config', 'user.name', 'CI')
        (self.repo / 'README.md').write_text('base\n')
        self.git('add', '.')
        self.git('commit', '-qm', 'base')
        self.base = self.git('rev-parse', 'HEAD').strip()

    def tearDown(self):
        self.tmp.cleanup()

    def git(self, *args):
        return subprocess.run(['git', '-C', str(self.repo), *args], check=True, text=True, stdout=subprocess.PIPE).stdout

    def commit(self, path, content='x'):
        target = self.repo / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content)
        self.git('add', path)
        self.git('commit', '-qm', path)
        return self.git('rev-parse', 'HEAD').strip()

    def test_path_classification(self):
        cases = {
            P + 'packs.json': set(router.CHECKS),
            P + 'multi-upstream.json': {'run_multi'},
            P + 'overlays/gekkoushi/id/A.kt': {'run_id'},
            P + 'overlays/gekkoushi/en/A.kt': {'run_en'},
            P + 'overlays/gekkoushi/all/A.kt': {'run_global'},
            P + 'tools/prepare_global_gekkoushi_shard.py': {'run_global'},
            'compatibility/miyorare-source-pack-contract.v1.json': {'run_contract', 'run_mirror'},
            '.github/scripts/source_pack_paths.py': set(router.CHECKS),
            '.github/actions/source-pack-routing/action.yml': set(router.CHECKS),
        }
        for path, expected in cases.items():
            self.assertEqual(expected, selected([path]), path)

    def test_docs_only_routes_none(self):
        self.assertEqual(set(), selected(['README.md', 'docs/x.md']))

    def test_unknown_source_pack_path_routes_full(self):
        self.assertEqual(set(router.CHECKS), selected([P + 'unknown.toml']))

    def test_unknown_ci_path_routes_full(self):
        self.assertEqual(set(router.CHECKS), selected(['.github/actions/source-pack-new/action.yml']))

    def test_mixed_paths_union(self):
        self.assertEqual({'run_id', 'run_en'}, selected([P + 'overlays/gekkoushi/id/A.kt', P + 'overlays/gekkoushi/en/B.kt']))

    def test_renames_route_both_old_and_new_paths(self):
        first = self.commit(P + 'overlays/gekkoushi/id/A.kt', 'a')
        self.git('mv', P + 'overlays/gekkoushi/id/A.kt', P + 'overlays/gekkoushi/en/A.kt')
        self.git('commit', '-qm', 'rename')
        head = self.git('rev-parse', 'HEAD').strip()
        self.assertEqual({'run_id', 'run_en'}, router.route(self.repo, 'pull_request', first, head))

    def test_deleted_paths_are_classified(self):
        first = self.commit(P + 'overlays/gekkoushi/all/A.kt', 'a')
        self.git('rm', P + 'overlays/gekkoushi/all/A.kt')
        self.git('commit', '-qm', 'delete')
        head = self.git('rev-parse', 'HEAD').strip()
        self.assertTrue(router.route(self.repo, 'pull_request', first, head)['run_global'])

    def test_missing_history_zero_base_parser_failure_and_unknown_event_run_full(self):
        head = self.commit('README.md', 'docs')
        for event, base in [('pull_request', 'f' * 40), ('push', '0' * 40), ('push', ''), ('unknown', self.base)]:
            result = router.route(self.repo, event, base, head)
            self.assertTrue(all(result[k] for k in router.CHECKS))
        with patch.object(router, 'git', side_effect=UnicodeDecodeError('utf8', b'\xff', 0, 1, 'invalid')):
            result = router.route(self.repo, 'pull_request', self.base, head)
            self.assertTrue(all(result[k] for k in router.CHECKS))

    def test_push_full_diff_and_manual_force_full_on_docs_head(self):
        head = self.commit('README.md', 'docs')
        self.assertFalse(any(router.route(self.repo, 'push', self.base, head)[k] for k in router.CHECKS))
        self.assertTrue(all(router.route(self.repo, 'workflow_dispatch', '', head)[k] for k in router.CHECKS))


class WorkflowContractTest(unittest.TestCase):
    def test_event_paths_reach_each_owner_including_unknown_and_shared_inputs(self):
        cases = [*router.LOCAL_CONTRACT, *router.WORKFLOWS,
                 'compatibility/miyorare-source-pack-contract.v1.json',
                 router.APP_ROOT + 'tsuki/NewRuntime.kt',
                 router.APP_ROOT + 'sources/compat/New.kt',
                 'app/src/test/kotlin/org/koitharu/kotatsu/sources/compat/NewTest.kt',
                 P + 'packs.json', P + 'multi-upstream.json', P + 'unknown.toml',
                 '.github/scripts/source_pack_new.py', '.github/scripts/source_pack_paths.py',
                 '.github/scripts/test_source_pack_paths.py',
                 '.github/actions/source-pack-new/action.yml',
                 '.github/actions/source-pack-routing/action.yml',
                 '.github/actions/source-pack-toolchain/action.yml',
                 *(P + 'tools/' + name for name in router.TOOLS),
                 *(P + 'overlays/gekkoushi/' + locale + '/New.kt' for locale in ('id', 'en', 'all'))]
        for path in cases:
            required = selected([path])
            for workflow, owners in router.WORKFLOWS.items():
                if not required.intersection(owners):
                    continue
                text = read(workflow)
                sections = text.split('    paths:\n')[1:]
                self.assertTrue(sections, workflow)
                for section in sections:
                    rules = []
                    for line in section.splitlines():
                        if not line.startswith('      - '):
                            break
                        rules.append(ast.literal_eval(line[8:]))
                    self.assertTrue(any(fnmatch.fnmatchcase(path, rule) for rule in rules), (workflow, path))

    def test_every_preserved_validation_command_checkout_and_artifact(self):
        for (filename, name), digest in PRESERVED.items():
            block = step('.github/workflows/' + filename, name)
            cleaned = re.sub(r'^        if: .*\n', '', block, flags=re.M).strip()
            self.assertEqual(digest, hashlib.sha256(cleaned.encode()).hexdigest(), (filename, name))

    def test_local_contract_assertions_are_unchanged_and_mirror_is_actual_checkout(self):
        path = '.github/workflows/source-pack-contract.yml'
        body = run_body(step(path, 'Validate local contract and runtime enforcement'))
        self.assertEqual(LOCAL_ASSERTIONS_SHA256, hashlib.sha256(body.encode()).hexdigest())
        mirror = step(path, 'Checkout Source Packs contract')
        self.assertIn('repository: Noirero/Miyorare-Source-Packs', mirror)
        self.assertIn('ref: main', mirror)
        self.assertIn('run_mirror', mirror)
        self.assertIn('cmp --silent "$LOCAL" "$REMOTE"', step(path, 'Validate identical external contract'))
        self.assertIn('exit 1', step(path, 'Validate identical external contract'))

    def test_workflow_events_exact_checkout_identity_gates_and_manual_preserved(self):
        identities = {
            'miyorare-source-pack-check.yml': ('Miyorare Source Pack Check', 'build-pack:', 'Build Miyorare-${{ matrix.pack }}'),
            'miyorare-global-source-pack-check.yml': ('Miyorare Global Source Pack Check', 'build-global:', 'Build Miyorare-Global'),
            'miyorare-multi-upstream-check.yml': ('Miyorare Multi-Upstream Check', 'verify:', 'Verify Keiyoushi + UMA source intake'),
            'source-pack-contract.yml': ('Source Pack Compatibility Contract', 'contract:', 'contract'),
        }
        for filename, (title, job, job_name) in identities.items():
            text = read('.github/workflows/' + filename)
            self.assertIn('name: ' + title, text)
            self.assertIn(job, text)
            self.assertIn('name: ' + job_name, text)
            self.assertIn('workflow_dispatch:', text)
            self.assertIn('ref: ${{ github.event_name == \'pull_request\' && github.event.pull_request.head.sha || github.sha }}', text)


if __name__ == '__main__':
    unittest.main()
