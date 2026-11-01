#!/usr/bin/env python3
"""Read-only owner-facing summary of exact-head targeted diagnostic evidence.

Never treats a non-gating diagnostic as merge/release approval or a required CI gate.
No arbitrary PR code is checked out or executed by this reporter.
"""
from __future__ import annotations

import argparse
import io
import json
import os
import re
import subprocess
import sys
import zipfile
from typing import Any

import miyori_status as ms

DIAGNOSTIC_NAME = "Manual Targeted CI Diagnostics"
TITLE_RE = re.compile(r"^Diagnostic PR #([1-9][0-9]*) @ ([0-9a-fA-F]{40})$")
ARTIFACT_NAME = "targeted-diagnostic-evidence"
MAX_ARCHIVE_BYTES = 16 * 1024 * 1024


def matching_run(runs: list[dict[str, Any]], number: int, sha: str) -> dict[str, Any] | None:
    for run in sorted(runs, key=lambda x: (x.get("created_at") or "", x.get("id") or 0), reverse=True):
        if run.get("name") != DIAGNOSTIC_NAME or run.get("event") != "workflow_dispatch":
            continue
        if run.get("head_branch") != "main" or not str(run.get("path") or "").startswith(
            ".github/workflows/manual-targeted-ci-diagnostics.yml@"
        ):
            continue
        match = TITLE_RE.fullmatch(str(run.get("display_title") or ""))
        if match and int(match[1]) == number and match[2].lower() == sha.lower():
            return run
    return None


def fetch_runs(client: ms.GitHubClient) -> list[dict[str, Any]]:
    # Bound pagination; missing older evidence must be reported as unknown, never assumed passing.
    runs: list[dict[str, Any]] = []
    for page in range(1, 6):
        payload = client.get(
            f"/repos/{client.repository}/actions/runs?event=workflow_dispatch&per_page=100&page={page}"
        )
        batch = payload.get("workflow_runs", [])
        runs.extend(batch)
        if len(batch) < 100:
            break
    return runs


def read_evidence(client: ms.GitHubClient, run_id: int, token: str) -> dict[str, Any] | None:
    artifacts = client.artifacts_for_run(run_id)
    candidates = [x for x in artifacts if x.get("name") == ARTIFACT_NAME and not x.get("expired")]
    if not candidates:
        return None
    artifact = candidates[0]
    if int(artifact.get("size_in_bytes") or 0) > MAX_ARCHIVE_BYTES:
        return None
    artifact_id = int(artifact["id"])

    # gh CLI handles GitHub's signed artifact redirect; do not forward authorization
    # headers or execute anything retrieved from the archive.
    env = {**os.environ, "GH_TOKEN": token}
    process = subprocess.Popen(
        ["gh", "api", f"repos/{client.repository}/actions/artifacts/{artifact_id}/zip"],
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, env=env,
    )
    data = bytearray()
    try:
        assert process.stdout is not None
        for piece in iter(lambda: process.stdout.read(64 * 1024), b""):
            data.extend(piece)
            if len(data) > MAX_ARCHIVE_BYTES:
                process.kill()
                process.wait()
                return None
        if process.wait(timeout=20) != 0:
            return None
    finally:
        if process.poll() is None:
            process.kill()
            process.wait()

    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            results = [
                name for name in archive.namelist()
                if name == "results.json" or name.endswith("/results.json")
            ]
            if len(results) != 1 or archive.getinfo(results[0]).file_size > 256 * 1024:
                return None
            parsed = json.loads(archive.read(results[0]))
            return parsed if isinstance(parsed, dict) else None
    except (ValueError, KeyError, zipfile.BadZipFile):
        return None


def render_report(number: int, sha: str, run: dict[str, Any] | None,
                  evidence: dict[str, Any] | None) -> str:
    lines = [
        f"🌸 **Targeted diagnostics for PR #{number}**", "",
        f"- Current PR HEAD: `{sha}`",
        "- Scope: read-only diagnostic; **not** a mandatory CI gate or merge approval.",
    ]
    if run is None:
        lines.append("- No manual diagnostic run matching this exact SHA was found in the recent run history.")
        lines.append("- The workflow may not yet be activated on the default branch.")
        return "\n".join(lines)

    url = str(run.get("html_url") or "")
    lines.extend([
        f"- Workflow: {url}" if url.startswith("https://github.com/") else "- Workflow: link unavailable",
        f"- Run state: {run.get('status', 'unknown')} / {run.get('conclusion') or 'pending'}",
    ])
    if (evidence is None or str(evidence.get("sha", "")).lower() != sha.lower()
            or evidence.get("pr_number") != number):
        lines.append("- Per-test evidence unavailable or could not be verified for this PR HEAD.")
        return "\n".join(lines)

    items = evidence.get("runs")
    if not isinstance(items, list) or not 1 <= len(items) <= 15:
        lines.append("- Diagnostic evidence has an invalid invocation list.")
        return "\n".join(lines)
    valid = all(
        isinstance(x, dict) and type(x.get("exit_code")) is int and
        isinstance(x.get("targets"), list) and
        type(x.get("reported_tests")) is int and
        x.get("reported_tests") >= 0 for x in items
    )
    if not valid:
        lines.append("- Diagnostic evidence contains an invalid result entry.")
        return "\n".join(lines)

    passed = sum(x["exit_code"] == 0 and x["reported_tests"] > 0 for x in items)
    failed = len(items) - passed
    lines.extend([
        f"- Platform: **{evidence.get('platform', 'unknown')}**; mode: **{evidence.get('mode', 'unknown')}**",
        f"- Recorded invocations: **{len(items)}**; passed: **{passed}**; failed: **{failed}**.",
    ])
    if passed > 0 and failed > 0:
        lines.append("- Interpretation: **inconsistent results** across these diagnostic invocations; root cause remains unknown.")
    elif failed > 0:
        lines.append("- Interpretation: failures occurred in all reported invocations; further diagnosis is required.")
    else:
        lines.append("- Interpretation: no failure observed in this limited diagnostic sample.")
    for item in items[:15]:
        safe_targets = [
            str(t) for t in item.get("targets", [])
            if isinstance(t, str) and re.fullmatch(r"[A-Za-z0-9_.#]+", t)
        ]
        label = ", ".join(safe_targets[:8]) or "unknown target"
        outcome = "PASS" if item["exit_code"] == 0 and item["reported_tests"] > 0 else "FAIL"
        lines.append(f"- Attempt {item.get('index', '?')}: **{outcome}** — \`{label}\`")
    lines.append("- Review the workflow artifact for JUnit XML, test logs, and Android evidence.")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pr-number", type=int, required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    if args.pr_number <= 0:
        parser.error("PR number must be positive")
    repository = os.environ.get("GITHUB_REPOSITORY", "")
    token = os.environ.get("MIYORI_READ_TOKEN", "")
    if not repository or not token:
        parser.error("GITHUB_REPOSITORY and MIYORI_READ_TOKEN are required")
    client = ms.GitHubClient(
        os.environ.get("GITHUB_API_URL", "https://api.github.com"), repository, token
    )
    pr = client.get(f"/repos/{repository}/pulls/{args.pr_number}")
    if pr.get("state") != "open":
        raise SystemExit("Target PR is not open")
    sha = pr["head"]["sha"]
    runs = fetch_runs(client)
    run = matching_run(runs, args.pr_number, sha)
    evidence = None
    if run is not None:
        try:
            evidence = read_evidence(client, int(run["id"]), token)
        except (OSError, subprocess.SubprocessError, ValueError):
            evidence = None
    with open(args.output, "w", encoding="utf-8") as output:
        output.write(render_report(args.pr_number, sha, run, evidence) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
