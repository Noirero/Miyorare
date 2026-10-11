# Miyorare Details Diagnostic — pengujian hanya dengan HP

Build ini mengukur delay Details. **Bukan production fix.** Tidak mengubah lifecycle, pagination, cache policy, ordering source/persistence, database schema, atau layout. Source baseline main `c22517f2b8c9ecf81e98cbb28ce1f2675f473f01`; PR #577 tidak disertakan.

Application ID: **`org.noirero.miyorare.detailsdiag`**. Nama launcher: **Miyorare Details Diagnostic**. APK Preview debuggable, terisolasi dari Main/Beta/Amain. Metadata export menyertakan exact build SHA.

## Langkah owner

1. Install APK, lalu buka **Miyorare Details Diagnostic**. Instalasi Main tetap terpisah.
2. Pakai backup library ~16k yang sama melalui fitur backup/restore aplikasi. Jika belum ada backup, buat backup dari Main terlebih dahulu; jangan hapus data Main. Pasang Source Pack/extension yang biasa digunakan, jika diperlukan oleh instalasi terisolasi. Cocokkan settings/tampilan dengan reproduksi asli. **Tunggu restore selesai.**
3. Dari route yang biasanya lambat, buka satu manga cached, tunggu chapter tampil, lalu Back. Ulangi **3 kali**, dengan manga dan route yang sama. Jika chapter berada di bawah layar, scroll secukupnya agar chapter terlihat; jangan mengubah filter/cache untuk eksperimen. Navigation pertama bisa network; export akan menunjukkan cache/source state. Subsequent opens memberi cached samples bila cache masih fresh.
4. Buka **Settings / Pengaturan → About / Tentang → Export Details diagnostic**. Tidak perlu menyalakan verbose logging, menjalankan extension tests, atau memakai komputer.
5. Android membuka pemilih lokasi file. Pilih **Downloads / Unduhan**, lalu **Save / Simpan**. Nama default: **`miyorare_details_diag_<timestamp>.jsonl`**.
6. Dari ChatGPT, attach file `.jsonl` yang baru disimpan melalui Files/Downloads. Cukup upload satu file; seluruh navigation dalam proses tersebut ada di dalamnya. Export sebelum force-close/reinstall. Tambahkan keterangan singkat apakah delay masih tampak dan route yang digunakan.

Control small/fresh hanya jika data/profile aman sudah tersedia. Cukup 2–3 navigation dengan settings/binary yang sama; **jangan menghapus library untuk membuat control**. Capture library besar lebih diprioritaskan.

## Apa yang direkam

Setiap navigation punya session ID; tidak memakai manga ID sebagai session ID. Export JSON Lines berisi metadata, satu `DETAILS_DIAG` summary per session, lalu bounded events. Manga/chapter/category ID dan counts direkam; judul, deskripsi, URL cover, search text, tags, credential, dan full content tidak didump oleh diagnostic recorder.

| Area / file | Marker atau snapshot |
| --- | --- |
| `AppRouter.openDetails` / `MangaIntent` | navigation entry, source route/activity, session transport |
| `BaseApp` / `DetailsDiagnostic` | application ActivityLifecycleCallbacks: create/start/resume/pause/stop/destroy; source return |
| `DetailsExpressiveActivity.onCreate`, `setupContent` | entry, first composition, actual detail/chapter UI subscription receives, view-tree draw, Window FrameMetrics |
| `DetailsLoadUseCase.invoke`, `loadRemote` | resolve, cache initialized/fresh, source fetch, persistence; ordering tetap baseline |
| `MangaDataRepository.findMangaById`, `resolveIntent` | explicit-session DAO and chapter materialization |
| `DetailsViewModel.init`, `doLoad` | chapter-bearing publication begin/end, history, actual setter duration/loaded state |
| `ChaptersPagesViewModel.chapters` | mapping/filter/title mode duration, final downstream combine duration/model-ready |
| `DetailsExpressiveScreen` / `InlineChapterCard` / `DiagnosticCompose` | chapter section/header/item composition, placement, bounds intersection, draw after drawContent |
| `FlowObserver.observe` | counts on existing collection; finally removes count; no subscription/lifecycle policy change |
| `FavouritesListViewModel` | VM presence, DB/display limits, emitted DB rows, mapped size, groups/member count, query length/filter count, manual job states, scope children, content/DB/groups upstream |
| `FavouritesContainerViewModel` | counts pipeline timing, categories/counts, search-active state; no diagnostic query |
| `MangaListFragment`, `FavouritesListFragment`, `FavouritesContainerFragment` | owner/view lifecycle, retained adapter size, current pager, prefetch activity and pending list |
| `FavouritesSearchRepository` | existing cache populated/counts/generation per space; no load/clear |
| `AppModule`, `DiagnosticImageListener`, Details cover/backdrop + prefetch builders | singleton cache occupancy/max, aggregate request/fetch/decode activity, bounded role-tagged results; original captcha callback retained |
| `AboutSettingsFragment/ViewModel` | dedicated diagnostic export via existing CreateDocument pattern, IO serialization/write |

Controlled memory points: before navigation, chapter-bearing publication end, first chapter draw, source return. Runtime used/total/free/max, native allocated heap, image cache bytes/active work, ART runtime GC counters are cheap samples. Owner maps and PSS are taken on IO; actual sample timestamp, queue delay, and PSS sampling cost are recorded because those values are not simultaneous with the frame marker.

FrameMetrics are attached specifically to Details window. They include total, layout/measure, draw, sync, command issue, swap, unknown delay and vsync clocks. Callback dropped count is **listener delivery loss**, not automatically dropped display frames. Choreographer gaps are a separate bounded indicator, not CPU scheduling proof.

Unsupported FrameMetrics values are exported as `unknown`. First chapter draw includes the actual chapter ID; filter snapshots include type names without tag/source text.

Every event carries elapsedRealtime nanoseconds, System.nanoTime, native TID, thread name, and thread CPU clock. Frame API timestamps use the monotonic clock; do not subtract them directly from elapsedRealtime across suspend/deep sleep. Main CPU span is reported separately from wall latency.

Exactly **one conditional main-stack snapshot** is allowed per navigation, 150 ms after chapter-bearing publication if draw remains pending. It records up to 24 method frames and its measured collection cost. It is not a method trace or continuous sampling loop. Java thread state cannot distinguish Linux running/runnable/descheduled. A pending draw caused by an off-screen chapter can produce an idle stack; do not label that a stall automatically.

## Expected output / interpretation

Summary fields include `stateReadyMs`, `publicationBeginMs`, `publicationSetterMs`, `stateToConsumerMs`, `consumerToCompositionMs`, `compositionToFirstChapterMs`, `modelReadyMs`, `firstChapterDrawMs`, `mainCpuNavigationToDrawMs`, `totalNavigationMs`, `slowestMeasuredStage`, memory/ownership snapshots and frame/image counters. Actual event rows retain all measured stages for end-to-end reconstruction.

Missing stage → `unknown`. Consumer can execute during setter assignment; that span is `overlap_or_earlier_emission`, not a fabricated zero/positive latency. First values can derive from the initial seed or later Room/source publication; manga/session/count and loaded/cache state must be considered together. No claim that unrelated progressive emissions are the same state revision.

Placement/composition can precede visibility due to LazyColumn precomposition. Draw is accepted only when the item bounds intersect a shown root window. **A draw callback records draw commands; it does not prove physical display presentation, full visibility or absence of occlusion.** If no chapter enters the viewport, draw can stay unknown. The UI is not scrolled or otherwise changed by instrumentation.

## Overhead / limitations

- Ring buffer: at most 4096 events, 16 session summaries. Eviction count is disclosed. Image rows ≤64/session; FrameMetrics rows ≤48/window; main-frame gap rows ≤16/session. Normal frames use aggregate counters; no per-library-item dumps.
- Runtime observations use existing state/cache sizes. No full-library query, bitmap allocation, cache clear, explicit GC, lifecycle change, artificial UI delay, or method tracing.
- Recorder markers report maximum measured marker elapsed cost; memory/PSS/stack samples have cost fields. These do not prove zero device overhead. Weak owner references avoid creating new retention ownership.
- File serialization/writes happen on IO after Details stops or when owner exports. No synchronous diagnostic file write on main/render. In-process events may be lost on abrupt process kill before the next save; export during the same test process.
- FrameMetrics listener and one lightweight Choreographer callback add diagnostic work. Preview is debuggable/non-minified; compare this binary against itself for control, not as an absolute release benchmark.
- No heap dominator/retained-byte attribution, continuous CPU samples, native/GPU stacks, per-GC pause trace, kernel scheduling trace or display-present guarantee. PSS/native heap are not a precise bitmap retained graph. Counts/generation are observational and non-transactional snapshots.
- Default `observe` counts are measured on existing subscriptions; uninstrumented collectors elsewhere remain unknown. Pipeline wrappers track the lifetime of cold upstream subscriptions without changing SharingStarted; scope children counts alone do not mean expensive work is currently executing.

## Build / validation

Workflow **Details Render Diagnostic** builds only `diagnostics/details-render-hp-only`; no release publication or merge. It validates targeted diagnostic timeline, Details ordering/Room, and Favourites pagination tests, builds signed Preview, checks package ID/app label/debuggable flag/signature and uploads APK + checksum + exact-head build metadata + these instructions.

Diagnostic functionality is enabled only by `-PDETAILS_DIAGNOSTIC=true` for Preview. Other build types default to disabled. Do not merge this branch or treat it as a production candidate. Stop after APK/instructions are provided; analyze owner log before any optimization/fix.
