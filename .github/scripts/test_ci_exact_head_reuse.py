#!/usr/bin/env python3
"""Pure regression tests for exact-head compile evidence reuse."""
import unittest

from ci_exact_head_reuse import COMPILE_JOB, find_proof

HEAD = "a" * 40
BASE = "b" * 40
CURRENT = 100


def valid_run(**changes):
    result = {
        "id": 42,
        "workflow_id": 777,
        "event": "pull_request",
        "status": "completed",
        "conclusion": "failure",  # Android failed after the compile job passed.
        "head_sha": HEAD,
        "head_branch": "feature/example",
        "pull_requests": [{
            "number": 123,
            "head": {"sha": HEAD, "ref": "feature/example"},
            "base": {"sha": BASE, "ref": "Amain2"},
        }],
    }
    result.update(changes)
    return result


KW = dict(
    current_id=CURRENT,
    workflow_id=777,
    pr_number=123,
    head_sha=HEAD,
    head_ref="feature/example",
    base_sha=BASE,
    base_ref="Amain2",
)


def lookup(jobs):
    return lambda _run_id: jobs


class ReuseTests(unittest.TestCase):
    def test_successful_compile_from_failed_android_run_is_reusable(self):
        self.assertEqual(
            find_proof([valid_run()], lookup([{
                "name": COMPILE_JOB, "status": "completed", "conclusion": "success",
            }]), **KW),
            42,
        )

    def test_rejects_changed_source_base_or_identity(self):
        proof = [{"name": COMPILE_JOB, "status": "completed", "conclusion": "success"}]
        variants = [
            {"head_sha": "c" * 40},
            {"workflow_id": 778},
            {"head_branch": "other/ref"},
            {"event": "workflow_dispatch"},
            {"status": "in_progress"},
            {"pull_requests": []},
            {"pull_requests": [{
                "number": 123,
                "head": {"sha": HEAD, "ref": "feature/example"},
                "base": {"sha": "d" * 40, "ref": "Amain2"},
            }]},
            {"pull_requests": [{
                "number": 999,
                "head": {"sha": HEAD, "ref": "feature/example"},
                "base": {"sha": BASE, "ref": "Amain2"},
            }]},
        ]
        for changes in variants:
            with self.subTest(changes=changes):
                self.assertIsNone(find_proof([valid_run(**changes)], lookup(proof), **KW))

    def test_skipped_failed_or_incomplete_compile_never_reused(self):
        for conclusion in ("failed", "cancelled", "skipped", "neutral", None):
            with self.subTest(conclusion=conclusion):
                jobs = [{"name": COMPILE_JOB, "status": "completed", "conclusion": conclusion}]
                self.assertIsNone(find_proof([valid_run()], lookup(jobs), **KW))

    def test_older_valid_job_selected_after_newer_invalid(self):
        newer = valid_run(id=70)
        older = valid_run(id=42)
        def jobs(run_id):
            return [{
                "name": COMPILE_JOB, "status": "completed",
                "conclusion": "failed" if run_id == 70 else "success",
            }]
        self.assertEqual(find_proof([older, newer], jobs, **KW), 42)

    def test_current_run_is_never_self_proof(self):
        self.assertIsNone(
            find_proof([valid_run(id=CURRENT)], lookup([{
                "name": COMPILE_JOB, "status": "completed", "conclusion": "success",
            }]), **KW)
        )


if __name__ == "__main__":
    unittest.main()
