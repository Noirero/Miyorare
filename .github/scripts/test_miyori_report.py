#!/usr/bin/env python3
import unittest

import miyori_report as mr
import miyori_status as ms


class MiyoriReportTests(unittest.TestCase):
    def analysis(self, *, base="Amain2", head="feature/x"):
        pr = {
            "number": 42,
            "state": "open",
            "head": {"sha": "a" * 40, "ref": head},
            "base": {"ref": base},
        }
        run = {
            "id": 1,
            "status": "completed",
            "conclusion": "success",
            "html_url": "https://github.com/run/1",
        }
        states = [
            ms.GateState("PR Compile & Unit Check", base == "Amain2", run if base == "Amain2" else None),
            ms.GateState("Miyorare Identity Guard", base in {"main", "beta"}, run if base in {"main", "beta"} else None),
            ms.GateState("Beta to Main Release Gate", base == "main", run if base == "main" else None),
            ms.GateState("Promotion to Main Gate", base == "main", run if base == "main" else None),
        ]
        return {
            "pr": pr,
            "states": states,
            "labels": {"ci:owner-request", "non-theme"},
            "label_errors": [],
            "label_warnings": [],
            "traceability": "no linked issue declared",
            "technical": ("ready", "current-head applicable gates are non-blocking"),
            "promotion": ("ready", "promotion gate passed on the current head"),
            "release": ("ready", "technical evidence is ready; 1 workflow artifact(s) observed"),
            "artifact_count": 1,
            "drift": [],
        }

    def test_all_read_only_reports_render(self):
        analysis = self.analysis()
        for command in sorted(mr.READ_ONLY_COMMANDS):
            body = mr.render_report(command, analysis)
            self.assertIn("PR #42", body)

    def test_readiness_includes_promotion_release_for_candidate(self):
        body = mr.render_report(
            "readiness",
            self.analysis(base="main", head="Amain2"),
        )
        self.assertIn("Promotion:", body)
        self.assertIn("Release:", body)
        self.assertIn("not merge or release authorization", body)

    def test_checks_surfaces_drift(self):
        analysis = self.analysis()
        analysis["drift"] = ["tracked workflow missing"]
        body = mr.render_report("checks", analysis)
        self.assertIn("Automation attention", body)
        self.assertIn("tracked workflow missing", body)


if __name__ == "__main__":
    unittest.main()
