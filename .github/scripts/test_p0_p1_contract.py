#!/usr/bin/env python3
"""Cheap P0/P1 ownership regression: no Gradle, emulator or network."""

from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import p0_p1_contract as contract


class OwnershipTest(unittest.TestCase):
    def test_audited_provider_and_acceptance_sources_exist(self):
        contract.verify_contract()

    def test_ordinary_kotlin_uses_deep_without_duplicate_p0_execution(self):
        result = contract.ownership(["app/src/main/kotlin/org/koitharu/kotatsu/stats/ui/StatsViewModel.kt"], "ci:owner-request")
        self.assertTrue(result["ci_deep_required"])
        self.assertTrue(result["p0_ownership_validation"])
        self.assertFalse(result["p0_jvm_execution"])
        self.assertFalse(result["p0_favourites_capture"])

    def test_historical_jvm_tests_cannot_be_skipped_by_owner_label(self):
        for class_name in contract.JVM_CLASSES:
            with self.subTest(class_name=class_name):
                path = f"app/src/test/kotlin/{class_name.replace('.', '/')}.kt"
                self.assertTrue(contract.ownership([path], "ci:owner-request")["ci_deep_required"])

    def test_critical_runtime_changes_retain_android_owner(self):
        paths = [
            "app/src/main/kotlin/org/koitharu/kotatsu/core/db/migrations/NewMigration.kt",
            "app/src/main/kotlin/org/koitharu/kotatsu/backup/local/data/LocalBackupRepository.kt",
            "app/build.gradle",
            *[f"app/src/androidTest/kotlin/{name.replace('.', '/')}.kt" for name in contract.RUNTIME_CLASSES if ".alternatives." not in name],
        ]
        for path in paths:
            with self.subTest(path=path):
                result = contract.ownership([path], "ci:owner-request")
                self.assertTrue(result["android_runtime_required"])
                self.assertTrue(result["ci_deep_required"])
                self.assertFalse(result["p0_favourites_capture"])

    def test_noncritical_runtime_label_policy_is_not_rewritten(self):
        path = f"app/src/androidTest/kotlin/{contract.RUNTIME_CLASSES[1].replace('.', '/')}.kt"
        self.assertFalse(contract.ownership([path], "ci:owner-request")["android_runtime_required"])
        self.assertTrue(contract.ownership([path])["android_runtime_required"])
        self.assertTrue(contract.ownership([path], "ci:owner-request,ci:runtime-required")["android_runtime_required"])
        self.assertTrue(contract.ownership([path], "ci:owner-request,ci:user-issue")["android_runtime_required"])

    def test_favourites_unique_input_keeps_manual_evidence(self):
        paths = [
            f"app/src/androidTest/kotlin/{contract.FAVOURITES_CLASS.replace('.', '/')}.kt",
            "app/src/main/kotlin/org/koitharu/kotatsu/core/ui/MiyorareFavouritesVisualSpec.kt",
            "app/src/main/assets/miyorare/header-full/favourites/new.webp",
        ]
        for path in paths:
            with self.subTest(path=path):
                pr = contract.ownership([path], "ci:owner-request")
                self.assertTrue(pr["ci_deep_required"])
                self.assertFalse(pr["p0_favourites_capture"])
                self.assertTrue(contract.ownership([path], event="workflow_dispatch")["p0_favourites_capture"])

    def test_mixed_changes_union_provider_requirements(self):
        docs = "docs/guide.md"
        critical = "app/src/main/kotlin/org/koitharu/kotatsu/core/db/NewPersistence.kt"
        for paths in ([docs, critical], [critical, docs]):
            result = contract.ownership(paths, "ci:owner-request")
            self.assertTrue(result["ci_deep_required"])
            self.assertTrue(result["android_runtime_required"])
            self.assertTrue(result["p0_ownership_validation"])

    def test_unknown_and_empty_scope_do_not_skip_validation(self):
        for paths in ([], ["future/unknown.input"], ["app/src/main/kotlin/org/koitharu/kotatsu/favourites/future.input"]):
            with self.subTest(paths=paths):
                result = contract.ownership(paths)
                self.assertTrue(result["ci_deep_required"])
                self.assertTrue(result["p0_ownership_validation"])
        with self.assertRaises(contract.ContractError):
            contract.ownership([], event="future_event")

    def test_self_changes_force_current_contract_and_deep(self):
        for path in (".github/workflows/p0-p1-acceptance.yml", ".github/scripts/p0_p1_contract.py", ".github/scripts/test_p0_p1_contract.py"):
            with self.subTest(path=path):
                result = contract.ownership([path], "ci:owner-request")
                self.assertTrue(result["p0_ownership_validation"])
                self.assertTrue(result["ci_deep_required"])

    def test_docs_metadata_add_no_p0_gradle_or_emulator(self):
        result = contract.ownership(["docs/audit.md", "README.md", ".gitignore", ".gitattributes", "LICENSE"])
        self.assertFalse(result["ci_deep_required"])
        self.assertFalse(result["android_runtime_required"])
        self.assertFalse(result["p0_jvm_execution"])
        self.assertFalse(result["p0_favourites_capture"])

    def test_manual_dispatch_keeps_full_retained_acceptance(self):
        result = contract.ownership([], event="workflow_dispatch")
        self.assertTrue(result["p0_ownership_validation"])
        self.assertTrue(result["p0_favourites_capture"])
        self.assertFalse(result["p0_jvm_execution"])
        text = (contract.ROOT / ".github/workflows/p0-p1-acceptance.yml").read_text()
        jvm, visual = text.split("  favourites-golden-evidence:", 1)
        self.assertNotIn("./gradlew", jvm)
        self.assertNotIn("setup-java", jvm)
        self.assertNotIn("android-emulator-runner", jvm)
        self.assertIn("if: github.event_name == 'workflow_dispatch'", visual)
        self.assertIn("needs: jvm-acceptance", visual)
        self.assertIn(":app:connectedPreviewAndroidTest", visual)
        self.assertIn("-PMIYORARE_ANDROID_TEST_BUILD_TYPE=preview", visual)
        self.assertIn("-PMIYORARE_VISUAL_TEST_SIGNING=true", visual)
        self.assertIn(contract.FAVOURITES_CLASS, visual)
        for output in ("implementation.png", "geometry.json"):
            self.assertIn(f"test -s favourites-golden-evidence/{output}", visual)

    def test_provider_or_manual_contract_drift_fails_before_policy_import(self):
        for relative in contract.AUDITED_FILES:
            with self.subTest(relative=relative), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                for name in contract.AUDITED_FILES:
                    target = root / name
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(contract.ROOT / name, target)
                with (root / relative).open("a") as file:
                    file.write("\n# contract changed\n")
                with patch.object(contract, "load_policy") as loader:
                    with self.assertRaisesRegex(contract.ContractError, "re-audit"):
                        contract.ownership([], root=root)
                    loader.assert_not_called()

    def test_missing_or_disabled_test_fails_validation(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for relative in contract.AUDITED_FILES:
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(contract.ROOT / relative, target)
            with self.assertRaises(FileNotFoundError):
                contract.verify_contract(root)
            first = contract.JVM_CLASSES[0]
            target = root / f"app/src/test/kotlin/{first.replace('.', '/')}.kt"
            target.parent.mkdir(parents=True)
            target.write_text((contract.ROOT / target.relative_to(root)).read_text() + "\n@Ignore\n")
            with self.assertRaisesRegex(contract.ContractError, "disabled"):
                contract.verify_contract(root)

    def test_cli_failure_is_nonzero_not_a_skip_output(self):
        with patch.object(sys, "argv", ["contract", "--event", "pull_request", "--base", "", "--head", "bad"]):
            self.assertEqual(contract.main(), 1)


class CandidateTest(unittest.TestCase):
    def test_whole_pr_exact_candidate_deletions_and_manual_head(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            contract.git(root, "init", "--quiet")
            contract.git(root, "config", "user.name", "Local contract test")
            contract.git(root, "config", "user.email", "contract@example.invalid")

            def commit():
                contract.git(root, "add", "--all")
                contract.git(root, "commit", "--quiet", "-m", "fixture")
                return contract.git(root, "rev-parse", "HEAD").decode().strip()

            (root / "old.kt").write_text("old")
            base = commit()
            (root / "old.kt").rename(root / "new.kt")
            middle = commit()
            (root / "docs.md").write_text("latest commit only docs")
            head = commit()
            self.assertEqual(set(contract.candidate_paths(root, "pull_request", base, head)), {"old.kt", "new.kt", "docs.md"})
            self.assertEqual(contract.candidate_paths(root, "workflow_dispatch", "", head), [])
            with self.assertRaisesRegex(contract.ContractError, "differs"):
                contract.candidate_paths(root, "pull_request", base, middle)
            with self.assertRaisesRegex(contract.ContractError, "base"):
                contract.candidate_paths(root, "pull_request", "", head)
            with self.assertRaises(subprocess.CalledProcessError):
                contract.candidate_paths(root, "pull_request", "a" * 40, head)
            with self.assertRaises(contract.ContractError):
                contract.candidate_paths(root, "unknown", base, head)

    def test_bad_diff_decoding_does_not_become_empty_scope(self):
        head = "b" * 40
        with patch.object(contract, "git", side_effect=[f"{head}\n".encode(), b"a", b"bad\xffpath\0"]):
            with self.assertRaises(UnicodeError):
                contract.candidate_paths(Path("."), "pull_request", "a" * 40, head)


if __name__ == "__main__":
    unittest.main()
