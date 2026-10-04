#!/usr/bin/env python3
"""Exact-candidate Phase 10 routing; uncertain coverage requests full validation."""
from __future__ import annotations

import argparse
import ast
from dataclasses import dataclass
import hashlib
from pathlib import Path
import re
import subprocess
import sys

import exclusive_visual_paths as kotlin

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = '.github/workflows/reader-journey-phase10-validation.yml'
PREFIX = 'app/src/main/kotlin/org/koitharu/kotatsu/'
QUEUE = PREFIX + 'readerjourney/domain/CelebrationQueue.kt'
ANDROID = 'app/src/androidTest/kotlin/org/koitharu/kotatsu/'
STATE_TEST = ANDROID + 'readerjourney/theme/ReaderJourneyPhase10StateRestorationTest.kt'
RENDER_TEST = ANDROID + 'readerjourney/theme/ReaderJourneyPhase10RenderedMatrixTest.kt'
BACKUP_TEST = ANDROID + 'backup/local/LocalBackupIdentityTest.kt'
STATE_CLASSES = ('org.koitharu.kotatsu.readerjourney.theme.ReaderJourneyPhase10StateRestorationTest,'
                 'org.koitharu.kotatsu.core.db.ChapterPersistenceRegressionTest')
BACKUP_CLASS = 'org.koitharu.kotatsu.backup.local.LocalBackupIdentityTest'
# These complete audited contracts pin event scope, labels, diff semantics, exact
# head checkout, task/filter, JDK, variant and device configuration. Any evolution
# requires re-audit; it cannot silently make delegated coverage disappear.
DEEP_CONTRACT = {
    '.github/workflows/ci-deep.yml': '0ffceba96f7aa7276d5ba11940ea9501077322028656f2ced07a4f8c7881355c',
    '.github/scripts/ci_deep_paths.py': '1de72ee76ca96630aff882223ad75dea2a4a601aca7bed230b8a2102601e5bc7',
    'app/build.gradle': '389b672afdad89d364f8096cc5da8ec6ce69f05170a73a13ee344903c513cce7',
    'build.gradle': '3bd348120d80c9adcba9a0699100a8c852eeecbb1a02176e897d1bc28452fc30',
    'settings.gradle': '76a55062b8167559a859deb3b65459f5305d41c614aff98306aa5ad49a15308e',
    'gradle.properties': 'cf41e7a3478b3de0888fbfbfb160a2fd9efc7d97360ef85c6835c5b1239c16de',
    'gradle/libs.versions.toml': '239f8cc155c2914e128afa31c78872d17312305a8ce3e8e624c557d5d6aa8574',
}
RUNTIME_CONTRACT = {
    '.github/workflows/android-runtime.yml': '4d20eb3d0ef7e406678e455ed968d4cdc8f16c01d55128b9a9c05bb333278aee',
    '.github/scripts/android_runtime_paths.py': '3d938f2c572b78dc4ae7b041d7bbd539c0da3439b4b2bb0ad93a13efef4b4d6c',
    BACKUP_TEST: '62be3626aa35433c337f94e3189e3892a463ba8abe9ab7011a5c02786c87fa69',
}
SELF = {WORKFLOW, '.github/scripts/phase10_paths.py', '.github/scripts/test_phase10_paths.py',
        '.github/scripts/exclusive_visual_paths.py'}
BUILD_EXACT = {'app/build.gradle', 'build.gradle', 'settings.gradle', 'gradle.properties',
               'gradlew', 'gradlew.bat', 'app/src/main/AndroidManifest.xml'}


@dataclass(frozen=True)
class Route:
    jvm: bool = False
    state: bool = False
    render: bool = False
    backup: bool = False

    @property
    def state_classes(self) -> str:
        classes = STATE_CLASSES if self.state else ''
        if self.backup:
            classes += (',' if classes else '') + BACKUP_CLASS
        return classes

    def outputs(self) -> str:
        return '\n'.join((f'run_jvm={str(self.jvm).lower()}',
                          f'run_state={str(self.state or self.backup).lower()}',
                          f'run_render={str(self.render).lower()}',
                          f'state_classes={self.state_classes}'))


FULL = Route(True, True, True, True)


def workflow_paths() -> list[str]:
    section = (ROOT / WORKFLOW).read_text().split('    paths:\n', 1)[1].split('\n  workflow_dispatch:', 1)[0]
    rules = []
    for line in section.splitlines():
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        if not line.startswith('      - '):
            raise ValueError('unsupported Phase 10 path syntax')
        rule = ast.literal_eval(line[8:])
        if not isinstance(rule, str) or rule.startswith('!'):
            raise ValueError('unsupported Phase 10 path rule')
        rules.append(rule)
    if not rules:
        raise ValueError('missing Phase 10 paths')
    return rules


def contract_matches(contract: dict[str, str]) -> bool:
    return all(hashlib.sha256((ROOT / path).read_bytes()).hexdigest() == digest
               for path, digest in contract.items())


def balanced_tokens(source: str) -> list[str]:
    values = [v for v, _, _ in kotlin.tokens(source)]
    stack = []
    pairs = {')': '(', ']': '[', '}': '{'}
    for value in values:
        if value in ('(', '[', '{'):
            stack.append(value)
        elif value in pairs:
            if not stack or stack.pop() != pairs[value]:
                raise ValueError('unbalanced Kotlin')
    if stack or any(values[i:i + 2] in (['/', '*'], ['*', '/']) for i in range(len(values) - 1)):
        raise ValueError('unbalanced/unsupported Kotlin')
    return values


def queue_fingerprint(values: list[str]) -> list[str]:
    # Only these existing member bodies execute on enqueue/close in ReaderActivity
    # or DeveloperToolsFragment. Neither host is opened by Phase 10. All imports,
    # declarations, initialization and other methods remain protected.
    result = values.copy()
    for name in ('close', 'enqueue'):
        starts = [i for i in range(len(result) - 2) if result[i:i + 3] == ['fun', name, '(']]
        if len(starts) != 1:
            raise ValueError('missing/ambiguous queue member')
        start = starts[0] + 2
        body = kotlin.closing(result, start, '(', ')') + 1
        if result[body] != '{':
            raise ValueError('unsupported queue member')
        end = kotlin.closing(result, body, '{', '}')
        result[body + 1:end] = ['<audited body>']
    return result


def source_is_jvm_only(path: str, before: str | None, after: str | None) -> bool:
    if before is None or after is None:
        return False
    try:
        old, new = balanced_tokens(before), balanced_tokens(after)
        if old == new:
            return True  # comments/formatting cannot change a device fixture
        if path != QUEUE:
            return False
        # New dependency references, reflection/string calls or declarations are
        # outside the audited edit. Keep a narrow exception, not a Kotlin allowlist.
        if any(v not in old for v in new if re.fullmatch(r'[A-Za-z_]\w*', v) or v.startswith(('"', "'", '`'))):
            return False
        return queue_fingerprint(old) == queue_fingerprint(new)
    except (ValueError, IndexError):
        return False


def route_paths(paths: list[str], sources: dict[str, tuple[str | None, str | None]] | None = None,
                *, deep_covers: bool = True, runtime_covers_backup: bool = False) -> Route:
    if not paths:
        return FULL
    sources = sources or {}
    rules = workflow_paths()
    state = render = relevant = False
    for path in paths:
        if not path or path.startswith('/') or '..' in path.split('/') or '\\' in path or '\n' in path:
            return FULL
        if path.startswith('docs/') or path in {'README.md', 'LICENSE', '.gitignore', '.gitattributes'}:
            continue
        if not any(kotlin.matches(path, rule) for rule in rules):
            continue
        relevant = True
        if path in SELF or path in DEEP_CONTRACT or path in RUNTIME_CONTRACT:
            return FULL
        if path in BUILD_EXACT or path.startswith(('gradle/', 'buildSrc/', 'build-logic/')):
            return FULL
        if path.startswith('app/src/test/'):
            continue  # all original 13 selectors are in Deep's unfiltered Debug suite
        if path.endswith('.kt') and source_is_jvm_only(path, *sources.get(path, (None, None))):
            continue
        if path == STATE_TEST:
            state = True
        elif path == RENDER_TEST:
            render = True
        elif path in {PREFIX + 'stats/ui/' + name for name in ('StatsScreen.kt', 'StatsActivity.kt', 'ReaderJourneyFragment.kt')} or path.startswith(('app/src/main/res/', 'app/src/main/assets/')):
            render = True
        else:
            # Reader Journey domain/data/theme, persistence, application/DI and
            # unknown inputs in scope can affect both unique state and rendering.
            state = render = True
    if relevant and not deep_covers:
        return FULL
    # State runs retain ChapterPersistence's measured 4-core/4-GB configuration.
    # Only the functional backup class has no unique Phase 10 timing/display state.
    backup = state and not runtime_covers_backup
    return Route(state=state, render=render, backup=backup)


def git_paths(base: str, head: str, *, merge_base: bool = False) -> list[str]:
    if merge_base:
        base = subprocess.check_output(['git', 'merge-base', base, head], cwd=ROOT, text=True).strip()
    raw = subprocess.check_output(['git', 'diff', '--name-only', '--no-renames', '-z', base, head], cwd=ROOT)
    return raw.decode('utf-8').rstrip('\0').split('\0') if raw else []


def git_source(ref: str, path: str) -> str | None:
    result = subprocess.run(['git', 'show', f'{ref}:{path}'], cwd=ROOT, capture_output=True)
    return result.stdout.decode('utf-8') if result.returncode == 0 else None


def classify(base: str, head: str, *, manual: bool = False) -> Route:
    if manual:
        return FULL
    try:
        for ref in (base, head):
            if not re.fullmatch(r'[0-9a-f]{40}', ref):
                raise ValueError('expected immutable full commit SHA')
            subprocess.check_output(['git', 'cat-file', '-e', f'{ref}^{{commit}}'], cwd=ROOT, stderr=subprocess.PIPE)
        if subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip() != head:
            raise ValueError('checkout is not the exact PR candidate')
        paths = git_paths(base, head)
        if paths and all(p.startswith('docs/') or p in {'README.md', 'LICENSE', '.gitignore', '.gitattributes'} for p in paths):
            return Route()
        if not contract_matches(DEEP_CONTRACT) or not contract_matches(RUNTIME_CONTRACT):
            return FULL
        # Import only after verifying the complete audited implementations.
        from ci_deep_paths import requires_deep
        from android_runtime_paths import policy_requires_android_runtime, OWNER_REQUEST
        deep_covers = requires_deep(paths)
        runtime_paths = git_paths(base, head, merge_base=True)
        # Delegation must survive owner labeling too. Critical/theme paths win over
        # every origin label; label-only runtime runs are not a durable replacement.
        runtime_covers, _ = policy_requires_android_runtime(runtime_paths, {OWNER_REQUEST})
        # A new consumer can invalidate the queue's unvisited-host exception.
        consumers = set()
        for source in (ROOT / 'app/src').rglob('*'):
            if source.suffix in {'.kt', '.java'} and '/test/' not in source.as_posix():
                if re.search(r'\bCelebration(?:Queue(?:Item)?|Presentation)\b', source.read_text()):
                    consumers.add(source.relative_to(ROOT).as_posix())
        allowed = {QUEUE, PREFIX + 'readerjourney/ui/ReaderJourneyCelebrationDialog.kt',
                   PREFIX + 'reader/ui/ReaderActivity.kt', PREFIX + 'settings/developer/DeveloperToolsFragment.kt'}
        queue_safe = consumers == allowed
        sources = {p: (git_source(base, p), git_source(head, p)) for p in paths
                   if p.endswith('.kt') and not p.startswith('app/src/test/') and (p != QUEUE or queue_safe)}
        return route_paths(paths, sources, deep_covers=deep_covers, runtime_covers_backup=runtime_covers)
    except (subprocess.CalledProcessError, ValueError, IndexError, OSError, UnicodeError, ImportError) as error:
        print(f'Phase 10 routing uncertain; run full validation: {error}', file=sys.stderr)
        return FULL


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', default='')
    parser.add_argument('--head', default='')
    parser.add_argument('--manual', action='store_true')
    args = parser.parse_args()
    print(classify(args.base, args.head, manual=args.manual).outputs())
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
