# Reader Journey — Phase 10 Full Validation

Status: validation contract for the Phase 10 candidate branch. This document deliberately separates
automated evidence from device/manual evidence so a green JVM suite is never mistaken for full
production validation.

## Source of truth

Phase 10 follows the consolidated Reader Journey Rank Rewards V2 contract:

- all 12 Rank Themes;
- Light / Dark / OLED;
- small/narrow and modern-phone behavior;
- Indonesian / long labels / large text;
- animation disabled / Reduce Motion / Minimal Cosmetic expectations;
- wallpaper ON/OFF;
- Reader, Library, Reader Journey, Profile, Achievements and Settings;
- cold start, first Library render, scroll, Reader movement, theme switch, wallpaper decode and
  memory pressure;
- representative golden visual regression;
- process recreation/death safety;
- system bars / edge-to-edge / IME integrity;
- selected state must not be color-only.

## Automated gate in this change

The dedicated `Reader Journey Phase 10 Full Validation` workflow is exact-head pinned.
PR routing and the coverage audit are documented below; manual dispatch retains the full original suite.

### Deterministic JVM matrix

It validates:

- exactly 12 semantic theme definitions;
- all three variants for every theme;
- OLED background/surface contract;
- reserved semantic status colors remain distinct from rank identity;
- stable representative golden theme IDs:
  - First Page;
  - Neon Archive;
  - Golden Manuscript;
  - Eternal Library;
- representative visual specs exist for every golden theme;
- Developer Theme Gallery includes Light/Dark/OLED selection, wallpaper ON/OFF, Indonesian long
  copy and non-color-only selected-state treatment;
- Reader Content Isolation;
- centralized theme-source precedence;
- Compose and legacy View consume the same Reader Journey theme runtime;
- system-bar / edge-to-edge wiring remains present;
- atomic cosmetic snapshot, policy sanitization and Collection regressions.

### Android 15 persistence probe

The Android job re-instantiates `ReaderProfileStore` across the persisted
`CosmeticLoadoutV2` boundary and verifies:

- a Full Set restores as one coherent state;
- badge/wallpaper/card/progress IDs remain matched to the selected theme;
- auto-equip preference survives;
- an old/corrupt snapshot fails closed to a safe state;
- no invalid partial cosmetic selection is restored.

This is the deterministic persistence boundary relevant to process recreation. It does not claim to
be a literal OS kill/relaunch benchmark.

## Performance evidence

The existing Reader Journey APK size gate already records exact base-vs-head Preview APK bytes.
Phase 9 ended at 49,031,574 bytes. This Phase 10 validation-only change intentionally adds tests,
workflow configuration and documentation rather than runtime assets.

A complete cold-start / render / scroll / memory performance verdict must use comparable
BEFORE-vs-AFTER measurements from the same build type, device class, Android version, dataset,
theme state and wallpaper state. No universal threshold is invented here.

## Representative visual regression

The project already has canonical Android screenshot-evidence infrastructure for Favourites.
Phase 10 keeps the Rank Theme golden matrix representative rather than attempting
12 themes × every screen × every device × every mode.

Reference themes remain frozen to:

1. First Page
2. Neon Archive
3. Golden Manuscript
4. Eternal Library

The deterministic gate protects their semantic/visual definitions now. Pixel screenshot evidence
for the full minimum screen set remains a device-render validation item rather than being falsely
reported as covered by JVM tests.

## Device/manual evidence still required before declaring Phase 10 fully PASS

These checks require actual rendered/device evidence:

- small/narrow phone;
- modern phone;
- large system font scaling;
- Reader interaction and page movement;
- Library/Favourites long-list scrolling;
- Reader Journey / Profile / Achievements / Settings rendering;
- status/navigation bars, gesture navigation, display cutout and IME transitions;
- wallpaper crop/decode behavior;
- animation disabled and Reduce Motion behavior;
- Minimal Cosmetic behavior once the production control is available;
- cold/warm start, first render, scroll, theme switch latency and memory pressure.

If any of these finds a regression, fix it on a branch from the latest `beta`, rerun exact-head
gates, confirm `behind=0`, then merge.

## Important finding

The current Beta has a general `VisualEffectLevel` control and a debug Gallery wallpaper toggle,
but the master contract's explicit Rank Theme controls for Reduce Motion, Reduce Glow and Minimal
Cosmetic Mode are not yet represented as complete production controls. Phase 10 must therefore not
claim those rows as PASS until that gap is implemented or explicitly resolved.

Likewise, production use of every badge/frame/wallpaper/card/progress primitive must be verified
surface-by-surface; availability in the visual registry or Developer Gallery alone is not proof of
production integration.


## CI cleanup Stage 4: coverage audit and routing

Audit baseline: `beta` at `2aba10a4e51159eb7adae14bafdc2b940753c3c1` (Stages 1–3 merged).
This cleanup changes CI routing and evidence upload only. No application, instrumentation/JVM test,
asset, label policy, ruleset, CI Deep, Android Runtime or visual workflow is changed.

### Actual coverage, rather than the legacy workflow's broad name

| Phase 10 work | Audit category | Decision and evidence |
| --- | --- | --- |
| 13 selected JVM classes via `:app:testDebugUnitTest --tests …` | B: subset of CI Deep | Delegate normal PR runs to Deep's **unfiltered** `:app:testDebugUnitTest --no-daemon --stacktrace`. Same exact head, Temurin 17, Debug variant, Gradle configuration and source trees; Phase 10 has no extra JVM property/environment or fixture setup. Deep has no PR path filter, runs on beta, and its classifier requires the full suite for all these source/test/CI inputs. |
| `ReaderJourneyPhase10StateRestorationTest` | A: unique | Keep SharedPreferences `ReaderProfileStore` reinstantiation, coherent FULL_SET components, auto-equip and corrupt-snapshot AUTO fallback. Runtime's four selectors do **not** include this class. This is a persisted-boundary probe, not a literal OS process-kill benchmark. |
| `ChapterPersistenceRegressionTest` | A/C: shared class, configuration not equivalent for measured assertions | Keep the entire class on its original API 35 / emulator build 13823996 / google_apis / x86_64 / pixel_2 / **4 cores / 4096 MB** device. Runtime selects the same Debug class but does not set those core/memory inputs. The 2,000-chapter store/cold-emission/concurrent-refresh thresholds and 3,000-chapter reopen threshold depend on elapsed time. Equivalent performance evidence is not proven; do not remove it or change its configuration. |
| `LocalBackupIdentityTest` | C: equivalent functional assertions | Delegate only when Runtime's **actual merge-base diff** has critical/theme paths that require the class even with owner labeling. Both use `connectedDebugAndroidTest`, the same runner, APK, API/image/build/architecture/profile, animation settings and exact candidate. The class resets Room/private backup policy in `@Before`, makes its own backup/restore fixtures, and has no device viewport, timing threshold or Phase 10 arguments/setup. Pin its audited contents too: a new assertion could make core/memory differences significant. If delegation is uncertain or Runtime is owner-skippable, retain the class in Phase 10. |
| `ReaderJourneyPhase10RenderedMatrixTest`, three Preview scenarios | D: unique | Keep all scenarios, Preview build and visual signing properties, locale `id-ID`, font/density/viewport/theme/IME setup, reduced/minimal mode, four representative equipped full sets, accessibility/overflow assertions, and required PNG/JSON. Badge/Nameplate/Navigation/Profile Frame workflows run other classes with component-specific fixtures/evidence; they do not replace this full-screen Settings/Reader Journey matrix. |
| Preview APK cold starts, `meminfo`, `gfxinfo` | A/D: unique evidence | Keep the three MainActivity starts and runtime evidence in **each** rendered scenario. This evidence is not a universal latency/performance acceptance threshold. |
| Checkout verification, JDK/Gradle/parser warmup, KVM, artifact upload | Supporting infrastructure | Every heavy job still checks out and verifies the exact PR head. JDK/Gradle/warmup in the deterministic job are conditional on JVM fallback/full dispatch. State and render jobs have separate routing outputs. Both Android report directories are uploaded as distinct artifact paths (the previous YAML folded them into one string). |

The 13 JVM selectors retained for full dispatch/fallback are:

- `readerjourney.theme.ReaderJourneyPhase10ValidationTest`
- `readerjourney.theme.ReaderJourneyPhase10AccessibilityRegressionTest`
- `readerjourney.theme.RankThemeFoundationTest`
- `readerjourney.theme.RankThemeVisualRegistryTest`
- `readerjourney.theme.RankThemeVisualsRegressionTest`
- `readerjourney.theme.ReaderContentIsolationRegressionTest`
- `readerjourney.theme.ReaderJourneyThemeActivationRegressionTest`
- `readerjourney.theme.ReaderJourneyThemePresentationResolverTest`
- `readerjourney.domain.ReaderJourneyCosmeticSnapshotCodecTest`
- `readerjourney.domain.ReaderJourneyCosmeticPolicyTest`
- `readerjourney.domain.ReaderJourneyThemeCollectionRegressionTest`
- `settings.developer.RankThemeGalleryRegressionTest`
- `core.ui.RankThemeMiyorareBridgeTest`

All live under `app/src/test/kotlin/org/koitharu/kotatsu/`; the cheap contract tests check their
existence, the original selectors, Deep's unfiltered task, and Deep's routing for each input.
Their assertions include source contracts as well as pure-domain/registry assertions; Deep runs
from the same repository/application layout, so source/provenance fixtures remain accessible.

### Event scope and decisions

No original non-document path is removed. Broad `readerjourney/**`, `core/ui/**`, `settings/**`,
their JVM tests, both persistence classes and Reader Journey androidTests still admit events.
The old Phase 10 documentation path remains a cheap-only event. Additional event paths protect
actual previously omitted inputs: Stats hosts/domain/data, Room, profile settings, MangaDataRepository,
application/DI, shared UI utilities and MainActivity startup, resources/assets, Gradle/build/variant
configuration, manifest, runner/shared test fixtures, classifier/parser and delegation contracts.
These are dependencies of the actual persistence and rendered fixtures, not an assertion that every
Reader Journey Kotlin edit requires an emulator.

| Candidate inputs | JVM in Phase 10 | State device | Three rendered devices |
| --- | --- | --- | --- |
| Original JVM test trees only | Deep supplies full JVM | Skip | Skip |
| Audited `CelebrationQueue.enqueue` / `close` body edit with unchanged imports/signatures/initialization and no new identifier/literal references | Deep supplies full JVM | Skip | Skip |
| Kotlin comments/formatting only with identical balanced tokens | Deep supplies full JVM | Skip | Skip |
| Cosmetic profile/store/codec/policy, ledger, theme, persistence, or unknown relevant inputs | Deep supplies full JVM | Keep; backup delegated only with durable Runtime coverage | Keep |
| `ReaderJourneyPhase10StateRestorationTest` | Deep supplies full JVM | Keep | Skip |
| `ReaderJourneyPhase10RenderedMatrixTest` | Deep supplies full JVM | Skip | Keep |
| StatsScreen/StatsActivity/ReaderJourneyFragment, resource or asset payload | Deep supplies full JVM | Skip | Keep |
| Build inputs, own workflow/classifier/test/parser, altered delegation contracts | Full original selected JVM | All three original classes | Keep |
| Docs/metadata-only | Skip | Skip | Skip |
| Missing history, wrong checkout, parser failure, unexplained empty diff | Full original selected JVM | All three original classes | Keep |
| `workflow_dispatch` | Full original selected JVM | All three original classes | Keep |

The queue exception is deliberately small: its only production consumers are ReaderActivity,
DeveloperToolsFragment and the celebration dialog, none opened by these instrumentation fixtures.
The classifier checks that consumer set at the exact candidate. New imports, identifiers/literals,
initialization, declarations, an additional consumer, unsupported tokens, or unreadable/new/deleted
source invalidate the exception. Other Reader Journey behavior edits are conservative until their
lack of unique coverage can be established. In particular, Stage 2/3's loaded-cosmetic StatsScreen
fingerprint is **not** reused: Phase 10 captures initial full-screen accessibility/overflow too, so a
loading change can matter to this matrix.

### Exact-candidate and fail-closed contract

`phase10_paths.py` uses only Python stdlib, local Git and the existing Kotlin lexer; no Gradle,
package installation or emulator. Checkout must equal the immutable full PR head SHA. Routing
compares `base.sha` → `head.sha` with rename detection disabled, so every commit and both sides
of a rename remain visible. Runtime delegation separately computes its real merge-base diff.
A docs-only synchronize commit cannot erase an earlier relevant change. Mixed inputs accumulate
state/render requirements; one safe input never suppresses another relevant input.

Audited SHA-256 contracts cover the complete Deep/Runtime workflow and classifier implementations,
JVM build configuration, and the backup class. Changed or unreadable contracts force full fallback;
they are not automatically accepted as equivalent. A future change must re-audit and deliberately
update these pins. The class-only backup delegation must survive **all** origin label states,
including `ci:owner-request`; it never depends on a label-only Runtime run that can later be skipped.
No label definitions/policies elsewhere are changed. The old blanket owner bypass and last-commit
previous-result reuse in Phase 10 are replaced by coverage decisions; unique persistence/render
inputs and routing self-changes are protected even on owner-request PRs.

Delegation does **not** claim a prior run succeeded or reuse previous-head artifacts. Deep and
Runtime check out the same exact candidate independently, and applicable checks must all pass.
The routing summary identifies their `ci-deep-unit-test-reports` and `android-runtime-evidence`
artifacts. Phase 10's own deterministic artifact remains available on dispatch/fallback; its state
and all three rendered artifacts retain their original assertions/evidence and retention.

Run cheap regressions with `python3 .github/scripts/test_phase10_paths.py`. They exercise routing,
manual full dispatch, immutable checkout/history, multiple commits, advanced-base/runtime diff
semantics, malformed input, source parser/dependency ambiguity, mixed PRs, all original JVM selectors,
state configuration and the complete unchanged rendered-job contract. The workflow runs them before
classification. This Stage 4 PR changes its own workflow/classifier, so it must run full original
Phase 10 validation at its final exact head.
