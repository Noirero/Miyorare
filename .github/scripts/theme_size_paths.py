#!/usr/bin/env python3
"""Route the Theme Size Baseline; uncertainty always requests the full APK audit."""
from __future__ import annotations

import argparse
from collections import Counter
import ast
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

import exclusive_visual_paths as visual

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = '.github/workflows/reader-journey-theme-size-baseline.yml'
PREFIX = 'app/src/main/kotlin/org/koitharu/kotatsu/'
STATS = PREFIX + 'stats/ui/StatsScreen.kt'
HOSTS = {PREFIX + 'stats/ui/' + name for name in ('StatsActivity.kt', 'ReaderJourneyFragment.kt')}
RESOURCE_NAME = re.compile(r'journey|rank|theme|badge|nameplate|cosmetic|profile_frame|exclusive', re.I)
STRING_FILE = re.compile(r'app/src/main/res/values[^/]*/(?:strings|settings_developer_diagnostics)\.xml')


def workflow_paths() -> list[str]:
    section = (ROOT / WORKFLOW).read_text().split('    paths:\n', 1)[1].split('\n  workflow_dispatch:', 1)[0]
    paths = []
    for line in section.splitlines():
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        if not line.startswith('      - '):
            raise ValueError('unsupported workflow path syntax')
        value = ast.literal_eval(line[8:])
        if not isinstance(value, str) or value.startswith('!'):
            raise ValueError('unsupported path rule')
        paths.append(value)
    if not paths:
        raise ValueError('missing size path rules')
    return paths


def host_fingerprint(source: str) -> list[str]:
    # #462 only collects/binds an existing Boolean. Keep every other token,
    # including imports, new calls, resource references and theme/profile bindings.
    values = [v for v, _, _ in visual.tokens(source)]
    stack = []
    pairs = {')': '(', ']': '[', '}': '{'}
    for value in values:
        if value in ('(', '[', '{'):
            stack.append(value)
        elif value in pairs:
            if not stack or stack.pop() != pairs[value]:
                raise ValueError('unbalanced host source')
    if stack:
        raise ValueError('unbalanced host source')
    collect = 'val hasLoadedStats by viewModel . hasLoadedStats . collectAsState ( )'.split()
    bind = 'hasLoadedStats = hasLoadedStats ,'.split()
    for sequence in (collect, bind):
        indices = [i for i in range(len(values)) if values[i:i + len(sequence)] == sequence]
        if len(indices) > 1:
            raise ValueError('ambiguous loading binding')
        if indices:
            i = indices[0]
            del values[i:i + len(sequence)]
    if 'StatsScreen' not in values:
        raise ValueError('missing audited screen host')
    return values


def source_requires_size(path: str, before: str | None, after: str | None) -> bool:
    if before is None or after is None:
        return True
    try:
        # A visual-safe helper edit can still hide a large embedded text payload.
        # Only small plain labels qualify for the size exception (routing bound,
        # not an APK acceptance budget). Unknown/raw/resource-like text runs.
        old_literals = Counter(v for v, _, _ in visual.tokens(before) if v.startswith('"'))
        new_literals = Counter(v for v, _, _ in visual.tokens(after) if v.startswith('"'))
        changed_literals = list((new_literals - old_literals).elements())
        if any(len(v.encode('utf-8')) > 256 or v.startswith('"""') or '@' in v or '$' in v
               for v in changed_literals):
            return True
        if sum(len(v.encode('utf-8')) for v in changed_literals) > 512:
            return True
        fingerprint = visual.visual_fingerprint if path == STATS else host_fingerprint
        return fingerprint(before) != fingerprint(after)
    except (ValueError, IndexError, StopIteration):
        return True


def protected_strings() -> set[str]:
    # Resolve actual resource references in all audited production theme/host
    # inputs. Naming protection additionally covers dynamically selected rank IDs.
    rules = workflow_paths()
    result = set()
    for source in (ROOT / 'app/src/main/kotlin').rglob('*.kt'):
        path = source.relative_to(ROOT).as_posix()
        if '/settings/developer/' in path and source.name not in {
                'ExclusiveThemeQaFragment.kt', 'RankThemeGalleryFragment.kt', 'DeveloperToolsFragment.kt'}:
            continue  # extension diagnostics are unrelated to the theme QA payload
        if any(visual.matches(path, rule) for rule in rules):
            result.update(re.findall(r'R\.string\.(\w+)', source.read_text()))
    return result


def strings_requires_size(before: str | None, after: str | None, protected: set[str]) -> bool:
    if before is None or after is None:
        return True
    try:
        def entries(source):
            # DTD/entities and resource aliases are outside the audited text-only case.
            if '<!DOCTYPE' in source or '<!ENTITY' in source:
                raise ValueError('unsupported XML entity')
            root = ET.fromstring(source)
            if root.tag != 'resources' or root.attrib:
                raise ValueError('unsupported resource root')
            result = {}
            for element in root:
                name = element.get('name')
                if element.tag != 'string' or not name or name in result:
                    raise ValueError('unsupported/duplicate string resource')
                result[name] = element
            return result
        old, new = entries(before), entries(after)
        if old.keys() != new.keys():
            return True  # new/deleted resource IDs can change the package table
        for name, element in old.items():
            other = new[name]
            if ET.tostring(element) == ET.tostring(other):
                continue
            if name in protected or RESOURCE_NAME.search(name):
                return True
            if element.attrib != other.attrib or len(element) or len(other):
                return True
            if any(len(text.encode('utf-8')) > 512 or '@' in text or '?' in text
                   for text in (element.text or '', other.text or '')):
                return True
        return False
    except (ET.ParseError, ValueError):
        return True


def metadata(path: str) -> bool:
    return path.startswith('docs/') or path in {'README.md', 'LICENSE', 'CHANGELOG.md', '.gitignore', '.gitattributes'}


def requires_size(paths: list[str], sources: dict[str, tuple[str | None, str | None]] | None = None,
                  protected: set[str] | None = None) -> bool:
    if not paths:
        return True  # an unexplained empty diff is not evidence of safety
    rules = workflow_paths()
    sources = sources or {}
    for path in paths:
        if not path or path.startswith('/') or '..' in path.split('/') or '\\' in path or '\n' in path:
            return True
        if metadata(path):
            continue
        if not any(visual.matches(path, rule) for rule in rules):
            continue  # preserve scope, like stage 2; not a universal APK-size gate
        before, after = sources.get(path, (None, None))
        if path == STATS or path in HOSTS:
            if source_requires_size(path, before, after):
                return True
        elif STRING_FILE.fullmatch(path):
            if strings_requires_size(before, after, protected if protected is not None else protected_strings()):
                return True
        else:
            return True  # every other matching input is unknown or package-sensitive
    return False


def git_source(ref: str, path: str) -> str | None:
    result = subprocess.run(['git', 'show', f'{ref}:{path}'], cwd=ROOT, capture_output=True)
    return result.stdout.decode('utf-8') if result.returncode == 0 else None


def classify(base: str, head: str) -> bool:
    try:
        # Compare the same two immutable trees that the size job actually builds.
        # Never compare only event.before or reuse unverified previous-head evidence.
        for ref in (base, head):
            if not re.fullmatch(r'[0-9a-f]{40}', ref):
                raise ValueError('expected immutable full commit SHA')
            subprocess.check_output(['git', 'cat-file', '-e', f'{ref}^{{commit}}'], cwd=ROOT, stderr=subprocess.PIPE)
        actual = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
        if actual != head:
            raise ValueError('checkout differs from exact candidate head')
        changed = subprocess.check_output(['git', 'diff', '--name-only', '--no-renames', '-z', base, head], cwd=ROOT)
        paths = changed.decode('utf-8').rstrip('\0').split('\0') if changed else []
        sources = {p: (git_source(base, p), git_source(head, p)) for p in paths
                   if p == STATS or p in HOSTS or STRING_FILE.fullmatch(p)}
        return requires_size(paths, sources)
    except (subprocess.CalledProcessError, ValueError, IndexError, OSError, UnicodeError) as error:
        print(f'Size routing uncertain; run full baseline: {error}', file=sys.stderr)
        return True


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', required=True)
    parser.add_argument('--head', required=True)
    args = parser.parse_args()
    print('true' if classify(args.base, args.head) else 'false')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
