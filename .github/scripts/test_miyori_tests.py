#!/usr/bin/env python3
"""Unit tests for Miyori's read-only exact-head diagnostic presentation."""
import unittest

import miyori_tests as mt


class TargetedDiagnosticsReportTests(unittest.TestCase):
    sha = "a" * 40

    def make_run(self):
        return {
            "id": 99, "name": mt.DIAGNOSTIC_NAME,
            "event": "workflow_dispatch",
            "head_branch": "main",
            "path": ".github/workflows/manual-targeted-ci-diagnostics.yml@main",
            "display_title": f"Diagnostic PR #42 @ {self.sha}",
            "status": "completed", "conclusion": "failure",
            "html_url": "https://github.com/Noirero/Miyorare/actions/runs/99",
        }

    def evidence(self):
        return {
            "pr_number": 42, "sha": self.sha, "platform": "android",
            "mode": "isolated",
            "runs": [
                {"index": 1, "targets": ["org.example.A#test"], "exit_code": 0, "reported_tests": 1},
                {"index": 2, "targets": ["org.example.A#test"], "exit_code": 1, "reported_tests": 1},
            ],
        }

    def test_exact_sha_match_required(self):
        self.assertEqual(mt.matching_run([self.make_run()], 42, self.sha)["id"], 99)
        self.assertIsNone(mt.matching_run([self.make_run()], 43, self.sha))
        self.assertIsNone(mt.matching_run([self.make_run()], 42, "b" * 40))
        injected = dict(self.make_run(), display_title="Diagnostic PR #42 @ " + self.sha + " unrelated")
        self.assertIsNone(mt.matching_run([injected], 42, self.sha))
        non_main = dict(self.make_run(), head_branch="feature/untrusted")
        self.assertIsNone(mt.matching_run([non_main], 42, self.sha))

    def test_inconsistent_sample_does_not_claim_root_cause(self):
        text = mt.render_report(42, self.sha, self.make_run(), self.evidence())
        self.assertIn("passed: **1**; failed: **1**", text)
        self.assertIn("inconsistent results", text)
        self.assertIn("root cause remains unknown", text)
        self.assertIn("not** a mandatory CI gate", text)

    def test_stale_artifact_is_not_used(self):
        stale = dict(self.evidence(), sha="b" * 40)
        text = mt.render_report(42, self.sha, self.make_run(), stale)
        self.assertIn("could not be verified", text)
        self.assertNotIn("passed: **1**", text)

    def test_no_run_is_honest(self):
        text = mt.render_report(42, self.sha, None, None)
        self.assertIn("No manual diagnostic run", text)
        self.assertIn("default branch", text)

    def test_zero_tests_cannot_be_reported_as_passed(self):
        evidence = dict(self.evidence(), runs=[
            {"index": 1, "targets": ["org.example.A#test"], "exit_code": 0, "reported_tests": 0}
        ])
        text = mt.render_report(42, self.sha, self.make_run(), evidence)
        self.assertIn("passed: **0**; failed: **1**", text)


if __name__ == "__main__":
    unittest.main()
