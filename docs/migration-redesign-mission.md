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

## 8. Current Checkpoint

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

## 9. Next Intended Action

Begin **P1 — Migration Data Integrity** with a targeted ownership/persistence audit and regression probes for the migration contract, then implement the smallest correct fixes needed to satisfy P1. Temporary bugs found during that work must follow the Detour Rule and return to P1 until its Definition of Done is met.
