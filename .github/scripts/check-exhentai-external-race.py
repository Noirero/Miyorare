#!/usr/bin/env python3
"""Classify only the original T01 diagnostic; required suite exit is never overridden."""
import argparse
import re
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

CLASS = "org.koitharu.kotatsu.local.domain.ExhentaiExternalRemovalRaceDiagnosticTest"
METHOD = "confirmedDeleteRejectsReplacementAtRemovalBoundary"
EXPECTED = "java.lang.AssertionError: An unapproved replacement must not be removed"

def classify(folder, head):
    if not re.fullmatch(r"[0-9a-f]{40}", head) or (folder / "commit.txt").read_text().strip() != head:
        raise ValueError("Diagnostic HEAD mismatch")
    status = int((folder / "exit-status.txt").read_text())
    log = (folder / "gradle.log").read_text(errors="replace")
    reports = list((folder / "xml").rglob("TEST-*.xml"))
    if len(reports) != 1:
        raise ValueError("Expected one fresh Android XML report")
    root = ET.parse(reports[0]).getroot()
    cases = list(root.iter("testcase"))
    if len(cases) != 1 or root.get("tests") != "1" or root.get("errors") != "0" or root.get("skipped") != "0":
        raise ValueError("Incomplete or unexpected diagnostic suite")
    case = cases[0]
    if (case.get("classname"), case.get("name")) != (CLASS, METHOD):
        raise ValueError("Unexpected diagnostic test")
    if list(case.iter("skipped")) or list(case.iter("error")):
        raise ValueError("Skipped/errored diagnostic")
    if re.search(r"device offline|Test run failed to complete|INSTRUMENTATION_FAILED|Process crashed|No tests found|No connected devices", log, re.I):
        raise ValueError("Infrastructure failure")
    failures = list(case.iter("failure"))
    if not failures:
        if status != 0 or root.get("failures") != "0" or "BUILD SUCCESSFUL" not in log or "BUILD FAILED" in log:
            raise ValueError("Inconsistent passing diagnostic")
        return "ACTUAL PASS - review mitigation; not proof of atomic deletion"
    if len(failures) != 1 or root.get("failures") != "1" or status != 1:
        raise ValueError("Unexpected diagnostic failure count/exit")
    trace = failures[0].text or ""
    if trace.splitlines()[:1] != [EXPECTED] or CLASS + "." + METHOD + "(" not in trace:
        raise ValueError("Unexpected failure signature")
    tasks = set(re.findall(r"Execution failed for task '([^']+)'", log))
    if (tasks != {":app:connectedDebugAndroidTest"}
            or "There were failing tests. See the report at:" not in log
            or log.count("BUILD FAILED") != 1
            or re.search(r"Build completed with \d+ failures", log)):
        raise ValueError("Unexpected Gradle failure")
    return "ACTUAL FAILURE - KNOWN EXTERNAL PLATFORM LIMITATION (owner-approved R1)"

class PolicyTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.folder = Path(self.tmp.name)
        self.head = "a" * 40
        (self.folder / "xml").mkdir()
        (self.folder / "commit.txt").write_text(self.head)
        self.report = self.folder / "xml" / "TEST-device.xml"
        self.log = self.folder / "gradle.log"
        self.status = self.folder / "exit-status.txt"
        self.write_case()

    def write_case(self, message=EXPECTED, name=METHOD, status=1, skipped=False):
        root = ET.Element("testsuites", tests="1", failures=str(int(message is not None)),
                          errors="0", skipped=str(int(skipped)))
        case = ET.SubElement(root, "testcase", classname=CLASS, name=name)
        if message is not None:
            ET.SubElement(case, "failure").text = message + "\nat " + CLASS + "." + METHOD + "(Fixture.kt:1)"
        if skipped:
            ET.SubElement(case, "skipped")
        ET.ElementTree(root).write(self.report)
        self.status.write_text(str(status))
        self.log.write_text("Execution failed for task ':app:connectedDebugAndroidTest'.\n"
                            "There were failing tests. See the report at: report\nBUILD FAILED"
                            if status else "BUILD SUCCESSFUL")

    def test_known_failure(self):
        self.assertIn("ACTUAL FAILURE", classify(self.folder, self.head))

    def test_real_pass(self):
        self.write_case(message=None, status=0)
        self.assertIn("ACTUAL PASS", classify(self.folder, self.head))

    def test_other_outcomes_block(self):
        for changes in (dict(message="other"), dict(name="other"), dict(status=2), dict(skipped=True)):
            with self.subTest(changes=changes):
                self.write_case(**changes)
                with self.assertRaises(ValueError):
                    classify(self.folder, self.head)

    def test_missing_or_extra_block(self):
        self.report.unlink()
        with self.assertRaises(ValueError):
            classify(self.folder, self.head)
        self.write_case()
        root = ET.parse(self.report).getroot()
        ET.SubElement(root, "testcase", classname=CLASS, name="extra")
        ET.ElementTree(root).write(self.report)
        with self.assertRaises(ValueError):
            classify(self.folder, self.head)

    def test_wrong_head_blocks(self):
        with self.assertRaises(ValueError):
            classify(self.folder, "b" * 40)

    def test_infra_or_other_task_blocks(self):
        for extra in ("device offline", "Execution failed for task ':app:compileDebugKotlin'."):
            with self.subTest(extra=extra):
                self.write_case()
                self.log.write_text(self.log.read_text() + "\n" + extra)
                with self.assertRaises(ValueError):
                    classify(self.folder, self.head)

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("folder", nargs="?", type=Path)
    parser.add_argument("head", nargs="?")
    args = parser.parse_args()
    if args.self_test:
        unittest.main(argv=[__file__])
    else:
        if args.folder is None or args.head is None:
            parser.error("folder and head required")
        outcome = classify(args.folder, args.head)
        (args.folder / "outcome.txt").write_text(outcome + "\n")
        print(outcome)
