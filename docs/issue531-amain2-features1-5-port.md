# Issue #531 — selective Amain2 port

Integration starts at `Amain2@004fe210c86cdd6721657d9e7566083ee5831d2f`.
The behavioral reference is `beta@1e687d3cdfdd9c1caa882aeb1947f1085f66a3f1`,
including the cumulative accepted implementation of #556, #560 and #568.
No beta merge or historical cherry-picks are part of this branch.

The port preserves the final source-date prediction algorithm and compact header,
the combined Library/Downloaded badge and noncompounding selection presentation,
nullable integer chapter ratings 1–5 and local notes, current-title Search Notes,
structured chapter-number formatting, and reversible chapter collapse.

## Reconciliation

Details Activity, Screen, ViewModel and the chapter pipeline retain Amain2's
existing tracker callbacks, lifecycle, privacy/session guards, recommendation
navigation, MangaUpdates progress/volume actions and on-device behavior. Only
chapter prediction, annotations, note search and rendering are added. Collapsing
omits rendered chapters and controls; the underlying chapter collection and
post-chapter tracker sections retain their existing ownership.

Overlapping chapter adapters preserve Amain2's Local download restrictions,
EPUB display restrictions, storage events and deletion behavior. The grid adapter
preserves Amain2's appearance and geometry implementation. Unrelated beta theme,
Reader Journey, library sync, Local/Reader, cache and CI changes are excluded.

## Persistence

Amain2 starts at Room version 48; beta's unrelated versions 49–51 are not imported.
`Migration48To49` adds only `chapter_personal`, with the same table definition as
beta's annotation migration. All existing Amain2 migrations and tables remain.
There is no destructive fallback. Annotation identity remains manga ID plus source
and chapter locator, independent of replaceable chapter-cache rows. Cleanup retains
annotated manga; explicit owner deletion cascades.

Native ZIP backup adds optional annotation fields, including annotations without
chapter cache. Older backups do not erase annotations. Private annotations follow
the existing Private opt-in. The Mihon interchange format remains unchanged and
does not carry personal chapter annotations. No tracker/cloud annotation sync is
introduced.

## Validation

The existing Amain2 PR workflow is unchanged. `ci:user-issue` identifies the task;
`ci:runtime-required` explicitly selects that workflow's Android 15 runtime job.
Its existing tracker, chapter-persistence and backup classes run together with the
ported regressions, preserving all #563 coverage. Added full-screen Details
coverage exercises collapse/expand, note search, personal actions, Characters,
Staff and recommendation navigation. The migration regression also retains all
eight MangaUpdates association fields, including a full-width provider ID, across
upgrade and reopen.

Local validation includes diff/whitespace review, changed-resource XML parsing,
29 existing Python CI/helper tests, migration SQL integrity checks and source
parity/preservation checks. Local Gradle could not download its distribution;
application compilation and emulator evidence must come from exact-head CI.
Final SHA, workflow links and results are recorded in the PR description.

Physical-device acceptance remains with the owner: card status/selection recycling
and cover controls; prediction fit at narrow widths and large fonts; integer
rating/note edit, clear and reopen; Search Notes entry, matching, clear and Back;
chapter numbering and repeated collapse/expand; tracker people/recommendation
navigation; and authenticated MangaUpdates association, existing-state adoption,
progress, volume, rating and logout. CI fixtures do not establish live-account or
physical-device acceptance.
