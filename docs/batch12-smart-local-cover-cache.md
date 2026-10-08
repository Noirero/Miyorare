# Smart Local derived covers

## Baseline and ownership

Based on Amain2 `8b7c81bd378ccef3b4801b049bfadcc2775da1f1` (#554–#558).
Coil 3.4.0 does not automatically read/write DiskCache for a custom SourceFetchResult.
LocalCoverFetcher previously always entered extraction after a memory miss. DataSource.DISK
and a stable cover key do not constitute persistent fetching.

SmartLocalLibrary.cover() now delegates to SmartLocalCoverCache before opening the selected root
or any source. The new directory, filesDir/smart-local-covers, contains only encoded thumbnails
and their validation header. MiyorareImageDiskCache retains its existing presentation/cache
routing (#557), remote covers, legacy migration, and scoped clearing. No full PDFs are stored
in the derived cache. Settings' existing explicit cover clear clears both cover authorities;
volatile-only clearing does not remove derived thumbnails.

LocalPdfCache retains page rendering. Its new transient cover-render API returns PNG bytes
without generating persistent Reader page/cover artifacts. PDF cover materializations release
references in NonCancellable finally, and delete only an unpinned app-owned backing file.
Reader/metadata pins remain protected (#558). A reused pinned backing file is not touched:
Reader page identity includes mtime, so touching it during cover generation would invalidate
an already returned lazy-page marker. User source files are never deleted by cache cleanup.

## Freshness contract

The successfully published Smart Local discovery snapshot is source-version authority.
Existing scan/add-root/Refresh operations validate metadata through LocalDocuments. Opening
an already indexed collection, binding/scrolling covers, and reopening cover owners do not
add source metadata queries or rescan the collection. Process recreation reloads the atomic
persisted index: its fingerprint describes the last accepted discovery snapshot, not an
assertion that external storage has remained physically unchanged since that scan.

External edits become observable when the existing Refresh/discovery updates that snapshot.
Manual Refresh is required after an external edit if no discovery operation has occurred.
There is no watcher, periodic scan, forced collection-open scan, or implicit source hashing.
A metadata-preserving external replacement (same stable key, size and positive modified time)
is indistinguishable under this metadata contract; explicit cover clear forces derivation.
For missing/zero size or modified metadata, the persisted scan timestamp is additionally used:
restart reuse remains possible, while a new Refresh conservatively invalidates the candidate.
This explicit contract avoids N SAF queries on RecyclerView binds and repeated full scans of
350+ title collections. It preserves existing discovery UX rather than pretending external
changes can be observed without reading metadata/content.

Fingerprint encoding is length-delimited: thumbnail version, root URI, then ordered candidate
key, URI, name, size and modified time (plus scan timestamp only for unknown metadata).
A cached winner validates its prefix, including failed higher-priority candidates. A changed
sidecar supersedes an archive fallback; changes to later non-cover chapters do not regenerate
a successful earlier cover. The full candidate fingerprint is resolved before Coil's memory
lookup by LocalCoverVersionInterceptor, without source I/O. A new version cannot reuse the
old version's memory key. The domain manga model also includes the version as a cover URL query,
so an existing grid cell is rebound after Refresh even if title/chapters are otherwise unchanged.
#557 still normalizes that URL to cover:<mangaId>. Smart Local manga/chapter URLs and IDs remain
unchanged. Cover plans are lazily memoized on immutable indexed books, with no second index map.

## Persistence, concurrency, and cleanup

Artifacts contain PNG <=768 px long edge, a candidate index, fingerprint and payload checksum.
No aggressive lossy encoding; direct-image EXIF orientation is applied before PNG encoding.
Archive discovery retains #554's streaming budgets: 64 entries, 8 image candidates, 8 MiB per
candidate and 32 MiB candidate-read budget. PDF extraction never forces archives through
seekable materialization.

Same-title requests use fixed bounded mutex stripes and recheck persistence before generation.
Two generation permits bound the complete miss workload (including SAF copy and PDF render),
not only native rendering. LocalPdfCache's existing rendering permits remain in place.

A write fsyncs a unique .partial then renames it atomically in the same directory. Partial
files are never readable entries; cancellation cannot publish incomplete payloads. Checksum,
length, version and fingerprint validation turn malformed artifacts into misses. Clear epochs
prevent an in-flight generation from repopulating persistent storage after explicit clear.
A request racing Refresh/removal retries against the current snapshot before returning.

Retention: 128 MiB, 1024 entries, maximum 4 MiB encoded payload. Entries idle for 30 days are
eligible for lazy cleanup at owner initialization/new writes. One artifact per manga replaces
obsolete versions atomically. Hits do not enumerate the cache; bytes are read under the cache
file mutex, so eviction never deletes an artifact still being streamed by Coil. Maintenance
never touches smart-local-content, Reader pages, remote covers or volatile Coil files.

## Automated evidence

SmartLocalCoverCacheTest tests recreation reuse, actual-source/fallback invalidation, single
flight, bounded concurrency/storage, corruption, cancellation/crash remnants, explicit-clear
races and unknown metadata versions. SmartLocalCoverPipelineTest executes real ImageRequests
through the domain owners and a test-only SAF DocumentsProvider, counts real extraction,
materialization and PDF cover-render calls, and verifies restart hits plus source refresh,
Reader backing/lazy-page safety and archive/sidecar/direct-image paths.

The SAF fixture provider exists only in the test APK. Test shell permissions are adopted for
MANAGE_DOCUMENTS; no production SAF grants/permissions or containment checks are changed.
Owner recreation is simulated by replacing ImageLoader, SmartLocalLibrary, LocalContentReader
and SmartLocalCoverCache; this is not a literal OS process kill or physical-device validation.

PR Compile & Unit Check validates the exact head and retains full compile/JVM checks.
The ci:runtime-required label enables an Android 15 job running the new pipeline suite together
with existing MiyorareImageDiskCacheTest. No merge is authorized.
