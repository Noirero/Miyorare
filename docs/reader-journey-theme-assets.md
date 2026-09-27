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

The September 2026 badge quality-lock revision uses the project-owner supplied **MIYORARE 12 Konsep Badge Eksklusif** poster as the GOLDEN REFERENCE. Stable badge IDs remain unchanged for persisted loadout compatibility; the visual foundations are now selected per tier according to measured fidelity instead of forcing one asset format.

| Tier | Runtime foundation | Thumbnail | Intended identity |
| --- | --- | --- | --- |
| 01 | VectorDrawable `badge_01_first_page_silver_base` | vector-scaled | silver open book + diamond points |
| 02 | VectorDrawable `badge_02_first_light_blue_base` | vector-scaled | blue guiding compass/star + gold trim |
| 03 | VectorDrawable `badge_03_cyan_orbit_base` | vector-scaled | luminous cyan planet + orbit rings/orbs |
| 04 | VectorDrawable `badge_04_emerald_pulse_base` | vector-scaled | emerald crystal + botanical gold |
| 05 | generated transparent WebP `badge_05_arcane_scholar_base` | dedicated 384px WebP | arcane book + rune/star + hanging crystal |
| 06 | generated transparent WebP `badge_06_violet_halo_base` | dedicated 384px WebP | crescent moon + pearl orbs + ritual halo |
| 07 | generated transparent WebP `badge_07_rose_nebula_base` | dedicated 384px WebP | rose + branch ring + cosmic bloom |
| 08 | generated transparent WebP `badge_08_crimson_ember_base` | dedicated 384px WebP | ruby crest + hot-gold/flame ornaments |
| 09 | generated transparent WebP `badge_09_amber_manuscript_base` | dedicated 384px WebP | manuscript + quill + antique gold |
| 10 | generated transparent WebP `badge_10_golden_manuscript_deluxe_base` | dedicated 384px WebP | crown + laurel + royal gem/jewels |
| 11 | generated transparent WebP `badge_11_eternal_library_prism_base` | dedicated 384px WebP | angular multi-facet refractive prism/shards |
| 12 | generated transparent WebP `badge_12_celestial_infinity_base` | dedicated 384px WebP | flowing infinity orbit + celestial star |

Tiers 05-12 are stored in the deterministic source payload `app/src/main/badge-assets/exclusive_badge_material_payload.b64`. The build decodes that payload into local `drawable-nodpi` WebP resources before Android resource merge. This keeps the installed/runtime representation as transparent WebP while allowing the repository integration path to preserve exact artwork bytes. The same payload carries the compact golden-reference contact sheet used only by Android visual tests.

Most facet, reflection, rim/specular light, parchment detail, crown/laurel detail, and celestial geometry is baked into the static artwork. Runtime code is deliberately limited to state treatment plus restrained halo, glint, shimmer, orbit/light-segment accents and reveal/press feedback. The badge must remain Exclusive-looking with animation disabled.

The asset decision is intentionally hybrid:
- 01-04 retain vectors because their geometry remains readable and lightweight.
- 05-10 use baked WebP material foundations because the GOLDEN REFERENCE depends on layered ornament/material depth.
- 11 uses baked WebP refractive facets and chromatic edges; runtime shimmer is only an accent.
- 12 uses baked WebP infinity/orbit geometry and luminous white-gold/cyan material; runtime light sweep is only an accent.

Any future replacement must beat the current candidate side-by-side at actual app sizes and remain inside the same performance/size gates.

## Packaging note

Badge resources remain local-only with no network decode path. Grid/catalog is fully static (including the selected tile; selection is expressed by the tile border/press feedback). The dedicated 148dp preview uses the full intended visual with one restrained ambient renderer. The 34dp profile badge is static with reduced glow so the profile frame remains the hero. CI captures all 12 static and preview renders, a grayscale 09-12 sheet, Reduce Motion evidence and Battery Saver evidence before merge. Any future WebP/AVIF/hybrid replacement must be recorded here and measured by the same visual/performance gates.
