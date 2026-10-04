# Smart Local Library — implementation audit

Base: `0d44abe9ab4f27e9205341749163f0121c733eca` (`beta`, PR #493).
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

## Validation boundary

Local Gradle invocation was attempted: wrapper download failed with `Network is unreachable`; no Android SDK is installed here. This is not a passing build. JVM/instrumentation sources will be added and existing CI used for actual Android compilation/runtime evidence where available. No workflow changes are planned.
