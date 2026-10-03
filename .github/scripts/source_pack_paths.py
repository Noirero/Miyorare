#!/usr/bin/env python3
"""Local, stdlib-only routing for the four read-only Source Pack workflows."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
PACK_ROOT = 'extensions/miyorare-sources/'
APP_ROOT = 'app/src/main/kotlin/org/koitharu/kotatsu/'
CHECKS = ('run_id', 'run_en', 'run_global', 'run_multi', 'run_contract', 'run_mirror')
BUILDERS = {'run_id', 'run_en', 'run_global'}
WORKFLOWS = {
    '.github/workflows/miyorare-source-pack-check.yml': {'run_id', 'run_en'},
    '.github/workflows/miyorare-global-source-pack-check.yml': {'run_global'},
    '.github/workflows/miyorare-multi-upstream-check.yml': {'run_multi'},
    '.github/workflows/source-pack-contract.yml': {'run_contract', 'run_mirror'},
}
LOCAL_CONTRACT = {
    '.github/workflows/main-build.yml', '.github/workflows/post-release-readme.yml',
    '.github/workflows/release-build-profile.yml',
    '.github/workflows/miyorare-farm-pack-membership-sync.yml',
    *(f'.github/scripts/{name}.py' for name in (
        'source_pack_readiness', 'test_source_pack_readiness', 'release_version',
        'test_release_version', 'summarize_gradle_profile', 'test_summarize_gradle_profile')),
    PACK_ROOT + 'tools/sync_approved_farm_sources.py',
    PACK_ROOT + 'tools/test_sync_approved_farm_sources.py',
    APP_ROOT + 'settings/sources/MiyorareSourcePackDetailSettingsFragment.kt',
}
TOOLS = {
    'prepare_pack.py': {'run_id', 'run_en'},
    # Global imports collect_sources from this module, which imports icon metadata.
    'prepare_gekkoushi_shard.py': BUILDERS,
    'source_icon_metadata.py': BUILDERS,
    'finalize_pack.py': {'run_id', 'run_en'},
    'finalize_logical_pack.py': {'run_id', 'run_en'},
    'prepare_global_gekkoushi_shard.py': {'run_global'},
    'finalize_global_shard.py': {'run_global'},
    'finalize_global_pack.py': {'run_global'},
    'exhentai_differential_diagnostic.py': {'run_global'},
    'test_exhentai_pagination.py': {'run_global'},
    'verify_multi_upstream.py': {'run_multi'},
}


def decision(checks: set[str], reason: str) -> dict:
    return {**{key: key in checks for key in CHECKS}, 'reason': reason}


def full(reason: str) -> dict:
    return decision(set(CHECKS), reason)


def classify(paths: list[str]) -> dict:
    if not paths:
        return full('unexplained empty diff')
    selected: set[str] = set()
    for path in paths:
        if (not path or path.startswith('/') or '\\' in path or any(ord(c) < 32 for c in path)
                or any(p in {'', '.', '..'} for p in path.split('/'))):
            return full('invalid diff path')
        if (path.startswith('.github/actions/source-pack-routing/')
                or path.startswith('.github/scripts/source_pack_paths')
                or path.startswith('.github/scripts/test_source_pack_paths')):
            return full('shared routing or regression input')
        if path.startswith('.github/actions/source-pack-toolchain/'):
            selected.update(BUILDERS)
        elif path in WORKFLOWS:
            selected.update(WORKFLOWS[path])
        elif path in LOCAL_CONTRACT:
            selected.add('run_contract')
        elif (path == 'compatibility/miyorare-source-pack-contract.v1.json'
              or path.startswith(APP_ROOT + 'tsuki/')):
            selected.update({'run_contract', 'run_mirror'})
        elif (path.startswith(APP_ROOT + 'sources/compat/')
              or path.startswith('app/src/test/kotlin/org/koitharu/kotatsu/sources/compat/')):
            selected.add('run_multi')
        elif path == PACK_ROOT + 'packs.json':
            # Shared schema/provider pins and membership consumed by all three builders
            # and by the actual external intake verifier. No JSON approximation here.
            selected.update(BUILDERS | {'run_multi'})
        elif path == PACK_ROOT + 'multi-upstream.json':
            selected.add('run_multi')
        elif path in {PACK_ROOT + 'README.md', PACK_ROOT + 'ATTRIBUTION.md'}:
            continue  # none of the four validators reads these documentation files
        elif path.startswith(PACK_ROOT + 'tools/') and path[len(PACK_ROOT + 'tools/'):] in TOOLS:
            selected.update(TOOLS[path[len(PACK_ROOT + 'tools/'):]])
        elif any(path.startswith(PACK_ROOT + f'overlays/gekkoushi/{pack}/')
                 and path.endswith('.kt') for pack in ('id', 'en', 'all')):
            locale = path[len(PACK_ROOT + 'overlays/gekkoushi/'):].split('/')[0]
            selected.add({'id': 'run_id', 'en': 'run_en', 'all': 'run_global'}[locale])
        elif (path.startswith(PACK_ROOT) or path.startswith('compatibility/miyorare-source-pack')
              or path.startswith('.github/actions/source-pack-')
              or path.startswith('.github/scripts/source_pack')
              or path.startswith('.github/scripts/test_source_pack')):
            return full('unknown relevant Source Pack input: ' + path)
        # Other app/Gradle/docs inputs were never inputs to these independent
        # upstream build trees. Their existing app CI remains responsible.
    return decision(selected, 'union of audited input owners')


def git(repo: Path, *args: str) -> str:
    return subprocess.check_output(['git', *args], cwd=repo, text=True, stderr=subprocess.PIPE)


def route(repo: Path, event: str, base: str, head: str) -> dict:
    try:
        if not re.fullmatch(r'[0-9a-f]{40}', head) or git(repo, 'rev-parse', 'HEAD').strip() != head:
            return full('candidate identity could not be verified')
        if event == 'workflow_dispatch':
            return full('manual full validation')
        if event not in {'pull_request', 'push'} or not re.fullmatch(r'[0-9a-f]{40}', base):
            return full('unsupported event or missing base history')
        for sha in (base, head):
            git(repo, 'cat-file', '-e', sha + '^{commit}')
        raw = git(repo, 'diff', '--name-only', '--no-renames', '-z', base, head, '--')
        if raw and not raw.endswith('\0'):
            return full('malformed git diff')
        return classify(raw[:-1].split('\0') if raw else [])
    except (OSError, subprocess.CalledProcessError, UnicodeError, ValueError) as exc:
        print('Source Pack routing fallback: ' + type(exc).__name__, file=sys.stderr)
        return full('history/parser failure')


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--event', required=True)
    parser.add_argument('--base', default='')
    parser.add_argument('--head', required=True)
    args = parser.parse_args()
    print(json.dumps(route(ROOT, args.event, args.base, args.head), sort_keys=True))


if __name__ == '__main__':
    main()
