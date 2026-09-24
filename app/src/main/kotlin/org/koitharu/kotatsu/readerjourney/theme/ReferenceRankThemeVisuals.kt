package org.koitharu.kotatsu.readerjourney.theme

/**
 * Visual primitives for the two mandatory reference themes.
 *
 * These IDs describe presentation only. They do not grant ownership and they do not encode
 * progression. Renderers may change implementation without changing persisted RankThemeId.
 */
enum class ReferenceBadgeStyle {
	CRYSTAL,
	ARCHIVE_SEAL,
}

enum class ReferenceFrameStyle {
	SIMPLE_GRAPHITE,
	NEON_MAGENTA_EDGE,
}

enum class ReferenceWallpaperStyle {
	MINIMAL_PAGES,
	DIGITAL_NIGHT_ARCHIVE,
}

enum class ReferenceCardStyle {
	GRAPHITE_PAPER,
	NEON_ARCHIVE_GLASS,
}

enum class ReferenceProgressStyle {
	SILVER_GRAPHITE,
	PINK_MAGENTA,
}

data class ReferenceRankThemeVisualSpec(
	val themeId: RankThemeId,
	val badgeId: String,
	val badgeStyle: ReferenceBadgeStyle,
	val frameId: String,
	val frameStyle: ReferenceFrameStyle,
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

/**
 * Reference themes are deliberately limited to FIRST PAGE and NEON ARCHIVE until the shared engine,
 * gallery, content isolation and performance gates prove stable. Do not scale this visual registry
 * to the other ten ranks prematurely.
 */
object ReferenceRankThemeVisualRegistry {

	val firstPage = ReferenceRankThemeVisualSpec(
		themeId = RankThemeId.FIRST_PAGE,
		badgeId = "NEWCOMER_CRYSTAL_BADGE",
		badgeStyle = ReferenceBadgeStyle.CRYSTAL,
		frameId = "NEWCOMER_SIMPLE_GRAPHITE_FRAME",
		frameStyle = ReferenceFrameStyle.SIMPLE_GRAPHITE,
		wallpaperId = "NEWCOMER_MINIMAL_PAGES_WALLPAPER",
		wallpaperStyle = ReferenceWallpaperStyle.MINIMAL_PAGES,
		cardId = "NEWCOMER_GRAPHITE_PAPER_CARD",
		cardStyle = ReferenceCardStyle.GRAPHITE_PAPER,
		progressId = "NEWCOMER_SILVER_GRAPHITE_PROGRESS",
		progressStyle = ReferenceProgressStyle.SILVER_GRAPHITE,
		rankTitle = "Newcomer",
	)

	val neonArchive = ReferenceRankThemeVisualSpec(
		themeId = RankThemeId.NEON_ARCHIVE,
		badgeId = "ARCHIVIST_ARCHIVE_SEAL_BADGE",
		badgeStyle = ReferenceBadgeStyle.ARCHIVE_SEAL,
		frameId = "ARCHIVIST_NEON_MAGENTA_FRAME",
		frameStyle = ReferenceFrameStyle.NEON_MAGENTA_EDGE,
		wallpaperId = "ARCHIVIST_DIGITAL_NIGHT_ARCHIVE_WALLPAPER",
		wallpaperStyle = ReferenceWallpaperStyle.DIGITAL_NIGHT_ARCHIVE,
		cardId = "ARCHIVIST_NEON_ARCHIVE_GLASS_CARD",
		cardStyle = ReferenceCardStyle.NEON_ARCHIVE_GLASS,
		progressId = "ARCHIVIST_PINK_MAGENTA_PROGRESS",
		progressStyle = ReferenceProgressStyle.PINK_MAGENTA,
		rankTitle = "Archivist",
	)

	val all: List<ReferenceRankThemeVisualSpec> = listOf(firstPage, neonArchive)

	fun resolve(themeId: RankThemeId): ReferenceRankThemeVisualSpec? =
		all.firstOrNull { it.themeId == themeId }

	fun validate(): List<String> {
		val errors = mutableListOf<String>()
		val expected = setOf(RankThemeId.FIRST_PAGE, RankThemeId.NEON_ARCHIVE)
		if (all.map { it.themeId }.toSet() != expected) {
			errors += "reference registry must contain only First Page and Neon Archive"
		}

		val stableAssetIds = all.flatMap {
			listOf(it.badgeId, it.frameId, it.wallpaperId, it.cardId, it.progressId)
		}
		if (stableAssetIds.any(String::isBlank)) errors += "reference visual id is blank"
		if (stableAssetIds.distinct().size != stableAssetIds.size) errors += "duplicate reference visual id"

		all.forEach { spec ->
			if (!spec.wallpaperOptional) errors += "theme must remain usable with wallpaper off: ${spec.themeId.stableId}"
			if (spec.version <= 0) errors += "invalid reference visual version: ${spec.themeId.stableId}"
			if (spec.rankTitle.isBlank()) errors += "missing rank title: ${spec.themeId.stableId}"
		}
		return errors
	}
}
