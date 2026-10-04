# Smart Local Library — implementation audit

Base: `0d44abe9ab4f27e9205341749163f0121c733eca` (`beta`, PR #493).
Latest beta integrated during implementation: `3f4bad52f38121039005fad74fd0287e4e2925b0` (PR #497). The earlier PR #494 Favorites discovery, chapter-offline status and layout fixes, and PR #497 reconciliation across all favorite spaces, are retained.
Source of truth: `MIYORARE_SMART_LOCAL_LIBRARY_SPEC_2026-10-04.txt`, all 37 sections read.

| Existing component | Reuse / gap |
| --- | --- |
| LocalListFragment / LocalListViewModel | Manga-oriented list, search, Details navigation, list/grid and selection already exist. Folder management currently goes to Download Directory settings. Extend the list and its headers/actions. |
| LocalStorageManager | Existing storage list combines app imports, custom paths, Normal/Private download destinations. Keep this download compatibility infrastructure; selected Local roots need a separate SAF-backed store. |
| LocalMangaIndex | Room index, background scan, atomic index swap, offline SD preservation, download aliases. Keep for imports/downloads and exact identity lookup; add a bridge for selected-root records without persisting SAF as a fake physical path. |
| LocalMangaRepository | Existing download resolution and ownership are space-aware. Preserve them. Route managed Local Details/pages/deletion to selected-root library. |
| LocalMangaParser | Reuse archive page parser, PDF cache and EPUB parser. File-based parser cannot directly access arbitrary SAF providers. Use a bounded, on-demand seekable content cache for archive/PDF/EPUB; folder images remain individual documents. |
| Import flow | Keep explicit import and existing imported items; selected-folder reading never requires reimport or writes into download destinations. |
| Reader/history | Standard Local source, ReaderIntent and Room progress already work. Preserve IDs across rescans and use last_reader_activity_at for Continue Reading, not administrative updated_at. |
| Metadata/cover | index.json/EPUB metadata and first-image fallback exist; add optional ComicInfo XML enrichment with corrupt-metadata fallback. |
| SAF | Current path resolution depends on a real path and silently swallows permission errors. New roots retain actual tree grants; do not require broad storage access. |
| Favorites Local | Existing space/private isolation and shelf remain. Add a snapshot projection of managed Local manga, without root management or scans in Favorites. |

## Structural ambiguity

`Manga/Arc/Chapter.cbz` and `Container/Manga/Chapter.cbz` can have identical directory topology. No structural algorithm can know the intended title boundary for every metadata-free single-child chain. Such chains are reviewable rather than guessed; an explicit user-confirmed manga folder is persisted. Direct chapter collections, image chapter folders, multiple-title containers and single files are deterministic. No application/source names are hard-coded.

## Requirement → implementation map

| Spec sections / acceptance intent | Implementation |
| --- | --- |
| 1–4, selected roots and management | `LocalListFragment`, `LocalListViewModel`, `SmartLocalLibrary.addRoot`; standard document-tree picker, independent root configuration, actual persistable tree grants; never resolves a SAF tree into a guessed File path. |
| 2–3, selected-root isolation / multiple roots | `LocalDocuments`, `LocalTreeScanner`; every traversal edge must be proven inside its selected root. Canonical paths reject symlink escape; SAF uses the provider's descendant query with opaque IDs. Duplicate/overlapping roots are rejected or deduplicated. |
| 5, expandable Folder Lokal (N) • X manga | Headers, root/chapter counts and saved expansion state in the Local list. Add-folder action is available even when the section is collapsed. |
| 6, detach root | `removeRoot` removes configuration and its indexed books only; no file deletion. Grants shared with old import/download flows are retained. |
| 7, hide / physical deletion | Persistent exclusions in SharedPreferences plus atomic index snapshot. Restore action in Local Files. Physical deletion validates every owned leaf and shared ownership, rejects root/outside targets, and never recursively removes a directory. Destructive confirmation is separate from hiding. |
| 8–9, manga library / statistics | Existing list/grid presentation and MangaListMapper reused; manga/chapter/unread counts, collection statistics, advanced filters in a dialog. Technical information stays in explicit Info/Diagnosis actions. |
| 10, newly found chapters | Rescan compares stable document keys, persists new counts and supports explicit acknowledgement. It does not rename or move user files. |
| 11–12, progress / Continue / sort | Existing Room history and ReaderIntent. Continue uses real `last_reader_activity_at` and the existing completion threshold. All/unread/reading/completed filters; last-read/added/title ascending/title descending/latest-chapter sorts. Search covers title and author. |
| 13–16, Details / offline chapters / Reader | `LocalMangaRepository` delegates only smart-local identities. Existing Details, ChaptersMapper, ChaptersLoader, ReaderActivity and ReaderIntent remain authoritative. Local chapters display as available offline. PageLoader and reader-mode sampling support content URIs. |
| 17, progressive diagnosis | Recognized manga remain in the main collection. Review/unsupported/empty/unavailable/unreadable items have explicit diagnosis. Info exposes URI, size, last scan, chapter count and ignored files only on request. |
| 18, 20–21, CBZ/ZIP / image directories / mixed representations | Storage-independent structural scanner plus LocalContentReader. Natural page order; direct CBZ/ZIP and image-folder chapters coexist within one title. XML is optional. |
| 19, extension display | Display preference changes title rendering only. Original document names, URIs and chapter/page identities remain unchanged. |
| 22–24, PDF / EPUB / single-file titles | Existing LocalPdfCache and EpubParser reused. Files directly under a root become individual titles. PDF/EPUB volume collections retain their natural file ordering; the native EPUB reader receives local HTML and images through the same repository. |
| 25–27, metadata / cover | Bounded optional ComicInfo/metadata XML and index.json enrichment. Archive/EPUB metadata is enriched lazily when opening a single-file title; no scan-wide archive copying. Explicit cover → referenced metadata image → first readable page → existing default artwork. Corrupt metadata/cover never removes valid chapters. |
| 28, natural order | Decimal-aware, overflow-safe numeric tokens; original labels retained. Chapter 1, 1.5, 2, 10 and image 2 before 10 are covered. |
| 29–32, nested groups / sources | Iterative topology classification, no source-name/depth rules. Multiple-title containers are traversed. Unprovable single-child title/group boundaries require review and an explicitly confirmed title folder; confirmation is persistent. |
| 33–34, Favorites / downloads / reuse | Favorites Local projects the managed snapshot into its existing space-isolated shelf. No root management in Favorites. Legacy LocalMangaIndex, import flow, Normal/Private downloads and download destinations remain separate. Legacy imported-file sharing is retained. |
| 35, deferred ambiguity | Loose pages mixed with book files/subdirectories and conflicting text/image representations remain `Perlu diperiksa`. No permanent heuristic is invented for those structures. |
| 36, acceptance | Production scanner scenarios and Android runtime fixtures cover discovery, supported formats, actual Reader loading, persistence, exclusions, non-destructive detach and bounded deletion. See validation evidence below and the PR's final check results. |

## Identity and storage trade-offs

New SAF identities derive from authority/document ID, independent of tree-URI wrappers; file manga identities retain the original URI path used by LocalMangaParser, while canonical paths enforce containment and deduplication. Chapter IDs survive extension preference changes, scan ordering changes, rescan and reconstruction of the index. A moved file with a new provider ID/path is treated as a newly discovered identity; the scanner does not guess a history match by title.

When an already-read, ordinary file-backed folder/PDF/single-chapter archive is adopted, its legacy chapter IDs are retained only if the current history chapter can be proven. Previously read EPUBs retain their old spine sections and IDs, preserving the old chapter/page/scroll boundary. Existing imported/downloaded records remain on their original infrastructure. Automatic download chapter cleanup does not apply to selected user-owned roots; physical removal requires the explicit Local manga/chapter action. An unusual legacy archive with multiple internal chapter directories has no automatic title-boundary migration; no approximate progress is invented.

SAF archives/PDF/EPUB need a seekable cache copy on first open. Image-folder pages remain independent document streams. The 512 MiB cache budget is soft: archives already handed to an active Reader are pinned for the process lifetime to avoid invalidating its page URIs. Old, unpinned copies are reclaimed on subsequent use; large active volumes can exceed that budget and need free cache space.

Physical removal deletes indexed owned files first. Unknown/new files are retained. File-backed empty chapter/title directories can then be removed with non-recursive File.delete. Empty SAF directory shells are retained because DocumentsProvider directory deletion may be recursive and cannot guarantee safety against concurrently added unknown files. No parent, sibling or selected root is ever a deletion target. Partial provider failures are surfaced and followed by a rescan; success/exclusion is committed only after all owned file deletions succeed.

Providers that cannot prove descendant ownership fail closed with an unavailable diagnosis. SD-card speed, provider-specific persisted-grant behavior and physical removal failures still require device/provider testing beyond the deterministic emulator fixtures.

## Validation evidence

Local Gradle invocation was attempted: wrapper download failed with `Network is unreachable`; no Android SDK is installed here. This is not a passing local Android build.

Executed locally:

- JDK compilation of the production Java scanner and executable fixtures, followed by **38 passing assertions**: selected-root boundaries, outside-edge rejection, cycles, 1,500 nested levels, metadata-free manga, image chapters, mixed image/archive representations, PDF/EPUB/single files, container classification, confirmation, exclusions/restore, natural sorting and non-mutating extension display.
- `git diff --check`: passed.
- Smart Local resources XML parsing: passed.
- `test_p0_p1_contract.py`: 16 tests passed.
- `test_android_runtime_paths.py`: 12 tests passed.
- `test_ci_deep_paths.py`: 16 tests passed.

GitHub Actions CI Deep on implementation commit `6585eaa0038cfd60d6d6170bedf4919b3ae21554` passed the full JVM suite and Android test compilation for Debug and Preview. CI Fast, identity and P0/P1 ownership checks also passed. Subsequent Reader/metadata hardening is being validated on the PR head; the final PR description records the actual executed results, not a presumed pass.

`SmartLocalLibraryRuntimeTest` uses production Hilt dependencies, Room, persisted JSON/SharedPreferences, format backends and ReaderActivity. It checks content loading/decoding, native EPUB text rendering, content-URI image decoding, offline chapter mapping, restored index/history, legacy resume, metadata/cover fallback, added chapters, exclusions after reconstruction/rescan, restoration, detach, moved content, unavailable roots and physical deletion preserving siblings/parent/unknown files. MediaStore provides a real content URI for PageLoader; this is not a claim of testing every OEM SAF provider or a full process kill.

The runtime workflow's fixed test list now includes this class; its original four acceptance classes remain present. The pinned P0/P1 workflow fingerprint was updated after auditing that additive change. No CI routing, signing, release or promotion policy changed. The explicit runtime-required label requests emulator evidence for this owner-request, non-theme task.
