#!/usr/bin/env python3
import unittest

import miyori_command as mc


class MiyoriCommandTests(unittest.TestCase):
    def test_allowlist(self):
        self.assertEqual(mc.parse_command("/miyori help"), "help")
        self.assertEqual(mc.parse_command("  /miyori status  "), "status")
        self.assertEqual(mc.parse_command("/miyori require-runtime"), "require-runtime")

    def test_rejects_arguments_and_multiline_payload(self):
        self.assertEqual(mc.parse_command("/miyori require-runtime ci:user-issue"), "unknown")
        self.assertEqual(mc.parse_command("/miyori status\nrm -rf /"), "unknown")
        self.assertEqual(mc.parse_command("/miyori merge"), "unknown")


if __name__ == "__main__":
    unittest.main()
