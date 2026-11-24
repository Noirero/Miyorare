#!/usr/bin/env python3
import unittest
from pathlib import Path

import miyori_status as ms


class FakeClient:
    repository = "Noirero/Miyorare"

    def __init__(self, *, pr=None, runs=None, jobs=None, artifacts=None, workflows=None, last_label_change=None):
        self._pr = pr or {}
        self._runs = runs or []
        self._jobs = jobs or []
        self._artifacts = artifacts or []
        self._workflows = workflows or set(ms.GATES)
        self._last_label_change = last_label_change

    def pull(self, number):
        data = dict(self._pr)
        data.setdefault("number", number)
        return data

    def workflow_runs_for_head(self, sha):
        return self._runs

    def jobs_for_run(self, run_id):
        return self._jobs

    def latest_label_change(self, number):
        return self._last_label_change

    def artifacts_for_run(self, run_id):
        return self._artifacts

    def workflow_names(self):
        return set(self._workflows)


def make_pr(
    *,
    base="Amain2",
    head="feature/example",
    sha="a" * 40,
    labels=None,
    body="",
    draft=False,
    state="open",
    number=123,
):
    return {
        "number": number,
        "state": state,
        "draft": draft,
        "head": {"sha": sha, "ref": head},
        "base": {"ref": base},
        "labels": [{"name": label} for label in (labels or [])],
        "body": body,
    }


def run(name, *, number=10, attempt=1, status="completed", conclusion="success", run_id=1):
    return {
        "id": run_id,
        "name": name,
        "run_number": number,
        "run_attempt": attempt,
        "updated_at": "2026-10-28T00:00:00Z",
        "created_at": "2026-10-27T00:00:00Z",
        "status": status,
        "conclusion": conclusion,
        "html_url": f"https://github.com/run/{run_id}",
    }


class MiyoriStatusTests(unittest.TestCase):
    def test_latest_run_prefers_newer_run_number_over_older_rerun(self):
        runs = [
            run("Miyorare Identity Guard", number=10, attempt=2),
            run("Miyorare Identity Guard", number=11, attempt=1),
            run("Other", number=99),
        ]
        latest = ms.latest_runs_by_name(runs)
        self.assertEqual(latest["Miyorare Identity Guard"]["run_number"], 11)
        self.assertNotIn("Other", latest)

    def test_latest_run_prefers_newer_attempt_for_same_run(self):
        runs = [
            run("Miyorare Identity Guard", number=11, attempt=1),
            run("Miyorare Identity Guard", number=11, attempt=2),
        ]
        latest = ms.latest_runs_by_name(runs)
        self.assertEqual(latest["Miyorare Identity Guard"]["run_attempt"], 2)

    def test_gate_applicability_is_branch_aware(self):
        self.assertTrue(ms.gate_applicable("PR Compile & Unit Check", "Amain2"))
        self.assertFalse(ms.gate_applicable("PR Compile & Unit Check", "beta"))
        self.assertTrue(ms.gate_applicable("Miyorare Identity Guard", "Amain2"))
        self.assertTrue(ms.gate_applicable("Miyorare Identity Guard", "beta"))
        self.assertTrue(ms.gate_applicable("Miyorare Identity Guard", "main"))
        self.assertFalse(ms.gate_applicable("Promotion to Main Gate", "beta"))
        self.assertTrue(ms.gate_applicable("Promotion to Main Gate", "main"))

    def test_retired_legacy_gate_and_promotion_routes(self):
        self.assertNotIn("Beta to Main Release Gate", ms.GATES)
        self.assertEqual(ms.GATES.count("Promotion to Main Gate"), 1)
        for head in ("Amain2", "beta", "release/main-20261106",
                     "release/promote-main-20261106"):
            with self.subTest(head=head):
                self.assertTrue(ms.is_promotion_candidate("main", head))
        for head in ("hotfix/fix", "main", "release/other"):
            with self.subTest(head=head):
                self.assertFalse(ms.is_promotion_candidate("main", head))
        self.assertFalse(
            ms.is_promotion_candidate("Amain2", "release/promote-main-20261106")
        )

    def test_amain2_readiness_requires_identity_guard(self):
        pr = make_pr(labels=["ci:owner-request"])
        compile_only = FakeClient(pr=pr, runs=[run("PR Compile & Unit Check")])
        analysis = ms.analyze_pr(client=compile_only, pr_number=123)
        self.assertEqual(analysis["technical"][0], "waiting")
        self.assertIn("Miyorare Identity Guard", analysis["technical"][1])

        verified = FakeClient(
            pr=pr,
            runs=[run("PR Compile & Unit Check"), run("Miyorare Identity Guard")],
        )
        self.assertEqual(
            ms.analyze_pr(client=verified, pr_number=123)["technical"][0],
            "ready",
        )

    def test_identity_guard_skip_is_not_passing_evidence(self):
        pr = make_pr(labels=["ci:owner-request"])
        for conclusion in ("skipped", "neutral", "failure"):
            with self.subTest(conclusion=conclusion):
                states = [
                    ms.GateState("PR Compile & Unit Check", True,
                                 run("PR Compile & Unit Check")),
                    ms.GateState("Miyorare Identity Guard", True,
                                 run("Miyorare Identity Guard", conclusion=conclusion)),
                ]
                self.assertEqual(
                    ms.technical_readiness(pr=pr, states=states, label_errors=[])[0],
                    "blocked",
                )

    def test_phase1_workflow_routing_and_branch_prefix_safety(self):
        workflows = Path(__file__).resolve().parents[1] / "workflows"
        identity = (workflows / "identity-guard.yml").read_text(encoding="utf-8")
        identity_pr = identity.split("  pull_request:\n", 1)[1].split(
            "  workflow_dispatch:", 1
        )[0]
        for base in ("main", "beta", "Amain2"):
            self.assertIn(f"      - {base}\n", identity_pr)
        self.assertNotIn("github.head_ref", identity)
        identity_job = identity.split("  verify-identity:\n", 1)[1].split(
            "    steps:\n", 1
        )[0]
        self.assertNotIn("    if:", identity_job)

        upstream = (workflows / "miyorare-multi-upstream-check.yml").read_text(
            encoding="utf-8"
        )
        upstream_pr = upstream.split("  pull_request:\n", 1)[1].split(
            "  workflow_dispatch:", 1
        )[0]
        for base in ("beta", "Amain2"):
            self.assertIn(f"      - {base}\n", upstream_pr)
        self.assertIn("    paths:\n", upstream_pr)
        self.assertIn("extensions/miyorare-sources/tools/verify_multi_upstream.py",
                      upstream_pr)

        contract = (workflows / "source-pack-contract.yml").read_text(
            encoding="utf-8"
        )
        self.assertIn("  pull_request:\n    paths:\n", contract)
        contract_job = contract.split("  contract:\n", 1)[1].split(
            "    steps:\n", 1
        )[0]
        self.assertIn("    runs-on: ubuntu-latest", contract_job)
        if "github.head_ref" in contract_job:
            # The stable main contract may skip fix/hotfix PRs; preserve
            # that targeted policy rather than suppressing the entire check.
            self.assertIn("github.event_name != 'pull_request'", contract_job)
            for prefix in ("fix/", "bugfix/", "hotfix/"):
                self.assertIn(
                    f"!startsWith(github.head_ref, '{prefix}')", contract_job
                )

    def test_issue_traceability_extracts_closing_and_issue_url(self):
        body = "Fixes #531\nRelated: https://github.com/Noirero/Miyorare/issues/700"
        self.assertEqual(ms.extract_issue_refs(body), [531, 700])

    def test_label_consistency_catches_origin_and_theme_conflicts(self):
        errors, warnings = ms.label_consistency(
            labels={"ci:owner-request", "ci:user-issue", "ci:theme", "non-theme"},
            base_ref="Amain2",
            body="",
        )
        self.assertEqual(len(errors), 2)
        self.assertTrue(any("owner-request" in item for item in errors))
        self.assertTrue(any("theme" in item for item in errors))
        self.assertFalse(any("No task-origin" in item for item in warnings))

    def test_user_issue_without_traceability_is_warning(self):
        errors, warnings = ms.label_consistency(
            labels={"ci:user-issue"},
            base_ref="Amain2",
            body="No issue reference",
        )
        self.assertEqual(errors, [])
        self.assertTrue(any("no linked issue" in item for item in warnings))

    def test_failure_interpreter_is_deterministic(self):
        self.assertEqual(
            ms.classify_failure_area("Android 15 runtime", "connectedDebugAndroidTest"),
            "Android Runtime",
        )
        self.assertEqual(
            ms.classify_failure_area("Compile", "process resources"),
            "build/compile",
        )
        self.assertEqual(
            ms.classify_failure_area("Unit tests", "Run JVM unit tests"),
            "test",
        )

    def test_technical_readiness_requires_current_head_gate(self):
        pr = make_pr(labels=["ci:owner-request", "non-theme"])
        waiting = ms.technical_readiness(
            pr=pr,
            states=[ms.GateState("PR Compile & Unit Check", True, None)],
            label_errors=[],
        )
        self.assertEqual(waiting[0], "waiting")

        ready = ms.technical_readiness(
            pr=pr,
            states=[
                ms.GateState(
                    "PR Compile & Unit Check",
                    True,
                    run("PR Compile & Unit Check"),
                )
            ],
            label_errors=[],
        )
        self.assertEqual(ready[0], "ready")

    def test_promotion_readiness_requires_success_not_skip(self):
        pr = make_pr(base="main", head="Amain2", labels=["ci:owner-request"])
        skipped = ms.promotion_readiness(
            pr=pr,
            states=[
                ms.GateState(
                    "Promotion to Main Gate",
                    True,
                    run("Promotion to Main Gate", conclusion="skipped"),
                )
            ],
        )
        self.assertEqual(skipped[0], "blocked")

        ready = ms.promotion_readiness(
            pr=pr,
            states=[
                ms.GateState(
                    "Promotion to Main Gate",
                    True,
                    run("Promotion to Main Gate", conclusion="success"),
                )
            ],
        )
        self.assertEqual(ready[0], "ready")

    def test_analyze_pr_combines_labels_traceability_and_drift(self):
        pr = make_pr(
            labels=["ci:user-issue", "ci:new-classifier"],
            body="Fixes #531",
        )
        client = FakeClient(
            pr=pr,
            runs=[run("PR Compile & Unit Check"), run("Miyorare Identity Guard")],
            jobs=[{
                "name": "Android runtime validation — Android 15",
                "status": "completed", "conclusion": "success",
            }],
            workflows=set(ms.GATES),
        )
        analysis = ms.analyze_pr(client=client, pr_number=123)
        self.assertEqual(analysis["technical"][0], "ready")
        self.assertEqual(analysis["traceability"], "linked issue(s): #531")
        self.assertTrue(any("ci:new-classifier" in item for item in analysis["drift"]))

    def test_render_card_shows_only_applicable_gate_and_readiness(self):
        pr = make_pr(
            labels=["ci:owner-request", "non-theme"],
            body="",
        )
        client = FakeClient(
            pr=pr,
            runs=[run("PR Compile & Unit Check"), run("Miyorare Identity Guard")],
            workflows=set(ms.GATES),
        )
        analysis = ms.analyze_pr(client=client, pr_number=123)
        card = ms.render_card(
            pr_number=123,
            analysis=analysis,
            client=client,
            server_url="https://github.com",
        )
        self.assertIn(ms.CARD_MARKER, card)
        self.assertIn("PR Compile & Unit Check", card)
        self.assertIn("Miyorare Identity Guard |", card)
        self.assertEqual(analysis["technical"][0], "ready")
        self.assertIn("Technical readiness", card)
        self.assertIn("`ci:owner-request`", card)
        self.assertIn("does not approve, push, merge", card)

    def test_drift_detects_missing_tracked_workflow(self):
        client = FakeClient(workflows={"PR Compile & Unit Check"})
        findings = ms.automation_drift(client, {"ci:owner-request"})
        self.assertTrue(any("tracked workflow name" in item for item in findings))

    def test_workflow_inventory_paginates(self):
        class PagedClient(ms.GitHubClient):
            repository = "Noirero/Miyorare"

            def __init__(self):
                self.calls = []

            def get(self, path):
                self.calls.append(path)
                if path.endswith("page=1"):
                    return {
                        "total_count": 101,
                        "workflows": [
                            {"name": f"workflow-{index}"} for index in range(100)
                        ],
                    }
                return {
                    "total_count": 101,
                    "workflows": [{"name": "Promotion to Main Gate"}],
                }

        client = PagedClient()
        names = client.workflow_names()
        self.assertEqual(len(client.calls), 2)
        self.assertIn("workflow-99", names)
        self.assertIn("Promotion to Main Gate", names)


    def test_required_android_cannot_be_satisfied_by_skipped_or_absent_job(self):
        pr = make_pr(labels=["ci:owner-request", "ci:runtime-required"])
        state = [ms.GateState("PR Compile & Unit Check", True, run("PR Compile & Unit Check"))]
        missing = ms.technical_readiness(pr=pr, states=state, label_errors=[])
        self.assertEqual(missing[0], "waiting")
        skipped = ms.technical_readiness(
            pr=pr, states=state, label_errors=[],
            android_job={"name": "Android runtime validation — Android 15",
                         "status": "completed", "conclusion": "skipped"},
        )
        self.assertEqual(skipped[0], "blocked")

    def test_full_runtime_and_theme_are_mandatory_even_for_owner_draft(self):
        for label in ("ci:runtime-full", "ci:theme", "ci:user-issue"):
            with self.subTest(label=label):
                pr = make_pr(labels=["ci:owner-request", label])
                states = [ms.GateState("PR Compile & Unit Check", True,
                                       run("PR Compile & Unit Check"))]
                self.assertEqual(
                    ms.technical_readiness(pr=pr, states=states, label_errors=[])[0],
                    "waiting",
                )

    def test_prior_success_is_stale_after_label_mutation(self):
        pr = make_pr(labels=["ci:owner-request", "ci:runtime-required"])
        client = FakeClient(
            pr=pr, runs=[run("PR Compile & Unit Check")],
            jobs=[{"name": "Android runtime validation — Android 15",
                   "status": "completed", "conclusion": "success"}],
            last_label_change="2026-10-28T02:00:00Z",
        )
        analysis = ms.analyze_pr(client=client, pr_number=123)
        self.assertEqual(analysis["technical"][0], "waiting")
        self.assertIn("new label policy", analysis["technical"][1])

    def test_fresh_run_with_required_android_becomes_ready(self):
        pr = make_pr(labels=["ci:owner-request", "ci:runtime-full"])
        fresh = run("PR Compile & Unit Check")
        fresh["created_at"] = "2026-10-29T00:00:00Z"
        client = FakeClient(
            pr=pr, runs=[fresh, run("Miyorare Identity Guard")],
            jobs=[{"name": "Android runtime validation — Android 15",
                   "status": "completed", "conclusion": "success"}],
            last_label_change="2026-10-28T02:00:00Z",
        )
        self.assertEqual(ms.analyze_pr(client=client, pr_number=123)["technical"][0],
                         "ready")

    def test_skipped_or_action_required_compile_cannot_mark_ready(self):
        pr = make_pr(labels=["ci:owner-request"])
        for result in ("skipped", "neutral", "action_required", "cancelled", "failure"):
            with self.subTest(result=result):
                states = [ms.GateState("PR Compile & Unit Check", True,
                                       run("PR Compile & Unit Check", conclusion=result))]
                self.assertEqual(
                    ms.technical_readiness(pr=pr, states=states, label_errors=[])[0],
                    "blocked",
                )

    def test_status_card_explains_action_required_and_cancelled(self):
        for conclusion, phrase in (
            ("action_required", "GitHub approval is required"),
            ("cancelled", "not a test failure"),
            ("skipped", "not evidence that mandatory checks passed"),
        ):
            with self.subTest(conclusion=conclusion):
                pr = make_pr(labels=["ci:owner-request"])
                client = FakeClient(
                    pr=pr, runs=[run("PR Compile & Unit Check", conclusion=conclusion)]
                )
                status = ms.analyze_pr(client=client, pr_number=123)
                card = ms.render_card(
                    pr_number=123, analysis=status, client=client,
                    server_url="https://github.com",
                )
                self.assertIn(phrase, card)

    def test_missing_label_event_history_holds_readiness(self):
        pr = make_pr(labels=["ci:owner-request"])
        states = [ms.GateState("PR Compile & Unit Check", True,
                               run("PR Compile & Unit Check"))]
        self.assertEqual(
            ms.technical_readiness(
                pr=pr, states=states, label_errors=[], evidence_unavailable=True,
            )[0],
            "waiting",
        )

    def test_label_history_paginates_and_tracks_removed_labels(self):
        class PagedClient(ms.GitHubClient):
            repository = "Noirero/Miyorare"

            def __init__(self):
                self.calls = []

            def get(self, path):
                self.calls.append(path)
                if path.endswith("page=1"):
                    return ([{"event": "unlabeled",
                              "label": {"name": "ci:runtime-required"},
                              "created_at": "2026-11-04T01:00:00Z"}]
                            + [{"event": "assigned"}] * 99)
                return [{"event": "labeled", "label": {"name": "ci:theme"},
                         "created_at": "2026-11-04T02:00:00Z"}]

        client = PagedClient()
        self.assertEqual(client.latest_label_change(123), "2026-11-04T02:00:00Z")
        self.assertEqual(len(client.calls), 2)

    def test_legacy_marker_is_distinct(self):
        self.assertNotEqual(ms.CARD_MARKER, ms.LEGACY_MARKER)


if __name__ == "__main__":
    unittest.main()
