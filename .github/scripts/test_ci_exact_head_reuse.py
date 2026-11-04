#!/usr/bin/env python3
"""Pure regression tests for exact-head compile evidence reuse."""
import unittest

from ci_exact_head_reuse import COMPILE_JOB, ACTUAL_BUILD_STEPS, find_proof

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


def successful_real_job():
    return {
        "name": COMPILE_JOB, "status": "completed", "conclusion": "success",
        "steps": [
            {"name": name, "status": "completed", "conclusion": "success"}
            for name in sorted(ACTUAL_BUILD_STEPS)
        ],
    }


class ReuseTests(unittest.TestCase):
    def test_successful_compile_from_failed_android_run_is_reusable(self):
        self.assertEqual(
            find_proof([valid_run()], lookup([successful_real_job()]), **KW),
            42,
        )

    def test_rejects_changed_source_base_or_identity(self):
        proof = [successful_real_job()]
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
                jobs = [dict(successful_real_job(), conclusion=conclusion)]
                self.assertIsNone(find_proof([valid_run()], lookup(jobs), **KW))

    def test_older_valid_job_selected_after_newer_invalid(self):
        newer = valid_run(id=70)
        older = valid_run(id=42)
        def jobs(run_id):
            return [dict(successful_real_job(),
                         conclusion="failed" if run_id == 70 else "success")]
        self.assertEqual(find_proof([older, newer], jobs, **KW), 42)

    def test_successful_reused_job_is_not_a_reuse_proof(self):
        # A success check whose expensive steps were skipped cannot extend
        # an evidence chain if the original build later becomes unavailable.
        self.assertIsNone(find_proof(
            [valid_run()],
            lookup([{"name": COMPILE_JOB, "status": "completed",
                     "conclusion": "success", "steps": [
                         {"name": "Explain reused compile evidence",
                          "status": "completed", "conclusion": "success"},
                     ]}]),
            **KW,
        ))

    def test_partial_compile_is_never_accepted(self):
        for missing_step in ACTUAL_BUILD_STEPS:
            with self.subTest(missing_step=missing_step):
                job = successful_real_job()
                job["steps"] = [
                    step for step in job["steps"]
                    if step["name"] != missing_step
                ]
                self.assertIsNone(find_proof([valid_run()], lookup([job]), **KW))

    def test_current_run_is_never_self_proof(self):
        self.assertIsNone(
            find_proof([valid_run(id=CURRENT)], lookup([successful_real_job()]), **KW)
        )


if __name__ == "__main__":
    unittest.main()
