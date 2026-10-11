# Verbose performance diagnostics

Enable **Settings → About → Verbose logging**, reproduce the slow action, then disable
it to export the existing log plus `PERF SUMMARY`. Verbose remains off by default.
No new preference, database query, cache probe, download scan or source request is
introduced by diagnostics.

`PERF` lines contain a random internal session ID, monotonic `t_ms` and navigation
`trace`. Router launches (including Favourites → Details), bottom-tab selection,
transaction commit, Activity/Fragment lifecycle, first pre-draw, data milestones
and the Details Compose content commit share that trace. Back/resume begins a new
trace when returning to another screen. Labels contain no titles, manga/database
IDs, URLs, queries, headers, credentials, file paths or exception messages.

Use these measurements together:

- `navigation` → `transaction_committed` → `first_draw` measures the navigation and
  initial render boundary. `enter_animation_complete` records the platform Activity
  callback where available. `first_meaningful_content` is the pre-draw following
  committed Details content or meaningful list adapter publication (manga, group or
  empty state; loading/error placeholders do not qualify). These are rendering
  proxies, not compositor/pixel presentation; bottom-tab animation completion is not claimed.
- `manga_ready`, `favourite_state_ready`, `chapters_ready`,
  `download_local_state_ready`, `content_composed`, `adapter_ready` and
  `loading_finished` separate actual stages. `load_flow_finished` marks the whole
  Details flow, which can continue after the loading indicator ends. Milestones are once per transition;
  repeated refreshes/pagination still have operation timings. Empty chapter lists
  are ready when the existing loaded state says they are initialized.
- `repository` measures existing Details resolve/override/cache/local/history/store
  and Favourites history/unread/download operations. Favourites subscription timing
  is subscription-to-first-result, including Room scheduling/mapping, rather than
  claiming raw SQL timing. No raw SQLite query listener is installed.
- `source` separates external/native Details source work (including parsing) from
  HTTP. Base OkHttp clients and clients copied from them (including Mihon) report
  whole-call duration through body consumption, HTTP status, cache/network route,
  failure and cancellation. Calls on independently created plugin/third-party
  clients are outside this hook; source timing still covers their Details work.
- `image` reports Coil request, fetch and decode timings, failures/cancellation and
  observed `MEMORY_CACHE`, `MEMORY`, `DISK`, `NETWORK` or known `LOCAL` cover/archive
  routes. Memory/disk hit/miss counts are inferred from successful data sources when
  the corresponding read policy permits caching. They are fall-through observations,
  not independent probes; a failed fetch cannot prove which cache missed. Local
  cover/archive fetchers are distinguished from a disk cache. Existing Smart Local
  cover diagnostic events also report actual hit/miss reasons, source-open, encode,
  generate/publish and stale/cancel/failure durations without exposing their keys. Captcha retries,
  fetcher order, cache keys and policies remain owned by the existing pipeline.
- `frames` summarizes Android Window FrameMetrics total duration versus API 31+
  deadline, or the display refresh budget on API 26–30. It reports frame count,
  janky count, worst duration and dropped metrics callbacks at tab changes,
  Activity pause and session stop. It includes Compose and View rendering; it does
  not count normal frames as separate log lines. Callback delivery around a tab
  boundary can be attributed to the adjacent foreground trace.
- `stall` measures main Handler queue delay of at least 250 ms using a 200 ms
  heartbeat while an Activity is resumed. It reports recovery or a pending stall
  at session stop. This is a significant scheduling/queue stall, not a CPU profiler.
- `work_start` and terminal `work` events record Details load/single-flight ownership
  and active Favourites producers/card enrichment, preserving their existing scopes.
  Cancel and stale-generation discard are reported only on existing paths. Producer
  duration is ownership lifetime, not time spent continuously computing.

HTTP/image traces have `scope=foreground_at_start`: they correlate the foreground
screen at request creation, **not proven coroutine parentage**. Background work can
share that observation. Critical Details operations retain their captured trace.
Use source/repository stages and overlapping times before assigning causality.

For an uneven bottom-tab switch, compare commit-to-draw with frame and stall
summaries. For Favourites → Details, compare repository/source spans with data-ready
and content times. Fast data with a late pre-draw plus jank/stall suggests rendering
or main-thread contention; slow source/HTTP or cover fetch/decode points to those
stages. A navigation handoff snapshot can render before the refresh completes.

Only verbose sessions create the monitor thread/heartbeat and attach frame listeners.
The disabled path reads the volatile session flag; the image factory returns the
original CaptchaHandler and the network factory returns OkHttp's no-op listener.
Lifecycle tracking keeps a weak resumed Activity reference and weak fragment
registration guards while disabled.
Image/network fast successes are aggregated; slow/failing samples are capped at
four per category per second. Event output is capped at 2,500 per session. Summary
accounting continues after that cap and reports suppressed samples, frame totals,
aggregate counts/durations and up to three worst entries per category, including
bounded unfinished operations at stop. The summary is appended directly to the
export so shutdown cannot lose it while logcat drains.

JVM tests use a fake clock/sink for correlation, exactly-once outcomes, session
isolation, draw reservations, sparse sampling, frame budgets, stall thresholds,
unfinished work, concurrent accounting, bounded output and unchanged disabled-path
results/exceptions. Network event tests also verify cache-only success, call-start
timing, cancellation and exclusion of sentinel credentials/URLs/payloads/errors.
No APK, emulator or physical-device validation is needed for these logic checks.
