#!/usr/bin/env python3
"""Fail-closed evidence checks for the immutable runtime acceptance harness."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET


def junit_summary(results: Path, class_name: str, expected_count: int) -> dict:
    files = sorted(results.rglob('TEST-*.xml'))
    cases = []
    for path in files:
        cases.extend(ET.parse(path).getroot().iter('testcase'))
    selected = [case for case in cases if case.get('classname') == class_name]
    return {
        'class': class_name, 'expected': expected_count, 'report_files': len(files),
        'tests': len(selected),
        'failures': sum(case.find('failure') is not None for case in cases),
        'errors': sum(case.find('error') is not None for case in cases),
        'skipped': sum(case.find('skipped') is not None for case in selected),
        'unique_tests': len({case.get('name') for case in selected}),
    }


def require_junit(summary: dict) -> None:
    if (summary['tests'] != summary['expected'] or summary['unique_tests'] != summary['expected']
            or summary['expected'] <= 0 or summary['failures'] or summary['errors'] or summary['skipped']):
        raise ValueError('Expected test class did not execute all tests successfully')


def require_probe_manifest(text: str, package: str, runner: str) -> None:
    blocks = re.findall(r'(?m)^([ ]*)E: instrumentation[^\n]*\n((?:(?![ ]*E:)[^\n]*(?:\n|$))*)', text)
    if len(blocks) != 1:
        raise ValueError('Expected exactly one instrumentation declaration')
    attributes = {}
    for line in blocks[0][1].splitlines():
        match = re.search(r'A: android:(name|targetPackage)\([^)]*\)="([^"]+)"', line)
        if match:
            attributes[match[1]] = match[2]
    if attributes.get('targetPackage') != package or attributes.get('name') != runner:
        raise ValueError(f'Wrong instrumentation target/runner: {attributes}')


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    junit = sub.add_parser('junit')
    junit.add_argument('--results', type=Path, required=True)
    junit.add_argument('--class-name', required=True)
    junit.add_argument('--expected-count', type=int, required=True)
    junit.add_argument('--output', type=Path, required=True)
    manifest = sub.add_parser('manifest')
    manifest.add_argument('--file', type=Path, required=True)
    manifest.add_argument('--package', required=True)
    manifest.add_argument('--runner', required=True)
    args = parser.parse_args()
    try:
        if args.command == 'manifest':
            require_probe_manifest(args.file.read_text(), args.package, args.runner)
        else:
            try:
                summary = junit_summary(args.results, args.class_name, args.expected_count)
            except (OSError, ET.ParseError) as error:
                summary = {'error': str(error), 'class': args.class_name,
                           'expected': args.expected_count, 'tests': 0,
                           'failures': None, 'errors': None, 'skipped': None,
                           'unique_tests': 0,
                           'report_files': len(list(args.results.rglob('TEST-*.xml')))}
                args.output.parent.mkdir(parents=True, exist_ok=True)
                args.output.write_text(json.dumps(summary, indent=2) + '\n')
                raise ValueError('Cannot read instrumentation report') from error
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(summary, indent=2) + '\n')
            print(json.dumps(summary, sort_keys=True))
            require_junit(summary)
    except ValueError as error:
        raise SystemExit(str(error)) from error


if __name__ == '__main__':
    main()
