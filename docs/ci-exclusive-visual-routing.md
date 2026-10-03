# Badge and Nameplate visual routing — stage 2

## Contract

Generic Stats loading/content edits should not start the Badge or Nameplate emulator merely
because both surfaces share `StatsScreen.kt`. Relevant renderer, profile geometry, selection,
theme, asset and test changes must still run their existing golden validation. Unknown host
changes remain conservative. Application behavior, labels, rulesets, CI Deep compile protection
and other Reader Journey workflows are outside this change.

## Audit of the original paths

Paths below are relative to `app/src/` unless prefixed with `.github/`, `tools/` or `app/`.
Kotlin package prefixes are shortened to `readerjourney/` and `stats/` for readability.

| Original input | Badge | Nameplate | Decision / visual dependency |
| --- | --- | --- | --- |
| `main/kotlin/…/readerjourney/theme/ReferenceRankThemeVisuals.kt` | Keep | Keep | Artwork/spec registry for both renderers. |
| `main/kotlin/…/readerjourney/ui/ExclusiveBadge.kt` | Broaden to `ExclusiveBadge*.kt` | — | Production badge drawing, state/motion policy; covers future renderer splits. |
| `main/kotlin/…/readerjourney/ui/ExclusiveNameplate.kt` | — | Broaden to `ExclusiveNameplate*.kt` | Production nameplate drawing and animation assets. |
| `main/kotlin/…/readerjourney/ui/ReferenceRankThemeVisuals.kt` | Keep | Keep | Wrappers choose the actual production renderers. |
| `main/kotlin/…/stats/ui/ReaderJourneyExclusiveCollection.kt` | Keep | Keep | Actual selectors and large previews; not a generic host. |
| `main/kotlin/…/stats/ui/StatsScreen.kt` | Gate cheaply | Gate cheaply | `ReaderProfileCard` directly owns badge/nameplate sizing, positioning and equipped bindings. Removing the entire trigger would lose relevant coverage. |
| `main/res/drawable/badge_*` | Broaden to `drawable*/badge_*`; add `raw*/badge_*` | — | Preserve all existing artwork and cover resource qualifiers/payloads. |
| `main/badge-assets/**`, `tools/regenerate_badge_v2_payload.py` | Keep | — | Badge source payload, manifest and generator provenance. |
| `app/build.gradle` | Keep | Add | Preview variant, resource generation and Compose dependencies can change rendered results. |
| `test/kotlin/…/readerjourney/ui/ExclusiveBadgeGuideContractTest.kt` | Keep | — | Badge guide/payload contract. |
| `androidTest/kotlin/…/readerjourney/ui/ExclusiveBadgeGoldenVisualTest.kt` | Keep | — | Existing visual fixtures and assertions. |
| `androidTest/kotlin/…/readerjourney/ui/ExclusiveBadgeBatterySaverTest.kt` | Keep | — | Badge battery-saver visual policy. |
| `androidTest/kotlin/…/stats/ui/BadgeV2ActualUiSmokeTest.kt` | Keep | — | Production selector and equipped-profile evidence/crop. |
| `main/res/drawable/nameplate_*`, `drawable-nodpi/nameplate_*`, `raw/nameplate_*` | — | Broaden to `drawable*/nameplate_*`, `raw*/nameplate_*` | Preserve existing assets and add qualified resources. |
| `test/kotlin/…/readerjourney/ui/NameplateGuideContractTest.kt` | — | Keep | Nameplate asset hashes/dimensions/guide contract. |
| `androidTest/kotlin/…/readerjourney/ui/ExclusiveNameplateGoldenVisualTest.kt` | — | Keep | Existing runtime fixtures, static/motion and performance assertions. |
| `androidTest/assets/nameplate_golden_reference_contact_sheet.jpg` | — | Keep | Reference visual input. |
| Each golden's own `.github/workflows/exclusive-*-golden.yml` | Keep | Keep | A workflow change must exercise its complete visual pipeline. |

Additional audited inputs run unconditionally: `RankTheme.kt` tokens/signatures,
`ExclusiveThemeQa.kt`, `ExclusivePowerSaveModeRuntime.kt`, cosmetic policy/model files,
`ReaderProfile.kt`, `ReaderJourney.kt` rank mapping and `ReaderJourneyRewardAccess.kt`,
`StatsComponents.kt` (including profile padding), `AppSettings.kt` and `SettingsState.kt`
(motion/quality preferences), the shared lock icon, root Gradle configuration/wrapper/dependencies,
and the routing script/tests themselves. Badge also includes `ExclusiveProfileFrame.kt`, used
in its production equipped-profile context. These additions close direct dependency gaps rather
than replacing any old trigger.

## Shared-host gate

Each workflow keeps its own path filter and adds a standard-library Python classifier job.
This job checks out the exact candidate SHA, compares against the merge base, runs routing tests,
and emits `run_visual`. It installs no SDK/JDK and invokes neither Gradle nor an emulator.
Badge's existing payload and golden jobs depend on this result; Nameplate's existing golden
job does likewise. Manual dispatch always requests the complete existing golden validation.
The two workflows remain separate.

The workflows' actual `paths` are the classifier's source of truth. Any matching input other
than `StatsScreen.kt` requests visual validation, even when mixed with a generic Stats change.
The existing handling of unrelated paths is preserved; this is not a new universal visual
safety net for every unknown repository file.

For a Stats-only match the classifier compares a conservative Kotlin token fingerprint:

- Profile/renderer functions, imports, top-level declarations, parent layout, ambient providers,
  preferences and binding arguments remain protected.
- Whitelisted statistics helpers may change plain labels/numeric literals; their calls,
  control flow, interpolated strings and dependency references remain protected.
- Only the audited `!hasLoadedStats && isLoading` guard from #462 is normalized to its loaded
  `else` branch, with that branch's contents preserved. The Badge smoke fixture explicitly
  uses `hasLoadedStats=true`, `isLoading=false`. Nameplate's actual selector/golden does not
  depend on the Stats loading skeleton. The skeleton's audited reference allowlist permits
  its addition/loading-only geometry changes; new references request the goldens.
- New helpers, unfamiliar conditions, changed loaded/profile branches, missing/renamed hosts,
  ambiguous declarations, parse errors or unavailable Git history request visual validation.
  This deliberately limited token comparison is not a Kotlin compiler or general semantic parser.

An unfamiliar generic refactor may still run an emulator. Expand the safe cases only with a
dependency audit and a regression test; never infer safety from a removed path or from compilation.

## Validation

`python3 .github/scripts/test_exclusive_visual_paths.py` runs in CI Fast and both cheap classifier
jobs. It uses immutable real before/after #462 sources, checks every original non-host trigger,
qualified assets, relevant renderers/tests/workflows, profile geometry/bindings and conservative
fallbacks. An isolated Git-repository test exercises the actual CLI/merge-base diff: generic
loading emits `false` for both, then a badge asset emits `true` for Badge.

`ci-deep.yml`, `ci_deep_paths.py` and its tests remain unchanged. Android compile regressions
continue to be caught by Debug Kotlin/Java instrumentation compilation and routed Preview
compilation from stage 1, independent of whether these visual emulators run. Docs/metadata
still add no Android compile work and do not match these visual workflow paths.

Because this PR changes both golden workflows and their policy, both complete visual pipelines
must run on its exact head. Their capture commands, assertions, thresholds and artifact
contracts are unchanged. No other Reader Journey workflow is narrowed in stage 2.
