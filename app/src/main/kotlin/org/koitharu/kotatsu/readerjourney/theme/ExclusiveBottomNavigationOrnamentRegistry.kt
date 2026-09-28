package org.koitharu.kotatsu.readerjourney.theme

/**
 * Approved runtime ornament assets for the 12 Reader Journey Exclusive bottom-navigation themes.
 *
 * These WebP files are decorative skins only. Navigation destinations, icons, labels, selection,
 * touch targets, accessibility and navigation behavior remain native in the shared renderer.
 *
 * Keep the filenames and ordinal mapping stable: the source artwork is already runtime-normalized
 * to 960x320 RGBA with transparent safe margins and must not be cropped, trimmed or recompressed.
 */
data class ExclusiveBottomNavigationOrnamentSpec(
	val stableId: String,
	val assetPath: String,
)

object ExclusiveBottomNavigationOrnamentRegistry {
	const val ASPECT_RATIO = 3f
	private const val ASSET_ROOT = "navigation/themes"

	val presets: List<ExclusiveBottomNavigationOrnamentSpec> = listOf(
		ExclusiveBottomNavigationOrnamentSpec(RankThemeId.FIRST_PAGE.stableId, "$ASSET_ROOT/01_First_Page_Silver.webp"),
		ExclusiveBottomNavigationOrnamentSpec(RankThemeId.FIRST_LIGHT.stableId, "$ASSET_ROOT/02_First_Light_Blue.webp"),
		ExclusiveBottomNavigationOrnamentSpec(RankThemeId.CYAN_CODEX.stableId, "$ASSET_ROOT/03_Cyan_Orbit.webp"),
		ExclusiveBottomNavigationOrnamentSpec(RankThemeId.EMERALD_COMPASS.stableId, "$ASSET_ROOT/04_Emerald_Pulse.webp"),
		ExclusiveBottomNavigationOrnamentSpec(RankThemeId.VIOLET_VAULT.stableId, "$ASSET_ROOT/05_Arcane_Scholar.webp"),
		ExclusiveBottomNavigationOrnamentSpec(RankThemeId.ARCANE_SCHOLAR.stableId, "$ASSET_ROOT/06_Violet_Halo.webp"),
		ExclusiveBottomNavigationOrnamentSpec(RankThemeId.NEON_ARCHIVE.stableId, "$ASSET_ROOT/07_Rose_Nebula.webp"),
		ExclusiveBottomNavigationOrnamentSpec(RankThemeId.CRIMSON_LIBRARY.stableId, "$ASSET_ROOT/08_Crimson_Ember.webp"),
		ExclusiveBottomNavigationOrnamentSpec(RankThemeId.EMBER_VETERAN.stableId, "$ASSET_ROOT/09_Amber_Manuscript.webp"),
		ExclusiveBottomNavigationOrnamentSpec(RankThemeId.GOLDEN_MANUSCRIPT.stableId, "$ASSET_ROOT/10_Golden_Manuscript_Deluxe.webp"),
		ExclusiveBottomNavigationOrnamentSpec(RankThemeId.IMPERIAL_AURORA.stableId, "$ASSET_ROOT/11_Eternal_Library_Prism.webp"),
		ExclusiveBottomNavigationOrnamentSpec(RankThemeId.ETERNAL_LIBRARY.stableId, "$ASSET_ROOT/12_Celestial_Infinity.webp"),
	)

	private val byStableId = presets.associateBy { it.stableId }

	fun resolve(stableId: String?): ExclusiveBottomNavigationOrnamentSpec? =
		stableId?.let(byStableId::get)

	fun validate(): List<String> {
		val errors = mutableListOf<String>()
		if (presets.size != RankThemeId.entries.size) errors += "navigation ornament/theme count mismatch"
		if (presets.map { it.stableId }.distinct().size != presets.size) errors += "duplicate navigation ornament stable id"
		if (presets.map { it.assetPath }.distinct().size != presets.size) errors += "duplicate navigation ornament asset"
		RankThemeId.entries.forEach { id ->
			if (resolve(id.stableId) == null) errors += "missing navigation ornament: ${id.stableId}"
		}
		return errors
	}
}
