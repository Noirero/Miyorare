# Stage 6: P0/P1 acceptance ownership audit

Decision: **retain**. Audited beta `5a8df553699af23636bcaee0c481e6f3e3993383`
(Stage 5 / #467 merged). This is not a new JVM/runtime delegation: the current
P0/P1 PR job already runs no Gradle. There is no further proven duplicate heavy
PR work to remove. Preserve the cheap check identity and unique manual capture;
replace its three unstructured `grep` checks with a conservative local ownership
contract. Do not add PR emulators or a new classifier/workflow.

## Current job/step inventory

`P0 P1 Acceptance Gate` accepts all pull requests targeting **beta**, without
path or label filters, and `workflow_dispatch`. No push/schedule/workflow_call.
Permissions are `contents: read`; concurrency is
`p0-p1-acceptance-${{ github.ref }}` with cancellation. Both jobs use
ubuntu-latest and `FORCE_JAVASCRIPT_ACTIONS_TO_NODE24=true`. Ordinary, mixed,
unknown, docs, workflow and contract changes all run the cheap status gate.

| Job/step | Configuration and coverage | Failure / evidence | Status |
| --- | --- | --- | --- |
| `jvm-acceptance`, **P0/P1 deterministic acceptance** | Exact PR head (manual: github.sha), depth 0; timeout 45 min retained. No JDK, Gradle, test filter, variant, project/system property or emulator. | Candidate mismatch is fatal; no artifacts. | Retained here (F: compatibility/status wrapper). |
| Checkout + candidate verification | Never build GitHub's synthetic PR merge. Verification also applies to manual dispatch after Stage 6. | Fatal on mismatch. | Retained here. |
| Ownership verification | Previously only three grep strings in CI Deep. Stage 6 checks audited provider fingerprints, test source ownership and their local routing contracts before importing policies; logs base-to-head scope and expected provider decisions. | Provider drift, absent test, bad candidate/history/event or parser error fails the gate, rather than returning a skip. No API/network, Gradle or emulator. | Retained here, strengthened. |
| Cheap contract regression | Runs in the existing P0 job on every PR/manual dispatch. | Any assertion fails the job. | Retained here. |
| `favourites-golden-evidence`, **Favourites canonical visual evidence** | Only workflow_dispatch, needs jvm-acceptance; timeout 75 min. Exact github.sha. | PRs skip this job intentionally, as before Stage 6. | Retained manual-only (D/E). |
| Java/Gradle/permissions/KVM setup | Temurin 17; setup-gradle v6, basic cache, PR cache read-only expression retained; chmod gradlew; KVM chmod best effort. | Setup failures fatal except KVM chmod. | Retained manual-only. |
| Canonical Favourites capture | API 35, emulator build 13823996, google_apis, x86_64, pixel_2, animations disabled, SwiftShader/headless/no snapshot/no audio/no boot animation/back camera none. Override 864×1536, density 320, font 1.0, night mode. Clear previous capture. | Gradle/instrumentation/ADB failure or missing/nonempty capture checks fail. | Retained manual-only. |
| Gradle command | `:app:connectedPreviewAndroidTest --no-daemon --stacktrace -PMIYORARE_ANDROID_TEST_BUILD_TYPE=preview -PMIYORARE_VISUAL_TEST_SIGNING=true -Pandroid.testInstrumentationRunnerArguments.class=org.koitharu.kotatsu.favourites.ui.FavouritesGoldenVisualTest` | HiltTestRunner, Preview/Beta debuggable variant with existing opt-in visual signing; no signing/identity changes. | Retained manual-only. |
| Upload evidence (always) | `favourites-golden-evidence`: implementation.png, geometry.json, connected Android test reports/results. Retention 14 days, missing files warn. | Upload runs even on test failure; warning does not override capture failure. | Retained manual-only. |

The test seeds twelve deterministic manga and a category in Normal space,
initializes WorkManager for HiltTestApplication, configures Modern/Miyorare grid,
tabs/counts/actions/navigation, forces Indonesian locale, and waits for laid-out
controls and at least six covers. It asserts capture readiness and 864×1536
dimensions. Geometry differences are **logged**, not failed; no pixel-reference
threshold is enforced by this workflow. `favourites-golden-verify.sh` is an
offline ImageMagick overlay/difference helper, not an invoked CI step. Retaining
capture evidence does not imply an automated pixel-golden assertion.

## Historical execution → current owner matrix

Full history matters: `99f69560044498c5da459c31fdcb1a5cbc9806cc` removed the manual
Debug Android duplicate; `cfba7f7605317a1966b68f943b691b22a1537db0` replaced the
focused JVM invocation with compile-only; `49affb495b71f609012364e21fb2bfded9f279a9`
removed that duplicate compilation and retained the cheap status wrapper.
All predate Stage 6. No test source is deleted or moved here.

| Historical coverage | Current owner | Equivalence / evidence | Category |
| --- | --- | --- | --- |
| LocalArchiveFinalizationRegressionTest | CI Deep / Deep | Debug unit suite, 9 tests | B: already delegated to CI Deep |
| DownloadDeletionRegressionTest | CI Deep / Deep | Debug unit suite, 3 tests | B |
| ExtensionInstallerMethodDialogRegressionTest | CI Deep / Deep | Debug unit suite, 3 tests | B |
| LocalStackSafetyRegressionTest | CI Deep / Deep | Debug unit suite, 5 tests | B |
| FavouriteCategoryBatchingRegressionTest | CI Deep / Deep | Debug unit suite, 5 tests | B |
| RuntimeLagHardeningRegressionTest | CI Deep / Deep | Debug unit suite, 34 tests (source/geometry/performance wiring assertions, not screenshots) | B |
| ChapterPersistenceRegressionTest | Android Runtime / Android 15 runtime acceptance | File-backed Room close/reopen, migrations/chapter snapshots/ownership/progression and concurrent large-library state | C: already delegated to Android Runtime |
| ProfileMigrationPersistenceRegressionTest | Android Runtime / Android 15 runtime acceptance | Recreated preference stores, migration prepare/finalize/rollback/conflicts, initial reader profile, legacy UA | C |
| LocalBackupIdentityTest | Android Runtime / Android 15 runtime acceptance | Native backup category identity, Reader Journey/profile, private opt-in and 600+ rows, retry and malformed backup | C |
| AppBackupAgentTest | Android Runtime / Android 15 runtime acceptance | Mihon backup restore/progress/category/tracker fixtures and diagnostics | C |
| FavouritesGoldenVisualTest | P0/P1 / Favourites canonical visual evidence | Full seeded Favourites screen in Preview, Indonesian locale, canonical dimensions, screenshot + geometry | D/E: retained manual-only |
| Old compile-only JVM wrapper | CI Deep / Deep | Unit compilation is a dependency of testDebugUnitTest | F: already removed |

The six JVM classes remain under `app/src/test/kotlin/org/koitharu/kotatsu/`:
`local/data/output/`, `local/`, `settings/sources/`, `local/data/`,
`favourites/ui/categories/select/`, and `performance/`, respectively.
CI Deep runs **unfiltered** `:app:testDebugUnitTest --no-daemon --stacktrace`
(plus independently routed instrumentation compilation). Historical P0/P1 used
the same task with six `--tests` selectors, Java 17, basic Gradle cache, and no
additional test environment or -P/-D configuration. Source-file assertions work
from the same module working directory. Historical JitPack prewarm was dependency
availability only, not different assertions. Deep's report retention is 7 days
versus historical P0's 14; coverage is equivalent, artifact retention is not
identical.

Actual replacement before Stage 6: [CI Deep run 37107495902](https://github.com/Noirero/Miyorare/actions/runs/37107495902),
head `f10311acf913f9711d45c119e8bb0b950b3de409`, artifact
`ci-deep-unit-test-reports` (11268841954), contains all six class reports above:
**59 tests, zero failures and zero skipped**. Final Stage 6 head must also be
checked against its own Deep reports; this historical artifact is not final-head
evidence.

The historical four-class Android command is identical to current Android
Runtime's unfiltered-in-class `connectedDebugAndroidTest` selector, without
Preview/signing/state/viewport properties. Java 17, API 35 google_apis x86_64
pixel_2, disabled animations and emulator options match. The test itself owns
database/store recreation and fixtures; P0 did not add external state setup.
Actual [Android Runtime run 37074044584](https://github.com/Noirero/Miyorare/actions/runs/37074044584),
head `2fa4cff8200996fd282c8bba6507a7dd973a58ac`, job 111059867610, ran that exact
four-class emulator invocation and succeeded (not a skipped runtime job).
Its workflow and classifier are byte-identical to audited beta. Artifact
`android-runtime-evidence` (11255794890) reports 33 chapter, 7 profile migration,
5 native backup and 10 Mihon backup tests: **55 tests, zero failures/skips**.
Stage 6 makes no new runtime delegation and does not claim a new manual emulator
execution.

## Routing and replacement safety

CI Deep has beta/main PR events with **no path filter**, manual dispatch and
exact-head checkout. Only main-target promotion is skipped; P0 targets beta.
`ci_deep_paths.requires_deep` skips a small docs/root-metadata allowlist and
otherwise validates **every path**, including unknown paths, all six tests,
their production/resource/manifest dependencies, build inputs and P0 self
changes. Empty input fails closed. It has no owner-request/other label shortcut.
The whole PR's base SHA → head SHA diff is used. CI Fast does only local checks
and owns none of these six JVM or rendered assertions.

Android Runtime keeps its existing label policy unchanged. Critical DB,
backup/migration/build/manifest inputs override owner-request; runtime-required,
theme, user-issue and conflicting origin labels force runtime. Other unlabelled
runtime paths use legacy routing. **Owner-request noncritical alternatives,
reader, UI, etc. may skip runtime by that existing policy**; this audit does not
claim that every Kotlin/persistence-adjacent path forces an emulator. The old
P0 Android job was manual-only, so its removal did not subtract automatic
coverage. Manual Android Runtime still forces its full four-class suite.

The Stage 6 guard pins the audited CI Deep and Android Runtime workflows,
classifiers and policy tests. Even a comment/toolchain/event/env/property/filter
change requires an explicit ownership re-audit and a narrowly justified contract
update. Drift **fails** the P0 check; it never silently delegates to an unknown
replacement or launches a second JVM/emulator to mask broken ownership.
The contract does not poll another run or reuse a previous head's success.
It proves configuration/routing ownership; final-head CI supplies execution
evidence. Candidate/head/history errors likewise fail validation, not skip it.
No heavy routing decision is introduced: the cheap P0 job always validates.

## Visual comparison and manual scope

Badge Golden captures badge production contexts/UI/static-delta evidence;
Nameplate Golden tests motion/policy/performance and twelve nameplates;
Navigation Golden tests twelve navigation themes, widths/font/nav modes and
motion on fresh emulators. Profile Frame Wave 2 captures twelve profile frames;
Downloads Golden captures a separately seeded Downloads screen (Debug).
Phase 10 renders its Reader Journey theme/scenario matrix in Preview.
None runs FavouritesGoldenVisualTest or reproduces its full-screen fixture,
locale, geometry and capture. They are not replacements for this manual evidence
even when shared navigation/header code is involved. All remain unchanged.

Manual P0 dispatch runs the ownership/regression gate then the **entire retained
Favourites evidence job**. It does not execute the already delegated JVM or
Debug persistence suites; those have their own CI Deep/Android Runtime dispatch.
Favourites input changes remain Deep-protected automatically and retain this
explicit manual visual diagnostic; Stage 6 does not invent PR visual coverage.

## Identity, scope and validation

Repository-wide name/file searches found no workflow_run/reusable caller/needs
from another workflow, release/promotion/script/badge dependency; only the job's
internal needs and legacy docs references. The beta ruleset 23589709 lists
`verify-identity`, `Fast`, `Deep` as required checks. Legacy branch-protection
metadata is inaccessible to this integration (403). Preserve the P0 workflow,
filename, both job IDs/names and all-PR trigger anyway; do not change rulesets.
`docs/CI_AUDIT.md`'s older “unit + multiple emulator” row is historical, superseded
by this exact-beta inventory.

Regression runs locally without Gradle/emulator/network and in the existing P0
job. It covers ordinary Kotlin, unique/manual Favourites, critical runtime,
mixed/unknown/empty inputs, docs, self changes, provider drift, exact candidate
and whole-PR history failures. Only P0 orchestration, its local scripts and this
audit document change. Production/test sources/assets and Stages 1–5 are intact.
