#!/usr/bin/env python3
import unittest

import miyori_status as ms


class FakeClient:
    repository = "Noirero/Miyorare"

    def __init__(self, *, pr=None, runs=None, jobs=None, artifacts=None, workflows=None):
        self._pr = pr or {}
        self._runs = runs or []
        self._jobs = jobs or []
        self._artifacts = artifacts or []
        self._workflows = workflows or set(ms.GATES)

    def pull(self, number):
        data = dict(self._pr)
        data.setdefault("number", number)
        return data

    def workflow_runs_for_head(self, sha):
        return self._runs

    def jobs_for_run(self, run_id):
        return self._jobs

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
        self.assertTrue(ms.gate_applicable("Miyorare Identity Guard", "beta"))
        self.assertTrue(ms.gate_applicable("Miyorare Identity Guard", "main"))
        self.assertFalse(ms.gate_applicable("Promotion to Main Gate", "beta"))
        self.assertTrue(ms.gate_applicable("Promotion to Main Gate", "main"))

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
            runs=[run("PR Compile & Unit Check")],
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
            runs=[run("PR Compile & Unit Check")],
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
        self.assertNotIn("Miyorare Identity Guard |", card)
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

    def test_legacy_marker_is_distinct(self):
        self.assertNotEqual(ms.CARD_MARKER, ms.LEGACY_MARKER)


if __name__ == "__main__":
    unittest.main()
