# Reader Journey Theme Size Baseline routing — stage 3

## What the baseline measures

The workflow builds `:app:assemblePreview` twice: the immutable PR `base.sha` (beta candidate)
and immutable PR `head.sha` (feature candidate). It records the size of the entire APK,
`delta_bytes = head_bytes - base_bytes`, and `delta_percent = delta_bytes / base_bytes * 100`.
This is informational evidence, not an isolated theme-only APK or a release-size budget.
There is **no pass/fail maximum byte/percentage threshold**, and none is added here.

`preview` is debuggable and unminified; release R8/resource shrinking settings are not used.
Preview adds `src/release/kotlin` and `src/release/res` to its source sets, enables theme QA,
and uses the existing ordinary unsigned CI build. The variant, identity, signing, build commands,
JDK, Gradle configuration, dependency pre-warm and retry behavior remain unchanged.

Acceptance additionally audits exactly 12 packaged `nameplate_*.webp` resources against the
exact source filename/hash set, rejects duplicates and legacy master/normal/overlay assets,
and records compressed/uncompressed Nameplate bytes. `reader-journey-theme-preview-size`
contains both JSON reports; `nameplate-v2-preview-candidate` contains the feature APK.
Both retain their existing names and 14-day retention.

A generic Kotlin edit can change DEX bytes and a translated label can change APK bytes.
Skipping them does **not** assert identical APK bytes or reuse previous size evidence. The purpose
of this feature-specific gate is to validate theme/package payload changes, not measure every
ordinary application behavior edit. New dependencies, resource IDs, imports/references and
unfamiliar changes still run the full measurement. Other compile/runtime gates own correctness
of generic behavior changes.

## Audit of original triggers

Paths below are relative to `app/src/main/`; Kotlin prefixes shorten `org/koitharu/kotatsu/`.

| Original input | Decision | Concrete dependency / replacement |
| --- | --- | --- |
| `kotlin/…/readerjourney/**` | Retain conservatively | Contains theme specs/registry, Badge/Nameplate/frame renderers, resource bindings, profile/cosmetic models, QA and unknown future inputs. Even the domain/data files compile into unminified Preview DEX. No blanket exclusion for Kotlin changes. Unproven refactors continue to run. |
| `kotlin/…/core/ui/MiyorareColorScheme.kt` | Retain | Compiled color/theme implementation is part of the measured feature. |
| `kotlin/…/settings/developer/**` | Retain conservatively | QA/gallery/tools are production source with Preview QA enabled, and can introduce assets/dependencies. Extension diagnostics share the directory; their Kotlin changes are not automatically proven safe. |
| `kotlin/…/stats/domain/ReaderProfileShareModel.kt` | Retain | Encodes profile/rank/cosmetic theme selection for the feature share surface. |
| `kotlin/…/stats/share/ReaderProfileShareCard.kt` | Retain | Compiled theme/profile renderer and localized feature resource references. |
| `kotlin/…/stats/ui/StatsScreen.kt` | Keep event; classify cheaply | Profile/cosmetic renderer references, imports and host bindings stay protected. Only the audited #462 loading wrapper/skeleton and existing generic helper plain labels/numbers may skip. |
| `kotlin/…/stats/ui/StatsActivity.kt`, `ReaderJourneyFragment.kt` | Keep event; classify cheaply | Actual shared hosts. Normalize only the #462 collection/binding of `hasLoadedStats`; every other token remains protected, including theme/resource/dependency references. |
| `res/values/strings.xml`, `res/values-in/strings.xml` | Keep event; classify cheaply | Existing unrelated plain text may skip. Protect actual `R.string` references from feature/host/QA sources, plus Journey/rank/theme/cosmetic names. Large text, add/remove IDs, attribute changes, markup, aliases, duplicate IDs, DTDs and malformed XML run. |
| `res/values*/settings_developer_diagnostics.xml` | Keep event; classify cheaply | Gallery/theme strings remain protected; an existing extension-stage plain-text label may skip. Extension-only source references are excluded from the theme string reference scan. |
| `res/drawable-nodpi/nameplate_*.webp` | Retain and broaden | Direct bytes under packaging/hash acceptance. Include all main resources and qualifiers so new/legacy/unknown payloads cannot bypass the audit. |
| `docs/reader-journey-theme-assets.md` | Remove event | Provenance documentation is not a Gradle input or packaged resource. Docs edits do not create APK work. Asset/code changes described by the document still route through their own paths. |
| `.github/workflows/reader-journey-theme-size-baseline.yml` | Retain | Changes to measurement/routing must exercise the complete size/packaging pipeline. |

## Gaps closed by the audit

- Badge runtime WebPs are decoded by `prepareExclusiveBadgeAssets` from
  `app/src/main/badge-assets/exclusive_badge_material_payload.b64` into generated main resources.
  The source WebPs/manifest and `tools/regenerate_badge_v2_payload.py` create that payload.
  All source/payload/generator changes request full validation; the test-only reference entry
  shares the archive/generator, so this provenance area remains conservative.
- Main resources include profile-frame assets, qualified Nameplate/Badge resources and all
  unknown future payloads. Main assets/resources/native libraries and manifest also affect
  packaged output. Preview and release source sets are covered because Preview consumes them.
  Debug/test/androidTest sources are not Preview APK inputs and add no size work by themselves.
- App/root/module Gradle scripts, properties, lockfiles, version catalog, wrapper,
  dependency verification, `buildSrc`/`build-logic` and ProGuard inputs request validation.
  ProGuard is release-only today, but keep the packaging policy conservative if configuration changes.
- Collection/profile hosts, `StatsComponents`, `AppSettings`, `SettingsState`, `SettingsTheme` (the actual `MiyorareTheme` wrapper) are added as
  feature selection/compiled shared dependencies. Unknown changes here run.
- New size classifier/tests and the existing shared visual parser request full validation.
  The shared parser is reused **without editing** stage 2 scripts, tests or golden workflows.

## Routing and candidate contract

The actual workflow `paths` are the classifier's event scope. Every matching input defaults to
full validation, except the small audited source/text cases above. Unknown scoped paths, new or
removed hosts, malformed content, missing Git history, empty diff and a checkout/head mismatch
fail closed. Paths outside this scope retain existing handling, as in stage 2; this workflow is
not a universal APK-size safety net for unrelated application files. A mixed PR still runs if
any scoped change requires it.

The cheap job checks out and verifies the exact feature head, pins the exact base SHA, runs
standard-library Python regression tests, and compares **the two complete trees that will be
built** with rename detection disabled and NUL-delimited paths. It does not use `event.before`,
a previous-head incremental diff, a moving head ref, or unverified earlier artifacts. An asset
commit followed by a docs-only commit therefore still requires full validation for that PR.

New Kotlin labels above 256 UTF-8 bytes, raw/interpolated/resource-like literals, or more than
512 bytes of added literal text run conservatively; unrelated XML text above 512 bytes also runs.
These are limits of the proven small-label routing case, not APK acceptance thresholds.

There is no JDK/SDK setup, Gradle invocation, APK build or emulator in the classifier job.
Only an explicit `false` can skip the heavy job. Script/process failure, invalid output or
failed classifier job requests the heavy audit; a failed regression test still leaves CI red.
Cancellation is respected.

Manual `workflow_dispatch` always runs the complete two-APK comparison and packaging audit.
Its head is the dispatched immutable `github.sha`; beta is fetched and pinned once as base.
Dispatch on beta legitimately compares beta with itself. Heavy checkout verifies both SHAs
and writes the actual build SHAs into JSON evidence, including on manual dispatch/fallback.

## Regression validation

`python3 .github/scripts/test_theme_size_paths.py` runs in this workflow's cheap job.
It covers the immutable entire #462 PR, all three shared hosts, generic metric/skeleton literals,
profile/binding/dependency changes, source failures, actual old trigger coverage, theme payloads,
Gradle/dependency/policy inputs, shared XML, docs/metadata and conservative unknown inputs.
An isolated Git test proves full-tree handling of earlier asset changes, missing history,
moving refs, wrong/stale checkout and actual CLI output. Workflow contract checks retain
manual full validation, fallback behavior, packaging assertions and no size budget.

Stage 1 CI Deep instrumentation compile protection and stage 2 Badge/Nameplate visual routing
are unchanged. Android Runtime, Phase 10, P0/P1, Source Pack, release/promotion workflows,
labels and rulesets are unchanged. No production source or assets are modified by stage 3.
