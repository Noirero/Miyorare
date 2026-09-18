#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location(
    "summarize_gradle_profile",
    ROOT / "summarize_gradle_profile.py",
)
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


class GradleProfileSummaryTest(unittest.TestCase):
    def test_duration_parser(self) -> None:
        self.assertEqual(module.parse_duration_seconds("250ms"), 0.25)
        self.assertEqual(module.parse_duration_seconds("12.5s"), 12.5)
        self.assertEqual(module.parse_duration_seconds("1m 2.5s"), 62.5)
        self.assertIsNone(module.parse_duration_seconds("FROM-CACHE"))

    def test_extracts_and_sorts_task_rows(self) -> None:
        html = """
        <html><body>
          <h2>Task Execution</h2>
          <table>
            <tr><th>Task</th><th>Duration</th><th>Result</th></tr>
            <tr><td>:app:compileReleaseKotlin</td><td>45.0s</td><td></td></tr>
            <tr><td>:app:minifyReleaseWithR8</td><td>5m 30s</td><td></td></tr>
            <tr><td>:app:mergeReleaseResources</td><td>4.0s</td><td></td></tr>
          </table>
        </body></html>
        """
        tasks = module.extract_tasks(html)
        self.assertEqual(tasks[0][1], ":app:minifyReleaseWithR8")
        self.assertEqual(tasks[0][0], 330.0)
        self.assertEqual(tasks[1][1], ":app:compileReleaseKotlin")

    def test_find_profile_uses_a_report(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            report = root / "profile-test.html"
            report.write_text("<html></html>", encoding="utf-8")
            self.assertEqual(module.find_profile(root), report)


if __name__ == "__main__":
    unittest.main()
