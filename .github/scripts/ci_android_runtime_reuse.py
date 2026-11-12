#!/usr/bin/env python3
"""Fail-closed reuse of an ORIGINAL successful, exact-scope Android 15 PR run.

The current runtime plan is computed by the existing selector BEFORE this runs.
Any incomplete GitHub evidence (including the owner APK, when required) causes
normal Android execution; this helper does not turn unavailable reuse into CI failure.
"""
from __future__ import annotations

import hashlib
import io
import json
import os
import re
from pathlib import Path
import shutil
import sys
import tempfile
import urllib.parse
import urllib.request
import zipfile
from typing import Any, Callable

SCHEMA = 1
ANDROID_JOB = "Android runtime validation — Android 15"
REAL_STEP = "Run selected Android 15 runtime validation"
HISTORY_STEP = "Run mandatory History benchmark on fresh Android 15 emulator"
HISTORY_CLASS = "org.koitharu.kotatsu.history.ui.HistoryScrollBenchmarkTest"
PROOF_ARTIFACT = "android-runtime-scope-proof"
PROOF_FILE = "android-runtime-scope-proof.json"
APK_ARTIFACT = "on-device-cover-debug-apk"
MACHINERY = frozenset({
    ".github/workflows/pr-compile-unit-check.yml",
    ".github/scripts/select-pr-runtime-tests.sh",
    ".github/scripts/run-selected-android-runtime.sh",
    ".github/scripts/run-history-scroll-benchmark.sh",
    ".github/scripts/ci_android_runtime_reuse.py",
    ".github/scripts/test_ci_android_runtime_reuse.py",
})
EXECUTION_INPUTS = (
    ".github/workflows/pr-compile-unit-check.yml",
    ".github/scripts/select-pr-runtime-tests.sh",
    ".github/scripts/run-selected-android-runtime.sh",
    ".github/scripts/run-history-scroll-benchmark.sh",
    ".github/scripts/run-kotatsu-runtime-isolation-test.sh",
    ".github/scripts/check-migration-process-persistence.sh",
    ".github/scripts/collect-android-runtime-diagnostics.sh",
    ".github/scripts/collect-on-device-cover-evidence.sh",
)
POLICY_LABELS = (
    "ci:owner-request", "ci:runtime-required", "ci:runtime-full",
    "ci:user-issue", "ci:theme", "skip-device-test",
)


def boolean(value: str) -> bool:
    if value not in ("true", "false"):
        raise ValueError("expected literal true or false")
    return value == "true"


def canonical(value: dict[str, Any]) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("ascii")


def digest(value: dict[str, Any]) -> str:
    return hashlib.sha256(canonical(value)).hexdigest()


def build_scope(env: dict[str, str], file_bytes: Callable[[str], bytes]) -> dict[str, Any]:
    mode = env["RUNTIME_MODE"]
    if mode not in ("targeted", "full"):
        raise ValueError("invalid runtime mode")
    full = boolean(env["RUNTIME_FULL"])
    if (mode == "full") != full:
        raise ValueError("runtime mode and classifier disagree")
    classes_raw = env["RUNTIME_CLASSES"]
    classes = classes_raw.split(",") if classes_raw else []
    if any(not item or item != item.strip() for item in classes) or len(set(classes)) != len(classes):
        raise ValueError("invalid runtime class list")
    isolation = boolean(env["RUN_RUNTIME_ISOLATION"])
    migration = boolean(env["RUN_MIGRATION_PERSISTENCE"])
    if not classes and not isolation and not migration:
        raise ValueError("empty Android test plan")
    labels = {label: boolean(env["LABEL_" + label.upper().replace("-", "_").replace(":", "_")])
              for label in POLICY_LABELS}
    base_sha, head_sha = env["BASE_SHA"], env["HEAD_SHA"]
    if any(len(s) != 40 or any(ch not in "0123456789abcdef" for ch in s)
           for s in (base_sha, head_sha)):
        raise ValueError("invalid commit SHA")
    workflow_bytes = file_bytes(".github/workflows/pr-compile-unit-check.yml")
    runner_shas = re.findall(
        rb"(?m)^\s+uses:\s+reactivecircus/android-emulator-runner@([0-9a-f]{40})\s*$",
        workflow_bytes,
    )
    if len(runner_shas) != 2 or len(set(runner_shas)) != 1:
        raise ValueError("Both Android emulator sessions must use one identical pinned SHA")
    runner_action = "reactivecircus/android-emulator-runner@" + runner_shas[0].decode("ascii")
    inputs = {path: hashlib.sha256(file_bytes(path)).hexdigest() for path in EXECUTION_INPUTS}
    return {
        "schema_version": SCHEMA,
        "repository": env["GITHUB_REPOSITORY"],
        "pr_number": int(env["PR_NUMBER"]),
        "base_ref": env["BASE_REF"], "base_sha": base_sha,
        "head_ref": env["HEAD_REF"], "head_sha": head_sha,
        "workflow_id": int(env["WORKFLOW_ID"]),
        "workflow_path": ".github/workflows/pr-compile-unit-check.yml",
        "runtime": {"run_runtime": True, "full": full, "mode": mode,
                    "classes": classes, "run_runtime_isolation": isolation,
                    "run_migration_persistence": migration},
        "policy": {"draft": boolean(env["DRAFT"]), "labels": labels},
        "execution": {"runner": "ubuntu-latest", "android_api": 35,
                      "target": "google_apis", "arch": "x86_64", "profile": "pixel_2",
                      "emulator_action": runner_action,
                      "input_sha256": inputs},
    }


def eligible_run(run: dict[str, Any], *, current_id: int, workflow_id: int,
                 scope: dict[str, Any]) -> bool:
    if (int(run.get("id") or 0) == current_id
            or int(run.get("workflow_id") or 0) != workflow_id
            or run.get("event") != "pull_request"
            or run.get("status") != "completed"
            or run.get("conclusion") != "success"
            or int(run.get("run_attempt") or 0) != 1
            or run.get("head_sha") != scope["head_sha"]
            or run.get("head_branch") != scope["head_ref"]):
        return False
    return any(
        isinstance(pr, dict)
        and pr.get("number") == scope["pr_number"]
        and isinstance(pr.get("head"), dict)
        and isinstance(pr.get("base"), dict)
        and pr["head"].get("sha") == scope["head_sha"]
        and pr["head"].get("ref") == scope["head_ref"]
        and pr["base"].get("sha") == scope["base_sha"]
        and pr["base"].get("ref") == scope["base_ref"]
        for pr in (run.get("pull_requests") or [])
    )


def real_android_job(jobs: list[dict[str, Any]], *, history_required: bool = False) -> bool:
    for job in jobs:
        if job.get("name") != ANDROID_JOB or job.get("status") != "completed" or job.get("conclusion") != "success":
            continue
        steps = job.get("steps") or []
        def passed(name: str) -> bool:
            return any(s.get("name") == name and s.get("status") == "completed"
                       and s.get("conclusion") == "success" for s in steps)
        if passed(REAL_STEP) and (not history_required or passed(HISTORY_STEP)):
            return True
    return False


def artifact_id(artifacts: dict[str, Any], name: str) -> int | None:
    items = artifacts.get("artifacts")
    if not isinstance(items, list) or artifacts.get("total_count") != len(items):
        return None  # Do not trust incomplete pagination.
    candidates = [a for a in items if a.get("name") == name and not a.get("expired")
                  and a.get("size_in_bytes", 0) > 0]
    if len(candidates) != 1:
        return None
    return int(candidates[0]["id"])


def read_proof_zip(data: bytes) -> dict[str, Any]:
    if len(data) > 64 * 1024:
        raise ValueError("oversize proof archive")
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        files = [info for info in archive.infolist() if not info.is_dir()]
        if len(files) != 1 or files[0].filename != PROOF_FILE or files[0].file_size > 16 * 1024:
            raise ValueError("invalid proof archive contents")
        value = json.loads(archive.read(files[0]))
    if not isinstance(value, dict):
        raise ValueError("invalid proof object")
    return value


def matching_proof(value: dict[str, Any], scope: dict[str, Any], run_id: int) -> bool:
    return (set(value) == {"schema_version", "scope", "fingerprint", "source_run_id"}
            and value["schema_version"] == SCHEMA
            and value["scope"] == scope
            and value["fingerprint"] == digest(scope)
            and value["source_run_id"] == run_id)


def extract_apk(data: bytes, destination: Path) -> None:
    # Only called after matching scope, original real Android SUCCESS and artifact identity.
    # Avoid zip-slip, ambiguous artifacts, oversized/untrusted archives.
    if len(data) > 250 * 1024 * 1024:
        raise ValueError("oversize APK archive")
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        files = [info for info in archive.infolist() if not info.is_dir()]
        if len(files) != 1 or not files[0].filename.endswith(".apk"):
            raise ValueError("APK artifact must contain exactly one APK")
        item = files[0]
        if (item.file_size < 1024 * 1024 or item.file_size > 230 * 1024 * 1024
                or "/" in item.filename or "\\" in item.filename
                or item.filename.startswith(".") or item.filename in ("", ".", "..")):
            raise ValueError("unsafe APK artifact")
        destination.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(dir=destination, prefix=".reused-", delete=False) as temp:
            pending = Path(temp.name)
            try:
                with archive.open(item) as source:
                    shutil.copyfileobj(source, temp)
                temp.flush()
                if pending.stat().st_size != item.file_size:
                    raise ValueError("incomplete APK")
                with pending.open("rb") as apk:
                    signature = apk.read(2)
                if signature != b"PK":
                    raise ValueError("invalid APK header")
                pending.replace(destination / item.filename)
            finally:
                pending.unlink(missing_ok=True)


def machinery_changed(paths: set[str]) -> bool:
    return not paths or bool(paths & MACHINERY)


def find_proof(runs: list[dict[str, Any]], jobs_for_run: Callable[[int], list[dict[str, Any]]],
               artifacts_for_run: Callable[[int], dict[str, Any]],
               zip_for_artifact: Callable[[int], bytes], *, current_id: int,
               workflow_id: int, scope: dict[str, Any],
               require_owner_apk: bool, apk_destination: Path) -> int | None:
    for run in sorted(runs, key=lambda item: int(item.get("id") or 0), reverse=True):
        if not eligible_run(run, current_id=current_id, workflow_id=workflow_id, scope=scope):
            continue
        run_id = int(run["id"])
        history_required = scope["runtime"]["full"] and HISTORY_CLASS in scope["runtime"]["classes"]
        if not real_android_job(jobs_for_run(run_id), history_required=history_required):
            continue  # Both mandatory real emulator sessions must succeed; no reuse chains.
        artifacts = artifacts_for_run(run_id)
        proof_id = artifact_id(artifacts, PROOF_ARTIFACT)
        if proof_id is None:
            continue
        if not matching_proof(read_proof_zip(zip_for_artifact(proof_id)), scope, run_id):
            continue
        if require_owner_apk:
            apk_id = artifact_id(artifacts, APK_ARTIFACT)
            if apk_id is None:
                continue
            extract_apk(zip_for_artifact(apk_id), apk_destination)
        return run_id
    return None


class SafeArtifactRedirect(urllib.request.HTTPRedirectHandler):
    """Never forward the GitHub API bearer token to redirected artifact storage."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if urllib.parse.urlsplit(newurl).scheme != "https":
            raise ValueError("refusing non-HTTPS artifact redirect")
        redirected = super().redirect_request(req, fp, code, msg, headers, newurl)
        if redirected is not None and urllib.parse.urlsplit(newurl).netloc != urllib.parse.urlsplit(req.full_url).netloc:
            redirected.remove_header("Authorization")
        return redirected


def main() -> int:
    output = Path(os.environ["GITHUB_OUTPUT"])
    reuse = False
    source_id: int | None = None
    fingerprint = ""
    try:
        env = dict(os.environ)
        repo = env["GITHUB_REPOSITORY"]
        token = env["GH_TOKEN"]
        current_id = int(env["GITHUB_RUN_ID"])
        api_url = env.get("GITHUB_API_URL", "https://api.github.com").rstrip("/")

        opener = urllib.request.build_opener(SafeArtifactRedirect())

        def request(path: str) -> bytes:
            req = urllib.request.Request(api_url + path, headers={
                "Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json",
                "X-GitHub-Api-Version": "2022-11-28"})
            with opener.open(req, timeout=20) as response:
                return response.read()

        def get(path: str) -> dict[str, Any]:
            result = json.loads(request(path))
            if not isinstance(result, dict):
                raise ValueError("invalid API response")
            return result

        current = get(f"/repos/{repo}/actions/runs/{current_id}")
        if (current.get("head_sha") != env["HEAD_SHA"]
                or current.get("event") != "pull_request"):
            raise ValueError("current run identity mismatch")
        env["WORKFLOW_ID"] = str(int(current["workflow_id"]))
        scope = build_scope(env, lambda path: Path(path).read_bytes())
        fingerprint = digest(scope)
        # Persist proof metadata before attempting lookup. Upload only after real
        # Android success; an inaccessible API leaves no reusable source artifact.
        proof = {"schema_version": SCHEMA, "scope": scope,
                 "fingerprint": fingerprint, "source_run_id": current_id}
        Path(env["RUNNER_TEMP"]).joinpath(PROOF_FILE).write_bytes(canonical(proof) + b"\n")

        changed = set(Path(env["CHANGED_PATHS_FILE"]).read_text(encoding="utf-8").splitlines())
        if machinery_changed(changed):
            print("::notice::Runtime decision machinery changed or diff unavailable; real Android required.")
        else:
            query = urllib.parse.urlencode({"head_sha": scope["head_sha"],
                                           "event": "pull_request", "per_page": 100})
            runs = get(f"/repos/{repo}/actions/runs?{query}")["workflow_runs"]

            def jobs_for_run(run_id: int) -> list[dict[str, Any]]:
                return get(f"/repos/{repo}/actions/runs/{run_id}/jobs?filter=latest&per_page=100")["jobs"]

            def artifacts_for_run(run_id: int) -> dict[str, Any]:
                return get(f"/repos/{repo}/actions/runs/{run_id}/artifacts?per_page=100")

            def zip_for_artifact(artifact: int) -> bytes:
                return request(f"/repos/{repo}/actions/artifacts/{artifact}/zip")

            source_id = find_proof(
                runs, jobs_for_run, artifacts_for_run, zip_for_artifact,
                current_id=current_id, workflow_id=scope["workflow_id"], scope=scope,
                require_owner_apk=not scope["policy"]["labels"]["skip-device-test"],
                apk_destination=Path("app/build/outputs/apk/debug"),
            )
            reuse = source_id is not None
    except (OSError, ValueError, KeyError, TypeError, IndexError,
            json.JSONDecodeError, zipfile.BadZipFile) as exc:
        print(f"::warning::Android evidence reuse unavailable ({type(exc).__name__}); running real Android 15.")
    with output.open("a", encoding="utf-8") as handle:
        print(f"reuse={str(reuse).lower()}", file=handle)
        print(f"source_run_id={source_id or ''}", file=handle)
        print(f"fingerprint={fingerprint}", file=handle)
    if reuse:
        print(f"REUSED Android execution from original run {source_id}, fingerprint {fingerprint}.")
    else:
        print(f"No exact-scope original Android proof; running real Android 15 (fingerprint {fingerprint or 'unavailable'}).")
    return 0


if __name__ == "__main__":
    sys.exit(main())