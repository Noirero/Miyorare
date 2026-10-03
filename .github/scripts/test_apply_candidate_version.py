#!/usr/bin/env python3
import tempfile
import unittest
from pathlib import Path
from apply_candidate_version import apply_version


class CandidateVersionTest(unittest.TestCase):
    def fixture(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        path = Path(tmp.name) / "build.gradle"
        path.write_text("android {\n    defaultConfig {\n        versionCode = 75\n        versionName = '1.2.0'\n    }\n}\n", encoding="utf-8")
        return path

    def test_valid_input_replaces_exactly_once(self):
        path = self.fixture()
        apply_version(path, "1.4.5", "212453409", 212453408)
        text = path.read_text(encoding="utf-8")
        self.assertEqual(text.count("versionName = '1.4.5'"), 1)
        self.assertEqual(text.count("versionCode = 212453409"), 1)
        self.assertNotIn("versionCode = 75", text)

    def test_rejects_invalid_version_name(self):
        with self.assertRaises(SystemExit):
            apply_version(self.fixture(), "1.4", "212453409", 212453408)

    def test_rejects_non_numeric_version_code(self):
        with self.assertRaises(SystemExit):
            apply_version(self.fixture(), "1.4.5", "abc", 212453408)

    def test_rejects_non_monotonic_version_code(self):
        with self.assertRaises(SystemExit):
            apply_version(self.fixture(), "1.4.5", "212453408", 212453408)

    def test_rejects_too_large_version_code(self):
        with self.assertRaises(SystemExit):
            apply_version(self.fixture(), "1.4.5", "2100000001", 212453408)


if __name__ == "__main__":
    unittest.main()
