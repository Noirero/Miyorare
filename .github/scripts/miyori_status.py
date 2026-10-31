#!/usr/bin/env python3
"""Render Miyori's persistent PR status card from trusted GitHub metadata.

The module is intentionally read-only. A workflow may use a separate GitHub App
installation token to publish the rendered body, but this code never mutates a
repository.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Any

CARD_MARKER = "<!-- miyori-status-card:v1 -->"
LEGACY_MARKER = "<!-- miyori-status:identity-guard -->"

GATES = (
    "PR Compile & Unit Check",
    "Miyorare Identity Guard",
    "Beta to Main Release Gate",
    "Promotion to Main Gate",
)

TRACKED_LABELS = (
    "ci:owner-request",
    "ci:user-issue",
    "ci:theme",
    "ci:runtime-required",
    "ci:runtime-full",
    "non-theme",
    "skip-device-test",
)

ORIGIN_LABELS = {"ci:owner-request", "ci:user-issue"}
PASSING_CONCLUSIONS = {"success", "skipped", "neutral"}
FAILURE_CONCLUSIONS = {
    "failure",
    "timed_out",
    "action_required",
    "startup_failure",
}
PROMOTION_HEADS = {"beta", "Amain2"}

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

CLOSING_ISSUE_RE = re.compile(
    r"(?im)\b(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?)\s*:?\s*#(\d+)\b"
)
ISSUE_URL_RE = re.compile(
    r"https://github\.com/[^/\s]+/[^/\s]+/issues/(\d+)\b", re.IGNORECASE
)


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
            {"head_sha": sha, "event": "pull_request", "per_page": 100}
        )
        payload = self.get(f"/repos/{self.repository}/actions/runs?{query}")
        return list(payload.get("workflow_runs", []))

    def jobs_for_run(self, run_id: int) -> list[dict[str, Any]]:
        payload = self.get(
            f"/repos/{self.repository}/actions/runs/{run_id}/jobs?per_page=100"
        )
        return list(payload.get("jobs", []))

    def artifacts_for_run(self, run_id: int) -> list[dict[str, Any]]:
        payload = self.get(
            f"/repos/{self.repository}/actions/runs/{run_id}/artifacts?per_page=100"
        )
        return list(payload.get("artifacts", []))

    def workflow_names(self) -> set[str]:
        names: set[str] = set()
        page = 1
        while True:
            payload = self.get(
                f"/repos/{self.repository}/actions/workflows?per_page=100&page={page}"
            )
            workflows = list(payload.get("workflows", []))
            names.update(
                str(item.get("name") or "")
                for item in workflows
                if item.get("name")
            )
            total_count = int(payload.get("total_count") or 0)
            if not workflows or len(workflows) < 100 or (total_count and page * 100 >= total_count):
                break
            page += 1
        return names


def latest_runs_by_name(runs: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    latest: dict[str, dict[str, Any]] = {}
    for run in runs:
        name = str(run.get("name") or "")
        if name not in GATES:
            continue
        current = latest.get(name)
        current_key = (
            (
                int(current.get("run_number") or 0),
                int(current.get("run_attempt") or 0),
                str(current.get("updated_at") or ""),
            )
            if current
            else (-1, -1, "")
        )
        candidate_key = (
            int(run.get("run_number") or 0),
            int(run.get("run_attempt") or 0),
            str(run.get("updated_at") or ""),
        )
        if current is None or candidate_key > current_key:
            latest[name] = run
    return latest


def gate_applicable(gate: str, base_ref: str) -> bool:
    if gate == "PR Compile & Unit Check":
        return base_ref == "Amain2"
    if gate == "Miyorare Identity Guard":
        return base_ref in {"main", "beta"}
    return base_ref == "main"


def is_promotion_candidate(base_ref: str, head_ref: str) -> bool:
    return base_ref == "main" and (
        head_ref in PROMOTION_HEADS or head_ref.startswith("release/main-")
    )


def extract_issue_refs(body: str) -> list[int]:
    refs = {int(value) for value in CLOSING_ISSUE_RE.findall(body or "")}
    refs.update(int(value) for value in ISSUE_URL_RE.findall(body or ""))
    return sorted(refs)


def tracked_label_set(pr: dict[str, Any]) -> set[str]:
    return {
        str(item.get("name"))
        for item in pr.get("labels", [])
        if item.get("name")
    }


def label_consistency(
    *, labels: set[str], base_ref: str, body: str
) -> tuple[list[str], list[str]]:
    errors: list[str] = []
    warnings: list[str] = []
    origins = sorted(ORIGIN_LABELS & labels)
    refs = extract_issue_refs(body)

    if len(origins) > 1:
        errors.append("`ci:owner-request` and `ci:user-issue` are both present.")
    elif not origins:
        warnings.append("No task-origin CI label is present.")

    if "ci:theme" in labels and "non-theme" in labels:
        errors.append("`ci:theme` and `non-theme` conflict.")

    if "ci:user-issue" in labels and not refs:
        warnings.append("`ci:user-issue` is present but no linked issue is visible in the PR body.")

    if "ci:owner-request" in labels and refs:
        warnings.append(
            "The PR closes/links an issue while classified as `ci:owner-request`; verify task origin."
        )

    if "skip-device-test" in labels and base_ref == "beta":
        warnings.append("`skip-device-test` has no known Miyori-specific beta behavior.")

    unknown_ci = sorted(
        label for label in labels if label.startswith("ci:") and label not in TRACKED_LABELS
    )
    if unknown_ci:
        warnings.append(
            "Unrecognized CI label(s): " + ", ".join(f"`{item}`" for item in unknown_ci)
        )

    return errors, warnings


def traceability_summary(labels: set[str], body: str) -> str:
    refs = extract_issue_refs(body)
    if refs:
        rendered = ", ".join(f"#{number}" for number in refs)
        return f"linked issue(s): {rendered}"
    if "ci:user-issue" in labels:
        return "user-issue classification; PR body has no linked issue"
    return "no linked issue declared"


def classify_failure_area(job_name: str, step_name: str) -> str:
    text = f"{job_name} {step_name}".lower()
    if any(token in text for token in ("emulator", "android 15", "instrumentation", "connecteddebug", "runtime")):
        return "Android Runtime"
    if any(token in text for token in ("compile", "resources", "manifest", "assemble", "build")):
        return "build/compile"
    if any(token in text for token in ("unit test", "jvm", "pytest", "unittest", "test")):
        return "test"
    if any(token in text for token in ("identity", "permission", "policy", "guard")):
        return "automation/policy"
    if any(token in text for token in ("artifact", "upload", "download")):
        return "artifact delivery"
    return "workflow step"


def failure_details(client: GitHubClient, run: dict[str, Any]) -> list[str]:
    conclusion = run.get("conclusion")
    if conclusion not in FAILURE_CONCLUSIONS:
        return []

    details: list[str] = []
    try:
        jobs = client.jobs_for_run(int(run["id"]))
    except Exception as exc:
        return [f"failure details unavailable ({type(exc).__name__})"]

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
                area = classify_failure_area(job_name, step_name)
                details.append(f"**{area}** · `{job_name}` → `{step_name}`")
        else:
            area = classify_failure_area(job_name, "")
            details.append(f"**{area}** · `{job_name}`")
        if len(details) >= 4:
            break

    return details[:4] or ["failed before a failing job/step could be identified"]


def technical_readiness(
    *, pr: dict[str, Any], states: list[GateState], label_errors: list[str]
) -> tuple[str, str]:
    if bool(pr.get("draft")):
        return "blocked", "draft PR"
    if label_errors:
        return "blocked", "label consistency error"

    applicable = [state for state in states if state.applicable]
    if not applicable:
        return "waiting", "no applicable gate is known for this base branch"

    for state in applicable:
        if not state.run:
            return "waiting", f"{state.name} has not been observed on the current head"
        status = str(state.run.get("status") or "")
        conclusion = state.run.get("conclusion")
        if status != "completed":
            return "waiting", f"{state.name} is {status or 'pending'}"
        if conclusion not in PASSING_CONCLUSIONS:
            return "blocked", f"{state.name} concluded {conclusion or 'unknown'}"

    return "ready", "current-head applicable gates are non-blocking"


def promotion_readiness(
    *, pr: dict[str, Any], states: list[GateState]
) -> tuple[str, str]:
    base_ref = str(pr["base"]["ref"])
    head_ref = str(pr["head"]["ref"])
    if not is_promotion_candidate(base_ref, head_ref):
        return "not-applicable", "PR is not a recognized promotion candidate"

    state = next((item for item in states if item.name == "Promotion to Main Gate"), None)
    if state is None or not state.run:
        return "waiting", "Promotion to Main Gate has not been observed"
    if state.run.get("status") != "completed":
        return "waiting", "Promotion to Main Gate is still running"
    if state.run.get("conclusion") == "success":
        return "ready", "promotion gate passed on the current head"
    return "blocked", f"promotion gate concluded {state.run.get('conclusion') or 'unknown'}"


def release_readiness(
    *,
    pr: dict[str, Any],
    technical: tuple[str, str],
    promotion: tuple[str, str],
    artifact_count: int | None,
) -> tuple[str, str]:
    base_ref = str(pr["base"]["ref"])
    head_ref = str(pr["head"]["ref"])
    if not is_promotion_candidate(base_ref, head_ref):
        return "not-applicable", "PR is not a recognized release/promotion candidate"
    if technical[0] != "ready" or promotion[0] != "ready":
        return "blocked", "technical or promotion evidence is not ready"
    if artifact_count is None:
        return "ready", "technical evidence is ready; artifact visibility was unavailable"
    return "ready", f"technical evidence is ready; {artifact_count} workflow artifact(s) observed"


def artifact_count_for_states(
    client: GitHubClient, states: list[GateState]
) -> int | None:
    total = 0
    observed = False
    try:
        for state in states:
            if not state.applicable or not state.run:
                continue
            if state.name not in {"Beta to Main Release Gate", "Promotion to Main Gate"}:
                continue
            observed = True
            total += len(client.artifacts_for_run(int(state.run["id"])))
    except Exception:
        return None
    return total if observed else 0


def automation_drift(
    client: GitHubClient, labels: set[str]
) -> list[str]:
    findings: list[str] = []
    unknown_ci = sorted(
        label for label in labels if label.startswith("ci:") and label not in TRACKED_LABELS
    )
    if unknown_ci:
        findings.append(
            "unrecognized CI label(s): " + ", ".join(f"`{item}`" for item in unknown_ci)
        )

    try:
        workflow_names = client.workflow_names()
    except Exception as exc:
        findings.append(f"workflow inventory unavailable ({type(exc).__name__})")
        return findings

    missing = sorted(set(GATES) - workflow_names)
    if missing:
        findings.append(
            "tracked workflow name(s) missing: " + ", ".join(f"`{item}`" for item in missing)
        )
    return findings


def readiness_icon(level: str) -> str:
    return {
        "ready": "✅",
        "waiting": "⏳",
        "blocked": "❌",
        "not-applicable": "➖",
    }.get(level, "❔")


def analyze_pr(
    *, client: GitHubClient, pr_number: int
) -> dict[str, Any]:
    pr = client.pull(pr_number)
    head_sha = str(pr["head"]["sha"])
    base_ref = str(pr["base"]["ref"])
    labels = tracked_label_set(pr)
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

    label_errors, label_warnings = label_consistency(
        labels=labels,
        base_ref=base_ref,
        body=str(pr.get("body") or ""),
    )
    technical = technical_readiness(
        pr=pr, states=states, label_errors=label_errors
    )
    promotion = promotion_readiness(pr=pr, states=states)
    artifacts = (
        artifact_count_for_states(client, states)
        if is_promotion_candidate(base_ref, str(pr["head"]["ref"]))
        else None
    )
    release = release_readiness(
        pr=pr,
        technical=technical,
        promotion=promotion,
        artifact_count=artifacts,
    )
    drift = automation_drift(client, labels)

    return {
        "pr": pr,
        "states": states,
        "labels": labels,
        "label_errors": label_errors,
        "label_warnings": label_warnings,
        "traceability": traceability_summary(labels, str(pr.get("body") or "")),
        "technical": technical,
        "promotion": promotion,
        "release": release,
        "artifact_count": artifacts,
        "drift": drift,
    }


def render_card(
    *,
    pr_number: int,
    analysis: dict[str, Any],
    client: GitHubClient,
    server_url: str,
) -> str:
    pr = analysis["pr"]
    states: list[GateState] = analysis["states"]
    labels: set[str] = analysis["labels"]
    head_sha = str(pr["head"]["sha"])
    base_ref = str(pr["base"]["ref"])
    head_ref = str(pr["head"]["ref"])
    short_sha = head_sha[:12]
    tracked_labels = [label for label in TRACKED_LABELS if label in labels]
    applicable_states = [state for state in states if state.applicable]

    lines = [
        CARD_MARKER,
        "🌸 **Miyori — PR Status**",
        "",
        f"PR: #{pr_number} · `{head_ref}` → `{base_ref}` · Head: `{short_sha}`",
        f"CI labels: {', '.join(f'`{label}`' for label in tracked_labels) if tracked_labels else '_none of the tracked CI labels_'}",
        f"Traceability: {analysis['traceability']}",
        "",
        "| Gate | Status |",
        "| --- | --- |",
    ]

    for state in applicable_states:
        display = state.display
        if state.run_url:
            display = f"{display} · [run]({state.run_url})"
        lines.append(f"| {state.name} | {display} |")

    technical = analysis["technical"]
    lines.extend(
        [
            "",
            f"{readiness_icon(technical[0])} **Technical readiness:** {technical[1]}.",
        ]
    )

    if is_promotion_candidate(base_ref, head_ref):
        promotion = analysis["promotion"]
        release = analysis["release"]
        lines.append(
            f"{readiness_icon(promotion[0])} **Promotion readiness:** {promotion[1]}."
        )
        lines.append(
            f"{readiness_icon(release[0])} **Release readiness:** {release[1]}."
        )

    attention: list[str] = []
    for item in analysis["label_errors"]:
        attention.append(f"- **Label conflict:** {item}")
    for item in analysis["label_warnings"]:
        attention.append(f"- **Label/traceability:** {item}")

    for state in applicable_states:
        if not state.run or state.run.get("conclusion") not in FAILURE_CONCLUSIONS:
            continue
        details = failure_details(client, state.run)
        detail_text = "; ".join(details)
        attention.append(
            f"- **{state.name}** needs attention: {detail_text}. "
            f"[Open run]({state.run_url})"
        )

    for item in analysis["drift"]:
        attention.append(f"- **Automation drift:** {item}.")

    if attention:
        lines.extend(["", "### ⚠️ Attention", *attention])

    if "ci:runtime-required" in labels:
        lines.extend(
            [
                "",
                "🛡️ `ci:runtime-required` is active; Android Runtime validation is explicitly required.",
            ]
        )

    if "skip-device-test" in labels:
        lines.extend(
            [
                "",
                "📦 `skip-device-test` is active; owner-facing device-test APK delivery may be skipped while required automated validation remains governed by core CI.",
            ]
        )

    lines.extend(
        [
            "",
            f"[Open PR]({server_url.rstrip('/')}/{client.repository}/pull/{pr_number})",
            "",
            "_Readiness is technical evidence only. Miyori does not approve, push, merge, publish releases, or replace required CI._",
        ]
    )
    return "\n".join(lines)


def build_card(
    *,
    client: GitHubClient,
    pr_number: int,
    server_url: str,
) -> tuple[str, dict[str, Any]]:
    analysis = analyze_pr(client=client, pr_number=pr_number)
    return (
        render_card(
            pr_number=pr_number,
            analysis=analysis,
            client=client,
            server_url=server_url,
        ),
        analysis["pr"],
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
    print(
        f"Rendered Miyori status card for PR #{args.pr_number} at head {pr['head']['sha']}."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
