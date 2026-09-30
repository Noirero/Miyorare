# Miyorare Development Pipeline

This document defines the development and release contract while CI is consolidated. It does not
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
Relevant source compiles and cheap identity/config/static/targeted unit/contract checks pass.
No emulator and no release APK are required.

### DEEP GREEN
Full applicable regression, integration, persistence-logic, source compatibility, and cross-subsystem
contract tests pass.

### RUNTIME GREEN
Required emulator/instrumentation/UI/golden evidence passes for runtime-sensitive changes.
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

During CI consolidation, migrate one coverage family at a time. First add the replacement, run it,
compare its coverage/evidence with the predecessor, then remove the old mechanism in a later proven
step. Never improve CI speed by disabling a failing test.

## Risk-proportional pull request routing

Pull requests use conservative change classification so validation cost follows change risk without reducing coverage. Documentation-only and explicitly allowlisted repository metadata changes keep cheap structural/identity checks but skip Gradle-heavy Deep/P0-P1 work and Android emulator runtime acceptance. Code, tests, Android resources, build configuration, persistence/migration areas, mixed changes, unknown paths, and empty classifications fail closed into the broader relevant gates. Manual validation remains available when a full gate is required explicitly.

Release candidates are separate from ordinary pull-request routing: promotion and official build/release workflows retain their dedicated release checks, signing policy, identity guards, and runtime acceptance.

## Final CI architecture

The consolidated pipeline is intentionally layered rather than workflow-per-bug:

```text
PR beta/main
  -> CI Fast
  -> CI Deep when change risk requires broad JVM regression
  -> Android Runtime when Android/runtime/persistence risk requires emulator proof
  -> relevant area-specific visual/source checks only when their paths apply

release candidate -> Beta to Main Release Gate -> main
beta -> manual Beta Build
main -> manual Stable Release
scheduled/external work -> maintenance/source-pack workflows
```

Historical targeted JVM workflows must not be reintroduced merely to rerun tests already owned by CI Deep. A new specialized workflow is justified only when it provides a distinct execution environment, external-system contract, artifact, branch operation, or runtime/visual evidence that cannot be represented by the existing layered gates.

The final architecture preserves application behavior: CI consolidation does not authorize changing production UI/features, persistence semantics, application IDs, signing, release identity, or Source Pack compatibility behavior.

