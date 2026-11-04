#!/usr/bin/env python3
"""Fail-closed reuse of successful PR compile/JVM evidence for the exact build inputs.

This is only an optimization. Any missing, ambiguous, or inaccessible proof
results in a normal compile. Android runtime is never reused by this helper.
"""
from __future__ import annotations

import json
import os
import sys
import urllib.parse
import urllib.request
from typing import Any, Callable

COMPILE_JOB = "Compile, resources & unit tests"


def eligible_run(
    run: dict[str, Any], *,
    current_id: int,
    workflow_id: int,
    pr_number: int,
    head_sha: str,
    head_ref: str,
    base_sha: str,
    base_ref: str,
) -> bool:
    if (
        int(run.get("id") or 0) == current_id
        or int(run.get("workflow_id") or 0) != workflow_id
        or run.get("event") != "pull_request"
        or run.get("status") != "completed"
        or run.get("head_sha") != head_sha
        or run.get("head_branch") != head_ref
    ):
        return False
    return any(
        pr.get("number") == pr_number
        and pr.get("head", {}).get("sha") == head_sha
        and pr.get("head", {}).get("ref") == head_ref
        and pr.get("base", {}).get("sha") == base_sha
        and pr.get("base", {}).get("ref") == base_ref
        for pr in (run.get("pull_requests") or [])
    )


def find_proof(
    runs: list[dict[str, Any]],
    jobs_for_run: Callable[[int], list[dict[str, Any]]],
    *,
    current_id: int,
    workflow_id: int,
    pr_number: int,
    head_sha: str,
    head_ref: str,
    base_sha: str,
    base_ref: str,
) -> int | None:
    # The API returns the newest runs first. Never use another PR's check.
    for run in sorted(runs, key=lambda item: int(item.get("id") or 0), reverse=True):
        if not eligible_run(
            run,
            current_id=current_id,
            workflow_id=workflow_id,
            pr_number=pr_number,
            head_sha=head_sha,
            head_ref=head_ref,
            base_sha=base_sha,
            base_ref=base_ref,
        ):
            continue
        jobs = jobs_for_run(int(run["id"]))
        if any(
            job.get("name") == COMPILE_JOB
            and job.get("status") == "completed"
            and job.get("conclusion") == "success"
            for job in jobs
        ):
            return int(run["id"])
    return None


def main() -> int:
    repo = os.environ["GITHUB_REPOSITORY"]
    token = os.environ["GH_TOKEN"]
    current_id = int(os.environ["GITHUB_RUN_ID"])
    pr_number = int(os.environ["PR_NUMBER"])
    head_sha = os.environ["HEAD_SHA"]
    head_ref = os.environ["HEAD_REF"]
    base_sha = os.environ["BASE_SHA"]
    base_ref = os.environ["BASE_REF"]
    api_url = os.environ.get("GITHUB_API_URL", "https://api.github.com").rstrip("/")

    def get(path: str) -> dict[str, Any]:
        request = urllib.request.Request(
            api_url + path,
            headers={
                "Authorization": f"Bearer {token}",
                "Accept": "application/vnd.github+json",
                "X-GitHub-Api-Version": "2022-11-28",
            },
        )
        with urllib.request.urlopen(request, timeout=15) as response:
            return json.load(response)

    proof = None
    try:
        current = get(f"/repos/{repo}/actions/runs/{current_id}")
        query = urllib.parse.urlencode(
            {"head_sha": head_sha, "event": "pull_request", "per_page": 100}
        )
        runs = get(f"/repos/{repo}/actions/runs?{query}")["workflow_runs"]

        def jobs_for_run(run_id: int) -> list[dict[str, Any]]:
            return get(
                f"/repos/{repo}/actions/runs/{run_id}/jobs?filter=latest&per_page=100"
            )["jobs"]

        proof = find_proof(
            runs, jobs_for_run,
            current_id=current_id,
            workflow_id=int(current["workflow_id"]),
            pr_number=pr_number,
            head_sha=head_sha,
            head_ref=head_ref,
            base_sha=base_sha,
            base_ref=base_ref,
        )
    except (OSError, ValueError, KeyError, TypeError) as exc:
        print(
            f"::warning::Exact-head compile reuse unavailable ({type(exc).__name__}); "
            "running full compile/JVM validation."
        )

    reused = proof is not None
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        print(f"reuse={str(reused).lower()}", file=output)
        print(f"source_run_id={proof or ''}", file=output)
    if reused:
        print(
            f"Reusing successful exact-head compile/JVM job from run {proof}; "
            "current Android requirements will still execute."
        )
    else:
        print("No complete compatible proof; compile/resources/JVM will run.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
