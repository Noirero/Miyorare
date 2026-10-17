# Miyorare Migration Redesign Mission

Status: ACTIVE / MISSION LOCKED
Target integration branch: `Amain2`

## 1. Mission

When a manga becomes unusable because its current source/extension or upstream website is broken, unavailable, or no longer usable, Miyorare must be able to find the same manga in another source and migrate the user's existing manga state to that replacement safely.

Migration must feel like the same manga changing source, not like deleting the old manga and adding an unrelated new one.

The workflow must remain understandable and practical whether the user is migrating one manga or a large collection.

## 2. Locked Product Contract

The following is the owner-approved target contract. It must not be weakened or silently changed by implementation detours, regressions, CI failures, or newly discovered bugs. Any product-level change to this contract requires an explicit owner decision.

Migration should preserve, where applicable and technically valid:

- Favourite/library membership and all favourite categories.
- Private membership according to existing privacy semantics.
- Reading history.
- Reading progress and resume state.
- Bookmarks.
- Manga-level notes and ratings.
- Per-chapter notes and ratings.
- Manga-specific reader profile/settings.
- Tracker/scrobbling state that can be migrated safely.
- Download ownership/readability and other user-owned local state where safe.
- Other user metadata discovered during the data-ownership audit that belongs to the migrated manga.

Migration must be non-destructive by default:

- A source change must not silently delete user data that cannot be mapped.
- Existing downloaded files must not be deleted or physically moved merely because the remote source changes.
- Failed or partial migration must not leave the user in a worse state than before the migration without an explicit, recoverable contract.

## 3. Matching Contract

A matching title alone is not sufficient proof that two entries are the same manga.

Candidate matching should use the strongest available evidence, such as canonical/alternate titles, author/artist or other metadata, stable source identity, chapter structure, known URLs/identifiers, and other reliable signals available in Miyorare.

The user-facing result should be classified into simple states:

- **Ready to migrate** — confidence is high enough for safe batch handling.
- **Needs review** — there are plausible candidates, but automatic selection is not sufficiently safe.
- **No replacement found** — no candidate meets the required confidence.

Ambiguous candidates must remain user-reviewable. Automatic migration must not rely on title equality alone.

## 4. UX Contract

### Small migration

For one or a few affected manga, the flow should be direct and simple: show the affected manga, proposed replacement, confidence/state, what user data will be preserved, and a clear migration action.

### Large migration

For many affected manga, the UI must support batch handling without forcing the user to inspect every manga individually.

The summary should separate at least:

- Ready to migrate.
- Needs review.
- No replacement found.

Safe high-confidence items may be migrated as a batch. Ambiguous items remain available for manual review.

The feature should use user-understandable migration language rather than exposing implementation-centric concepts such as a generic `Fix` action as the primary mental model.

## 5. Phase Map

This is the persistent direction of work. Bugs may create temporary detours, but they do not change the next intended phase.

### P1 — Migration Data Integrity

Goal: establish and enforce the complete user-data migration contract.

Definition of Done:

- All user-owned state included in the approved contract has an explicit migration/preservation policy.
- Mappable state is migrated correctly.
- Unmappable state is not silently destroyed.
- Partial/interrupted migration has defined safe behavior.
- Download/local ownership is preserved or reconciled without destructive file operations.
- Relevant persistence, privacy, transaction, lifecycle, and restart behavior is validated.

### P2 — Matching Engine

Goal: reliably identify the same manga across sources and produce defensible confidence/review states.

Definition of Done:

- Matching does not depend on title equality alone.
- High-confidence automatic candidates have evidence-backed criteria.
- Ambiguous cases are surfaced for manual review.
- False-positive and false-negative regression cases are covered.

### P3 — Single/Small Migration UX

Goal: make migration obvious and low-friction for one or a few manga.

Definition of Done:

- User can understand source problem, replacement, confidence, preserved data, and final action without knowing internal migration architecture.
- Failure/review states are understandable and recoverable.

### P4 — Batch Migration UX

Goal: make migration practical for large collections.

Definition of Done:

- Large affected sets are summarized by migration state.
- Safe items can be handled in batch.
- Review-required and unresolved items are easy to filter and revisit.
- Batch operations retain the same data-integrity guarantees as single migration.

### P5 — End-to-End Hardening

Goal: validate the complete migration experience under real failure and lifecycle conditions.

Definition of Done:

- Regression coverage spans matching, migration, persistence, download/local ownership, privacy, interruption/restart, and UI state.
- Exact-head validation/CI is green for the completed implementation.
- Final audit confirms the implemented behavior still satisfies this Mission and Product Contract.

## 6. Detour Rule

A newly discovered bug may interrupt the current task only as a scoped detour.

Every detour must record:

- **Reason** — why the detour is necessary.
- **Scope** — what may be changed.
- **Do Not** — nearby systems that must not be broadened into the detour without evidence/authorization.
- **Return To** — the exact phase/task checkpoint to resume after the detour closes.

Closing a detour does not close the parent phase. The parent phase is closed only when its Definition of Done is satisfied.

## 7. Authority and Drift Prevention

- Repository state is the implementation source of truth.
- This document is the persistent product-direction source of truth for the Migration Redesign workstream.
- The latest explicit owner instruction has highest authority.
- Bugs, CI failures, implementation convenience, model changes, chat changes, or agent changes must not silently rewrite the locked product goal.
- Product-scope changes require an explicit owner decision and must be reflected here before subsequent implementation depends on them.
- Before beginning a new phase, re-read the Mission, Locked Product Contract, current phase Definition of Done, and current checkpoint.

## 8. Delivery Gate

Every Migration pull request must follow this mandatory loop and closure sequence:

1. Run the required exact-head CI/checks for the current PR head.
2. If CI/checks are red, audit the failure and determine the real cause.
3. If the failure belongs to the current change and is within scope, fix it on the same branch/PR, then run the relevant exact-head validation again.
4. Repeat the red → root-cause → fix → validate loop as many times as necessary. A red PR must not be abandoned merely because validation failed.
5. Only when the required exact-head CI/checks are green, perform a full diff review and self-audit of the exact PR head.
6. Perform the final post-CI audit, including PR/base/head state, mergeability, exact-head checks, review/comments/threads, base drift, and any relevant source changes.
7. Merge only after the PR remains clean after the final post-CI audit.
8. Perform post-merge verification on the target integration branch, confirming the expected merge commit/content is present and no unexpected branch movement or regression invalidates the closure.
9. Close the completed work with a short **Closure Checkpoint** that records:
   - what was completed;
   - the final integration branch HEAD/state;
   - validation/post-merge verification result;
   - whether the parent phase is complete or still active;
   - the exact `RETURN TO` point if the completed work was a detour;
   - any remaining blocker/risk relevant to the mission.
10. After the Closure Checkpoint, propose the **single next recommended Migration step** and explain briefly why it is next.
11. **STOP and wait for explicit owner approval before starting that next task, detour, or phase.** Standing merge authorization for Migration PRs does not authorize automatic start of the next logical work item.

Operational shorthand:

`CI PROCESS → RED → ROOT-CAUSE/FIX → CI PROCESS → RED → ROOT-CAUSE/FIX → ... → GREEN → FULL DIFF/SELF-AUDIT → FINAL POST-CI AUDIT → MERGE → POST-MERGE VERIFICATION → CLOSURE CHECKPOINT → PROPOSE NEXT STEP → OWNER APPROVAL → NEXT WORK`

A green CI result alone is not sufficient closure. Merge completion alone is also not sufficient closure. If CI is red, the current PR remains the active task until the failure is resolved, proven unrelated/infrastructure-only under the governing rules, or a genuine blocker makes progress impossible. If a blocker prevents merge or post-merge verification, the work remains open at that gate. After successful closure, the next Migration task still must not begin until the owner approves the proposed next step.

## 9. Current Checkpoint

Mission and product contract: LOCKED by owner.

Current phase: **P1 — Migration Data Integrity**.

P1 implementation work has not started under this mission document yet.

Known read-only audit evidence from the current Amain2 baseline before P1:

- The existing migration path is built around Migration UI → `AutoFixService` → `AutoFixUseCase` → `MigrateUseCase`.
- Existing migration already handles several state classes, including favourites, private favourites, reading/history-related state, manga preferences, trackers, manga-level notes, and reader profile state.
- Per-chapter note/rating persistence is not currently migrated by `MigrateUseCase`.
- Existing bookmark migration can drop bookmarks that fail exact chapter mapping, because unmatched entries are omitted before old bookmarks are deleted.
- Download ownership continuity across a migrated manga ID requires targeted proof; current audit found a strong risk because download ownership is ID/index based and is not explicitly migrated in `MigrateUseCase`.
- Source availability classification in the current Migration UI is not yet fully provider-neutral across all supported source families.

These findings are starting evidence for P1/P2. They do not change the Mission and must be revalidated against the exact current repository head before implementation.

## 10. Next Intended Action

After this delivery-gate document change itself is fully closed through green exact-head CI, full diff/self-audit, final post-CI audit, merge, post-merge verification, and Closure Checkpoint, propose **P1 — Migration Data Integrity** as the next work item. Do not begin P1 until the owner explicitly approves that proposal.
