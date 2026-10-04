# Continue Reading: actual Reader activity

Owner request, non-theme. Base: `beta` at `814ec6c757c56833dd7e9909942aaedbac8b3101`.

## Root cause and invariant

`history.updated_at` is a progress-mutation clock, not a Reader-activity clock. Tracking,
Feed mark-read, mark-completed, and chapter positioning can legitimately create history
without a Reader session. Continue Reading previously selected the newest progress row.

Progress may change from many sources. Only saving actual Reader state creates a new
local Reader-activity marker. Library membership is not a prerequisite. Existing deletion,
private-space isolation (including disabled isolation), and incognito policies still apply.

## Persistence and compatibility

Room 50 -> 51 adds two non-null integer columns, both defaulting to zero:

- `last_reader_activity_at`: device-local evidence of a saved Reader state. The Reader
  clock uses max(wall-clock now, previous local Reader clock + 1), so same-millisecond
  saves and clock rollback still have deterministic order. This is not a chapter/progress
  timestamp and is not exported to progress-sync formats.
- `legacy_resume_updated_at`: a frozen snapshot of pre-upgrade `updated_at`. It is
  explicitly a compatibility heuristic, never a reconstructed actual-reading timestamp.

Migration also adds Reader/fallback ordering and fallback-retirement indexes, avoiding a full-history clock scan or retirement scan on every Reader save.

Migration leaves all actual Reader markers at zero and snapshots old progress timestamps.
Before the first new Reader save, the fallback orders the old rows by frozen time descending,
then manga ID ascending for ties. It preserves a sensible pre-upgrade resume entry, though
old data cannot reveal whether that entry came from Reader or an administrative action.
New tracking/import/manual rows have both markers zero and cannot enter that fallback.

The first actual Reader save clears fallback values on **all** rows, including private
history and tombstones, in the same transaction as progress and the Reader marker. The
fallback remains retired after deletion, GC, app restart, and Feed undo. A private-only
Reader session can therefore retire legacy fallback while leaving normal resume empty;
this avoids exposing private titles or reviving ambiguous pre-upgrade data.

The existing `updated_at` ordering, History screen filters, progress positions, tracker
creation/advance policy, scrobbling side effects, and library membership remain unchanged.

## Writer audit and caller behavior

| Writer / operation | Policy |
| --- | --- |
| `HistoryUpdateUseCase` from actual Reader state | Calls `addOrUpdateFromReader`; saves progress and records Reader activity atomically; incognito skips both. Network/source checks remain outside the transaction. |
| `SyncProgressFromScrobblersUseCase` -> `advanceFromTracking` | May create/advance progress; never sets Reader/fallback marker, never resurrects tracking-deleted rows. |
| `FeedViewModel.markAsReadImpl` | Existing force-progress mutation remains; does not set Reader marker. |
| Feed undo | `HistoryDao.undoFeedProgress` restores prior progress or deletes Feed-created history. Existing Reader/fallback marker is preserved. A subsequent Reader save wins over stale undo; unread-log/counter undo remains through the existing reversible handle. |
| `MarkAsReadUseCase` (Favourites/History completed/read) | Existing force-progress mutation; completed position/page/percent and remote side effects remain, without Reader provenance. |
| `ChaptersPagesViewModel.markChapterAsCurrent` | Existing force-progress mutation; chosen chapter remains current without claiming Reader activity. Opening Reader and saving afterward creates provenance. |
| `ProgressUpdateUseCase` | Recalculates chapter mapping/percent through progress-only DAO update; markers are preserved. |
| `HistoryRepository.recoverIfNeeded` | Repairs missing chapter pointer through progress-only DAO update; markers are preserved. |
| Native ZIP restore: `LocalBackupRepository` -> `HistoryBackup.toEntity` -> `upsert` | Imported progress has markers zero; active local markers are retained by update. Reviving a tombstone through progress does not revive its deleted Reader evidence. Restore time is never used as Reader time. |
| Mihon restore: `MihonBackupManager` | Existing imported progress/history handling remains; imported `lastRead` is not assumed to prove a local Reader session. Existing local markers survive DAO updates. |
| Google Drive sync: `SyncHistory.toEntity` -> `upsertForSync` | Active local markers survive replacing progress/tombstone fields. New remote rows and non-reader revival of deleted progress have zero local markers. |
| Service Library Sync: `LibrarySyncEngine` | Existing direct history upsert remains progress-only, preserving local markers. |
| `MigrateUseCase` (alternative/source identity translation) | Copies original marker times through `upsertForMangaMigration`; merges with an existing destination using max, never migration time. |
| `KotatsuMangaMigrator` (local source identity conversion) | Same historical marker transfer; no new reading time is fabricated. |
| DAO `update`, `upsert`, iterable `upsert`, direct `insert` | Progress updates preserve active markers; administrative resurrection clears deleted markers. Audited production inserts use defaults except explicit local identity translations. |
| Delete, recover, clear, delete-after, delete-not-favourite, GC/source cleanup | Existing soft/hard deletion semantics remain; queries exclude deleted rows. Explicit deletion undo/recovery restores original evidence without a new timestamp; administrative/import/sync resurrection does not revive it. |
| Historical schema migrations | Old history creation/copy/default migrations have no Reader provenance; v51 snapshots their final old ordering once. |
| Backup-agent audit | No production Android `BackupAgent`/`onRestore` history writer was found; the existing `AppBackupAgentTest` exercises Mihon restore. |

Both portable native backup and Google Drive formats intentionally retain their existing
progress-only wire schema. On a fresh device, restoring/importing progress alone leaves
Continue Reading empty until a local Reader save. On an existing device, restore/sync
preserves the local last-reader identity. This trade-off prevents old or third-party
progress payloads from claiming unprovable local reading; progress itself remains available.

`getLastOrNull` / `observeLast` keep their latest-progress semantics. Explicit
`getLastReadOrNull` / `observeLastRead` use `HistoryDao.findLastRead` / `observeLastRead`.
Resume query ordering is Reader clock descending, frozen fallback descending, ID ascending,
with the existing deleted/private visibility predicates. There is no normal-favourite filter.

Updated callers: `MainViewModel.openLastReader`, Continue-button long-press
`openLastDetails`, `ReadingResumeEnabledUseCase`, `ContinueReadingWidget`, and
`MangaPrefetchService.prefetchLast`. General History, recent dynamic shortcuts, and unrelated
sorting continue to use progress ordering. Empty results use the existing EmptyHistoryException,
disabled FAB, and widget-empty UX, respectively.

## Behavioral regression coverage

New tests are added to the already-routed `LocalBackupIdentityTest` and
`ChapterPersistenceRegressionTest`; no workflow changes or new workflows are required.

- Actual `HistoryUpdateUseCase` saves select the latest manga without favourites membership;
  observable and one-shot resume queries agree.
- Tracking creates and advances history without changing Reader identity; tracking-only
  history has an empty resume state while remaining visible to the normal History API.
- Feed-style force-progress updates and actual unread-log reversible handles still undo;
  Feed-created history is removed on undo; a later Reader session survives stale undo.
- Completed/read and current-position mutations preserve progress without stealing resume.
- Previously tracking/Feed/manual-only manga becomes resumable after actual Reader save.
- Incognito, private isolation enabled/disabled, deletion/explicit undo, and non-reader tombstone resurrection are exercised on real Room data.
- Native backup ZIP round-trip and cloud DAO replacement cannot create local Reader evidence
  or erase local markers; local identity translation preserves historical timestamps.
- Same-clock and clock-rollback Reader saves have deterministic order.
- Full Room-schema 50 -> 51 migration validates column defaults, freezes deterministic legacy
  ordering, ignores later progress/sync timestamps, and keeps fallback retired after Reader
  deletion/GC and database reopen.

CI remains the repository's Fast, Deep (full JVM plus instrumentation compilation),
Identity Guard, and Android Runtime gates according to the unchanged classifier. Production
persistence/schema paths trigger its critical override for owner-request non-theme work.
Physical-device acceptance is reserved for the owner. This document does not claim tests
have passed before their CI results are inspected.
