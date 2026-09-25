package org.koitharu.kotatsu.readerjourney.theme

/**
 * Shared visual primitives for all Reader Journey rank themes.
 *
 * Stable IDs describe presentation only. They never grant ownership and never encode progression.
 * The renderer is shared across all ranks; each rank only supplies a compact visual spec.
 */
enum class ReferenceBadgeStyle {
	CRYSTAL,
	STAR,
	OPEN_BOOK,
	COMPASS,
	GEM,
	ARCANE_STAR,
	ARCHIVE_SEAL,
	ROSE_BOOK,
	FLAME_WING,
	CROWN_BOOK,
	AURORA_PRISM_CREST,
	CELESTIAL_PRISM_CROWN,
}

enum class ReferenceFrameStyle {
	NEWCOMER_CRYSTAL_RING,
	READER_PAGE_RING,
	BOOKWORM_CODEX_RING,
	EXPLORER_COMPASS_RING,
	COLLECTOR_GEM_VAULT,
	SCHOLAR_ARCANE_CREST,
	ARCHIVIST_NEON_SEAL,
	BIBLIOPHILE_ROSE_CREST,
	VETERAN_EMBER_WINGS,
	MASTER_GOLDEN_CROWN,
	GRAND_AURORA_HALO,
	LEGEND_PRISM_CROWN,
}

enum class ReferenceNameplateStyle {
	NEWCOMER_CRYSTAL_CAPSULE,
	READER_BOOKMARK,
	BOOKWORM_CODEX_TAB,
	EXPLORER_COMPASS_BANNER,
	COLLECTOR_GEM_PLAQUE,
	SCHOLAR_ARCANE_PLAQUE,
	ARCHIVIST_NEON_ARCHIVE,
	BIBLIOPHILE_ROSE_BANNER,
	VETERAN_EMBER_BANNER,
	MASTER_GOLDEN_MANUSCRIPT,
	GRAND_AURORA_CEREMONIAL,
	LEGEND_PRISM_RELIC,
}

enum class ReferenceWallpaperStyle {
	MINIMAL_PAGES,
	NIGHT_LIBRARY_BLUE,
	DIGITAL_CODEX,
	MAP_LABYRINTH,
	VIOLET_VAULT,
	ARCANE_MANUSCRIPT,
	DIGITAL_NIGHT_ARCHIVE,
	CLASSIC_CRIMSON_LIBRARY,
	WARM_EMBER_LIBRARY,
	GOLDEN_MANUSCRIPT_LIBRARY,
	AURORA_COSMIC_ARCHIVE,
	ETERNAL_COSMIC_LIBRARY,
}

enum class ReferenceCardStyle {
	GRAPHITE_PAPER,
	BLUE_LIBRARY_GLASS,
	CYAN_CODEX_GLASS,
	EMERALD_MAP_GLASS,
	VIOLET_VAULT_GLASS,
	ARCANE_MANUSCRIPT_GLASS,
	NEON_ARCHIVE_GLASS,
	CRIMSON_LIBRARY_GLASS,
	EMBER_LIBRARY_GLASS,
	GOLDEN_MANUSCRIPT_GLASS,
	AURORA_LIBRARY_GLASS,
	ETERNAL_LIBRARY_GLASS,
}

enum class ReferenceProgressStyle {
	SILVER_GRAPHITE,
	BLUE,
	CYAN,
	EMERALD,
	PURPLE,
	VIOLET_LAVENDER,
	PINK_MAGENTA,
	CRIMSON,
	AMBER_ORANGE,
	DARK_GOLD_CHAMPAGNE,
	AURORA_PRISM,
	CELESTIAL_PRISM,
}

data class ReferenceRankThemeVisualSpec(
	val themeId: RankThemeId,
	val badgeId: String,
	val badgeStyle: ReferenceBadgeStyle,
	val frameId: String,
	val frameStyle: ReferenceFrameStyle,
	val nameplateId: String,
	val nameplateStyle: ReferenceNameplateStyle,
	val wallpaperId: String,
	val wallpaperStyle: ReferenceWallpaperStyle,
	val cardId: String,
	val cardStyle: ReferenceCardStyle,
	val progressId: String,
	val progressStyle: ReferenceProgressStyle,
	val rankTitle: String,
	val wallpaperOptional: Boolean = true,
	val version: Int = 1,
)

object RankThemeVisualRegistry {

	val firstPage = ReferenceRankThemeVisualSpec(
		themeId = RankThemeId.FIRST_PAGE,
		badgeId = "NEWCOMER_CRYSTAL_BADGE",
		badgeStyle = ReferenceBadgeStyle.CRYSTAL,
		frameId = "NEWCOMER_SIMPLE_GRAPHITE_FRAME",
		frameStyle = ReferenceFrameStyle.NEWCOMER_CRYSTAL_RING,
		nameplateId = "NEWCOMER_CRYSTAL_NAMEPLATE",
		nameplateStyle = ReferenceNameplateStyle.NEWCOMER_CRYSTAL_CAPSULE,
		wallpaperId = "NEWCOMER_MINIMAL_PAGES_WALLPAPER",
		wallpaperStyle = ReferenceWallpaperStyle.MINIMAL_PAGES,
		cardId = "NEWCOMER_GRAPHITE_PAPER_CARD",
		cardStyle = ReferenceCardStyle.GRAPHITE_PAPER,
		progressId = "NEWCOMER_SILVER_GRAPHITE_PROGRESS",
		progressStyle = ReferenceProgressStyle.SILVER_GRAPHITE,
		rankTitle = "Newcomer",
	)

	val firstLight = ReferenceRankThemeVisualSpec(
		themeId = RankThemeId.FIRST_LIGHT,
		badgeId = "READER_STAR_BADGE",
		badgeStyle = ReferenceBadgeStyle.STAR,
		frameId = "READER_SIMPLE_BLUE_FRAME",
		frameStyle = ReferenceFrameStyle.READER_PAGE_RING,
		nameplateId = "READER_BOOKMARK_NAMEPLATE",
		nameplateStyle = ReferenceNameplateStyle.READER_BOOKMARK,
		wallpaperId = "READER_NIGHT_LIBRARY_BLUE_WALLPAPER",
		wallpaperStyle = ReferenceWallpaperStyle.NIGHT_LIBRARY_BLUE,
		cardId = "READER_BLUE_LIBRARY_CARD",
		cardStyle = ReferenceCardStyle.BLUE_LIBRARY_GLASS,
		progressId = "READER_BLUE_PROGRESS",
		progressStyle = ReferenceProgressStyle.BLUE,
		rankTitle = "Reader",
	)

	val cyanCodex = ReferenceRankThemeVisualSpec(
		themeId = RankThemeId.CYAN_CODEX,
		badgeId = "BOOKWORM_OPEN_BOOK_BADGE",
		badgeStyle = ReferenceBadgeStyle.OPEN_BOOK,
		frameId = "BOOKWORM_CYAN_FRAME",
		frameStyle = ReferenceFrameStyle.BOOKWORM_CODEX_RING,
		nameplateId = "BOOKWORM_CODEX_NAMEPLATE",
		nameplateStyle = ReferenceNameplateStyle.BOOKWORM_CODEX_TAB,
		wallpaperId = "BOOKWORM_DIGITAL_CODEX_WALLPAPER",
		wallpaperStyle = ReferenceWallpaperStyle.DIGITAL_CODEX,
		cardId = "BOOKWORM_CYAN_CODEX_CARD",
		cardStyle = ReferenceCardStyle.CYAN_CODEX_GLASS,
		progressId = "BOOKWORM_CYAN_PROGRESS",
		progressStyle = ReferenceProgressStyle.CYAN,
		rankTitle = "Bookworm",
	)

	val emeraldCompass = ReferenceRankThemeVisualSpec(
		themeId = RankThemeId.EMERALD_COMPASS,
		badgeId = "EXPLORER_COMPASS_BADGE",
		badgeStyle = ReferenceBadgeStyle.COMPASS,
		frameId = "EXPLORER_EMERALD_FRAME",
		frameStyle = ReferenceFrameStyle.EXPLORER_COMPASS_RING,
		nameplateId = "EXPLORER_COMPASS_NAMEPLATE",
		nameplateStyle = ReferenceNameplateStyle.EXPLORER_COMPASS_BANNER,
		wallpaperId = "EXPLORER_MAP_LABYRINTH_WALLPAPER",
		wallpaperStyle = ReferenceWallpaperStyle.MAP_LABYRINTH,
		cardId = "EXPLORER_EMERALD_MAP_CARD",
		cardStyle = ReferenceCardStyle.EMERALD_MAP_GLASS,
		progressId = "EXPLORER_EMERALD_PROGRESS",
		progressStyle = ReferenceProgressStyle.EMERALD,
		rankTitle = "Explorer",
	)

	val violetVault = ReferenceRankThemeVisualSpec(
		themeId = RankThemeId.VIOLET_VAULT,
		badgeId = "COLLECTOR_GEM_BADGE",
		badgeStyle = ReferenceBadgeStyle.GEM,
		frameId = "COLLECTOR_VIOLET_FRAME",
		frameStyle = ReferenceFrameStyle.COLLECTOR_GEM_VAULT,
		nameplateId = "COLLECTOR_GEM_NAMEPLATE",
		nameplateStyle = ReferenceNameplateStyle.COLLECTOR_GEM_PLAQUE,
		wallpaperId = "COLLECTOR_VIOLET_VAULT_WALLPAPER",
		wallpaperStyle = ReferenceWallpaperStyle.VIOLET_VAULT,
		cardId = "COLLECTOR_VIOLET_VAULT_CARD",
		cardStyle = ReferenceCardStyle.VIOLET_VAULT_GLASS,
		progressId = "COLLECTOR_PURPLE_PROGRESS",
		progressStyle = ReferenceProgressStyle.PURPLE,
		rankTitle = "Collector",
	)

	val arcaneScholar = ReferenceRankThemeVisualSpec(
		themeId = RankThemeId.ARCANE_SCHOLAR,
		badgeId = "SCHOLAR_ARCANE_STAR_BADGE",
		badgeStyle = ReferenceBadgeStyle.ARCANE_STAR,
		frameId = "SCHOLAR_ARCANE_FRAME",
		frameStyle = ReferenceFrameStyle.SCHOLAR_ARCANE_CREST,
		nameplateId = "SCHOLAR_ARCANE_NAMEPLATE",
		nameplateStyle = ReferenceNameplateStyle.SCHOLAR_ARCANE_PLAQUE,
		wallpaperId = "SCHOLAR_ARCANE_MANUSCRIPT_WALLPAPER",
		wallpaperStyle = ReferenceWallpaperStyle.ARCANE_MANUSCRIPT,
		cardId = "SCHOLAR_ARCANE_MANUSCRIPT_CARD",
		cardStyle = ReferenceCardStyle.ARCANE_MANUSCRIPT_GLASS,
		progressId = "SCHOLAR_VIOLET_LAVENDER_PROGRESS",
		progressStyle = ReferenceProgressStyle.VIOLET_LAVENDER,
		rankTitle = "Scholar",
	)

	val neonArchive = ReferenceRankThemeVisualSpec(
		themeId = RankThemeId.NEON_ARCHIVE,
		badgeId = "ARCHIVIST_ARCHIVE_SEAL_BADGE",
		badgeStyle = ReferenceBadgeStyle.ARCHIVE_SEAL,
		frameId = "ARCHIVIST_NEON_MAGENTA_FRAME",
		frameStyle = ReferenceFrameStyle.ARCHIVIST_NEON_SEAL,
		nameplateId = "ARCHIVIST_NEON_NAMEPLATE",
		nameplateStyle = ReferenceNameplateStyle.ARCHIVIST_NEON_ARCHIVE,
		wallpaperId = "ARCHIVIST_DIGITAL_NIGHT_ARCHIVE_WALLPAPER",
		wallpaperStyle = ReferenceWallpaperStyle.DIGITAL_NIGHT_ARCHIVE,
		cardId = "ARCHIVIST_NEON_ARCHIVE_GLASS_CARD",
		cardStyle = ReferenceCardStyle.NEON_ARCHIVE_GLASS,
		progressId = "ARCHIVIST_PINK_MAGENTA_PROGRESS",
		progressStyle = ReferenceProgressStyle.PINK_MAGENTA,
		rankTitle = "Archivist",
	)

	val crimsonLibrary = ReferenceRankThemeVisualSpec(
		themeId = RankThemeId.CRIMSON_LIBRARY,
		badgeId = "BIBLIOPHILE_ROSE_BOOK_BADGE",
		badgeStyle = ReferenceBadgeStyle.ROSE_BOOK,
		frameId = "BIBLIOPHILE_CRIMSON_FRAME",
		frameStyle = ReferenceFrameStyle.BIBLIOPHILE_ROSE_CREST,
		nameplateId = "BIBLIOPHILE_ROSE_NAMEPLATE",
		nameplateStyle = ReferenceNameplateStyle.BIBLIOPHILE_ROSE_BANNER,
		wallpaperId = "BIBLIOPHILE_CLASSIC_CRIMSON_LIBRARY_WALLPAPER",
		wallpaperStyle = ReferenceWallpaperStyle.CLASSIC_CRIMSON_LIBRARY,
		cardId = "BIBLIOPHILE_CRIMSON_LIBRARY_CARD",
		cardStyle = ReferenceCardStyle.CRIMSON_LIBRARY_GLASS,
		progressId = "BIBLIOPHILE_CRIMSON_PROGRESS",
		progressStyle = ReferenceProgressStyle.CRIMSON,
		rankTitle = "Bibliophile",
	)

	val emberVeteran = ReferenceRankThemeVisualSpec(
		themeId = RankThemeId.EMBER_VETERAN,
		badgeId = "VETERAN_FLAME_WING_BADGE",
		badgeStyle = ReferenceBadgeStyle.FLAME_WING,
		frameId = "VETERAN_EMBER_FRAME",
		frameStyle = ReferenceFrameStyle.VETERAN_EMBER_WINGS,
		nameplateId = "VETERAN_EMBER_NAMEPLATE",
		nameplateStyle = ReferenceNameplateStyle.VETERAN_EMBER_BANNER,
		wallpaperId = "VETERAN_WARM_EMBER_LIBRARY_WALLPAPER",
		wallpaperStyle = ReferenceWallpaperStyle.WARM_EMBER_LIBRARY,
		cardId = "VETERAN_EMBER_LIBRARY_CARD",
		cardStyle = ReferenceCardStyle.EMBER_LIBRARY_GLASS,
		progressId = "VETERAN_AMBER_ORANGE_PROGRESS",
		progressStyle = ReferenceProgressStyle.AMBER_ORANGE,
		rankTitle = "Veteran Reader",
	)

	val goldenManuscript = ReferenceRankThemeVisualSpec(
		themeId = RankThemeId.GOLDEN_MANUSCRIPT,
		badgeId = "MASTER_CROWN_BOOK_BADGE",
		badgeStyle = ReferenceBadgeStyle.CROWN_BOOK,
		frameId = "MASTER_CHAMPAGNE_FRAME",
		frameStyle = ReferenceFrameStyle.MASTER_GOLDEN_CROWN,
		nameplateId = "MASTER_GOLDEN_NAMEPLATE",
		nameplateStyle = ReferenceNameplateStyle.MASTER_GOLDEN_MANUSCRIPT,
		wallpaperId = "MASTER_GOLDEN_MANUSCRIPT_LIBRARY_WALLPAPER",
		wallpaperStyle = ReferenceWallpaperStyle.GOLDEN_MANUSCRIPT_LIBRARY,
		cardId = "MASTER_GOLDEN_MANUSCRIPT_CARD",
		cardStyle = ReferenceCardStyle.GOLDEN_MANUSCRIPT_GLASS,
		progressId = "MASTER_DARK_GOLD_CHAMPAGNE_PROGRESS",
		progressStyle = ReferenceProgressStyle.DARK_GOLD_CHAMPAGNE,
		rankTitle = "Master Reader",
	)

	val imperialAurora = ReferenceRankThemeVisualSpec(
		themeId = RankThemeId.IMPERIAL_AURORA,
		badgeId = "GRAND_CROWN_RUNE_BADGE",
		badgeStyle = ReferenceBadgeStyle.AURORA_PRISM_CREST,
		frameId = "GRAND_AURORA_FRAME",
		frameStyle = ReferenceFrameStyle.GRAND_AURORA_HALO,
		nameplateId = "GRAND_AURORA_NAMEPLATE",
		nameplateStyle = ReferenceNameplateStyle.GRAND_AURORA_CEREMONIAL,
		wallpaperId = "GRAND_AURORA_COSMIC_ARCHIVE_WALLPAPER",
		wallpaperStyle = ReferenceWallpaperStyle.AURORA_COSMIC_ARCHIVE,
		cardId = "GRAND_AURORA_LIBRARY_CARD",
		cardStyle = ReferenceCardStyle.AURORA_LIBRARY_GLASS,
		progressId = "GRAND_VIOLET_GOLD_PROGRESS",
		progressStyle = ReferenceProgressStyle.AURORA_PRISM,
		rankTitle = "Grand Reader",
	)

	val eternalLibrary = ReferenceRankThemeVisualSpec(
		themeId = RankThemeId.ETERNAL_LIBRARY,
		badgeId = "LEGEND_PRISM_CROWN_BADGE",
		badgeStyle = ReferenceBadgeStyle.CELESTIAL_PRISM_CROWN,
		frameId = "LEGEND_PRISM_FRAME",
		frameStyle = ReferenceFrameStyle.LEGEND_PRISM_CROWN,
		nameplateId = "LEGEND_PRISM_NAMEPLATE",
		nameplateStyle = ReferenceNameplateStyle.LEGEND_PRISM_RELIC,
		wallpaperId = "LEGEND_ETERNAL_COSMIC_LIBRARY_WALLPAPER",
		wallpaperStyle = ReferenceWallpaperStyle.ETERNAL_COSMIC_LIBRARY,
		cardId = "LEGEND_ETERNAL_LIBRARY_CARD",
		cardStyle = ReferenceCardStyle.ETERNAL_LIBRARY_GLASS,
		progressId = "LEGEND_SUBTLE_PRISM_PROGRESS",
		progressStyle = ReferenceProgressStyle.CELESTIAL_PRISM,
		rankTitle = "Legend",
	)

	val all: List<ReferenceRankThemeVisualSpec> = listOf(
		firstPage,
		firstLight,
		cyanCodex,
		emeraldCompass,
		violetVault,
		arcaneScholar,
		neonArchive,
		crimsonLibrary,
		emberVeteran,
		goldenManuscript,
		imperialAurora,
		eternalLibrary,
	)

	fun resolve(themeId: RankThemeId): ReferenceRankThemeVisualSpec? =
		all.firstOrNull { it.themeId == themeId }

	fun validate(): List<String> {
		val errors = mutableListOf<String>()
		if (all.map { it.themeId }.toSet() != RankThemeId.entries.toSet()) {
			errors += "visual registry must contain exactly one spec for every rank theme"
		}
		if (all.size != RankThemeId.entries.size) {
			errors += "visual registry/theme count mismatch"
		}
		val stableAssetIds = all.flatMap {
			listOf(it.badgeId, it.frameId, it.nameplateId, it.wallpaperId, it.cardId, it.progressId)
		}
		if (stableAssetIds.any(String::isBlank)) errors += "rank visual id is blank"
		if (stableAssetIds.distinct().size != stableAssetIds.size) errors += "duplicate rank visual id"
		all.forEach { spec ->
			if (!spec.wallpaperOptional) errors += "theme must remain usable with wallpaper off: ${spec.themeId.stableId}"
			if (spec.version <= 0) errors += "invalid rank visual version: ${spec.themeId.stableId}"
			if (spec.rankTitle.isBlank()) errors += "missing rank title: ${spec.themeId.stableId}"
		}
		return errors
	}
}

/**
 * Compatibility anchor used by Phase 3 regression tests. These remain the two canonical reference
 * themes even after the full 12-theme visual registry is enabled.
 */
object ReferenceRankThemeVisualRegistry {
	val firstPage: ReferenceRankThemeVisualSpec
		get() = RankThemeVisualRegistry.firstPage
	val neonArchive: ReferenceRankThemeVisualSpec
		get() = RankThemeVisualRegistry.neonArchive
	val all: List<ReferenceRankThemeVisualSpec>
		get() = listOf(firstPage, neonArchive)

	fun resolve(themeId: RankThemeId): ReferenceRankThemeVisualSpec? = when (themeId) {
		RankThemeId.FIRST_PAGE -> firstPage
		RankThemeId.NEON_ARCHIVE -> neonArchive
		else -> null
	}

	fun validate(): List<String> {
		val errors = mutableListOf<String>()
		val expected = setOf(RankThemeId.FIRST_PAGE, RankThemeId.NEON_ARCHIVE)
		if (all.map { it.themeId }.toSet() != expected) {
			errors += "reference registry must contain only First Page and Neon Archive"
		}
		return errors
	}
}
