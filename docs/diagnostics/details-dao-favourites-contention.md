# Miyorare DAO Contention Test — one causal experiment

This is an isolated, debuggable experiment, not a production candidate or a proven fix.
Verified main baseline: `c22517f2b8c9ecf81e98cbb28ce1f2675f473f01` (fetched 11 October 2026).
Branch: `experiment/details-dao-favourites-contention`.
Package: `org.noirero.miyorare.daocontentiontest`. App name: **Miyorare DAO Contention Test**.

## Phone-only owner test

1. Install the APK and open **Miyorare DAO Contention Test**. Other installations are separate.
2. Restore the same ~16k backup into this installation. Match the source/extension/settings used in the previous capture and wait until restore finishes. Do not delete another installation's data.
3. **Browse → same Extension → same cached manga**, exactly as in the successful previous reproduction. Do not change these trials to the Favourites route.
4. Wait for Details/chapters, then press Back.
5. Perform **three trials total** on that same route and manga.
6. Record **Trial 1: slow/fast; Trial 2: slow/fast; Trial 3: slow/fast**.
7. **Settings → About → Export DAO contention log**. Choose **Downloads**, then **Save**.
8. Upload `miyorare_dao_contention_<timestamp>.jsonl` (or the `.txt` file Android adds) and the three subjective results. Export before force-closing/reinstalling.

No PC, ADB, Android Studio or desktop profiler is required. No destructive small-library reset is requested.

## Exact hypothesis and scope

Hypothesis: the background **Favourites list refresh producer** contributes to the reproduced multi-second `MangaDao.find` interval. It includes the Room list subscription, its downstream downloaded-status/override queries, and mapper-owned card-enrichment jobs.

`FavouritesListViewModel.content` keeps its original eager `StateFlow`, pagination, sort/filter/search inputs, and last mapped result. A diagnostic-only gate wraps its cold producer **before** `stateIn`. It runs normally while any Favourites container is started and visible; it cancels and joins that producer when all containers are hidden/stopped. It also cancels and joins the list's tracked card-enrichment jobs, including older cancelled jobs with replaced handles. Scalar preference state and the existing manual refresh-trigger listener remain alive.

The latest database result is retained by **the same reference**, without copying it, so cancelling the producer does not deliberately discard the 16k manga graph and turn this into a cache/memory-clearing test. Window, display limit, last mapped `content`, and completed enrichment state are preserved. Returning to Favourites resumes observation using current inputs; it does not emit an empty list or reset pagination/search. Pause/resume freshness is covered by deterministic JVM tests, not claimed as physical-device acceptance.

Groups, group repair, container category/count producers, singleton membership/search caches, images and custom prefetch are unchanged. Therefore a negative result falsifies **this list-refresh workload**, not every possible Favourites owner. Groups/counts remain candidates only if later concrete evidence implicates them. This experiment does not disable arbitrary ViewModels or replace `Eagerly` globally.

## Why subscription cancellation is meaningful here

The baseline default `Flow.observe(owner)` collects until the owner is destroyed. A hidden/stopped container and pages can remain in CREATED with eager producers and collectors still alive. This gate is independent of that UI subscriber count.

Room **2.8.4** generated `FavouritesDao.observeAllImpl` uses `createFlow(db, inTransaction=true, ...)`. Room's `createFlow` maps invalidations to `performSuspending`, whose compatibility-mode dispatcher is a structured `withContext`. `flatMapLatest` cancels **and joins** the previous producer before acknowledging PAUSED; that acknowledgement follows the Room call's completion/unwind and the tracked enrichment-job joins. A cancelled in-flight call cannot be declared drained merely because the visibility flag changed.

CI preserves generated `MangaDao_Impl.kt`, `FavouritesDao_Impl.kt` and `MangaDatabase_Impl.kt` and checks the transactional generated call sites. No DAO/query/Room/executor configuration is changed.

## Required evidence for interpretation

Each navigation has a session ID and captures `navigation`, `dao_find_start/end`, cache state, publication, actual chapter UI consumption, chapter section/item composition, placement and draw. `workload_before`, `workload_dao_start/end`, `workload_state_ready` and `workload_first_draw` include:

- Each registered list owner, category/space, phase, starts/completions, row-emission count and tracked auxiliary-job count.
- `pausedConfirmed`: every registered producer has reached PAUSED after joining, and no tracked enrichment job remains.
- `generation`: changes on registration, visibility, producer transitions/emissions or auxiliary-job tracking/completion.
- Retained database window/row count, mapped result size, display limit, search-active/filter count and groups count. These cached snapshots do not query the library or log titles/metadata.

`DETAILS_DIAG.daoWorkloadChecks[].pausedThroughout=true` requires both DAO-boundary samples to be confirmed paused **and have the same generation**. This establishes inactivity of the tested producer under the verified structured cancellation contract. It does not report a measured count of native SQLite statements, and does not establish that all process-wide database owners were idle.

If boundary evidence is missing/false, generation changes, no list owner exists, or the restored/library ownership differs materially, the A/B interpretation is **INCONCLUSIVE**. Do not interpret a latency change as causality without this check. Keep first uncached/source-backed trial separate from cached/fresh trials using the recorded flags.

Positive: confirmed inactive producer + DAO returns to short latency + owner-visible improvement supports this producer as a causal contributor. Actual executor queue wait versus SQL execution remains unknown until independently measured; this result does not alone identify a specific native lock/SQL statement.

Negative: confirmed inactive producer + DAO remains seconds-scale + owner still reports slow falsifies the tested list-refresh workload as the primary cause. Return to the exact DAO/Room path; do not keep modifying Favourites blindly.

## Diagnostic integrity and limits

Ring buffer: 4096 events / 16 sessions. Markers are value-only, monotonic, include thread/TID and thread CPU time. Phase events are lifecycle/workload transitions, not per-row SQL logs. JVM tests cover retention, resume freshness, real producer cancellation, delayed drain acknowledgement, replaced auxiliary jobs, multiple containers and summary interpretation.

Cheap Runtime/native-heap snapshots are taken before navigation, state-ready, first chapter draw and Back. No extra SQL, cache clearing, forced GC, arbitrary delay, continuous stack sampling, allocation tracing, Choreographer loop or per-frame logging is added. Serialization/file writes run on IO after stop/export. Snapshot/marker costs are recorded; actual phone overhead is not yet measured.

This capture intentionally keeps only comparison-critical instrumentation from the previous diagnostic. Previous FrameMetrics/image counters/conditional stack/PSS probes are not ported. Image behavior is unchanged. Instrumentation overhead therefore differs from the previous binary and must be considered when interpreting small changes; compile success does not prove a speedup or contention mechanism.

DAO duration includes dispatch/queueing, transaction acquisition, SQL, relation materialization and continuation return; it is not isolated SQL execution time. Individual GC pauses, heap dominators, scheduling/native/GPU waits and physical screen presentation remain UNKNOWN. An item composition/placement can be prefetched; the draw marker requires intersection with the window but still marks draw commands, not physical presentation. `totalNavigationMs` includes time until Back and must not be used as time-to-visible. Negative publication-to-consumer subtraction remains `overlap_or_earlier_emission`.

## Export / build

The About export serializes the current process's bounded capture and saves through Android's document picker on IO. A best-effort copy is also written privately to `files/dao-contention/latest.jsonl` after Details stops; the owner uses the export button, not the private path. The export must happen before force-close.

The reused diagnostic build workflow accepts only this experiment branch, validates targeted regressions and builds the isolated signed Preview. APK metadata, label, debuggability, signature and exact head are included in its artifact. Normal builds keep `DETAILS_DIAGNOSTIC=false`. No merge, production fix, promotion or release is authorized by this experiment.
