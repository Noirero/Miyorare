#!/usr/bin/env python3
from __future__ import annotations
import importlib.util
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("ci_deep_paths", ROOT / "ci_deep_paths.py")
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)

class CiDeepPathsTest(unittest.TestCase):
    def test_docs_only_skips_full_regression(self):
        for paths in [
            ["README.md"],
            ["docs/CI_AUDIT.md"],
            ["README.md", "docs/DEVELOPMENT_PIPELINE.md"],
        ]:
            with self.subTest(paths=paths):
                self.assertFalse(module.requires_deep(paths))

    def test_source_tests_resources_and_build_config_run(self):
        for path in [
            "app/src/main/kotlin/org/koitharu/kotatsu/main/MainActivity.kt",
            "app/src/test/kotlin/org/koitharu/kotatsu/history/data/TrackingProgressPolicyTest.kt",
            "app/src/androidTest/kotlin/org/koitharu/kotatsu/core/db/ChapterPersistenceRegressionTest.kt",
            "app/src/main/res/values/strings.xml",
            "app/src/main/AndroidManifest.xml",
            "app/build.gradle",
            "gradle/libs.versions.toml",
            "settings.gradle",
        ]:
            with self.subTest(path=path):
                self.assertTrue(module.requires_deep([path]))

    def test_unknown_changes_fail_closed(self):
        self.assertTrue(module.requires_deep(["tools/new_release_helper.py"]))
        self.assertTrue(module.requires_deep([".github/workflows/other-workflow.yml"]))

    def test_classifier_and_workflow_changes_force_deep(self):
        for path in [
            ".github/workflows/ci-deep.yml",
            ".github/scripts/ci_deep_paths.py",
            ".github/scripts/test_ci_deep_paths.py",
        ]:
            with self.subTest(path=path):
                self.assertTrue(module.requires_deep([path]))

    def test_mixed_docs_and_source_runs(self):
        self.assertTrue(module.requires_deep([
            "docs/CI_AUDIT.md",
            "app/src/main/kotlin/org/koitharu/kotatsu/core/db/MangaDatabase.kt",
        ]))

    def test_empty_diff_fails_closed(self):
        self.assertTrue(module.requires_deep([]))


class AndroidTestCompilePathsTest(unittest.TestCase):
    def test_docs_and_metadata_add_no_gradle_compile_work(self):
        for paths in [
            ["README.md", "docs/DEVELOPMENT_PIPELINE.md"],
            ["CONTRIBUTING.mdx", "LICENSE", ".gitignore", ".gitattributes"],
            [".github/CODEOWNERS", ".github/PULL_REQUEST_TEMPLATE.md"],
            [".github/ISSUE_TEMPLATE/bug.yml", ".github/release-notes.md"],
            ["docs/CI_AUDIT.md", ".github/BUILD_NOW", ".github/PF5_BUILD_NOW"],
        ]:
            with self.subTest(paths=paths):
                self.assertFalse(module.requires_android_test_compile(paths))
                self.assertFalse(module.requires_preview_android_test_compile(paths))

    def test_android_inputs_and_unknown_paths_are_protected(self):
        for path in [
            # PR #462: production API changes must protect unchanged callers in androidTest.
            "app/src/main/kotlin/org/koitharu/kotatsu/stats/ui/StatsScreen.kt",
            "app/src/main/java/org/koitharu/kotatsu/Api.java",
            "app/src/androidTest/kotlin/org/koitharu/kotatsu/stats/ui/BadgeV2ActualUiSmokeTest.kt",
            "app/src/androidTest/java/org/koitharu/kotatsu/ApiTest.java",
            "app/src/preview/kotlin/org/koitharu/kotatsu/PreviewApi.kt",
            "app/src/release/kotlin/org/koitharu/kotatsu/ReleaseApi.kt",
            "app/src/main/res/values/strings.xml",
            "app/src/main/AndroidManifest.xml",
            "app/src/androidTest/AndroidManifest.xml",
            "app/src/test/kotlin/org/koitharu/kotatsu/ApiTest.kt",
            "app/build.gradle", "build.gradle", "settings.gradle", "gradle.properties",
            "gradle/libs.versions.toml", "gradle/wrapper/gradle-wrapper.properties",
            "gradlew", "buildSrc/src/main/kotlin/ConventionPlugin.kt",
            "tools/regenerate_badge_v2_payload.py", "unknown/new-input",
            ".github/workflows/ci-deep.yml", ".github/scripts/ci_deep_paths.py",
            ".github/scripts/test_ci_deep_paths.py",
        ]:
            with self.subTest(path=path):
                self.assertTrue(module.requires_android_test_compile([path]))
                self.assertTrue(module.requires_deep([path]))

    def test_mixed_changes_and_normalized_paths(self):
        self.assertTrue(module.requires_android_test_compile([
            "docs/CI_AUDIT.md", ".github/CODEOWNERS",
            " app\\src\\main\\kotlin\\StatsScreen.kt ", "",
        ]))

    def test_empty_classification_fails_closed(self):
        for paths in [[], ["", " "]]:
            with self.subTest(paths=paths):
                self.assertTrue(module.requires_android_test_compile(paths))
                self.assertTrue(module.requires_preview_android_test_compile(paths))

    def test_shared_sources_use_the_cheaper_debug_compilation(self):
        for path in [
            "app/src/main/kotlin/org/koitharu/kotatsu/stats/ui/StatsScreen.kt",
            "app/src/main/java/org/koitharu/kotatsu/Api.java",
            "app/src/androidTest/kotlin/StatsScreenTest.kt",
            "app/src/androidTest/java/ApiTest.java",
            "app/src/main/res/values/strings.xml",
            "app/src/debug/kotlin/DebugApi.kt",
        ]:
            with self.subTest(path=path):
                self.assertTrue(module.requires_android_test_compile([path]))
                self.assertFalse(module.requires_preview_android_test_compile([path]))

    def test_variant_build_and_unknown_inputs_also_compile_preview(self):
        for path in [
            "app/src/preview/kotlin/PreviewApi.kt", "app/src/release/kotlin/ReleaseApi.kt",
            "app/src/androidTestPreview/java/PreviewApiTest.java",
            "app/build.gradle", "build.gradle", "settings.gradle", "gradle.properties",
            "gradle/libs.versions.toml", "gradlew", "tools/regenerate_badge_v2_payload.py",
            ".github/workflows/ci-deep.yml", ".github/scripts/ci_deep_paths.py",
            "new-module/src/main/kotlin/Api.kt", "unknown/path",
        ]:
            with self.subTest(path=path):
                self.assertTrue(module.requires_preview_android_test_compile([path]))
                self.assertTrue(module.requires_android_test_compile([path]))
                self.assertTrue(module.requires_deep([path]))
        self.assertFalse(module.requires_preview_android_test_compile([
            "docs/CI_AUDIT.md", "app/src/main/kotlin/StatsScreen.kt",
        ]))
        self.assertTrue(module.requires_preview_android_test_compile([
            ".github/CODEOWNERS", "app/src/main/kotlin/StatsScreen.kt", "app/src/release/kotlin/Api.kt",
        ]))

    def test_cli_defaults_preserve_jvm_routing_and_compile_is_independent(self):
        with tempfile.TemporaryDirectory() as directory:
            paths_file = Path(directory) / "paths.txt"
            for paths, deep, compile_required, preview_required in [
                (["README.md"], "false", "false", "false"),
                ([".github/CODEOWNERS"], "true", "false", "false"),
                (["app/src/main/kotlin/StatsScreen.kt"], "true", "true", "false"),
                (["app/src/release/kotlin/Api.kt"], "true", "true", "true"),
                ([], "true", "true", "true"),
            ]:
                paths_file.write_text("\n".join(paths), encoding="utf-8")
                command = [sys.executable, str(ROOT / "ci_deep_paths.py"), "--paths-file", str(paths_file)]
                with self.subTest(paths=paths):
                    self.assertEqual(subprocess.check_output(command, text=True).strip(), deep)
                    self.assertEqual(subprocess.check_output(
                        command + ["--gate", "android-test-compile"], text=True,
                    ).strip(), compile_required)
                    self.assertEqual(subprocess.check_output(
                        command + ["--gate", "preview-android-test-compile"], text=True,
                    ).strip(), preview_required)

    def test_workflow_wires_compile_to_exact_head_without_runtime_or_apk(self):
        workflow = (ROOT.parent / "workflows/ci-deep.yml").read_text(encoding="utf-8")
        self.assertIn("run_android_test_compile: ${{ steps.classify.outputs.run_android_test_compile }}", workflow)
        self.assertIn("--gate android-test-compile", workflow)
        self.assertIn("run_android_test_compile=true", workflow)  # manual dispatch
        command = next(line for line in workflow.splitlines() if "run: ./gradlew" in line)
        self.assertIn("needs.classify.outputs.run_android_test_compile == 'true'", command)
        self.assertIn(":app:testDebugUnitTest", command)
        self.assertIn(":app:compileDebugAndroidTestKotlin", command)
        self.assertIn(":app:compileDebugAndroidTestJavaWithJavac", command)
        self.assertNotIn("MIYORARE_ANDROID_TEST_BUILD_TYPE", command)
        preview_command = next(line for line in workflow.splitlines() if "run: ./gradlew :app:compilePreview" in line)
        self.assertIn(":app:compilePreviewAndroidTestKotlin", preview_command)
        self.assertIn(":app:compilePreviewAndroidTestJavaWithJavac", preview_command)
        self.assertIn("-PMIYORARE_ANDROID_TEST_BUILD_TYPE=preview", preview_command)
        self.assertNotIn("UnitTest", preview_command)
        self.assertIn("if: needs.classify.outputs.run_preview_android_test_compile == 'true'", workflow)
        self.assertIn("--gate preview-android-test-compile", workflow)
        self.assertIn("run_preview_android_test_compile=true", workflow)
        self.assertNotIn("SIGNING", command)
        for forbidden in ["assemble", "bundle", "connected", "install", "emulator-runner"]:
            self.assertNotIn(forbidden, command)
            self.assertNotIn(forbidden, preview_command)
        self.assertNotIn("android-emulator-runner", workflow)
        self.assertIn('test "$actual" = "$EXPECTED_HEAD"', workflow)
        self.assertEqual(workflow.count("ref: ${{ github.event_name == 'pull_request' && github.event.pull_request.head.sha || github.sha }}"), 2)

    def test_owner_request_cannot_bypass_compile_protection(self):
        spec = importlib.util.spec_from_file_location("android_runtime_paths", ROOT / "android_runtime_paths.py")
        assert spec and spec.loader
        runtime = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(runtime)
        paths = ["app/src/main/kotlin/org/koitharu/kotatsu/stats/ui/StatsScreen.kt"]
        run_runtime, _ = runtime.policy_requires_android_runtime(paths, {"ci:owner-request"})
        self.assertFalse(run_runtime)
        self.assertTrue(module.requires_android_test_compile(paths))

    def test_fast_checks_the_same_candidate_without_gradle(self):
        workflow = (ROOT.parent / "workflows/ci-fast.yml").read_text(encoding="utf-8")
        self.assertIn("ref: ${{ github.event_name == 'pull_request' && github.event.pull_request.head.sha || github.sha }}", workflow)
        self.assertIn('test "$(git rev-parse HEAD)" = "$EXPECTED_HEAD"', workflow)
        self.assertIn("python3 .github/scripts/test_ci_deep_paths.py", workflow)
        self.assertNotIn("./gradlew", workflow)
        self.assertNotIn("setup-gradle", workflow)
        self.assertNotIn("android-emulator-runner", workflow)

if __name__ == "__main__":
    unittest.main()
