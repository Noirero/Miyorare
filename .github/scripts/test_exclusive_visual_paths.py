#!/usr/bin/env python3
"""Regression tests for shared-host gating and both workflows' real path rules."""
from __future__ import annotations

import contextlib
import importlib.util
import io
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("exclusive_visual_paths", Path(__file__).with_name("exclusive_visual_paths.py"))
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)
ROOT = module.ROOT
UI = "app/src/main/kotlin/org/koitharu/kotatsu/readerjourney/ui/"
THEME = "app/src/main/kotlin/org/koitharu/kotatsu/readerjourney/theme/"
STATS_UI = "app/src/main/kotlin/org/koitharu/kotatsu/stats/ui/"
TEST_UI = "app/src/test/kotlin/org/koitharu/kotatsu/readerjourney/ui/"
ANDROID_UI = "app/src/androidTest/kotlin/org/koitharu/kotatsu/readerjourney/ui/"


class VisualRoutingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # Immutable real #462 before/after sources, reachable from beta. CI checks
        # out full history; no network, Gradle, Android SDK or emulator is needed.
        cls.before = subprocess.check_output([
            "git", "show", "e7ebcd9b30d042e1c5c1d23ee14d22307b02fa11:" + module.STATS,
        ], cwd=ROOT, text=True)
        cls.after = subprocess.check_output([
            "git", "show", "48277f835f888ecaf943ed0d2326f0ce2e2efb74:" + module.STATS,
        ], cwd=ROOT, text=True)

    def assert_both(self, expected, paths, before=None, after=None):
        for kind in module.WORKFLOWS:
            with self.subTest(kind=kind, paths=paths):
                self.assertEqual(expected, module.requires_visual(kind, paths, before, after))

    def changed(self, old, new):
        self.assertTrue(old in self.after, f"Missing source probe: {old}")
        return self.after.replace(old, new, 1)

    def test_real_462_loading_only_change_skips_both_emulators(self):
        self.assert_both(False, [module.STATS], self.before, self.after)
        self.assert_both(False, [module.STATS, STATS_UI + "StatsViewModel.kt", "docs/notes.md"],
                         self.before, self.after)

    def test_generic_loading_dimensions_skip_both(self):
        self.assert_both(False, [module.STATS], self.after, self.changed("height(214.dp)", "height(215.dp)"))

    def test_generic_metric_label_and_number_skip_both(self):
        # The same production parser also accepts a small independent fixture.
        old = "fun StatsScreen() {}\nprivate fun ReaderProfileCard() {}\nprivate fun MetricsGrid() { Text(\"stats\"); Spacer(Modifier.height(12.dp)) }\n"
        new = old.replace('"stats"', '"statistics"').replace("12.dp", "14.dp")
        self.assert_both(False, [module.STATS], old, new)

    def test_comments_and_whitespace_are_not_visual_inputs(self):
        self.assert_both(False, [module.STATS], self.after, "// a generic stats note\n" + self.after)

    def test_profile_badge_and_nameplate_geometry_stay_protected(self):
        for old, new in [("size(34.dp)", "size(35.dp)"), ("width(176.dp)", "width(180.dp)")]:
            self.assert_both(True, [module.STATS], self.after, self.changed(old, new))

    def test_profile_binding_host_layout_and_imports_stay_protected(self):
        for old, new in [
            ("profile = profile,", "profile = ReaderProfileSettings(),"),
            ("LocalContentColor provides MaterialTheme.colorScheme.onSurface", "LocalContentColor provides MaterialTheme.colorScheme.primary"),
            ("import androidx.compose.foundation.background", "import androidx.compose.foundation.background\nimport unknown.Rendering"),
        ]:
            self.assert_both(True, [module.STATS], self.after, self.changed(old, new))

    def test_unfamiliar_loading_condition_stays_protected(self):
        self.assert_both(True, [module.STATS], self.after,
                         self.changed("!hasLoadedStats && isLoading", "!hasLoadedStats || isLoading"))

    def test_changed_loaded_profile_branch_stays_protected(self):
        self.assert_both(True, [module.STATS], self.before,
                         self.changed("size(34.dp)", "size(36.dp)"))

    def test_new_helpers_or_references_fail_closed(self):
        candidates = [
            self.after + "\nprivate fun UnknownRenderer() {}\n",
            self.changed("height(214.dp)", "height(214.dp).unknownRenderingHook()"),
            self.changed("height(214.dp)", "height(214.dp).then(ExclusiveBadgeModifier)"),
        ]
        for candidate in candidates:
            self.assert_both(True, [module.STATS], self.after, candidate)

    def test_new_calls_in_generic_metric_helpers_stay_protected(self):
        old = "fun StatsScreen() {}\nprivate fun ReaderProfileCard() {}\nprivate fun MetricsGrid() { Text(\"stats\") }\n"
        for new in [old.replace('Text("stats")', 'UnknownSideEffect(); Text("stats")'),
                    old.replace('"stats"', '"${changeTheme()}"'),
                    old.replace('Text("stats")', 'ExclusiveBadge()')]:
            self.assert_both(True, [module.STATS], old, new)

    def test_missing_renamed_or_malformed_host_fails_closed(self):
        for candidate in [None, self.after + "{", self.after + '\n"unterminated',
                          self.after + "\n/* nested /* comment */ */",
                          self.after.replace("fun ReaderProfileCard(", "fun OtherProfile("),
                          self.after + "\nfun StatsScreen() {}\n"]:
            self.assert_both(True, [module.STATS], self.after, candidate)
        self.assert_both(True, [module.STATS], None, self.after)

    def test_each_badge_input_remains_unconditional(self):
        for path in [
            UI + "ExclusiveBadge.kt", UI + "ExclusiveBadgeNewRenderer.kt", UI + "ExclusiveProfileFrame.kt",
            "app/src/main/res/drawable/badge_core.webp", "app/src/main/res/drawable-nodpi/badge_core.png",
            "app/src/main/res/drawable-hdpi/badge_core.png", "app/src/main/res/raw/badge_payload.bin",
            "app/src/main/badge-assets/v2-source-manifest.json", "tools/regenerate_badge_v2_payload.py",
            TEST_UI + "ExclusiveBadgeGuideContractTest.kt", ANDROID_UI + "ExclusiveBadgeGoldenVisualTest.kt",
            ANDROID_UI + "ExclusiveBadgeBatterySaverTest.kt",
            "app/src/androidTest/kotlin/org/koitharu/kotatsu/stats/ui/BadgeV2ActualUiSmokeTest.kt",
            module.WORKFLOWS["badge"],
        ]:
            with self.subTest(path=path):
                self.assertTrue(module.requires_visual("badge", [module.STATS, path], self.before, self.after))

    def test_each_nameplate_input_remains_unconditional(self):
        for path in [
            UI + "ExclusiveNameplate.kt", UI + "ExclusiveNameplateNewRenderer.kt",
            "app/src/main/res/drawable/nameplate_core.xml", "app/src/main/res/drawable-nodpi/nameplate_core.png",
            "app/src/main/res/drawable-xhdpi/nameplate_core.png", "app/src/main/res/raw/nameplate_frames.webp",
            "app/src/main/res/raw-night/nameplate_frames.webp", TEST_UI + "NameplateGuideContractTest.kt",
            ANDROID_UI + "ExclusiveNameplateGoldenVisualTest.kt",
            "app/src/androidTest/assets/nameplate_golden_reference_contact_sheet.jpg",
            module.WORKFLOWS["nameplate"],
        ]:
            with self.subTest(path=path):
                self.assertTrue(module.requires_visual("nameplate", [module.STATS, path], self.before, self.after))

    def test_shared_visual_dependencies_and_build_inputs_protect_both(self):
        for path in [
            THEME + "ReferenceRankThemeVisuals.kt", THEME + "RankTheme.kt", THEME + "ExclusiveThemeQa.kt",
            UI + "ReferenceRankThemeVisuals.kt", UI + "ExclusivePowerSaveModeRuntime.kt",
            STATS_UI + "ReaderJourneyExclusiveCollection.kt", STATS_UI + "StatsComponents.kt",
            "app/src/main/kotlin/org/koitharu/kotatsu/readerjourney/domain/ReaderJourneyCosmeticLoadout.kt",
            "app/src/main/kotlin/org/koitharu/kotatsu/readerjourney/domain/ReaderProfile.kt",
            "app/src/main/kotlin/org/koitharu/kotatsu/readerjourney/domain/ReaderJourney.kt",
            "app/src/main/kotlin/org/koitharu/kotatsu/readerjourney/domain/ReaderJourneyRewardAccess.kt",
            "app/src/main/kotlin/org/koitharu/kotatsu/core/prefs/AppSettings.kt",
            "app/src/main/kotlin/org/koitharu/kotatsu/settings/compose/SettingsState.kt",
            "app/src/main/res/drawable/ic_lock.xml", "app/build.gradle", "build.gradle", "settings.gradle",
            "gradle.properties", "gradle/libs.versions.toml", "gradle/wrapper/gradle-wrapper.properties",
            "gradlew", "gradlew.bat", ".github/scripts/exclusive_visual_paths.py",
            ".github/scripts/test_exclusive_visual_paths.py",
        ]:
            self.assert_both(True, [module.STATS, path], self.before, self.after)

    def test_specific_renderers_do_not_cross_trigger(self):
        self.assertFalse(module.requires_visual("badge", [UI + "ExclusiveNameplate.kt"]))
        self.assertFalse(module.requires_visual("nameplate", [UI + "ExclusiveBadge.kt"]))

    def test_docs_metadata_and_unrelated_sources_keep_existing_path_filter_behavior(self):
        self.assert_both(False, ["README.md", "docs/DEVELOPMENT_PIPELINE.md", ".github/CODEOWNERS"])
        self.assert_both(False, [STATS_UI + "StatsViewModel.kt", "app/src/main/res/drawable/bg_badge_empty.xml"])

    def test_empty_diff_is_conservative(self):
        self.assert_both(True, [])

    def test_every_original_non_host_trigger_is_retained(self):
        # Protect the complete pre-stage-2 inventory, including generator/test inputs.
        for kind, path in module.WORKFLOWS.items():
            original = subprocess.check_output(["git", "show", "0f917e7920ad049d4f0b45768109b52c7b2e4657:" + path], cwd=ROOT, text=True)
            rules = original.split("    paths:\n", 1)[1].split("\n  workflow_dispatch:", 1)[0]
            for line in rules.splitlines():
                if not line.strip():
                    continue
                pattern = module.ast.literal_eval(line[8:])
                if pattern == module.STATS:
                    continue
                sample = pattern.replace("**", "nested/fixture").replace("*", "fixture")
                with self.subTest(kind=kind, original=pattern):
                    self.assertTrue(module.requires_visual(kind, [sample]))

    def test_wiring_gates_before_sdk_and_emulator_and_preserves_manual_override(self):
        for kind, path in module.WORKFLOWS.items():
            source = (ROOT / path).read_text()
            classifier = source.split("  classify:\n", 1)[1].split("\n  payload:" if kind == "badge" else "\n  golden:", 1)[0]
            self.assertNotIn("setup-java", classifier)
            self.assertNotIn("gradlew", classifier)
            self.assertNotIn("emulator-runner", classifier)
            self.assertIn("fetch-depth: 0", classifier)
            self.assertIn("pull_request.head.sha", classifier)
            self.assertIn('if [ "$EVENT_NAME" = "workflow_dispatch" ]; then\n            run_visual=true', classifier)
            self.assertIn("needs: classify\n    if: needs.classify.outputs.run_visual == 'true'", source)
            self.assertIn("test_exclusive_visual_paths.py", classifier)
            if kind == "badge":
                self.assertIn("  golden:\n    needs: payload", source)
        fast = (ROOT / ".github/workflows/ci-fast.yml").read_text()
        self.assertIn("python3 .github/scripts/test_exclusive_visual_paths.py", fast)
        self.assertIn("python3 .github/scripts/test_ci_deep_paths.py", fast)

    def test_cli_invalid_refs_choose_visual(self):
        with patch.object(sys, "argv", ["router", "--kind", "badge", "--base", "missing-base", "--head", "missing-head"]), contextlib.redirect_stdout(io.StringIO()) as output, contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(0, module.main())
        self.assertEqual("true", output.getvalue().strip())

    def test_cli_real_git_diff_generic_then_asset(self):
        with tempfile.TemporaryDirectory() as directory:
            repo = Path(directory)
            def git(*args):
                return subprocess.check_output(["git", *args], cwd=repo, text=True, stderr=subprocess.DEVNULL).strip()
            git("init")
            git("config", "user.name", "Routing test")
            git("config", "user.email", "routing@example.invalid")
            for path in module.WORKFLOWS.values():
                target = repo / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text((ROOT / path).read_text())
            host = repo / module.STATS
            host.parent.mkdir(parents=True)
            host.write_text(self.before)
            git("add", ".")
            git("commit", "-m", "Before loading change")
            base = git("rev-parse", "HEAD")
            host.write_text(self.after)
            git("add", ".")
            git("commit", "-m", "Generic loading only")
            for kind in module.WORKFLOWS:
                with patch.object(module, "ROOT", repo), patch.object(sys, "argv", ["router", "--kind", kind, "--base", base, "--head", "HEAD"]), contextlib.redirect_stdout(io.StringIO()) as output:
                    self.assertEqual(0, module.main())
                self.assertEqual("false", output.getvalue().strip())
            asset = repo / "app/src/main/res/drawable/badge_changed.xml"
            asset.parent.mkdir(parents=True)
            asset.write_text("changed badge asset")
            git("add", ".")
            git("commit", "-m", "Relevant badge asset")
            with patch.object(module, "ROOT", repo), patch.object(sys, "argv", ["router", "--kind", "badge", "--base", base, "--head", "HEAD"]), contextlib.redirect_stdout(io.StringIO()) as output:
                self.assertEqual(0, module.main())
            self.assertEqual("true", output.getvalue().strip())


if __name__ == "__main__":
    unittest.main()
