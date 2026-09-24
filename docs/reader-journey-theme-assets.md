# Reader Journey Rank Theme Asset Provenance

This manifest tracks visual assets introduced by the Reader Journey Rank Theme system.

## Policy

- No copyrighted character art, manga panels, anime wallpapers, Pinterest/Google Images downloads, or unknown-origin artwork.
- Rank theme assets must be original project work, generated specifically for Miyorare, properly licensed, or public domain with required attribution recorded here.
- The reading content itself is never a theme asset.
- Stable asset IDs are presentation identifiers; Rank Theme ownership continues to derive from Reader Journey progression.
- The current 12-theme pack uses original static procedural Compose rendering only; no external raster/vector artwork is bundled.

## Theme visual IDs

| Theme | Badge | Frame | Wallpaper | Card | Progress | Origin / license |
| --- | --- | --- | --- | --- | --- | --- |
| First Page | `NEWCOMER_CRYSTAL_BADGE` | `NEWCOMER_SIMPLE_GRAPHITE_FRAME` | `NEWCOMER_MINIMAL_PAGES_WALLPAPER` | `NEWCOMER_GRAPHITE_PAPER_CARD` | `NEWCOMER_SILVER_GRAPHITE_PROGRESS` | Original Miyorare procedural implementation; project source license |
| First Light | `READER_STAR_BADGE` | `READER_SIMPLE_BLUE_FRAME` | `READER_NIGHT_LIBRARY_BLUE_WALLPAPER` | `READER_BLUE_LIBRARY_CARD` | `READER_BLUE_PROGRESS` | Original Miyorare procedural implementation; project source license |
| Cyan Codex | `BOOKWORM_OPEN_BOOK_BADGE` | `BOOKWORM_CYAN_FRAME` | `BOOKWORM_DIGITAL_CODEX_WALLPAPER` | `BOOKWORM_CYAN_CODEX_CARD` | `BOOKWORM_CYAN_PROGRESS` | Original Miyorare procedural implementation; project source license |
| Emerald Compass | `EXPLORER_COMPASS_BADGE` | `EXPLORER_EMERALD_FRAME` | `EXPLORER_MAP_LABYRINTH_WALLPAPER` | `EXPLORER_EMERALD_MAP_CARD` | `EXPLORER_EMERALD_PROGRESS` | Original Miyorare procedural implementation; project source license |
| Violet Vault | `COLLECTOR_GEM_BADGE` | `COLLECTOR_VIOLET_FRAME` | `COLLECTOR_VIOLET_VAULT_WALLPAPER` | `COLLECTOR_VIOLET_VAULT_CARD` | `COLLECTOR_PURPLE_PROGRESS` | Original Miyorare procedural implementation; project source license |
| Arcane Scholar | `SCHOLAR_ARCANE_STAR_BADGE` | `SCHOLAR_ARCANE_FRAME` | `SCHOLAR_ARCANE_MANUSCRIPT_WALLPAPER` | `SCHOLAR_ARCANE_MANUSCRIPT_CARD` | `SCHOLAR_VIOLET_LAVENDER_PROGRESS` | Original Miyorare procedural implementation; project source license |
| Neon Archive | `ARCHIVIST_ARCHIVE_SEAL_BADGE` | `ARCHIVIST_NEON_MAGENTA_FRAME` | `ARCHIVIST_DIGITAL_NIGHT_ARCHIVE_WALLPAPER` | `ARCHIVIST_NEON_ARCHIVE_GLASS_CARD` | `ARCHIVIST_PINK_MAGENTA_PROGRESS` | Original Miyorare procedural implementation; project source license |
| Crimson Library | `BIBLIOPHILE_ROSE_BOOK_BADGE` | `BIBLIOPHILE_CRIMSON_FRAME` | `BIBLIOPHILE_CLASSIC_CRIMSON_LIBRARY_WALLPAPER` | `BIBLIOPHILE_CRIMSON_LIBRARY_CARD` | `BIBLIOPHILE_CRIMSON_PROGRESS` | Original Miyorare procedural implementation; project source license |
| Ember Veteran | `VETERAN_FLAME_WING_BADGE` | `VETERAN_EMBER_FRAME` | `VETERAN_WARM_EMBER_LIBRARY_WALLPAPER` | `VETERAN_EMBER_LIBRARY_CARD` | `VETERAN_AMBER_ORANGE_PROGRESS` | Original Miyorare procedural implementation; project source license |
| Golden Manuscript | `MASTER_CROWN_BOOK_BADGE` | `MASTER_CHAMPAGNE_FRAME` | `MASTER_GOLDEN_MANUSCRIPT_LIBRARY_WALLPAPER` | `MASTER_GOLDEN_MANUSCRIPT_CARD` | `MASTER_DARK_GOLD_CHAMPAGNE_PROGRESS` | Original Miyorare procedural implementation; project source license |
| Imperial Aurora | `GRAND_CROWN_RUNE_BADGE` | `GRAND_AURORA_FRAME` | `GRAND_AURORA_COSMIC_ARCHIVE_WALLPAPER` | `GRAND_AURORA_LIBRARY_CARD` | `GRAND_VIOLET_GOLD_PROGRESS` | Original Miyorare procedural implementation; project source license |
| Eternal Library | `LEGEND_PRISM_CROWN_BADGE` | `LEGEND_PRISM_FRAME` | `LEGEND_ETERNAL_COSMIC_LIBRARY_WALLPAPER` | `LEGEND_ETERNAL_LIBRARY_CARD` | `LEGEND_SUBTLE_PRISM_PROGRESS` | Original Miyorare procedural implementation; project source license |

## Packaging note

All current rank badge/frame/wallpaper/card/progress visuals are static local code paths. They add no raster wallpaper files, image decode dependency, network dependency, or persistent animation. If a future revision adds WebP/AVIF/vector resources, every new file must be recorded here before merge and measured by the Preview APK size baseline workflow.
