# Reader Journey Rank Theme Asset Provenance

This manifest tracks visual assets introduced by the Reader Journey Rank Theme system.

## Policy

- No copyrighted character art, manga panels, anime wallpapers, Pinterest/Google Images downloads, or unknown-origin artwork.
- Rank theme assets must be original project work, generated specifically for Miyorare, properly licensed, or public domain with required attribution recorded here.
- The reading content itself is never a theme asset.
- Stable asset IDs are presentation identifiers; Rank Theme ownership continues to derive from Reader Journey progression.
- The current badge pack uses original Miyorare-authored local drawable foundations plus restrained Compose runtime accents; no external artwork or network asset is used.

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

## Exclusive badge drawable foundations

The September 2026 badge quality-lock revision replaces the former generic procedural badge body with one authored static foundation per tier. Stable badge IDs above are deliberately unchanged for persisted loadout compatibility.

| Tier | Local drawable foundation | Intended identity |
| --- | --- | --- |
| 01 | `badge_01_first_page_silver_base.xml` | silver open book + diamond points |
| 02 | `badge_02_first_light_blue_base.xml` | blue guiding compass/star + gold trim |
| 03 | `badge_03_cyan_orbit_base.xml` | luminous cyan planet + orbit rings/orbs |
| 04 | `badge_04_emerald_pulse_base.xml` | emerald crystal + botanical gold |
| 05 | `badge_05_arcane_scholar_base.xml` | arcane book + rune/star + hanging crystal |
| 06 | `badge_06_violet_halo_base.xml` | crescent moon + pearl orbs + ritual halo |
| 07 | `badge_07_rose_nebula_base.xml` | rose + branch ring + cosmic bloom |
| 08 | `badge_08_crimson_ember_base.xml` | ruby crest + hot-gold/flame ornaments |
| 09 | `badge_09_amber_manuscript_base.xml` | manuscript + quill + antique gold |
| 10 | `badge_10_golden_manuscript_deluxe_base.xml` | crown + laurel + royal gem/jewels |
| 11 | `badge_11_eternal_library_prism_base.xml` | angular multi-facet prism/shards |
| 12 | `badge_12_celestial_infinity_base.xml` | flowing infinity orbit + celestial star |

These drawables are original project work authored specifically for Miyorare and inherit the project source license. The renderer treats them as static material foundations: silhouette, midtone, rim/specular highlights and most depth live in the drawable. Runtime code is limited to state treatment, restrained halo, glint/shimmer/orbit/light-segment accents and reveal/press feedback.

The project-owner supplied **MIYORARE 12 Konsep Badge Eksklusif** poster is the golden reference for this revision. Vector-backed foundations are retained only while the generated static/preview evidence preserves the required silhouette, center symbol, ornament placement and material hierarchy at real app sizes. Tiers 05-12 remain eligible for transparent WebP/hybrid replacement behind the same registry if side-by-side evidence shows a meaningful fidelity gain; format choice is not allowed to override the visual gate.

## Packaging note

Badge resources remain local-only with no network decode path. Grid/catalog is fully static (including the selected tile; selection is expressed by the tile border/press feedback). The dedicated 136dp preview uses the full intended visual with one restrained ambient renderer. The 34dp profile badge is static with reduced glow so the profile frame remains the hero. CI captures all 12 static and preview renders, a grayscale 09-12 sheet, Reduce Motion evidence and Battery Saver evidence before merge. Any future WebP/AVIF/hybrid replacement must be recorded here and measured by the same visual/performance gates.
