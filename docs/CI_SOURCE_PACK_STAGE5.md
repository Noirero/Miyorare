# Stage 5 — Source Pack validation family

Audit baseline: `beta` commit `bd1d189973661ddc96d60b8fd591144d3904c595`.
Result: **partial consolidation**. Keep four workflow/check identities and their
distinct lifecycles. Share the local classifier and the identical build toolchain;
skip unrelated external/build steps within the existing checks. No validation is
moved into app CI. Farm Membership Sync remains a separate, unchanged automation.

## Lifecycle, security and execution audit

| Workflow | Events and original routing | Permissions / concurrency / timeout | Checkout and toolchain |
| --- | --- | --- | --- |
| Miyorare Source Pack Check | PR to `beta`: entire staging directory and own workflow; manual. No push, schedule or workflow_call. | `contents: read`; `miyorare-source-packs-${github.ref}`, cancel older run; no explicit timeout (GitHub default). Matrix fail-fast false. Legacy fix/bugfix/hotfix branch bypass. | Original app checkout used PR merge ref. Two jobs ID/EN resolve UMA and Gekkoushi commits from `packs.json`, then depth-1 checkout InvalidDavid/UMA and Gekkoushi/plugin-source. Temurin 17, setup-gradle v6 / Gradle 8.14.3, setup-android v4 / platform-tools; runner Python. |
| Miyorare Global Source Pack Check | PR and push to `beta`: entire staging directory and own workflow; manual. No schedule or workflow_call. | `contents: read`; `miyorare-global-source-pack-${github.ref}`, cancel older run; no explicit timeout. | Original app checkout used PR merge ref. Gekkoushi/plugin-source at literal `9aa770b7cb699c6385f574f13710990e17ef5517`, depth 1. Identical Java/Gradle/SDK setup; runner Python. |
| Miyorare Multi-Upstream Check | PR to `beta`: sources/compat production/tests, both manifests, verifier, staging README and own workflow; manual. No push, schedule or workflow_call. | `contents: read`; `miyorare-multi-upstream-${github.ref}`, cancel older run; no explicit timeout; FORCE_JAVASCRIPT_ACTIONS_TO_NODE24=true. | Original app checkout used PR merge ref. InvalidDavid/UMA at literal `a0ebc9ffc7de02b7b50cbcb620092d0a9263c785`, keiyoushi/extensions-source at literal `2025891752e4705effc28e87a043f7205014cde3`. Default checkout depth 1. Runner Python; **no Gradle/JDK/SDK build**. |
| Source Pack Compatibility Contract | PR to any branch and push to `main`: contract, tsuki/**, release/readiness/profile/version/Farm workflows and helpers/tests, own workflow; manual. No schedule or workflow_call. | `contents: read`; no concurrency or explicit timeout. Legacy fix/bugfix/hotfix branch bypass. | Original app checkout used PR merge ref. Sparse checkout Noirero/Miyorare-Source-Packs **moving `main`**, only compatibility JSON, cone mode false. Runner Python/bash/cmp/grep, no Gradle/JDK/SDK. |

All four use checkout v5 and upload-artifact v4 where present. There is no
custom token or secret input: checkout uses the implicit read-only GITHUB_TOKEN.
All are read-only validations of repository/external state. Preparers, overlays,
structural adaptation and JAR finalization write only disposable checkout/build
directories. None commits, pushes, publishes or synchronizes remote state.

Build workflows need GitHub source downloads plus upstream Gradle wrapper,
Android/toolchain and Maven/JitPack dependency resolution. Multi-Upstream needs
the two real external source checkouts but never fetches provider websites.
Contract needs its real external sparse checkout; helper tests use local
fixtures. Global syntax-checks the differential diagnostic module, but does
**not** execute that diagnostic's website requests or consume its cookie envs.
Source icon extraction records URLs without downloading images or pages.

## Coverage matrix and retained owners

| Category | Steps / assertions | Owner after Stage 5 |
| --- | --- | --- |
| A — shared orchestration | Exact app checkout, local base-to-head classification, routing regression tests, candidate summary | Each existing job calls `source-pack-routing`; shared Python classifier. No emulator, Gradle, API query or network request inside classifier. |
| A — identical build setup | Temurin 17 + Gradle 8.14.3 + SDK platform-tools | `source-pack-toolchain` composite, called separately by ID/EN and Global jobs. Versions and setup options unchanged. |
| B — ID/EN pack contract | Resolve manifest pins; ID HoloToon overlay; curated UMA preparation; independent UMA build/finalization; Gekkoushi preparation/build/finalization; logical manifest; HoloToon in ID and absent from EN | `build-pack` / `Build Miyorare-id` and `Build Miyorare-en`. Every old validation command preserved. |
| B — local consumer/release contract | Contract schema/identity/digest/rollback/downgrade/held invariants; Tsuki source wiring; stable signing-before-build readiness ordering; immutable release lock and provenance; release/README/profile safety assertions; Farm source-wiring assertions | `contract`, local assertion step. Existing readiness, release-version and Farm engine suites retained. Their fixtures are local; the production Farm engine is **not invoked on repository manifests**. |
| C — Global Source Pack | Apply only `all` overlays; syntax/pagination regression; EXHENTAI canonical gallery/pagination patch; real Gekkoushi build; global shard/logical finalization; exact EXHENTAI/GELBOORU set, global-single-owner and all 18 language presets | `build-global` / `Build Miyorare-Global`. |
| D — multi-upstream compatibility | Verify exact SHA checkouts, manifest schema, UMA pack pin equality, official selected filename/pluginId/language, UMA annotation/domain, Keiyoushi module name/lang/host/deterministic or explicit source ID, canonical/ID uniqueness | `verify` / `Verify Keiyoushi + UMA source intake`. Actual UMA and Keiyoushi trees remain required; no local approximation. |
| E — unique external build evidence | KSP enum/summary match, metadata compiled/exposed/hidden coherence, nonempty dexed JAR, upstream license/provenance embedding, SHA-256, no logical runtime-key overlap | ID/EN and Global preparers/finalizers and the existing staging artifacts. These are different pack configurations, not interchangeable builds. |
| E — unique external contract evidence | Byte-identical local JSON versus Source-Packs `main`; mismatch fails with diff | Existing `contract` job, separately gated mirror checkout and cmp. Moving main tests the state at execution time, not an immutable producer revision. |
| F — mutation/sync | Farm schedules/dispatches, exact snapshot fetch, materializes membership, commits/pushes, opens/closes/merges PRs, dispatches and waits for Source Pack Check | **Unchanged** `miyorare-farm-pack-membership-sync.yml`. Never merged into validation. Contract only inspects its source and tests its pure engine with fixtures. |

ID/EN `prepare_pack.py` prunes the disposable UMA tree to the selected language
and filenames. `prepare_gekkoushi_shard.py` retains the upstream compiled support
set and exposes matching-language IDs minus UMA-owned IDs. Global imports
`collect_sources` from that same module, which imports `source_icon_metadata`:
both modules therefore protect **all three builders**. Only the requested
language's first-party overlays are applied to each disposable tree. Global
applies `all` overlays separately; ID/EN do not consume those local overlays.

Multi-Upstream reads `packs.json` schema, UMA repository/commit and selected
pack pluginId/language/sources. All `packs.json` changes remain conservatively
routed to all builders plus Multi-Upstream, including malformed/deleted inputs.
No field-level JSON classifier or projected substitute weakens these assertions.
Its optional structural adapter requires both `source-packs/tools/...` and an
existing semantic report; this workflow checks out neither, so the existing
optional path remains absent. It is not removed or simulated by Stage 5.

## Routing contract

| Changed input | Required subset |
| --- | --- |
| ID / EN `.kt` overlay | That language's complete UMA + Gekkoushi build/finalization only |
| Global `.kt` overlay, global prepare/finalize/pagination/diagnostic tool | Global only |
| Curated UMA preparer or ID/EN shard/logical finalizer | ID and EN |
| Shared Gekkoushi discovery or icon extraction | ID, EN and Global |
| `packs.json` | ID, EN, Global, Multi-Upstream |
| `multi-upstream.json`, intake verifier, app sources/compat or its tests | Actual Multi-Upstream validation |
| Readiness/version/profile/Farm workflow/helper/tests or source-pack details fragment | Local contract suites/assertions; no producer checkout/build |
| Consumer contract JSON or unknown tsuki runtime input | Local contract and actual producer mirror comparison |
| A workflow's own file | Full coverage owned by that workflow |
| Shared classifier, regression suite or routing composite | All six outputs true; full family where the existing event/branch lifecycle applies |
| Toolchain composite | All builders |
| Staging README/ATTRIBUTION, unrelated docs/metadata/app/Gradle files | No Source Pack upstream checkout, toolchain setup or build |
| Unknown staging input, unknown Source Pack CI helper/contract input, invalid diff path | Full conservative decision |

Broad staging event reception is retained/added to catch unknown future inputs;
the cheap classifier gates expensive steps. A known local contract-only change
may start cheap wrappers but cannot activate unrelated upstream validations.
App Gradle files are not inputs to the independent external build trees and were
never covered by these workflows; their existing app CI is unchanged.

Checkout is pinned to `pull_request.head.sha` (otherwise `github.sha`) and uses
full history. A separate identity assertion rejects the wrong checkout before
any validation. The classifier compares **event base SHA to exact candidate**,
not last commit and not a previous run. Push uses `event.before`; manual always
requests full owned validation even for docs-only heads. Deletions/renames use
`--no-renames`, preserving old and new paths; mixed changes select the union.
Missing/zero history, unsupported events, decoding/parser failures and empty
unexplained diffs request validation. Classification itself never fetches.

Only an explicit, validated boolean `false` skips a step. Broken/missing Python,
failed regression tests, malformed/incomplete output or parser failure request
full validation. A failed regression suite additionally fails the job after
validation; it cannot silently produce a green skip. Wrong candidate identity
stops with a failing check. The old branch-name bypasses are removed: a risky
fix/bugfix/hotfix PR now gets its applicable coverage just like every other PR.
This increases coverage for previously unprotected relevant fixes, not for
unrelated ordinary changes.

## Evidence and failure semantics

No artifact identity, path or retention changes:

- `miyorare-id-staging`, `miyorare-en-staging`, `miyorare-global-staging`: existing
  output directories, missing files are errors, retention 7 days.
- Their Gradle diagnostics: existing log directory, failure-only, missing files
  warn, retention 1 day. Build pipelines retain pipefail and all buildJar flags.
- `miyorare-multi-upstream-intake`: existing intake JSON, missing files are errors,
  default retention unchanged.
- Contract retains log evidence, diff and nonzero mismatch/assertion failures.

Skipped subsets do not upload nonexistent staging artifacts. Workflow/job names,
IDs, permissions, original event/branch lifecycles, concurrency, matrix and
default timeouts are preserved. External literal refs are unchanged. ID/EN still
resolve its two immutable provider SHAs from the exact candidate manifest.

## Identity/dependency audit

Repository searches covered workflows, scripts, README/badges, docs, compatibility
and staging sources, including workflow_run/workflow_call/needs references.
Farm Membership Sync dispatches and queries `miyorare-source-pack-check.yml`
(baseline lines 216 and 244), then waits for its success before merge. Its manual
ID/EN behavior and filenames/artifacts are preserved. Existing CI_AUDIT.md entries
describe recommendations, not replacement coverage. No workflow/job is renamed
or deleted; no required-check identity removal depends on assumed ruleset safety.
Rulesets and label definitions are not changed. There is no old-to-new check
mapping because each old check continues to own its original coverage.

## Regression and exact-head verification

Run `python3 -B .github/scripts/test_source_pack_paths.py`. The stdlib suite tests
local/global/upstream ownership, mixed PR union, unknown relevant paths, manual
and push behavior, real multi-commit git histories, candidate mismatch, missing
history, deletion/rename, parser failure, failed regression fallback and malformed
classifier output. It locks all original external checkouts, commands, artifact
options and local assertions against baseline fingerprints. Each existing check
executes this cheap suite before choosing coverage; CI Fast/Deep are unchanged.

The Stage 5 PR changes the shared routing and all four workflows, so all owned
validation is requested on its exact head. Review actual jobs, external checkout
steps, staging/intake artifacts and applicable app CI at that SHA. A local test
is not a substitute for those external runs.

Pre-existing issue found during audit: `packs.json` records UMA
`52185ec4faada143f57dcfb41c6fa49aa346b4db`; Multi-Upstream's literal checkout and
`multi-upstream.json` record `a0ebc9ffc7de02b7b50cbcb620092d0a9263c785`.
`verify_multi_upstream.py` requires pack/intake pins to match, so this is an
existing compatibility failure, not a classifier-safe skip. Stage 5 preserves
the refs, manifests and assertion. A deterministic red external check must be
reported as a completion blocker; production manifest edits or a weakened
assertion to make cleanup green are outside scope.
