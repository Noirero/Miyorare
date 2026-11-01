#!/usr/bin/env python3
import unittest

import miyori_command as mc


class MiyoriCommandTests(unittest.TestCase):
    def test_allowlist(self):
        expected = {
            "/miyori help": "help",
            "/miyori status": "status",
            "/miyori checks": "checks",
            "/miyori explain": "explain",
            "/miyori labels": "labels",
            "/miyori readiness": "readiness",
            "/miyori head": "head",
            "/miyori tests": "tests",
            "/miyori require-runtime": "require-runtime",
        }
        for text, parsed in expected.items():
            self.assertEqual(mc.parse_command(text), parsed)

    def test_rejects_arguments_and_multiline_payload(self):
        self.assertEqual(
            mc.parse_command("/miyori require-runtime ci:user-issue"), "unknown"
        )
        self.assertEqual(mc.parse_command("/miyori status\nrm -rf /"), "unknown")
        self.assertEqual(mc.parse_command("/miyori merge"), "unknown")
        self.assertEqual(mc.parse_command("/miyori release"), "unknown")
        self.assertEqual(mc.parse_command("/miyori rerun"), "unknown")


if __name__ == "__main__":
    unittest.main()
