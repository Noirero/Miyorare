# Stage 7 — final CI architecture audit and routing baseline

Audit date: **2026-10-03**. Audited latest `beta`:
`b689bbc9cf5a1e55ab45596be04bcaac6fe9bc41` (merge of #469).
Closure fix audited the current required contexts again read-only.
Fetched `main` control plane: `33a9d10138e5032318dd3b24fa3af43271cbad20`.
This describes the **beta architecture plus the narrow Stage 7 fixes**, not an
assertion that those workflows are already deployed to main.

**Closure: BLOCKED — closure-fix exact-head PR integration validation is pending.**
The two operational blockers now have tested, fail-closed paths described below.
Closure is not declared from local tests alone. No Stage 8 is proposed.

## Recount and audit method

There are **29 workflow files with event definitions**, all `.yml`; no `.yaml`.
The count comes from the fetched beta tree, not GitHub search on an old commit,
old run names, or the historical audit's count. **0 workflows are deleted in
Stage 7.** No existing job/check identity is renamed. None of the Stage 1–6
providers, source-pack assertions/pins, production files or app test
sources is changed. The closure fix changes only README/main and Farm required-check
orchestration. Fast retains the existing main-candidate test entry point, which now
also executes the automation orchestration suite; Fast/Deep workflow bytes are unchanged.

The [machine-readable inventory](ci/stage7-workflow-inventory.json) records every
workflow's display name, category, exact events/branches/path filters,
permissions, concurrency, job/check identities, conditions/needs, timeouts,
checkout refs, toolchain, command invocation summaries, environment, secret **names**, emulator
configuration and artifacts/retention. Missing timeout means GitHub's default;
missing permissions means repository/GitHub defaults, not an inferred read-only
grant. The inventory hashes the final workflow contents so drift is visible.
The tables below supply domain ownership, execution cost, external dependencies
and mutation semantics. No workflow uses `workflow_run` or `workflow_call`.

Full current workflow files, classifiers, invoked helpers and tests were read
before editing. Whole-PR classifier tests run locally without SDK/Gradle or
emulators. See the Stage 1–6 audits for test-by-test details; their historical
before columns are not current workflow inventories.

Stage ancestry is present in beta: #463 (instrumentation compile), #464
(Badge/Nameplate routing), #465 (theme size), #466 (Phase 10), #467 (Source Pack
and UMA correction), #468 (P0/P1 ownership), #469 (final audit). Each merged commit is an ancestor
of the exact audited beta SHA.

## Active workflow inventory

`PR β` means pull requests targeting beta; `PR main` means main. `P` is a path
filter, whose exact patterns are in the JSON inventory. Every workflow with a
manual event retains it. Gradle rows imply JDK 17 and dependency/tool downloads
unless noted. APK capture/build is distinct from publishing a release. Ordinary
beta PRs do not run branch-build/release workflows.

### Core PR pipeline — 5

| Filename / display name | Events, effective routing | Coverage / cost / external dependency / mutation |
| --- | --- | --- |
| `ci-fast.yml` — CI Fast | PR β/main, manual; automatic job skips main | `Fast`: diff sanity, identity/launcher assertions and Python/shell helpers; no Gradle/emulator/APK; read-only. Main lane owns its own cheap checks. |
| `ci-deep.yml` — CI Deep | PR β/main, manual; automatic jobs skip main; docs allowlist skips heavy job | `Classify deep regression risk` + `Deep`: full Debug JVM suite, routed Debug and Preview instrumentation **compilation**; Maven/JitPack/toolchain; no emulator or APK; read-only. |
| `android-runtime.yml` — Android Runtime | PR β/main including label changes, manual; automatic jobs skip main; risk/label policy | `Classify core Android runtime` + `Android 15 runtime acceptance`: four Debug persistence/migration/backup classes on API 35, pinned emulator build 13823996; reports; read-only. |
| `identity-guard.yml` — Miyorare Identity Guard | PR β/main; push main; manual; PR branch bypass for fix/bugfix/hotfix | `verify-identity`: PR wrapper checks Fast's identity/launcher step presence; push/manual executes identity and launcher assertions; manual publishes commit status (statuses write). PR wrapper is not independent production validation. |
| `p0-p1-acceptance.yml` — P0 P1 Acceptance Gate | Every PR β, manual | `P0/P1 deterministic acceptance` is a cheap fail-closed provider/test ownership contract; historical suites owned by Deep/Runtime. `Favourites canonical visual evidence` is manual-only Preview API-35 capture. Gradle/emulator only for that manual evidence; read-only. |

### Specialized validation — 11

| Filename / display name | Events/routing | Unique evidence / cost / external dependency / mutation |
| --- | --- | --- |
| `exclusive-badge-golden.yml` — Exclusive Badge Golden Visual | PR β P, manual; cheap Stats-aware visual classifier | Payload provenance + 12-context static production-renderer/actual UI Preview capture; JVM guide checks, Gradle/JitPack, API 35 pixel_2 864×1536; 14-day evidence. Declared contents write is used for disposable local regeneration checks; no remote write. |
| `exclusive-nameplate-golden.yml` — Exclusive Nameplate Golden Visual | PR β P, manual; cheap Stats-aware visual classifier | 12-nameplate Preview capture, motion/static/policy evidence; API 35, animations on, 720×1280; Gradle/JitPack, 14-day evidence; read-only. Legacy references are informational. |
| `exclusive-navigation-golden.yml` — Exclusive Navigation Golden Visual | PR β P, manual | 12-theme static geometry and motion across widths/fonts/nav modes, Preview/API 35; separate emulator sessions are unique configurations; Gradle/JitPack, 14-day evidence; read-only. |
| `profile-frame-wave2-actual-ui-smoke.yml` — Profile Frame Wave 2 Actual UI Smoke | PR β P, manual | Despite historical Wave 2 name, current actual UI capture covers all 12 frames with geometry/static/reduce-motion assertions; Preview/API 35 864×1536; guide JVM/Gradle/JitPack; 14-day evidence; read-only. |
| `downloads-golden-visual.yml` — Downloads Golden Visual Evidence | PR β P, manual | Downloads-specific Debug/API-35 screenshot + geometry; 864×1536; Gradle and 14-day evidence; read-only. Different fixture from Favourites. |
| `reader-journey-phase10-validation.yml` — Reader Journey Phase 10 Full Validation | PR β P, manual; cheap provider-aware classifier | Deterministic fallback, unique state-restoration/Chapter timing (API 35, 4 cores/4 GB), and three Preview rendered/device configurations; backup delegated only when durable Runtime ownership is proven. Gradle/emulators and 14-day evidence only as routed; read-only. |
| `reader-journey-theme-size-baseline.yml` — Reader Journey Theme Size Baseline | PR β P, manual; cheap content-aware classifier | Same-toolchain **base Preview APK vs exact feature Preview APK**, package/source-payload invariants and byte/percentage delta JSON; Maven/JitPack; 14-day APK/evidence; read-only. It has **no size-budget threshold**; not a general Kotlin APK-size gate. |
| `miyorare-source-pack-check.yml` — Miyorare Source Pack Check | PR β P, manual; independent ID/EN flags | `Build Miyorare-id/en`: actual pinned UMA + Gekkoushi checkout/buildJar, curated/finalized independent packs, ID overlay and membership checks; Java 17/Gradle 8.14.3/SDK, external dependencies; 7-day staging + failure diagnostics; read-only external state, disposable tree edits only. |
| `miyorare-global-source-pack-check.yml` — Miyorare Global Source Pack Check | PR β and push β P, manual | `Build Miyorare-Global`: actual pinned Gekkoushi build, Global overlays, pagination and complete ownership assertions; same build toolchain/artifacts; read-only. Post-merge push is intentional fresh evidence. |
| `miyorare-multi-upstream-check.yml` — Miyorare Multi-Upstream Check | PR β P including app compatibility production/tests, manual | `Verify Keiyoushi + UMA source intake`: **actual pinned** trees, normalized aliases/IDs/domain/membership and exact Git HEAD/pin assertions; Python, **no Gradle**; intake JSON, missing artifact fails; read-only. |
| `source-pack-contract.yml` — Source Pack Compatibility Contract | PR any branch P, push main P, manual; local and mirror flags independent | `contract`: local consumer/installer/readiness/release/Farm contracts; actual sparse checkout of Source-Packs **moving main** for byte equality when needed; Python/bash, no Gradle/emulator; mismatch fails, logs as evidence; read-only. |

### Build / release / promotion — 8

| Filename / display name | Lifecycle | Coverage / external dependency / mutation |
| --- | --- | --- |
| `beta-to-main-release-gate.yml` — Beta to Main Release Gate | PR main, manual promotion or PR-bound README maintenance; ancestry-based lanes | Stable required `Verify beta is safe to promote`; promotion is cheap ancestry/identity/launcher verification; README maintenance is exact-head/current-main/README-only; hotfix pays JVM + routed instrumentation compile/runtime. Read-only; hotfix Gradle/dependencies and conditional API-35 emulator evidence. |
| `preview-build.yml` — Beta Build | Manual, beta-only | Signed Preview APK/identity/hash evidence (30 days), permanent keystore secrets; Gradle/JitPack; no remote release mutation. |
| `main-build.yml` — Miyorare Main Build | Manual, main-only; outside-main notice job | Source Pack readiness before signing/build, monotonic version, signed Release **APK** (not AAB); GitHub/Maven/JitPack and immutable source markers; creates release/tag/assets with contents write. Artifact 14 days. |
| `experimental-build.yml` — Experimental Build | Manual; checks out moving experimental branch | Signed Preview APK with established build-only experimental identity/version overrides; secrets, Gradle/JitPack; artifact 14 days; no remote release mutation. Kept distinct from production builds. |
| `pf5-build.yml` — PF5 Build | Manual, pf5-only | Signed Preview APK, secrets/Gradle/JitPack, hashes and 14-day artifact; no remote mutation. |
| `pf5-fast.yml` — PF5 Fast Gate | Push pf5, app/** | Branch-specific Debug unit compilation and Private/screenshot/TTS tracking selectors, JDK/Gradle/JitPack; no APK/emulator; permissions unspecified; read-only commands. Not a beta-PR duplicate. |
| `post-release-readme.yml` — Post Release README | Published stable release | Verifies actual released APK hashes and originating Main Build run/SHA; creates README-only main PR, dispatches Identity Guard + main gate, resolves/waits exact runs, then head-matched merge; contents/PR/actions write. Production adopts this only after main cutover. No Gradle/emulator. |
| `release-build-profile.yml` — Release Build Profile | Manual, beta-only diagnostic | Unsigned assembleRelease profile/time/RSS/task evidence, workers=4; Gradle/JitPack; 14-day artifact, no signing/release mutation. Also observability, not an official release. |

### Maintenance / observability — 5

| Filename / display name | Lifecycle | Coverage / external dependency / mutation |
| --- | --- | --- |
| `ci-metrics.yml` — CI Metrics | PR β P, manual, Monday 03:17 UTC | Cheap helper tests + Actions API last-300-run health JSON/Markdown, 90-day artifact; actions read; no build/mutation. |
| `gradle-benchmark.yml` — Gradle Benchmark | Manual | Four worker/heap alternatives; controlled cold/warm JVM + Preview assemble/compile/time/RSS; Gradle/dependencies, 30-day artifact; deliberately repeated cost measurement, no release. |
| `dependency-reproducibility.yml` — Dependency Reproducibility Evidence | PR β P, manual | Pinned parser/dependency checks, online then offline debugRuntimeClasspath resolution, Gradle/JitPack, 30-day reports; no emulator/mutation. Reports are resolution evidence, not byte-for-byte reproducible APK proof. |
| `miyorare-farm-pack-membership-sync.yml` — Miyorare Farm Pack Membership Sync | Manual, cron minute 4/34 every hour | Main control plane, beta target, real Source-Packs compatibility-farm-foundation snapshot; only approved ID/EN packs.json materialization, automation PR, manual Identity/Fast/Deep/pack checks and exact-head auto-merge; contents/actions/PR write, 180-minute timeout, 14-day evidence. **Separate mutation/synchronization**; engine/registry/materialization semantics unchanged. Production cutover below. |
| `readme-release-stats.yml` — README Release Stats | Manual, Monday 03:23 UTC | Stable release download counts from API; README-only automation PR to main; contents/PR/actions write; explicit Identity + main gate validation; no Gradle/emulator. Validated PR remains open, preserving existing intent. |

All Gradle builds require external artifact repositories/tool downloads. Emulator
jobs also require system images. No app AAB release is produced by these
workflows. Secrets remain confined to existing signed branch builds; their exact
names are in the inventory. No task requires a new signing secret.

## Coverage ownership map

| Risk/input | Authoritative workflow/job | Deliberate overlap / boundaries |
| --- | --- | --- |
| Source diff sanity, app identity, launcher separation | Beta: Fast; main: Classify main candidate; manual/push: verify-identity | Identity Guard's automatic PR step is a wrapper, not replacement for actual assertions. |
| Ordinary JVM regression | Deep / `:app:testDebugUnitTest`; direct-main hotfix JVM job | Same suite in different protected-branch lanes, not two runs on the same PR. |
| Instrumentation Kotlin **and Java** compilation | Deep routed Debug + Preview tasks; hotfix JVM now uses the same router/tasks | No emulator/APK assembly needed. API compatibility protects unchanged androidTest callers. |
| Persistence, profile migration, chapter data, local/native backup | Android Runtime / four Debug classes; main hotfix runtime | One runtime policy, critical override beats owner label. Phase 10 unique state/timing is separately retained. |
| Reader Journey state/restoration | Phase 10 Android state restoration | StateRestorationRegressionTest is unique; Chapter uses different 4-core/4-GB setup and unique timing/state evidence. |
| Reader Journey render / Appearance / IME / theme presentation | Phase 10 rendered matrix | Three device/theme/font configurations; not substituted by Runtime or cosmetic golden jobs. |
| Favourites canonical visual | P0/P1 manual Favourites canonical visual evidence | Seeded 12-manga fixture, Preview/API 35, screenshot/geometry; no claim of pixel-diff threshold. Geometry drift is reported, not universally fatal. |
| Badge / payload | Badge provenance + V2 static production-renderer evidence | Local guide JVM selectors overlap Deep intentionally to validate capture fixtures/payload before device evidence. |
| Nameplate / motion/static/policy | 12-nameplate runtime visual evidence | Different dimensions/animation policy from Badge. Guide JVM preconditions intentionally retained. |
| Navigation | 12-theme production visual evidence | Static matrix and motion sessions are different evidence, not equivalent emulator duplication. |
| Profile Frame | Wave 2 static runtime proof | Current 12-frame actual UI/geometry proof; guide JVM capture preconditions retained. |
| Downloads | Downloads canonical visual evidence | Downloads fixture/layout is distinct from Favourites. |
| Theme APK delta / packaging | Preview APK before vs after | Two immutable trees; **delta recording**, source/payload correctness, no acceptance size ceiling. |
| Source Pack ID/EN | Build Miyorare-id/en | Actual UMA/Gekkoushi compilation per selected language/curated membership. |
| Source Pack Global | Build Miyorare-Global | Global ownership/pagination + real Gekkoushi build; post-merge refresh intentionally retained. |
| Multi-Upstream intake | Verify Keiyoushi + UMA source intake | Exact pinned external source metadata/compatibility evidence; not an upstream Gradle build. |
| External compatibility contract | Source Pack Compatibility Contract / mirror subset | Source-Packs moving main byte equality; separate failure domain from pinned providers. |
| Farm approved membership | Farm sync engine tests + separate sync workflow | Engine validation is read-only; scheduled/manual sync actually mutates via PR; not consolidated. |
| beta → main | Verify beta is safe to promote | Cheap ancestry + identity; trusts protected beta's validation, does not query/re-run every historical beta check. |
| Direct-main hotfix | main candidate + hotfix JVM/compile + routed hotfix runtime → stable required gate | Beta core jobs skip main; this lane must own its own compile/runtime proof. |
| Stable release | Main Build / readiness/sign/build/hash/provenance + Post Release README | Release readiness validates external stable Source Packs before signing/build; README downstream uses the protected maintenance lane after default-main cutover. |
| Metrics/benchmark/dependency/build profiling | CI Metrics, Gradle Benchmark, Dependency Reproducibility, Release Build Profile | Diagnostic evidence, not PR runtime/pixel/device substitutes. |

No previously-owned test source or unique validation is deleted/delegated in
Stage 7. No obsolete-workflow deletion has sufficient additional equivalence
proof to justify a change here. Separate lifecycles, devices, artifacts and
mutation permissions justify retaining these workflows.

## Stage 1–6 contract verification

- **Stage 1:** Deep invokes full Debug JVM plus Kotlin/Java instrumentation
  compile tasks, with Preview separately routed by variant/build/unknown input.
  Historical controlled Kotlin-negative run
  [37095904181](https://github.com/Noirero/Miyorare/actions/runs/37095904181) and
  Java-negative run [37096283526](https://github.com/Noirero/Miyorare/actions/runs/37096283526)
  failed compilation without a visual emulator. These are historical negative
  evidence, not a claim of new Stage 7 negative device runs.
- **Stage 2:** real #462 before/after Stats sources are fixture inputs to both
  visual classifiers. Loading-only/generic metric changes skip Badge/Nameplate;
  renderer/profile/layout/assets/policy/import/unrecognized helper changes run.
  Missing source/parser/history uncertainty selects validation.
- **Stage 3:** #462 entire-PR diff skips the two APK builds; relevant payload,
  packaging/resources/dependencies/build/generator/self changes run. Stats and
  shared-host exceptions require structural/content proof and small bounded
  literals; additions/unknown payloads fail closed. Manual is full.
- **Stage 4:** audited provider fingerprints keep JVM delegation and durable
  backup delegation safe. Drift/missing candidate/history/parser returns full
  fallback. Unique state/restoration/timing plus all three rendered scenarios
  remain. Only proven queue-body/comment exceptions skip unique work; **ordinary
  Reader Journey Kotlin is not blanket skipped**.
- **Stage 5:** six independent routing outputs preserve ID, EN, Global, Multi,
  local contract and external mirror. ID/EN/Global share setup but retain actual
  builds; Multi remains actual source checkouts. UMA is
  `52185ec4faada143f57dcfb41c6fa49aa346b4db` in `packs.json`, `multi-upstream.json`
  and checkout. `packs.json UMA pin does not match multi-upstream manifest`
  remains a fatal assertion, as do external HEAD equality assertions. #467's
  correction follows the approved Farm pin update, not an arbitrary CI pin.
  Farm sync is separate; only its required-check dispatch/wait/merge guard changes in this closure fix.
- **Stage 6:** P0/P1 PR execution is cheap; six historical JVM classes belong to
  Deep, four persistence classes to Runtime; seven provider fingerprints and
  enabled/package/class checks reject drift. Unique Favourites Preview capture
  remains manual. Stage 6 exact-head Deep run
  [37110380884](https://github.com/Noirero/Miyorare/actions/runs/37110380884)
  includes the 59 tests across those six classes; prior actual Runtime run
  [37074044584](https://github.com/Noirero/Miyorare/actions/runs/37074044584)
  passed all 55 tests in the four byte-unchanged runtime classes. Historical
  actual evidence is distinct from Stage 7 applicable CI.

## Final routing proof — scenarios A–P

These decisions are based on the current functions/tests, not successful runs
on earlier heads being reused as today's status. Stage 7 runs all cheap
regressions; GitHub supplies actual integration evidence for its exact PR head.

| Scenario | Expected/current ownership | Cheap regression proof |
| --- | --- | --- |
| A — docs / metadata | docs/**, root README/license/allowlisted docs skip Deep and instrumentation, specialized heavy jobs; Fast, P0/P1 contract, Runtime classification stay cheap. **Not all metadata is JVM-exempt**: CODEOWNERS/issue templates still inherit existing Deep JVM routing, but add no instrumentation compile. Source-Pack README/attribution skips its heavy subsets. | `test_ci_deep_paths.py` docs/metadata tests; `test_source_pack_paths.py` low-risk tests; P0 docs test |
| B — ordinary app Kotlin | Fast + Deep JVM/Debug compile; Preview only if routed; domain workflows only when their path/contract inputs match, Runtime according to policy. | Deep source/unknown tests; visual unrelated-path and Phase 10 queue-body tests |
| C — bad androidTest Kotlin/Java | Deep Debug/Preview compile catches source/API errors without visual emulator. main hotfix now has identical compile ownership. | Deep compile-contract tests; historical real negatives above; new main candidate test |
| D — owner request non-theme/non-critical | `ci:owner-request` skips core Runtime; Deep and applicable domain evidence remain. Owner label is not a global visual/build bypass. | Runtime owner-request tests; Deep owner cannot bypass compile test |
| E — owner + critical persistence | DB/migration/backup/build/gate inputs force Runtime, even with owner label. | Runtime critical-overrides-owner tests |
| F — user issue | `ci:user-issue` requires Runtime; origin-label conflict also requires it. | Runtime user/conflicting-origin tests |
| G — theme | `ci:theme` or recognized theme path requires Runtime, relevant visual/size/Phase 10 subsets remain independently path-driven. A label alone does not trigger every visual workflow. | Runtime theme tests; visual/theme-size payload tests |
| H — explicit runtime | `ci:runtime-required` forces Runtime, wins over owner. | Runtime explicit-override tests |
| I — Badge/Nameplate visual inputs | Appropriate renderer/profile/layout/assets/policy triggers and classifier choose visual evidence. | visual geometry/binding/import/payload/unknown tests |
| J — generic Stats/loading #462 | Badge/Nameplate emulator and size's two APKs skip after content proof; Deep stays; **Phase 10 can still render** the shared host for its unique whole-screen evidence. | visual real-462 test; size entire-PR real-462 test; Phase 10 host routing test |
| K — Reader Journey theme/size | Payload/theme/build changes run size; Phase 10 state/render as actually classified; backup delegates only with proven durable Runtime coverage. | size payload/build tests; Phase 10 state/theme/backup/drift tests |
| L — ID-only overlay | Only `run_id` among Source Pack heavy subsets; actual UMA/Gekkoushi ID build remains. EN/Global/Multi/mirror do not run solely because staging changed. | Source Pack language-overlay test + preserved external step fingerprints |
| M — packs.json | ID + EN + Global + Multi (all actual consumers); local compatibility/mirror only when their own inputs also change. | Source Pack shared-manifest test; UMA equality and checkout contracts |
| N — unknown relevant | Unknown Deep input compiles/tests; unknown within domain fails closed; missing history/parser/provider drift validates or fails the gate. Unknown outside a specialized domain does not mean every emulator. | each classifier's unknown/parser/history/drift tests |
| O — mixed PR | Whole base-SHA → exact head (or its merge-base diff) selects union across all commits; relevant early commit cannot hide behind last docs commit. | each classifier mixed/history fixture; new main compile test with last commit docs |
| P — manual | Deep/full runtime, full visual, full size, full Phase 10, Source Pack coverage and unique P0 Favourites evidence remain available. Main manual promotion now requires eligible ref + exact SHA + beta ancestry. Branch builds/profiling retain their guards. | each workflow dispatch contract; main real-shell dispatch rejection fixture |

Label semantics are unchanged: Runtime tests force-runtime, theme, critical,
conflicting origin, user issue, owner, then legacy path policy in that order.
Unknown **relevant** input fails closed in its owner. Empty/missing diff is
conservative; invalid ownership/candidate can fail the cheap gate instead of
silently skipping. Source Pack tests/parser failure requests all applicable
subsets and explicitly fails the routing contract; only explicit false skips
expensive steps. Theme Size checks `!= false` after classifier failure. No
classifier queries previous-head status to make a skip decision.

## Main promotion and direct hotfix

Normal path: feature/fix → beta PR → applicable beta CI → protected beta merge →
beta or frozen `release/main-*` PR → main. Gate checks **exact candidate SHA**,
beta containment, identity/launcher and diff sanity. Its stable final check
requires successful classification with hotfix jobs skipped: no repeated Gradle
or emulator for normal promotion. An ancestry failure, rejected source branch,
failed classification or cancelled/failed required hotfix job cannot pass.
Manual promotion retains the same ref/SHA/ancestry checks. README maintenance
dispatch additionally requires the open main PR number, exact candidate SHA and
protected-main base SHA; the run context SHA must equal the checked-out candidate.

`hotfix/* → main` must include exact protected-main base. Since Fast/Deep/Runtime
skip automatic main PR work, existing hotfix JVM runs the full Debug suite and
now also uses Deep's routed Debug/Preview **compile-only** tasks. Existing
unlabelled runtime path policy selects the four persistence/backup classes; a
critical change requires runtime. This lane does not apply beta's owner-label
skip. Hotfix API 35 is **not pinned to Runtime's emulator-build number**; it is
an independent lane, not asserted device-equivalent evidence. Identity/launcher
checks remain cheap. All hotfix evidence must finish successfully before the
same required check can pass.

The gate trusts protected-beta provenance; it does **not** separately enumerate
or attest every candidate's previous CI run. Rulesets are non-strict about
up-to-date base. Those are existing policies, not new guarantees from this audit.
A production hotfix may also need specialized evidence not triggered by
beta-only visual workflows; select existing manual diagnostic owners explicitly.
This audit does not widen their triggers or claim all UI risks are covered by
four persistence tests.

## Concrete defects fixed in Stage 7

| Defect proven before editing | Before | After / regression |
| --- | --- | --- |
| Manual main gate candidate bypass | Any workflow_dispatch ref chose promotion before checking exact HEAD or ancestry | Eligible beta/release ref, full SHA and beta containment required on manual too; real local Git fixture rejects arbitrary/diverged refs and mismatch. |
| Direct-main instrumentation compile orphan | Core Deep skips main; hotfix only ran testDebugUnitTest; unrecognized androidTest source also need not activate Runtime | Hotfix reuses **unchanged Deep router** and exact base→head inputs for Debug Kotlin/Java and separately Preview compilation; mixed-commit and command contracts in new test. No APK/emulator added by compilation. |
| Direct-main launcher isolation orphan | Fast skips main; Identity Guard bypasses hotfix; gate checked labels/IDs but not launcher separation | Existing Fast launcher assertions also run in candidate classification; equality regression prevents drift. No source/assets/identity changed. |
| Candidate evidence command substitution | Backticks inside double-quoted echo tried executing SHA/ref, leaving empty fields | Literal printf format + environment ref records exact SHA/branch; real shell fixture requires zero stderr and actual SHA. |

`test_main_candidate_contract.py` executes the **actual embedded gate scripts**
against temporary local bare origins/candidates, and tests required-result
failure behavior. CI Fast invokes it without Gradle. Only these concrete
workflow defects are changed; no release publication/version/signing policy,
Stage 1–6 provider fingerprint or label/ruleset is edited.

## Identity, active dependencies and required-check audit

Repository-wide names/filenames/`needs`/dispatch/README/docs searches distinguish
historical descriptions from live callers. No workflow_run/workflow_call caller
or dangling needs is present. References to deleted backup/chapter/old Badge/
Betawi/theme-regression workflows occur in **historical CI_AUDIT.md**, now marked
historical. Source Pack release-seal filename references describe the **external
Source-Packs repository's** provenance workflow, not a missing local workflow.
`runtime_smoke_paths.py` is still tested by Fast, but
`beta-main-runtime-smoke.sh` has **no current beta workflow caller**; its older
upgrade-install evidence must not be claimed for the new cheap promotion lane.
Names retained for all 29 workflows/jobs; current counts cannot be inferred from
main's older workflows or historical Actions run names.

Read-only GitHub ruleset metadata:

| Branch | Ruleset | Required contexts |
| --- | --- | --- |
| beta | 23589709, active | `verify-identity`, `Fast`, `Deep` (GitHub Actions integration 15368) |
| main | 24291198, active | `verify-identity`, `Verify beta is safe to promote` (integration 15368) |

Both prevent deletion/non-fast-forward changes and require PR/conversation
resolution; required approval count is zero, strict-required-status policy false.
No ruleset changes. Legacy branch-protection endpoints returned **403** due
integration permissions, so additional legacy protection cannot be inferred.
Workflow collection API was unavailable; repository beta workflow definitions
are the inventory source. Manual/scheduled/release control-plane execution uses
the default branch; main has not yet adopted the beta tree. This matters for the
default-branch cutover described below.

## Resolved closure blockers and default-main cutover

| Operational blocker | Narrow replacement path | Evidence / intent preserved |
| --- | --- | --- |
| README automation created main PRs rejected by the candidate gate and did not explicitly obtain the required promotion check | Existing main gate has a third `maintenance` mode; both callers explicitly dispatch Identity Guard and the same main gate on the automation branch, with PR number, exact head SHA and main base SHA | Actual embedded gate/orchestration tests; no generic automation bypass; post-release still merges, stats still leaves its validated PR open. Stable release/tag/commit/Main Build/source SHA/APK/hash assertions are byte-unchanged. |
| Farm dispatched only Identity/Source Pack, omitting protected beta's Fast/Deep | Existing Farm dispatches Identity Guard, CI Fast, CI Deep and Source Pack Check, waits all four exact runs, then rereads PR head before head-matched merge | Actual embedded dispatch/wait/merge tests; no fake statuses, classifier exception, direct beta push or registry/membership/pin/consumer change. |

Maintenance permits **only** `automation/release-readme-*` and exact
`automation/readme-release-stats`. Arbitrary `automation/foo`, docs, feature and
fix branches remain rejected. The same contract applies on main PR events and
explicit dispatch: live same-repository **open PR to main**, full exact SHA,
checkout HEAD = PR head = dispatch context SHA (for manual runs), exact PR base
= current fetched protected main, and base ancestry. The entire base→head diff
must be exactly a modification to existing regular-file **README.md**, retaining
mode 100644. Empty, deleted, renamed, symlink/executable, mixed or non-README
changes fail the gate. A stale/diverged main base fails; rebuild the automation
candidate from current main rather than bypassing this invariant.

Required `Verify beta is safe to promote` explicitly distinguishes promotion,
hotfix and maintenance; unknown mode fails. Maintenance requires successful
classification and skipped hotfix JVM/runtime jobs. It adds no Gradle, emulator,
APK or visual work. `verify-identity` remains required and is obtained by actual
Identity Guard dispatch. Automation uses its existing token; successful automatic
PR checks cannot be assumed (token-created PR runs may require approval or not
execute), so explicit dispatch owns the required evidence.

All three callers bind run lookup to **workflow filename + branch +
workflow_dispatch event + exact head SHA**, and a creation time at or after
dispatch. Bounded lookup fails on missing/API-invalid runs; watch and explicit
completed/success/SHA checks reject cancelled/failed/wrong-head results. Auto-merge
requires all intended validations and a fresh matching PR head, then
`--match-head-commit` makes the head condition atomic at merge. No admin/ruleset
bypass is used. Stats dispatches/waits the same checks but does **not** merge.
Its existing branch-update intent uses an explicit lease against the observed
remote tip. Post-release's PR step now has its own token environment; stats gains
only the Actions write permission needed for dispatch.

Fast/Deep dispatch support and coverage remain byte-unchanged. Existing manual
Deep asks for full JVM + Debug/Preview instrumentation **compile**; the ordinary
PR classifier already asks for all three for Farm's actual `packs.json` diff.
This is the same coverage on that input, not an emulator/APK/visual request.
Stage 5 ID/EN routing still decides its own actual upstream builds.

**Control-plane cutover:** fetched default `main` at
`33a9d10138e5032318dd3b24fa3af43271cbad20` still has the older automation/gate.
Before promotion, scheduled/release automation does **not** use these fixes;
old README/Farm required-check gaps can still stall protected merges. A green
beta PR is not deployment. Normal protected beta→main promotion must carry the
updated gate, both README callers, Farm caller and associated cheap tests
**together**; no partial gate/caller rollout. After that promotion, the next
scheduled/release/manual invocation uses the new default-branch control plane,
and README branches inherit the new dispatch input contract. This task performs
neither promotion nor production README/Farm mutation. Deterministic local
orchestration plus actual beta PR CI are its evidence, not a claim that scheduled
production mutations were executed.

Other limitations: external moving-main contract comparisons test upstream at
execution time; network/Maven/JitPack/system-image availability can fail a valid
candidate; golden artifacts are not universally pixel-diff assertions; size is
a delta baseline without a budget; runtime's four persistence classes are not a
universal device suite. Manual Favourites/build/profile/benchmark/visual evidence
was **not executed** by this audit. Historical actual artifacts are labelled as
historical. None of these facts is silently promoted to exact Stage 7 evidence.

## Validation and future change procedure

Closure-fix cheap suite: **189 unittest tests passed**, plus each executable
`test_*.py` entry point and local Source Pack tooling tests. All 29 YAML documents
parse; jobs/needs/local actions are checked; shell blocks are syntax-checked;
`git diff --check` passes. The new gate's scripts are tested in real local Git
fixtures; no local Gradle/emulator is used to classify routes. Stage 7's changes
to CI code properly select actual Deep JVM and Debug/Preview compile work on
beta. Final PR description/report records **exact final head** and actual Actions
results; it must not claim CLOSED while those checks remain pending/red.
The main entry point additionally runs ten actual orchestration test methods
covering all four Farm dispatches, both README routes, missing/old/wrong-SHA runs,
failed/cancelled results, changed heads and atomic merge races. Seventeen main
candidate tests cover all three lanes, strict whole-diff/base/PR binding and
required-result logic. Source Pack engine/tooling tests and unchanged Stage 1–6
provider contracts pass without updating any provider fingerprint.

For future changes:

1. Identify risk, actual inputs, test/configuration and evidence needed; consult
   the ownership table before editing CI.
2. Prefer Fast, Deep, Android Runtime, existing visual or domain-specific owner.
   **Do not create a workflow merely because a new bug/feature exists.** A new
   workflow needs a genuinely uncovered failure domain that existing pipelines
   cannot represent, with its lifecycle/permissions/device/evidence rationale.
3. Keep whole-PR exact candidate identity, mixed-input union and fail-closed
   parser/history/provider-drift contracts. Regression tests must protect the
   replacement provider before delegating execution; never delete test source
   or reduce coverage for speed.
4. Audit required check names, active callers, default-branch control planes and
   token-created PR dispatch behavior before changing workflow identity/routing.
5. Regenerate this inventory from the final workflow files (YAML string loader
   preserving `on`) and review hashes/events/jobs against the original baseline;
   update ownership/routing and known limitations. Test cheaply first, then wait
   for actual applicable CI on the exact final head. Do not force irrelevant APK
   or emulator jobs for a docs-only change.
6. Preserve independent mutation/release/maintenance boundaries. Rulesets,
   signing/app identity and production semantics require their own authorization;
   CI audit is not permission to change them.

Historical [initial audit](CI_AUDIT.md),
[Stage 1](ci-android-test-compile.md),
[Stage 2](ci-exclusive-visual-routing.md),
[Stage 3](ci-theme-size-routing.md),
[Stage 4](reader-journey-phase10-validation.md),
[Stage 5](CI_SOURCE_PACK_STAGE5.md),
[Stage 6](CI_P0_P1_ACCEPTANCE_AUDIT.md) retain provenance. This is the current
inventory/ownership baseline; closure status above is conditional on exact-head CI.
