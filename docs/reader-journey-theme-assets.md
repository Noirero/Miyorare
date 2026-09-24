# Reader Journey Rank Theme Asset Provenance

This manifest tracks visual assets introduced by the Reader Journey Rank Theme system.

## Policy

- No copyrighted character art, manga panels, anime wallpapers, Pinterest/Google Images downloads, or unknown-origin artwork.
- Rank theme assets must be original project work, generated specifically for Miyorare, properly licensed, or public domain with required attribution recorded here.
- The reading content itself is never a theme asset.
- Stable asset IDs are presentation identifiers; Rank Theme ownership continues to derive from Reader Journey progression.

## Reference themes

| Asset ID | Theme | Kind | Implementation / filename | Origin | License / attribution |
| --- | --- | --- | --- | --- | --- |
| NEWCOMER_CRYSTAL_BADGE | First Page | Badge | Procedural Compose renderer in `ReferenceRankThemeVisuals.kt` | Original Miyorare project implementation | Project source license; no external attribution |
| NEWCOMER_SIMPLE_GRAPHITE_FRAME | First Page | Profile frame | Procedural Compose renderer | Original Miyorare project implementation | Project source license; no external attribution |
| NEWCOMER_MINIMAL_PAGES_WALLPAPER | First Page | Wallpaper motif | Static gradient + page outlines, procedural Compose renderer | Original Miyorare project implementation | Project source license; no external attribution |
| NEWCOMER_GRAPHITE_PAPER_CARD | First Page | Card identity | Semantic theme/card style; no external bitmap | Original Miyorare project implementation | Project source license; no external attribution |
| NEWCOMER_SILVER_GRAPHITE_PROGRESS | First Page | Progress | Procedural static gradient renderer | Original Miyorare project implementation | Project source license; no external attribution |
| ARCHIVIST_ARCHIVE_SEAL_BADGE | Neon Archive | Badge | Procedural Compose renderer in `ReferenceRankThemeVisuals.kt` | Original Miyorare project implementation | Project source license; no external attribution |
| ARCHIVIST_NEON_MAGENTA_FRAME | Neon Archive | Profile frame | Procedural Compose renderer | Original Miyorare project implementation | Project source license; no external attribution |
| ARCHIVIST_DIGITAL_NIGHT_ARCHIVE_WALLPAPER | Neon Archive | Wallpaper motif | Static gradient + archive-grid motif, procedural Compose renderer | Original Miyorare project implementation | Project source license; no external attribution |
| ARCHIVIST_NEON_ARCHIVE_GLASS_CARD | Neon Archive | Card identity | Semantic theme/card style; no external bitmap | Original Miyorare project implementation | Project source license; no external attribution |
| ARCHIVIST_PINK_MAGENTA_PROGRESS | Neon Archive | Progress | Procedural static gradient renderer | Original Miyorare project implementation | Project source license; no external attribution |

## Packaging note

The reference themes currently add no raster wallpaper files. Their reference badge/frame/wallpaper/progress visuals are static local code paths, so they do not add image decode/network dependencies and have negligible APK asset weight. If later revisions add WebP/AVIF/vector resources, every file must be added to this manifest before merge.
