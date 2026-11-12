#!/usr/bin/env python3
"""Pure/negative regression tests for fail-closed exact-scope Android evidence."""
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import urllib.request
import zipfile

from ci_android_runtime_reuse import (
    ANDROID_JOB, REAL_STEP, HISTORY_STEP, HISTORY_CLASS, PROOF_ARTIFACT, APK_ARTIFACT,
    PROOF_FILE, SCHEMA, MACHINERY, artifact_id, build_scope, canonical,
    digest, eligible_run, extract_apk, find_proof, matching_proof,
    read_proof_zip, real_android_job, machinery_changed, SafeArtifactRedirect, main,
)

HEAD, BASE = "a" * 40, "b" * 40
POLICY_LABELS = (
    "ci:owner-request", "ci:runtime-required", "ci:runtime-full",
    "ci:user-issue", "ci:theme", "skip-device-test",
)


def env():
    value = dict(
        GITHUB_REPOSITORY="Noirero/Miyorare", PR_NUMBER="863",
        BASE_REF="Amain2", BASE_SHA=BASE,
        HEAD_REF="fix/scope", HEAD_SHA=HEAD, WORKFLOW_ID="500",
        RUNTIME_MODE="full", RUNTIME_FULL="true",
        RUNTIME_CLASSES="org.test.Foo,org.test.Bar",
        RUN_RUNTIME_ISOLATION="true", RUN_MIGRATION_PERSISTENCE="true",
        DRAFT="false",
    )
    for label in POLICY_LABELS:
        value["LABEL_" + label.upper().replace("-", "_").replace(":", "_")] = "false"
    value["LABEL_CI_RUNTIME_FULL"] = "true"
    value["LABEL_SKIP_DEVICE_TEST"] = "true"
    return value


RUNNER_ACTION = b"    uses: reactivecircus/android-emulator-runner@a421e43855164a8197daf9d8d40fe71c6996bb0d\n"


def fixture_file(path):
    if path == ".github/workflows/pr-compile-unit-check.yml":
        return RUNNER_ACTION * 2
    return ("file:" + path).encode()

def scope(changes=None):
    e = env()
    if changes:
        e.update(changes)
    return build_scope(e, fixture_file)


def valid_run(**change):
    result = dict(id=42, workflow_id=500, event="pull_request",
                  status="completed", conclusion="success", run_attempt=1,
                  head_sha=HEAD, head_branch="fix/scope",
                  pull_requests=[{
                      "number": 863,
                      "head": {"sha": HEAD, "ref": "fix/scope"},
                      "base": {"sha": BASE, "ref": "Amain2"},
                  }])
    result.update(change)
    return result


def job(*, status="completed", conclusion="success", step="success"):
    return dict(name=ANDROID_JOB, status=status, conclusion=conclusion,
                steps=[dict(name=REAL_STEP, status="completed", conclusion=step)])


def artifact_zip(name, contents):
    content = io.BytesIO()
    with zipfile.ZipFile(content, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr(name, contents)
    return content.getvalue()


def proofs(current_scope=None, run_id=42, *, with_apk=False):
    current_scope = current_scope or scope()
    proof = dict(schema_version=SCHEMA, scope=current_scope,
                 fingerprint=digest(current_scope), source_run_id=run_id)
    artifacts = [dict(id=300, name=PROOF_ARTIFACT, expired=False, size_in_bytes=512)]
    zips = {300: artifact_zip(PROOF_FILE, canonical(proof))}
    if with_apk:
        artifacts.append(dict(id=301, name=APK_ARTIFACT, expired=False, size_in_bytes=900000))
        zips[301] = artifact_zip("app-debug.apk", b"PK" + b"a" * (1024 * 1024))
    return {"total_count": len(artifacts), "artifacts": artifacts}, zips


class AndroidReuseTests(unittest.TestCase):
    def select(self, runs=None, jobs=None, source_scope=None, apk=False, artifacts=None):
        items, zips = proofs(source_scope, with_apk=apk)
        if artifacts is not None:
            items = artifacts
        with tempfile.TemporaryDirectory() as folder:
            result = find_proof(
                runs if runs is not None else [valid_run()],
                lambda rid: jobs if jobs is not None else [job()],
                lambda rid: items, lambda aid: zips[aid],
                current_id=100, workflow_id=500, scope=scope(),
                require_owner_apk=apk, apk_destination=Path(folder),
            )
            if result and apk:
                self.assertEqual((Path(folder) / "app-debug.apk").stat().st_size,
                                 1024 * 1024 + 2)
            return result

    def test_A_valid_exact_scope_reuse(self):
        self.assertEqual(self.select(), 42)
        self.assertEqual(self.select(apk=True), 42)

    def test_B_head_mismatch(self):
        self.assertIsNone(self.select(runs=[valid_run(head_sha="c" * 40)]))

    def test_C_base_sha_and_base_ref_mismatch(self):
        for new_base in (dict(sha="c" * 40, ref="Amain2"),
                         dict(sha=BASE, ref="main")):
            with self.subTest(base=new_base):
                run = valid_run()
                run["pull_requests"][0]["base"] = new_base
                self.assertIsNone(self.select(runs=[run]))

    def test_D_targeted_cannot_reuse_full(self):
        older = scope(dict(RUNTIME_MODE="targeted", RUNTIME_FULL="false"))
        self.assertIsNone(self.select(source_scope=older))

    def test_E_different_targeted_classes(self):
        self.assertIsNone(self.select(source_scope=scope(dict(RUNTIME_CLASSES="org.test.Bar,org.test.Foo"))))
        self.assertIsNone(self.select(source_scope=scope(dict(RUNTIME_CLASSES="org.test.Foo"))))

    def test_F_isolation_change(self):
        self.assertIsNone(self.select(source_scope=scope(dict(RUN_RUNTIME_ISOLATION="false"))))

    def test_G_migration_change(self):
        self.assertIsNone(self.select(source_scope=scope(dict(RUN_MIGRATION_PERSISTENCE="false"))))

    def test_H_heavy_label_and_draft_changes(self):
        for changes in (
            dict(LABEL_CI_RUNTIME_FULL="false", LABEL_CI_RUNTIME_REQUIRED="true"),
            dict(LABEL_CI_RUNTIME_FULL="false", LABEL_CI_OWNER_REQUEST="true"),
            dict(LABEL_CI_RUNTIME_FULL="false", LABEL_CI_USER_ISSUE="true"),
            dict(LABEL_CI_RUNTIME_FULL="false", LABEL_CI_THEME="true"),
            dict(DRAFT="true"),
        ):
            with self.subTest(labels=changes):
                self.assertIsNone(self.select(source_scope=scope(changes)))

    def test_I_reject_failed_cancelled_skipped_or_inprogress(self):
        for conclusion in ("failure", "cancelled", "skipped", "neutral", None):
            with self.subTest(conclusion=conclusion):
                self.assertIsNone(self.select(jobs=[job(conclusion=conclusion)]))
                self.assertIsNone(self.select(runs=[valid_run(conclusion=conclusion)]))
        self.assertIsNone(self.select(jobs=[job(status="in_progress")]))
        self.assertIsNone(self.select(runs=[valid_run(status="in_progress")]))

    def test_J_real_step_must_have_success(self):
        for step in ("skipped", "failed", "cancelled", None):
            with self.subTest(step=step):
                self.assertIsNone(self.select(jobs=[job(step=step)]))
        self.assertIsNone(self.select(jobs=[dict(name=ANDROID_JOB, status="completed", conclusion="success", steps=[])]))

    def test_K_reuse_chain_never_accepted_and_original_always_preferred(self):
        reused_job = dict(name=ANDROID_JOB, status="completed", conclusion="success",
                          steps=[dict(name="Explain reused Android proof", status="completed", conclusion="success")])
        self.assertIsNone(self.select(jobs=[reused_job]))
        items, zips = proofs()
        with tempfile.TemporaryDirectory() as folder:
            result = find_proof([valid_run(id=90), valid_run(id=42)],
                                lambda rid: [reused_job] if rid == 90 else [job()],
                                lambda rid: items,
                                lambda aid: zips[aid],
                                current_id=100, workflow_id=500, scope=scope(),
                                require_owner_apk=False, apk_destination=Path(folder))
            self.assertEqual(result, 42)

    def test_L_API_failure_main_returns_success_with_reuse_false(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / "outputs"
            vars = {**env(), "GH_TOKEN": "test-token", "GITHUB_RUN_ID": "100",
                    "GITHUB_OUTPUT": str(output), "RUNNER_TEMP": folder}
            with patch.dict("os.environ", vars, clear=True), \
                 patch("urllib.request.OpenerDirector.open", side_effect=OSError("API unavailable")):
                self.assertEqual(main(), 0)
            self.assertIn("reuse=false", output.read_text())
            self.assertIn("source_run_id=", output.read_text())

    def test_isolated_history_benchmark_is_mandatory_for_original_proof(self):
        old = job()
        self.assertFalse(real_android_job([old], history_required=True))
        old["steps"].append(dict(name=HISTORY_STEP, status="completed", conclusion="success"))
        self.assertTrue(real_android_job([old], history_required=True))
        old["steps"][-1]["conclusion"] = "skipped"
        self.assertFalse(real_android_job([old], history_required=True))
        old["steps"][-1]["conclusion"] = "failure"
        self.assertFalse(real_android_job([old], history_required=True))
        self.assertIn(HISTORY_CLASS, "org.koitharu.kotatsu.history.ui.HistoryScrollBenchmarkTest")
        self.assertIn(".github/scripts/run-history-scroll-benchmark.sh", MACHINERY)

    def test_runtime_scope_rejects_omitting_the_second_pinned_emulator(self):
        self.assertRaises(
            ValueError, build_scope, env(),
            lambda path: RUNNER_ACTION if path.endswith(".yml") else b"file:" + path.encode(),
        )

    def test_M_runtime_machinery_guard_and_execution_inputs(self):
        for changed in MACHINERY:
            self.assertTrue(machinery_changed({changed, "README.md"}))
        self.assertTrue(machinery_changed(set()))
        self.assertFalse(machinery_changed({"app/src/main/kotlin/org/example/Foo.kt"}))
        self.assertRaises(ValueError, build_scope, env(), lambda _: b"uses: reactivecircus/android-emulator-runner@v2")
        self.assertNotEqual(digest(scope()), digest(build_scope(env(), lambda path: RUNNER_ACTION * 2 if path.endswith(".yml") else b"changed")))
        self.assertRaises(ValueError, build_scope, {**env(), "RUNTIME_MODE": "targeted"}, fixture_file)
        self.assertRaises(ValueError, build_scope, {**env(), "RUNTIME_CLASSES": "a,a"}, fixture_file)

    def test_wrong_pr_ref_workflow_event_or_rerun(self):
        for changes in (dict(workflow_id=999), dict(event="workflow_dispatch"),
                        dict(head_branch="different"), dict(run_attempt=2),
                        dict(id=100), dict(pull_requests=[])):
            with self.subTest(changes=changes):
                self.assertIsNone(self.select(runs=[valid_run(**changes)]))

    def test_invalid_or_missing_artifact(self):
        items, _ = proofs()
        for change in (
            {"total_count": 3}, {"artifacts": []},
            {"artifacts": [dict(items["artifacts"][0], expired=True)]},
        ):
            with self.subTest(changes=change):
                self.assertIsNone(self.select(artifacts={**items, **change}))
        self.assertRaises(ValueError, read_proof_zip, artifact_zip("wrong.json", b"{}"))
        self.assertFalse(matching_proof({"schema_version": SCHEMA}, scope(), 42))

    def test_owner_apk_requirement_fails_closed_if_missing(self):
        self.assertIsNone(self.select(apk=True, artifacts=proofs()[0]))
        with tempfile.TemporaryDirectory() as folder:
            self.assertRaises(ValueError, extract_apk,
                              artifact_zip("../unsafe.apk", b"PK" + b"a" * 1048576),
                              Path(folder))

    def test_artifact_redirect_never_leaks_token(self):
        redirect = SafeArtifactRedirect()
        req = urllib.request.Request("https://api.github.com/repos/test/artifacts/3/zip",
                                     headers={"Authorization": "Bearer secret"})
        changed = redirect.redirect_request(req, None, 302, "Found", {},
                                            "https://pipelines.actions.githubusercontent.com/storage/proof")
        self.assertNotIn("Authorization", changed.headers)
        self.assertRaises(ValueError, redirect.redirect_request, req, None,
                          302, "Found", {}, "http://insecure.example/proof")

    def test_canonical_fingerprint_is_deterministic(self):
        a = scope()
        self.assertEqual(canonical(a), canonical(dict(reversed(list(a.items())))))
        self.assertEqual(digest(a), digest(dict(reversed(list(a.items())))))


if __name__ == "__main__":
    unittest.main()