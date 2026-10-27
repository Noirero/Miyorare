#!/usr/bin/env python3
"""Render the persistent Miyori PR status card from GitHub Actions metadata.

This module intentionally has no mutation capability. Workflows use a separate
GitHub App token to publish the rendered body.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Any

CARD_MARKER = "<!-- miyori-status-card:v1 -->"
LEGACY_MARKER = "<!-- miyori-status:identity-guard -->"

GATES = (
    "Miyorare Identity Guard",
    "Beta to Main Release Gate",
    "Promotion to Main Gate",
)

STATUS_LABELS = {
    ("completed", "success"): "✅ passed",
    ("completed", "failure"): "❌ failed",
    ("completed", "cancelled"): "⚪ cancelled",
    ("completed", "skipped"): "⏭️ skipped",
    ("completed", "timed_out"): "⏱️ timed out",
    ("completed", "action_required"): "⚠️ action required",
    ("completed", "neutral"): "➖ neutral",
    ("completed", "stale"): "⚠️ stale",
    ("completed", "startup_failure"): "❌ startup failure",
    ("completed", None): "❔ completed",
    ("in_progress", None): "⏳ running",
    ("queued", None): "🕒 queued",
    ("requested", None): "🕒 requested",
    ("waiting", None): "🕒 waiting",
    ("pending", None): "🕒 pending",
}

FAILURE_CONCLUSIONS = {
    "failure",
    "timed_out",
    "action_required",
    "startup_failure",
}


@dataclass(frozen=True)
class GateState:
    name: str
    applicable: bool
    run: dict[str, Any] | None

    @property
    def display(self) -> str:
        if not self.applicable:
            return "➖ not applicable"
        if not self.run:
            return "⚪ not observed"
        status = self.run.get("status")
        conclusion = self.run.get("conclusion")
        return STATUS_LABELS.get(
            (status, conclusion),
            STATUS_LABELS.get((status, None), f"❔ {conclusion or status or 'unknown'}"),
        )

    @property
    def run_url(self) -> str | None:
        return self.run.get("html_url") if self.run else None


class GitHubClient:
    def __init__(self, api_url: str, repository: str, token: str):
        self.api_url = api_url.rstrip("/")
        self.repository = repository
        self.token = token

    def get(self, path: str) -> Any:
        url = f"{self.api_url}{path}"
        req = urllib.request.Request(
            url,
            headers={
                "Accept": "application/vnd.github+json",
                "Authorization": f"Bearer {self.token}",
                "X-GitHub-Api-Version": "2022-11-28",
                "User-Agent": "miyorare-miyori-status",
            },
        )
        with urllib.request.urlopen(req, timeout=30) as response:
            return json.load(response)

    def pull(self, number: int) -> dict[str, Any]:
        return self.get(f"/repos/{self.repository}/pulls/{number}")

    def workflow_runs_for_head(self, sha: str) -> list[dict[str, Any]]:
        query = urllib.parse.urlencode(
            {
                "head_sha": sha,
                "event": "pull_request",
                "per_page": 100,
            }
        )
        payload = self.get(f"/repos/{self.repository}/actions/runs?{query}")
        return list(payload.get("workflow_runs", []))

    def jobs_for_run(self, run_id: int) -> list[dict[str, Any]]:
        payload = self.get(
            f"/repos/{self.repository}/actions/runs/{run_id}/jobs?per_page=100"
        )
        return list(payload.get("jobs", []))


def latest_runs_by_name(runs: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    latest: dict[str, dict[str, Any]] = {}
    for run in runs:
        name = str(run.get("name") or "")
        if name not in GATES:
            continue
        current = latest.get(name)
        current_key = (
            int(current.get("run_attempt") or 0),
            int(current.get("run_number") or 0),
            str(current.get("updated_at") or ""),
        ) if current else (-1, -1, "")
        candidate_key = (
            int(run.get("run_attempt") or 0),
            int(run.get("run_number") or 0),
            str(run.get("updated_at") or ""),
        )
        if current is None or candidate_key > current_key:
            latest[name] = run
    return latest


def gate_applicable(gate: str, base_ref: str) -> bool:
    if gate == "Miyorare Identity Guard":
        return base_ref in {"main", "beta"}
    return base_ref == "main"


def failure_details(client: GitHubClient, run: dict[str, Any]) -> list[str]:
    conclusion = run.get("conclusion")
    if conclusion not in FAILURE_CONCLUSIONS:
        return []

    details: list[str] = []
    try:
        jobs = client.jobs_for_run(int(run["id"]))
    except Exception as exc:
        return [f"failed; job details unavailable ({type(exc).__name__})"]

    for job in jobs:
        if job.get("conclusion") not in FAILURE_CONCLUSIONS:
            continue
        job_name = str(job.get("name") or "unnamed job")
        failed_steps = [
            str(step.get("name") or "unnamed step")
            for step in job.get("steps", [])
            if step.get("conclusion") in FAILURE_CONCLUSIONS
        ]
        if failed_steps:
            for step_name in failed_steps[:2]:
                details.append(f"`{job_name}` → `{step_name}`")
        else:
            details.append(f"`{job_name}`")
        if len(details) >= 4:
            break

    return details[:4] or ["failed before a failing step could be identified"]


def render_card(
    *,
    pr_number: int,
    pr: dict[str, Any],
    gate_states: list[GateState],
    client: GitHubClient,
    server_url: str,
) -> str:
    head_sha = str(pr["head"]["sha"])
    base_ref = str(pr["base"]["ref"])
    short_sha = head_sha[:12]
    labels = {
        str(item.get("name"))
        for item in pr.get("labels", [])
        if item.get("name")
    }

    lines = [
        CARD_MARKER,
        "🌸 **Miyori — PR Status**",
        "",
        f"PR: #{pr_number} · Base: `{base_ref}` · Head: `{short_sha}`",
        "",
        "| Gate | Status |",
        "| --- | --- |",
    ]

    for state in gate_states:
        display = state.display
        if state.run_url:
            display = f"{display} · [run]({state.run_url})"
        lines.append(f"| {state.name} | {display} |")

    attention: list[str] = []
    for state in gate_states:
        if not state.run or state.run.get("conclusion") not in FAILURE_CONCLUSIONS:
            continue
        details = failure_details(client, state.run)
        detail_text = "; ".join(details)
        attention.append(
            f"- **{state.name}** needs attention: {detail_text}. "
            f"[Open run]({state.run_url})"
        )

    if attention:
        lines.extend(["", "### ⚠️ Attention", *attention])

    if "ci:runtime-required" in labels:
        lines.extend(
            [
                "",
                "🛡️ `ci:runtime-required` is active; Android Runtime validation is explicitly required.",
            ]
        )

    lines.extend(
        [
            "",
            f"[Open PR]({server_url.rstrip('/')}/{client.repository}/pull/{pr_number})",
            "",
            "_Miyori owns PR status/reporting and owner-authorized safe control actions only. "
            "It does not push code, merge, publish releases, or replace core CI execution._",
        ]
    )
    return "\n".join(lines)


def build_card(
    *,
    client: GitHubClient,
    pr_number: int,
    server_url: str,
) -> tuple[str, dict[str, Any]]:
    pr = client.pull(pr_number)
    head_sha = str(pr["head"]["sha"])
    base_ref = str(pr["base"]["ref"])
    runs = client.workflow_runs_for_head(head_sha)
    latest = latest_runs_by_name(runs)
    states = [
        GateState(
            name=gate,
            applicable=gate_applicable(gate, base_ref),
            run=latest.get(gate),
        )
        for gate in GATES
    ]
    return (
        render_card(
            pr_number=pr_number,
            pr=pr,
            gate_states=states,
            client=client,
            server_url=server_url,
        ),
        pr,
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pr-number", type=int, required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    if args.pr_number <= 0:
        parser.error("--pr-number must be positive")

    api_url = os.environ.get("GITHUB_API_URL", "https://api.github.com")
    repository = os.environ.get("GITHUB_REPOSITORY", "").strip()
    server_url = os.environ.get("GITHUB_SERVER_URL", "https://github.com")
    token = os.environ.get("MIYORI_READ_TOKEN", "").strip()

    if not repository:
        raise SystemExit("GITHUB_REPOSITORY is required")
    if not token:
        raise SystemExit("MIYORI_READ_TOKEN is required")

    client = GitHubClient(api_url, repository, token)
    body, pr = build_card(
        client=client,
        pr_number=args.pr_number,
        server_url=server_url,
    )

    state = str(pr.get("state") or "")
    if state != "open":
        print(f"PR #{args.pr_number} is {state}; no status card should be published.")
        return 3

    output = os.path.abspath(args.output)
    with open(output, "w", encoding="utf-8") as fh:
        fh.write(body)
        fh.write("\n")
    print(f"Rendered Miyori status card for PR #{args.pr_number} at head {pr['head']['sha']}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
