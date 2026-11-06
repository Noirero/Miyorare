#!/usr/bin/env python3
"""Offline regression tests for the two existing JitPack pre-warm policies."""

from pathlib import Path
import os
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("prewarm-jitpack-parsers.sh")


class JitPackPrewarmTests(unittest.TestCase):
    def run_case(self, profile="preview", *, statuses=("200",), cached=False,
                 version="v1.2.3", missing_version=False):
        with tempfile.TemporaryDirectory() as work:
            root = Path(work)
            (root / "gradle").mkdir()
            (root / "gradle/libs.versions.toml").write_text(
                '[versions]\n' + ('' if missing_version else f'parsers = "{version}"\n'),
                encoding="utf-8",
            )
            (root / "bin").mkdir()
            calls = root / "curl-args"
            sleeps = root / "sleep-args"
            (root / "bin/curl").write_text(
                '#!/usr/bin/env bash\n'
                'printf "%s\\n" "$*" >> "$FAKE_ROOT/curl-args"\n'
                'n=0; [[ -f "$FAKE_ROOT/counter" ]] && read -r n < "$FAKE_ROOT/counter"\n'
                'printf "%s" "$((n+1))" > "$FAKE_ROOT/counter"\n'
                'IFS=, read -r -a statuses <<< "$FAKE_STATUSES"\n'
                'i="$n"; (( i >= ${#statuses[@]} )) && i=$((${#statuses[@]}-1))\n'
                'printf "%s\\n" "${statuses[$i]}"\n',
                encoding="utf-8",
            )
            (root / "bin/sleep").write_text(
                '#!/usr/bin/env bash\n'
                'printf "%s\\n" "$*" >> "$FAKE_ROOT/sleep-args"\n',
                encoding="utf-8",
            )
            for name in ("curl", "sleep"):
                (root / "bin" / name).chmod(0o755)
            if cached:
                cache = (root / ".gradle/caches/modules-2/files-2.1/"
                         f"com.github.Gekkoushi/plugin-source/{version}")
                cache.mkdir(parents=True)
                (cache / "parser.jar").write_bytes(b"jar")
                (cache / "parser.pom").write_bytes(b"pom")
            env = {**os.environ, "HOME": str(root), "FAKE_ROOT": str(root),
                   "FAKE_STATUSES": ",".join(statuses),
                   "PATH": str(root / "bin") + os.pathsep + os.environ["PATH"]}
            result = subprocess.run(["bash", str(SCRIPT), profile],
                                    cwd=root, env=env, capture_output=True,
                                    text=True, check=False, timeout=10)
            req = calls.read_text().splitlines() if calls.exists() else []
            delays = sleeps.read_text().splitlines() if sleeps.exists() else []
            return result, req, delays

    def test_preview_success_does_not_sleep_and_uses_original_timeouts(self):
        result, req, delays = self.run_case()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(req), 1)
        self.assertIn("--connect-timeout 5 --max-time 10", req[0])
        self.assertIn("plugin-source-v1.2.3.pom", req[0])
        self.assertIn("JitPack artifact is ready", result.stdout)
        self.assertEqual(delays, [])

    def test_preview_cache_hit_does_not_make_http_request(self):
        result, req, delays = self.run_case(cached=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(req, [])
        self.assertEqual(delays, [])
        self.assertIn("skipping JitPack pre-warm", result.stdout)

    def test_preview_failure_preserves_ten_attempts_and_delays(self):
        result, req, delays = self.run_case(statuses=("503",))
        self.assertEqual(result.returncode, 1)
        self.assertEqual(len(req), 10)
        self.assertEqual(delays, ["30"] * 10)
        self.assertIn("not available after 10 attempts", result.stdout)

    def test_acceptance_probes_even_with_cache_and_uses_12_long_retries(self):
        result, req, delays = self.run_case(profile="acceptance", cached=True,
                                            statuses=("503",))
        self.assertEqual(result.returncode, 1)
        self.assertEqual(len(req), 12)
        self.assertEqual(delays, ["20"] * 12)
        self.assertTrue(all("--connect-timeout 10 --max-time 30" in r for r in req))
        self.assertIn("not ready on JitPack", result.stdout)

    def test_acceptance_success_after_retry(self):
        result, req, delays = self.run_case(profile="acceptance",
                                            statuses=("503", "200"))
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertEqual(len(req), 2)
        self.assertEqual(delays, ["20"])
        self.assertIn("Parser artifact is ready", result.stdout)

    def test_missing_version_fails_closed_before_network(self):
        result, req, delays = self.run_case(missing_version=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(req, [])
        self.assertEqual(delays, [])
        self.assertIn("Missing versions.parsers", result.stderr)

    def test_unknown_profile_is_rejected(self):
        result, req, delays = self.run_case(profile="unknown")
        self.assertEqual(result.returncode, 2)
        self.assertEqual(req, [])
        self.assertEqual(delays, [])


if __name__ == "__main__":
    unittest.main()
