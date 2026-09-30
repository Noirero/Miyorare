# Miyorare CI Audit

> Baseline: `beta` on 2026-09-30.
>
> Scope: CI/build infrastructure only. No application behavior, persistence, signing identity,
> versioning contract, or source-pack contract is changed by this audit.

## Executive summary

The repository currently has 30 GitHub Actions workflow files. Coverage is broad, but responsibilities
are fragmented across historical phase-specific, feature-specific, visual/runtime, build/release, and
source-pack workflows. Multiple pull-request workflows repeat Gradle setup, JitPack pre-warming,
`:app:testDebugUnitTest`, emulator boot, and artifact upload.

The target is not to remove coverage. The target is to preserve the existing checks while moving them
behind a small set of meaningful CI layers:

1. **FAST** — cheap identity/config/static/compile/targeted contract checks; no emulator or APK.
2. **DEEP** — full unit/regression/integration/source compatibility checks; no release.
3. **RUNTIME** — emulator/UI/golden/persistence evidence only where Android runtime is required.
4. **BETA BUILD** — manual, beta-only signed preview APK.
5. **STABLE RELEASE** — manual, main-only release gate/sign/build/release.
6. **MAINTENANCE** — scheduled/manual upstream/source-pack/repository synchronization.

## Current build configuration findings

- `gradle.properties` currently configures Gradle `-Xmx8192M`, Kotlin daemon `-Xmx8192M`,
  `org.gradle.workers.max=8`, parallel execution, build cache, and configuration cache.
  These values must be benchmarked before changing them.
- Dependency resolution includes JitPack.
- `plugin-source` is pinned to commit `21d4b79b5f`; Tsuki is pinned to `1.0.5`.
- Several workflows pre-warm JitPack with curl/retry/sleep. This is a build-robustness smell, but must
  not be removed until deterministic dependency resolution is proven.
- Debug, preview (Beta), and release identities are intentionally distinct. Preview resolves to
  `org.noirero.miyorare.beta`. These identity/signing contracts are invariants.
- `testBuildType` is configurable so Preview instrumentation can exercise Beta/release sources.

## Workflow inventory

| Workflow | Trigger / role observed | Runtime / artifact | Classification | Migration note |
|---|---|---|---|---|
| backup-restore-regression.yml | PR beta + manual; unit + backup/restore regression | emulator + evidence | MERGE → RUNTIME/DEEP | Unit portion belongs DEEP; runtime persistence stays RUNTIME |
| beta-to-main-release-gate.yml | PR main + manual; promotion verification | conditional emulator evidence | KEEP → RELEASE GATE | Good existing consolidation pattern |
| betawi-build.yml | manual branch build/test | preview APK | MANUAL ONLY | Historical/special branch; keep until branch policy is explicitly retired |
| chapter-persistence-regression.yml | PR beta + manual; unit + persistence regression | emulator + evidence | MERGE → RUNTIME/DEEP | Duplicates setup/unit graph with backup regression |
| downloads-golden-visual.yml | PR beta + manual | emulator + golden evidence | MERGE → RUNTIME | Runtime-only value |
| exclusive-badge-evidence-export.yml | feature-branch push + manual | static evidence artifact | MERGE/DELETE after replacement | Historical feature-branch trigger |
| exclusive-badge-golden.yml | feature push, PR beta, manual | preview emulator + evidence | MERGE → RUNTIME | Preserve visual contract |
| exclusive-badge-ui-smoke.yml | branch push + manual | preview emulator + evidence | MERGE → RUNTIME | Feature-specific runtime workflow |
| exclusive-nameplate-golden.yml | PR beta + manual | preview emulator + evidence | MERGE → RUNTIME | Preserve golden coverage |
| exclusive-navigation-golden.yml | PR beta + manual | multiple emulator cases + evidence | MERGE → RUNTIME | Expensive matrix; path/label gating candidate |
| experimental-build.yml | manual | preview APK artifact | MANUAL ONLY / evaluate DELETE | Must prove whether still used before deletion |
| identity-guard.yml | push main, PR main/beta, manual | none | KEEP → FAST | Cheap invariant guard; remove branch-name bypass during migration only with equivalent policy |
| main-build.yml | manual | signed release APK + GitHub Release | KEEP → STABLE RELEASE | Must remain manual/main-only |
| merge-beta-to-betawi.yml | manual promotion | compile/unit; branch mutation | MANUAL ONLY | Branch-specific operational workflow |
| miyorare-farm-pack-membership-sync.yml | manual + twice-hourly schedule | metadata/evidence | SCHEDULED → MAINTENANCE | Not a PR blocker |
| miyorare-global-source-pack-check.yml | push/PR beta + manual | external pack build + artifacts | MERGE → DEEP/MAINTENANCE | Split deterministic contract from external health |
| miyorare-multi-upstream-check.yml | PR beta + manual | upstream evidence | MERGE → MAINTENANCE/DEEP | External/upstream nature should not slow FAST |
| miyorare-source-pack-check.yml | PR beta + manual | source-pack builds + artifacts | MERGE → DEEP | Preserve source-pack compatibility coverage |
| p0-p1-acceptance.yml | PR beta + manual | unit + multiple emulator/evidence jobs | MERGE → DEEP/RUNTIME | Major overlap with release-gate acceptance |
| pf5-build.yml | manual pf5 | signed preview APK | MANUAL ONLY | Historical/special branch; no automatic APK |
| pf5-fast.yml | push pf5 | compile + targeted unit | MERGE model → FAST | Useful fast-gate pattern; contains JitPack retry |
| post-release-readme.yml | release event | README mutation | KEEP → RELEASE/MAINTENANCE | Post-release responsibility, not CI |
| preview-build.yml | manual | signed Beta APK | KEEP → BETA BUILD | Already manual and beta-only; preserve |
| profile-frame-wave1-actual-ui-smoke.yml | PR beta + manual | unit + preview emulator | MERGE → RUNTIME/DEEP | Historical wave naming; preserve tests |
| profile-frame-wave2-actual-ui-smoke.yml | PR beta + manual | unit + preview emulator | MERGE → RUNTIME/DEEP | Same setup as wave 1 |
| reader-journey-phase10-validation.yml | PR beta + manual | unit + debug/preview emulator matrices | MERGE → DEEP/RUNTIME | Historical phase naming; preserve coverage |
| reader-journey-theme-regression.yml | PR beta + manual | unit artifacts | MERGE → DEEP | No emulator value observed |
| reader-journey-theme-size-baseline.yml | PR beta + manual | preview APK/size artifacts | MANUAL/DEEP | Do not make ordinary PR build APK unless baseline is intentionally requested |
| release-build-profile.yml | manual | release build profile artifact | MANUAL ONLY | Benchmarking tool, not required CI |
| source-compatibility-check.yml | PR beta + manual | targeted unit | MERGE → DEEP | Source compatibility contract |
| source-pack-contract.yml | PR/push main/manual | contract checks | MERGE → FAST/DEEP | Cheap deterministic portions can run early |

## Proven overlap map

### Unit/compile duplication
The following independent workflows invoke broad `testDebugUnitTest` or equivalent unit graphs:
backup/restore, chapter persistence, P0/P1 acceptance, profile-frame wave 1, profile-frame wave 2,
Reader Journey phase 10, Reader Journey theme regression, Betawi promotion/build, and the
beta-to-main release gate. This creates repeated dependency resolution and compilation for checks
that can share one DEEP task graph.

### Emulator duplication
Backup/restore, chapter persistence, downloads golden, badge/nameplate/navigation visual checks,
P0/P1 acceptance, profile-frame wave checks, and Reader Journey validation each own emulator
sessions. Runtime coverage is legitimate, but boot/setup should be consolidated and selectively
triggered. The existing beta-to-main release gate already consolidates several Android 15 acceptance
tests into one emulator session and should be treated as a reference pattern.

### Build/artifact duplication
Preview/Beta, PF5, Betawi, experimental, theme-size baseline, and stable workflows all assemble APKs
for different purposes. Official Beta and Stable builds are correctly manual; ordinary PR validation
must not depend on release APK production.

### External dependency duplication
JitPack availability/pre-warm logic is repeated in build/gate workflows. This should eventually become
a deterministic dependency strategy or shared setup, not a growing set of retry loops.

## Target architecture

### CI Fast
Trigger: PR to beta/main (and selected development pushes if justified).

Responsibilities:
- diff/config/identity guards;
- cheap source-pack/contract validation that needs no network-heavy external build;
- Kotlin/JVM compile relevant to changed code;
- targeted unit/contract tests;
- no emulator;
- no release/preview APK artifact.

### CI Deep
Trigger: PR ready for review, or PR beta/main while resource usage remains acceptable.

Responsibilities:
- full unit regression;
- integration and persistence logic tests that do not require Android runtime;
- source compatibility;
- deterministic source-pack contract checks;
- feature regression suites currently scattered across historical workflows.

### Android Runtime
Trigger: path classifier, explicit label/manual dispatch, or promotion when runtime-sensitive files changed.

Responsibilities:
- connected Android tests;
- UI/golden/screenshot evidence;
- navigation/layout/touch/lifecycle proof;
- persistence tests that genuinely require Android;
- consolidate tests into as few emulator boots as practical.

### Beta Build
Keep `preview-build.yml` semantics during migration:
manual dispatch, beta branch only, permanent signing identity, assemble Preview, checksum/artifact.

### Stable Release
Keep manual main release semantics. Promotion/release gate must be green before stable release.
Signing, applicationId, versioning, release/source-pack readiness remain protected invariants.

### Maintenance
Scheduled/manual upstream/source-pack/farm sync and external availability checks. These should not
become ordinary PR latency unless they validate a deterministic compatibility contract.

## Exact migration order

1. Land this audit and development contract only; no workflow deletion.
2. Establish one FAST entry point by moving cheap identity/config/compile/targeted contracts first.
3. Establish DEEP by collecting existing unit/regression/source compatibility coverage without removing originals.
4. Establish RUNTIME and move one emulator family at a time; compare evidence before deleting its predecessor.
5. Consolidate historical feature/wave/phase workflows only after replacement runs prove equal or greater coverage.
6. Keep Beta Build manual and beta-only throughout.
7. Keep Stable Release manual and main-only throughout.
8. Move external/upstream periodic checks to MAINTENANCE.
9. Update required checks only after stable names and replacement runs exist.
10. Benchmark Gradle current vs workers=4 / Xmx4g / Xmx6g; change configuration only from measured results.
11. Audit JitPack/external dependencies and remove retry/sleep only after deterministic replacement is verified.
12. Consider modularization only after the pipeline is stable.

## Risks and controls

- **Coverage loss:** never delete an old workflow in the same step that first introduces its replacement.
- **Required-check deadlock:** branch protection changes happen only after replacement status names are stable.
- **Runtime blind spots:** only move tests out of emulator when their contract is provably runtime-independent.
- **Signing/identity regression:** Beta/Stable application IDs and signing behavior are invariants.
- **Source-pack regression:** contract/readiness behavior remains unchanged until dedicated tests prove migration.
- **False speed-up:** do not disable tests or convert failures to warnings to improve CI time.
- **Dependency flakiness:** do not simply remove JitPack retries; first make resolution deterministic.
- **Gradle OOM/slowdown:** benchmark resource settings rather than assuming larger heaps/workers are faster.

## Acceptance for Phase 1

- Every current workflow has an explicit disposition above.
- Overlap is documented before deletion.
- Target FAST/DEEP/RUNTIME/BETA/STABLE/MAINTENANCE responsibilities are defined.
- Migration order preserves coverage.
- No application behavior or release identity is changed.
