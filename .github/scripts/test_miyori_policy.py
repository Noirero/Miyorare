#!/usr/bin/env python3
"""Static safety guard for the Miyori automation boundary."""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / ".github" / "workflows"
SCRIPTS = ROOT / ".github" / "scripts"

FORBIDDEN_PATTERNS = {
    r"(?mi)^\s*contents:\s*write\s*$": "Contents write permission",
    r"(?mi)^\s*actions:\s*write\s*$": "Actions write permission",
    r"(?mi)^\s*administration:\s*": "Administration permission",
    r"(?mi)^\s*permission-contents:\s*write\s*$": "GitHub App Contents write permission",
    r"(?mi)^\s*permission-actions:\s*write\s*$": "GitHub App Actions write permission",
    r"(?mi)^\s*permission-administration:\s*": "GitHub App Administration permission",
    r"(?i)\bgit\s+push\b": "git push",
    r"(?i)/merges(?:[\"'\s/]|$)": "repository merge endpoint",
    r"(?i)/pulls/[^\"'\s]+/merge(?:[\"'\s]|$)": "pull request merge endpoint",
    r"(?i)/releases(?:[\"'\s/]|$)": "release mutation endpoint",
    r"(?i)workflow_dispatch.+rerun": "arbitrary workflow rerun",
}

REQUIRED_STATUS_GATES = {
    "PR Compile & Unit Check",
    "Miyorare Identity Guard",
    "Beta to Main Release Gate",
    "Promotion to Main Gate",
}

REQUIRED_STATUS_LABELS = {
    "ci:owner-request",
    "ci:user-issue",
    "ci:theme",
    "ci:runtime-required",
    "ci:runtime-full",
    "non-theme",
    "skip-device-test",
}

ALLOWED_COMMANDS = {
    "/miyori help",
    "/miyori status",
    "/miyori checks",
    "/miyori explain",
    "/miyori labels",
    "/miyori readiness",
    "/miyori head",
    "/miyori require-runtime",
}

READ_ONLY_COMMANDS = {
    "checks",
    "explain",
    "labels",
    "readiness",
    "head",
}


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def main() -> int:
    workflow_files = sorted(WORKFLOWS.glob("miyori-*.yml"))
    if not workflow_files:
        fail("No Miyori workflows found.")

    combined = ""
    for path in workflow_files:
        text = path.read_text(encoding="utf-8")
        combined += f"\n# {path.name}\n{text}"
        for pattern, label in FORBIDDEN_PATTERNS.items():
            if re.search(pattern, text):
                fail(f"{label} is forbidden in {path.relative_to(ROOT)}")

    status = (WORKFLOWS / "miyori-pr-status.yml").read_text(encoding="utf-8")
    missing_gates = sorted(
        REQUIRED_STATUS_GATES
        - {gate for gate in REQUIRED_STATUS_GATES if gate in status}
    )
    if missing_gates:
        fail(f"Status workflow is missing tracked gates: {missing_gates}")

    missing_labels = sorted(
        REQUIRED_STATUS_LABELS
        - {label for label in REQUIRED_STATUS_LABELS if label in status}
    )
    if missing_labels:
        fail(f"Status workflow is missing tracked CI labels: {missing_labels}")

    if "pull_request_target:" not in status:
        fail("Status workflow must refresh safely on PR/label metadata changes.")
    if "ref: ${{ github.event.repository.default_branch }}" not in status:
        fail("PR-target status refresh must checkout trusted default-branch code.")
    if "ref: ${{ github.event.pull_request.head.sha }}" in status:
        fail("Status workflow must never checkout untrusted PR head code.")

    command = (WORKFLOWS / "miyori-command.yml").read_text(encoding="utf-8")
    for item in ALLOWED_COMMANDS:
        if item not in command:
            fail(f"Owner command allowlist is missing {item}")

    for forbidden in (
        "/miyori merge",
        "/miyori release",
        "/miyori push",
        "/miyori rerun",
        "skip-device-test",
        "ci:user-issue",
        "ci:owner-request",
    ):
        if forbidden in command:
            fail(f"Unsafe or scope-expanding command/action present: {forbidden}")

    if '{"labels":["ci:runtime-required"]}' not in command:
        fail("The only safe mutation must remain ci:runtime-required.")
    if "github.actor == github.repository_owner" not in command:
        fail("Owner-only actor guard is missing for Miyori commands.")
    if (
        "github.event_name == 'workflow_dispatch' ||\n      (startsWith"
        in command
    ):
        fail("workflow_dispatch must not bypass the owner actor guard.")

    report = (SCRIPTS / "miyori_report.py").read_text(encoding="utf-8")
    for item in READ_ONLY_COMMANDS:
        if f'"{item}"' not in report:
            fail(f"Read-only report implementation is missing {item}")

    diagnostics = (WORKFLOWS / "miyori-diagnostics.yml").read_text(encoding="utf-8")
    if "workflow_dispatch:" not in diagnostics or "issue_comment:" in diagnostics:
        fail("Diagnostics must remain manual-only.")

    if (
        "permission-issues: write" not in combined
        or "permission-pull-requests: write" not in combined
    ):
        fail("Expected narrow GitHub App comment permissions are missing.")

    print("Miyori automation contract: PASS")
    print(f"Checked {len(workflow_files)} workflow files.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
