#!/usr/bin/env python3
"""Render read-only Miyori owner command reports."""
from __future__ import annotations

import argparse
import os
import sys
from typing import Any

import miyori_status as ms

READ_ONLY_COMMANDS = {"checks", "explain", "labels", "readiness", "head"}


def gate_lines(states: list[ms.GateState]) -> list[str]:
    lines: list[str] = []
    for state in states:
        if not state.applicable:
            continue
        text = state.display
        if state.run_url:
            text += f" · {state.run_url}"
        lines.append(f"- {state.name}: {text}")
    return lines or ["- No applicable tracked gate."]


def render_report(command: str, analysis: dict[str, Any]) -> str:
    if command not in READ_ONLY_COMMANDS:
        raise ValueError(f"Unsupported read-only command: {command}")

    pr = analysis["pr"]
    number = int(pr["number"])
    head_sha = str(pr["head"]["sha"])
    head_ref = str(pr["head"]["ref"])
    base_ref = str(pr["base"]["ref"])
    states: list[ms.GateState] = analysis["states"]

    if command == "checks":
        lines = [
            f"🌸 **Checks for PR #{number}**",
            "",
            *gate_lines(states),
        ]
        if analysis["drift"]:
            lines.extend(["", "**Automation attention**"])
            lines.extend(f"- {item}" for item in analysis["drift"])
        else:
            lines.extend(["", "Automation contract: no known drift detected."])
        return "\n".join(lines)

    if command == "explain":
        lines = [
            f"🌸 **Why these checks apply to PR #{number}**",
            "",
            f"- Route: `{head_ref}` → `{base_ref}`",
            f"- Traceability: {analysis['traceability']}",
            "",
            *gate_lines(states),
        ]
        if analysis["label_errors"] or analysis["label_warnings"]:
            lines.append("")
            lines.append("**Attention**")
            lines.extend(
                f"- {item}"
                for item in analysis["label_errors"] + analysis["label_warnings"]
            )
        return "\n".join(lines)

    if command == "labels":
        tracked = [
            label for label in ms.TRACKED_LABELS if label in analysis["labels"]
        ]
        lines = [
            f"🌸 **CI labels for PR #{number}**",
            "",
            "- Active: "
            + (
                ", ".join(f"`{label}`" for label in tracked)
                if tracked
                else "_none of the tracked labels_"
            ),
            f"- Traceability: {analysis['traceability']}",
        ]
        findings = analysis["label_errors"] + analysis["label_warnings"]
        lines.extend(
            ["", "**Consistency**"]
            + ([f"- {item}" for item in findings] if findings else ["- No known conflict detected."])
        )
        return "\n".join(lines)

    if command == "readiness":
        technical = analysis["technical"]
        promotion = analysis["promotion"]
        release = analysis["release"]
        lines = [
            f"🌸 **Readiness for PR #{number}**",
            "",
            f"- {ms.readiness_icon(technical[0])} Technical: {technical[1]}",
        ]
        if ms.is_promotion_candidate(base_ref, head_ref):
            lines.append(
                f"- {ms.readiness_icon(promotion[0])} Promotion: {promotion[1]}"
            )
            lines.append(
                f"- {ms.readiness_icon(release[0])} Release: {release[1]}"
            )
        lines.extend(
            [
                "",
                "_Technical readiness is not merge or release authorization._",
            ]
        )
        return "\n".join(lines)

    if command == "head":
        observed = [
            state
            for state in states
            if state.applicable and state.run is not None
        ]
        return "\n".join(
            [
                f"🌸 **Current head for PR #{number}**",
                "",
                f"- SHA: `{head_sha}`",
                f"- Route: `{head_ref}` → `{base_ref}`",
                f"- Applicable gate evidence observed on this exact head: {len(observed)}",
                "- Miyori does not reuse a prior-head run as current-head evidence.",
            ]
        )

    raise AssertionError("unreachable")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--command", choices=sorted(READ_ONLY_COMMANDS), required=True)
    parser.add_argument("--pr-number", type=int, required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    repository = os.environ.get("GITHUB_REPOSITORY", "").strip()
    token = os.environ.get("MIYORI_READ_TOKEN", "").strip()
    api_url = os.environ.get("GITHUB_API_URL", "https://api.github.com")

    if not repository:
        raise SystemExit("GITHUB_REPOSITORY is required")
    if not token:
        raise SystemExit("MIYORI_READ_TOKEN is required")

    client = ms.GitHubClient(api_url, repository, token)
    analysis = ms.analyze_pr(client=client, pr_number=args.pr_number)
    pr = analysis["pr"]
    if str(pr.get("state") or "") != "open":
        raise SystemExit(f"PR #{args.pr_number} is not open.")

    body = render_report(args.command, analysis)
    with open(args.output, "w", encoding="utf-8") as fh:
        fh.write(body)
        fh.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
