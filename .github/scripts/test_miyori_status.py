#!/usr/bin/env python3
import unittest

import miyori_status as ms


class FakeClient:
    repository = "Noirero/Miyorare"

    def __init__(self, jobs=None):
        self._jobs = jobs or []

    def jobs_for_run(self, run_id):
        return self._jobs


class MiyoriStatusTests(unittest.TestCase):
    def test_latest_run_prefers_newer_run_number_over_older_rerun(self):
        runs = [
            {"name": "Miyorare Identity Guard", "run_number": 10, "run_attempt": 2, "updated_at": "c"},
            {"name": "Miyorare Identity Guard", "run_number": 11, "run_attempt": 1, "updated_at": "b"},
            {"name": "Other", "run_number": 99, "run_attempt": 1, "updated_at": "z"},
        ]
        latest = ms.latest_runs_by_name(runs)
        self.assertEqual(latest["Miyorare Identity Guard"]["run_number"], 11)
        self.assertNotIn("Other", latest)

    def test_latest_run_prefers_newer_attempt_for_same_run(self):
        runs = [
            {"name": "Miyorare Identity Guard", "run_number": 11, "run_attempt": 1, "updated_at": "a"},
            {"name": "Miyorare Identity Guard", "run_number": 11, "run_attempt": 2, "updated_at": "b"},
        ]
        latest = ms.latest_runs_by_name(runs)
        self.assertEqual(latest["Miyorare Identity Guard"]["run_attempt"], 2)

    def test_gate_applicability(self):
        self.assertTrue(ms.gate_applicable("PR Compile & Unit Check", "Amain2"))
        self.assertFalse(ms.gate_applicable("PR Compile & Unit Check", "main"))
        self.assertTrue(ms.gate_applicable("Miyorare Identity Guard", "beta"))
        self.assertTrue(ms.gate_applicable("Miyorare Identity Guard", "main"))
        self.assertFalse(ms.gate_applicable("Miyorare Identity Guard", "Amain2"))
        self.assertFalse(ms.gate_applicable("Promotion to Main Gate", "beta"))
        self.assertTrue(ms.gate_applicable("Promotion to Main Gate", "main"))

    def test_render_amain2_card_shows_only_amain2_gate_and_tracked_labels(self):
        run = {
            "id": 8,
            "status": "completed",
            "conclusion": "success",
            "html_url": "https://github.com/run/8",
        }
        client = FakeClient()
        pr = {
            "head": {"sha": "b" * 40},
            "base": {"ref": "Amain2"},
            "labels": [
                {"name": "ci:owner-request"},
                {"name": "non-theme"},
                {"name": "skip-device-test"},
                {"name": "unrelated-label"},
            ],
        }
        card = ms.render_card(
            pr_number=777,
            pr=pr,
            gate_states=[
                ms.GateState("PR Compile & Unit Check", True, run),
                ms.GateState("Miyorare Identity Guard", False, None),
                ms.GateState("Beta to Main Release Gate", False, None),
                ms.GateState("Promotion to Main Gate", False, None),
            ],
            client=client,
            server_url="https://github.com",
        )
        self.assertIn("PR Compile & Unit Check", card)
        self.assertNotIn("Miyorare Identity Guard |", card)
        self.assertNotIn("Promotion to Main Gate |", card)
        self.assertIn("`ci:owner-request`", card)
        self.assertIn("`non-theme`", card)
        self.assertIn("`skip-device-test`", card)
        self.assertNotIn("unrelated-label", card)
        self.assertIn("owner-facing device-test APK delivery may be skipped", card)

    def test_render_card_includes_failure_detail_and_runtime_override(self):
        run = {
            "id": 7,
            "status": "completed",
            "conclusion": "failure",
            "html_url": "https://github.com/run/7",
        }
        client = FakeClient(
            jobs=[
                {
                    "name": "verify-identity",
                    "conclusion": "failure",
                    "steps": [
                        {"name": "Checkout", "conclusion": "success"},
                        {"name": "Verify labels", "conclusion": "failure"},
                    ],
                }
            ]
        )
        pr = {
            "head": {"sha": "a" * 40},
            "base": {"ref": "main"},
            "labels": [{"name": "ci:runtime-required"}],
        }
        card = ms.render_card(
            pr_number=123,
            pr=pr,
            gate_states=[
                ms.GateState("PR Compile & Unit Check", False, None),
                ms.GateState("Miyorare Identity Guard", True, run),
                ms.GateState("Beta to Main Release Gate", True, None),
                ms.GateState("Promotion to Main Gate", True, None),
            ],
            client=client,
            server_url="https://github.com",
        )
        self.assertIn(ms.CARD_MARKER, card)
        self.assertIn("`verify-identity` → `Verify labels`", card)
        self.assertIn("ci:runtime-required", card)
        self.assertIn("does not push code, merge, publish releases", card)

    def test_all_requested_ci_labels_are_tracked(self):
        self.assertEqual(
            set(ms.TRACKED_LABELS),
            {
                "ci:owner-request",
                "ci:user-issue",
                "ci:theme",
                "ci:runtime-required",
                "non-theme",
                "skip-device-test",
            },
        )

    def test_legacy_marker_is_distinct(self):
        self.assertNotEqual(ms.CARD_MARKER, ms.LEGACY_MARKER)


if __name__ == "__main__":
    unittest.main()
