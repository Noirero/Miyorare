# On-device Files cover domain

This work starts from Amain2 `fd996867f0b4c15e66d248d7ecd1a7bbc397f594`, after merged #557, #558, #559 and #562. GitHub API verification found no newer integration commits. Historical draft #534 is divergent and is not the work branch. Tracker PR #563 descends from this baseline and does not authorize replacing the cover implementation.

The On-device Files UX, Track B, Batch 11, Room schema, manga/chapter IDs, selected-root ownership, Reader navigation and lazy page behavior remain outside the cover optimization scope.

## Ownership

| Domain | Authority and storage | Invalidation/clear |
| --- | --- | --- |
| Indexed source identity/version | `SmartLocalLibrary`, persisted discovery snapshot | Explicit Refresh, root/selection changes |
| Derived thumbnail | `SmartLocalCoverCache`, `filesDir/smart-local-covers` | Winning candidate fingerprint, recipe, corruption, retention, dedicated clear |
| Presentation memory | Coil, `LocalCoverVersionInterceptor` | Indexed fingerprint + derived-clear epoch; presentation acceleration only |
| Global/remote image disk | `MiyorareImageDiskCache` | Existing global cache actions; not Smart Local extraction authority |
| PDF Reader pages | `LocalPdfCache`, `cacheDir/local_pdf_pages` | Existing backing identity/page lifecycle; dedicated thumbnail clear does not touch it |
| Reader/source backing | `LocalContentReader`, `cacheDir/smart-local-content` | Reference-counted pins and existing materialization maintenance |

The dedicated On-device menu action calls only `SmartLocalCoverCache.clear()`. It does not clear Coil, delete sources/index/preferences, reset list state, or delete Reader backing/pages. The existing Settings-wide cover clear retains its existing broader behavior (presentation memory, global covers and derived Smart Local covers); it is not the scoped measurement action.

## Cache and publication

Each exact title key has a reference-counted single-flight mutex. Persistent lookup precedes source admission. PDF, archive/EPUB and image/sidecar lanes retain two permits each; static bitmap preparation retains its independent two-permit limit. Source admission continues through publication. Reader PDF rendering retains the existing app-wide two-permit renderer.

Entries contain the successful candidate index, fingerprint through that candidate, payload length and SHA-256 checksum. Publication writes/syncs a same-directory partial and atomically renames it. Cancellation/exception removes the partial; startup removes interrupted-publish leftovers. Clear increments an epoch under the file mutex, so an in-flight generation cannot republish an entry cleared during its source work. Presentation keys include that epoch, so retained Coil memory does not defeat a subsequent cold request.

Retention remains **128 MiB / 1,024 entries / 30 days idle**, with a **4 MiB** per-persistent-payload ceiling. No arbitrary capacity increase is made before collection measurements. `stats()` provides stored-entry median/p90/p95 and projections for 100/350/1,000 titles. These projections are estimates based on the measured set, not physical-device results or worst-case guarantees. A library dominated by near-4-MiB animations cannot fit 350 entries into 128 MiB; bounds take priority and that dataset requires an explicit measured capacity decision.

## Recipe

Recipe v3 is `v3:grid512:opaque-jpeg82:alpha-png:exif1:animation768-4MiB-passthrough:archive-index1:epub-opf1`. The normal grid prefers 120dp-wide portrait cards; a stable 512px long-edge bucket reduces representation size without making RecyclerView dimensions or density changes part of source identity. Very large/custom grids can upscale this representation; physical quality acceptance remains necessary. Archive/EPUB selection policy also participates in the recipe, so an intermediate v3 cached fallback cannot suppress the new declared cover selection.

Static images decode bounds, sample before pixel allocation, apply the existing EXIF orientation rules and scale down without upscaling. Opaque results use JPEG quality 82; any actual nonopaque pixel retains PNG. An ARGB capability flag alone does not force PNG. PDF cover rendering goes directly to this target and encoder, independently of unchanged legacy/Reader PDF cover/page APIs.

Animation classification is unchanged: confirmed bounded GIF/WebP animations remain encoded byte-for-byte, up to the existing 768px canvas / 4 MiB persistence limits. Oversized or uncertain animation passes through unmodified and remains nonpersistent, within the existing 8 MiB candidate-input ceiling. Recipe changes invalidate the former 768px/PNG entries once via the versioned fingerprint. Source/chapter identity does not change.

## Identity and freshness

The selected root URI, provider/file key and URI, display name, size and modified time remain candidate-aware. Failed higher-priority candidates participate in the winning fingerprint. A newly added/fixed sidecar supersedes an archive fallback. Later chapters do not invalidate the stored fingerprint through an unchanged earlier winner, although Coil may decode its persistent representation again after a whole-plan presentation-version change.

Known unchanged metadata survives Refresh. Unknown size/modified values use the persisted scan version: restart reuses that snapshot, but explicit Refresh conservatively invalidates unknown candidates. A same-size replacement is detected when modified/version metadata changes. A replacement preserving **both** size and modified cannot be proven without opening source content; no per-bind content hashing is introduced. Rename can invalidate the affected candidate because its name participates in format/priority. Provider/source identity remains unchanged when its key/URI remain stable. Root/key/URI changes are not silently aliased.

No identity hardening or speculative scan-version removal is justified solely by visible placeholders. Diagnose actual `MISS_FINGERPRINT` counts first. A provider that reports unknown metadata for every document can still regenerate on Refresh under the intentional conservative contract; physical-provider evidence is needed before relaxing it.

## Archive and EPUB access

Archive work starts only after the 350-title warm/restart/unchanged-Refresh assertions passed on the recipe checkpoint. JDK 17 fixture investigation confirmed the old `closeEntry()` drained 4,194,304 skipped uncompressed bytes (4,195,796 compressed source bytes read) outside the application's read/cancellation checks. This is structural/synthetic evidence, not an owner-device latency claim.

A scoped authorized descriptor is tried first. Its callback owns an input over a duplicate descriptor; both are closed before leaving `LocalDocuments.withReadDescriptor`. FileChannel seekability is checked before positional reads. The bounded ZIP central directory is read directly: at most 4,096 records / 2 MiB, plus a 65,557-byte end window. The index lives only in that request. No proc-fd path, source extraction path, persistent second index or full-source materialization is introduced.

Explicit EPUB metadata and the first safe cover-like archive name can select an entry anywhere in that bounded index. Generic first-valid fallback retains the existing first-64-local-entry discovery window. Selection still has at most eight image candidates, 8 MiB per candidate and 32 MiB aggregate candidate output/compressed-read limits. Failed candidates reserve their declared output budget. Local/central name, method, flags, range, length, inflation completion and CRC are checked; path traversal names are excluded. No archive path is extracted to disk.

EPUB reads only bounded `META-INF/container.xml` and the selected OPF (256 KiB each), then the declared EPUB 2 cover id / EPUB 3 `cover-image` / safe cover-like manifest item. Paths are percent-decoded and resolved within the archive, with absolute/external/root-escape paths rejected. Missing/broken metadata or cover falls back to the generic image policy. Reader spine/TOC/HTML parsing and its `ZipFile`/backing lifecycle are unchanged.

Pipe/rejected/incompatible descriptors fall back to streaming without making a full copy. ZIP64, multidisk, oversized/ambiguous directories and unsupported index variants remain streaming-only. Streaming keeps 64 entries / eight candidates / 8 MiB per candidate, and now manually reads **all** skipped/oversized payloads under cancellation and a **32 MiB total decompressed traversal** ceiling. It never drains a partial entry with `closeEntry()`. Only complete CRC-validated earlier fallback data can survive exhaustion of the traversal budget. A stream-only EPUB retains generic bounded image selection; direct OPF selection is a seekable-source capability.

`ARCHIVE_INDEXED` and `ARCHIVE_STREAMING` report actual source bytes and elapsed time; `ARCHIVE_FALLBACK` identifies the capability/format fallback. A valid index with no eligible cover does not cause a second streaming traversal. Warm eligible archive/EPUB covers use only the persistent derived cache. Sources beyond the index/discovery/output bounds can have no generated cover by design; those bounds and physical residual latency remain part of acceptance.

## Measurements

Diagnostics are process-local and bounded: O(1) counters plus 256 recent events, with no retained source paths/URIs, book titles or payloads. Counts distinguish persistent hit, absent/fingerprint/corrupt miss, noncacheable output, byte/count/idle eviction, generation, publication, clear, failure and cancellation. Source-open, PDF stages and presentation memory/load events distinguish extraction from rendering an already persistent thumbnail. The menu exposes statistics and an explicit measurement reset independent of cache/data clear.

`GENERATED` elapsed time includes source admission/wait and preparation up to publication admission. PDF descriptor-open, renderer-open, page-render and encode are separate measurements; publication time includes file write/sync/rename. `IMAGE_PREPARE` includes static classification/decode/orientation/resize/encode. Presentation time includes request processing; a `PRESENTATION_MEMORY_HIT` is not a derived disk read. Timings are monotonic nanoseconds and are not UI-frame or owner-device benchmarks.

Android instrumentation includes real DocumentsProvider fixtures, seekable descriptors, pipes/rejected descriptors, cancellation, source freshness, restart warm hits, animation, clear scope, recipe encoding, a 350-title mixed synthetic library with 35/100/350 boundaries, and separate 100-title PDF-heavy/archive-heavy collections. First-completed-cover and per-request percentiles are harness measurements, not first-visible UI frame measurements. JVM capacity data also exercises 35/100/350/1,000 retained entries close to the configured byte bound; that is a synthetic capacity model, not a measured 1,000-title production collection. The starting v2 cache format is exercised for one upgrade invalidation followed by owner-recreation reuse.

The existing exact-head Android job collects synthetic JSON for recipe bytes/times, collection distribution/evictions/opens, PDF stages and indexed archive reads, plus exact commit and assembled debug APK SHA-256. It uploads those reports and the debug APK without a second build or emulator session. Separate stored-representation bitmap decode median/p95 measures old PNG versus new JPEG; it excludes disk and Coil request work. The encoding survey compares old 768px PNG encoding with new 512px JPEG decode/resize/encode, so those stage durations are not an equal-work speed comparison. CI fixture measurements must be labeled synthetic. Production/provider/physical targets must be established separately.

## Owner physical validation SOP

Record branch, exact commit, build run, version, provider and **APK SHA-256**. A CI/emulator pass is not a physical-device pass.

1. Use the dedicated On-device cover clear, then reset diagnostics for a new measurement window.
2. Cold-load a mixed collection and record first-visible/full completion, generation/source-open counts, bytes/entry count and evictions.
3. Revisit three times. Distinguish presentation memory/decode from persistent miss and actual source open.
4. Force-close/reopen without Refresh. Eligible unchanged covers must hit persistent storage with zero source opens.
5. Refresh without changes; record fingerprint invalidations and provider metadata reliability.
6. Replace one source and Refresh. Only the related source should regenerate when its provider version changes.
7. Repeat with PDF-heavy, ZIP/CBZ-heavy and image-only sets; capture descriptor/indexed/streaming paths where available.
8. Use 35-, 100- and 350+-title collections, stress scroll/cancel, repeat clear/cold/warm, and record p95 entry bytes and evictions.
9. Repeat the relevant cases on a nonseekable/stream-only provider if available.
10. Smoke the existing manga/comic/PDF/EPUB Reader, including an active PDF backing file while clearing thumbnails.

Capture screen recording plus diagnostic counts/stats after each boundary. Restart resets process-local counters; a new window should show persistent hits. An older cache recipe legitimately cold-regenerates once after upgrade. Oversized/uncertain animation legitimately repeats extraction and must be classified `NOT_CACHEABLE`.

Quantitative first-cover, p95, decode and capacity targets remain **NOT VERIFIED** until owner measurements exist. Do not re-LOCK Batch 12, freeze/promote Amain2, or create a main promotion from this evidence alone. Main/beta/Amain2 policy and owner merge authority remain unchanged.

## Legacy/cleanup boundary

API source inspection confirms `LocalMangaParser` still consumes `LocalPdfCache.renderCover` and `renderPages`, and Reader enrichment still consumes `EpubParser`/`ZipFile` over retained backing. These remain live consumers and are not removed. The existing global Settings cover clear is also intentional broader behavior. Only the superseded private streaming `readEntry`/unchecked `closeEntry` implementation is replaced by the bounded traversal; no speculative preference/cache/Reader cleanup is performed.
