# Miyorare Development Pipeline

This document defines the development and release contract. The
[Stage 7 baseline](CI_FINAL_BASELINE.md) contains the current 29-workflow inventory,
ownership/routing proof and verified closure routes.
**CLOSED — architecture verified; no further restructuring required.**
Production automation adopts the baseline only after normal beta→main promotion
carries the gate, callers, tests and core CI definitions together. This document does not
authorize changing application behavior, persistence, signing, application IDs, versioning, or
source-pack contracts.

## Feature / bug-fix flow

```text
SPEC
  -> IMPLEMENT
  -> SELF AUDIT
  -> FAST CI
  -> DEEP CI
  -> RUNTIME (only when runtime proof is required)
  -> MERGE
  -> MANUAL BETA BUILD (when device validation is needed)
  -> DEVICE TEST
```

A change is not complete merely because it compiles.

## Release flow

```text
healthy beta
  -> freeze release candidate
  -> promotion/release gate
  -> main
  -> MANUAL STABLE RELEASE
```

Beta may continue development after a release candidate is frozen. Stable release must never be an
automatic consequence of an ordinary commit.

## Green definitions

### FAST GREEN
Cheap diff, identity/config/static and CI-helper checks pass. FAST is intentionally Gradle-free; JVM compilation/tests belong to DEEP so the same PR SHA is not compiled twice.
No emulator and no release APK are required.

### DEEP GREEN
Full applicable regression, integration, persistence-logic, source compatibility, and cross-subsystem
contract tests pass.

Relevant Android changes also compile the complete Debug instrumentation test source set (Kotlin
and Java) in the same Gradle invocation as the JVM suite. Variant/build/unknown changes additionally
compile Preview instrumentation sources in a separately routed invocation. This is compile-only validation: no
emulator, APK assembly, instrumentation execution, or signing credentials are required. See
[Android test compile protection](ci-android-test-compile.md) for routing and variant boundaries.

### RUNTIME GREEN
Android Runtime's four persistence/migration/backup classes pass when required.
Specialized visual/Reader Journey owners provide their own distinct device evidence.
Runtime CI is not required merely because a change exists.

### RELEASE GREEN
Promotion gate, identity/signing/readiness/compatibility checks and all release-critical validation pass.
A release APK is final verification output, not the first debugging mechanism.

## Spec contract

Before implementation, record:

- **FEATURE / BUG:** what is being changed.
- **EXPECTED:** observable required behavior.
- **MUST NOT CHANGE:** invariants and unrelated behavior.
- **EDGE CASES:** important boundaries/failure modes.
- **ACCEPTANCE:** concrete proof of success.
- **TEST PLAN:** tests/checks that enforce the contract.

After implementation, perform SPEC-vs-DIFF audit:

- every requirement is represented in the diff;
- important behavior is locked by tests where practical;
- no unrelated state/persistence/identity behavior changed;
- no hardcoded workaround or duplicate engine was introduced;
- tests validate the contract, not merely the implementation shape.

## Pull-request contract

Every substantial PR should state:

- **WHAT** — what changed.
- **WHY** — problem being solved.
- **IN SCOPE** — intended modifications.
- **OUT OF SCOPE** — deliberately untouched areas.
- **INVARIANTS** — behavior/data/identity that must remain stable.
- **TESTS** — automated validation run or added.
- **RUNTIME PROOF** — whether emulator/device proof is required and why.
- **RISK** — likely regression surfaces.

## CI responsibility boundaries

- FAST must stay cheap and deterministic.
- DEEP owns broad regression/integration work.
- RUNTIME owns checks that truly require Android runtime/UI.
- Beta Build is manual and beta-only.
- Stable Release is manual and main-only.
- Scheduled/external upstream health belongs to Maintenance.
- A new bug or feature does not automatically justify a new workflow file.

## Build-performance rule

Do not tune Gradle from intuition. Benchmark the current configuration against controlled alternatives,
including workers=4 and Gradle heap 4g/6g, recording cold/warm build, compile, unit-test,
assemblePreview, configuration/cache behavior, and memory when available.

## Dependency-robustness rule

Retries and sleeps are temporary safety nets. External build-critical dependencies should be pinned and
reproducible. Do not remove existing safety nets until an equivalent deterministic path has been proven.

## Migration safety

When changing coverage ownership, migrate one coverage family at a time. First add the replacement, run it,
compare its coverage/evidence with the predecessor, then remove the old mechanism in a later proven
step. Never improve CI speed by disabling a failing test.

## Risk-proportional pull request routing

Beta PRs run Fast and cheap classifiers/ownership contracts. Deep owns full Debug
JVM regression plus routed Kotlin/Java instrumentation compilation. docs/** and
allowlisted root documentation skip Deep; other repository metadata may still
run the existing JVM gate while adding no instrumentation compile. Unknown input
is conservative in its applicable owner. Mixed PRs evaluate the whole base SHA
through exact candidate head; the last commit alone never determines risk.

Android Runtime gives precedence to ci:runtime-required, theme label/path,
critical persistence/build/gate inputs, conflicting origin labels and
ci:user-issue. ci:owner-request skips only non-theme/non-critical core Runtime;
it does not suppress Deep or relevant specialized evidence. Labels alone do not
activate every path-filtered visual workflow.

Badge/Nameplate and theme-size classifiers can prove that generic Stats/loading
changes do not need their expensive evidence. Phase 10 retains unique
state/render scenarios and fails closed on delegation-provider drift. Source
Pack independently selects ID, EN, Global, actual Multi-Upstream intake and
local/external compatibility contract. P0/P1 automatically verifies ownership
cheaply; unique Favourites canonical visual capture remains manual.

## Current CI architecture

- **Beta PR:** Fast → relevant Deep/Runtime/domain owners. Independent jobs can
  run concurrently; this is an ownership list, not a serial mega-pipeline.
- **Normal main promotion:** beta or frozen release/main-* candidate must be
  contained in protected beta; existing required **Verify beta is safe to
  promote** checks exact identity/ancestry cheaply. Core beta jobs skip main.
- **Direct-main hotfix:** hotfix/* must contain protected-main base and pay full
  JVM plus routed Debug/Preview instrumentation compile and required persistence
  runtime in the existing main gate. No owner-label shortcut in this lane.
- **Build/release:** beta Preview and main stable release are manual with their
  existing identity/signing/readiness guards. Experimental/PF5 remain separate.
- **Manual evidence/observability:** Favourites, visual diagnostics, build
  profiling and benchmarks remain available; no manual device run is implied by
  a docs audit.
- **Maintenance:** scheduled metrics/stats and separate Farm synchronization use
  their established lifecycles/permissions. Main's older control plane has not
  yet adopted beta. README maintenance accepts only the owned release/stats
  automation branches, an open exact-head/current-main PR, and README.md-only
  changes; both required main workflows are explicitly dispatched and awaited.
  Stats remains validated/open; post-release retains guarded auto-merge. Farm
  waits Identity, Fast, Deep and Source Pack on its exact head before merging.
  The gate and callers must reach default main together through normal promotion;
  existing beta Fast/Deep definitions must also be present on default main for
  dispatch. No production cutover is implied by green beta CI.

Do not create a workflow merely because there is a new bug or feature. First
choose Fast, Deep, Android Runtime, an existing visual owner or an existing
domain-specific owner. A new workflow needs a genuinely uncovered failure domain
that these pipelines cannot represent, with an evidence/lifecycle rationale.
Never reduce coverage, remove test source or weaken fail-closed contracts for
speed. Audit active callers and required-check identity before any migration.

CI changes do not authorize production behavior, persistence, application ID,
signing, version/release semantics or Source Pack functionality changes.
