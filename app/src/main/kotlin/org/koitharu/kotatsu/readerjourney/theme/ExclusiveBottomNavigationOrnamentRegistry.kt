package org.koitharu.kotatsu.readerjourney.theme

/**
 * Approved runtime ornament assets for the 12 Reader Journey Exclusive bottom-navigation themes.
 *
 * The 960x320 WebP canvas is decorative artwork, not native touch geometry. Native tabs use one
 * shared responsive horizontal inner region, while the small per-theme vertical fractions align
 * icons/labels/selected chrome with each authored central frame/body band.
 *
 * Asset bytes remain authoritative and must not be cropped, trimmed, resized or recompressed.
 */
data class ExclusiveBottomNavigationOrnamentSpec(
	val stableId: String,
	val assetPath: String,
	val contentInsetTopFraction: Float,
	val contentInsetBottomFraction: Float,
) {
	val contentInsetStartFraction: Float
		get() = ExclusiveBottomNavigationOrnamentRegistry.CONTENT_HORIZONTAL_INSET_FRACTION
	val contentInsetEndFraction: Float
		get() = ExclusiveBottomNavigationOrnamentRegistry.CONTENT_HORIZONTAL_INSET_FRACTION
	val contentWidthFraction: Float
		get() = 1f - contentInsetStartFraction - contentInsetEndFraction
	val contentHeightFraction: Float
		get() = 1f - contentInsetTopFraction - contentInsetBottomFraction

	fun slotCenterFraction(slotIndex: Int, slotCount: Int = 5): Float {
		require(slotCount > 0)
		require(slotIndex in 0 until slotCount)
		return contentInsetStartFraction +
			contentWidthFraction * ((slotIndex + 0.5f) / slotCount.toFloat())
	}
}

object ExclusiveBottomNavigationOrnamentRegistry {
	const val ASPECT_RATIO = 3f

	/**
	 * Shared proportional content inset: 11% of the rendered ornament width on each side.
	 * It scales with device width and keeps first/last slot centres at ~18.8%/~81.2%.
	 */
	const val CONTENT_HORIZONTAL_INSET_FRACTION = 0.11f
	const val MIN_TOUCH_TARGET_DP = 48f
	private const val ASSET_ROOT = "navigation/themes"

	/*
	 * Only vertical body-band alignment varies by theme. These are display fractions, not bitmap
	 * transforms or pixel coordinates. Horizontal placement intentionally stays shared/responsive.
	 */
	val presets: List<ExclusiveBottomNavigationOrnamentSpec> = listOf(
		ExclusiveBottomNavigationOrnamentSpec(
			RankThemeId.FIRST_PAGE.stableId, "$ASSET_ROOT/01_First_Page_Silver.webp",
			0.349f, 0.355f,
		),
		ExclusiveBottomNavigationOrnamentSpec(
			RankThemeId.FIRST_LIGHT.stableId, "$ASSET_ROOT/02_First_Light_Blue.webp",
			0.283f, 0.324f,
		),
		ExclusiveBottomNavigationOrnamentSpec(
			RankThemeId.CYAN_CODEX.stableId, "$ASSET_ROOT/03_Cyan_Orbit.webp",
			0.314f, 0.305f,
		),
		ExclusiveBottomNavigationOrnamentSpec(
			RankThemeId.EMERALD_COMPASS.stableId, "$ASSET_ROOT/04_Emerald_Pulse.webp",
			0.311f, 0.311f,
		),
		ExclusiveBottomNavigationOrnamentSpec(
			RankThemeId.VIOLET_VAULT.stableId, "$ASSET_ROOT/05_Arcane_Scholar.webp",
			0.280f, 0.283f,
		),
		ExclusiveBottomNavigationOrnamentSpec(
			RankThemeId.ARCANE_SCHOLAR.stableId, "$ASSET_ROOT/06_Violet_Halo.webp",
			0.277f, 0.302f,
		),
		ExclusiveBottomNavigationOrnamentSpec(
			RankThemeId.NEON_ARCHIVE.stableId, "$ASSET_ROOT/07_Rose_Nebula.webp",
			0.296f, 0.255f,
		),
		ExclusiveBottomNavigationOrnamentSpec(
			RankThemeId.CRIMSON_LIBRARY.stableId, "$ASSET_ROOT/08_Crimson_Ember.webp",
			0.255f, 0.283f,
		),
		ExclusiveBottomNavigationOrnamentSpec(
			RankThemeId.EMBER_VETERAN.stableId, "$ASSET_ROOT/09_Amber_Manuscript.webp",
			0.308f, 0.311f,
		),
		ExclusiveBottomNavigationOrnamentSpec(
			RankThemeId.GOLDEN_MANUSCRIPT.stableId, "$ASSET_ROOT/10_Golden_Manuscript_Deluxe.webp",
			0.299f, 0.305f,
		),
		ExclusiveBottomNavigationOrnamentSpec(
			RankThemeId.IMPERIAL_AURORA.stableId, "$ASSET_ROOT/11_Eternal_Library_Prism.webp",
			0.336f, 0.327f,
		),
		ExclusiveBottomNavigationOrnamentSpec(
			RankThemeId.ETERNAL_LIBRARY.stableId, "$ASSET_ROOT/12_Celestial_Infinity.webp",
			0.330f, 0.274f,
		),
	)

	private val byStableId = presets.associateBy { it.stableId }

	fun resolve(stableId: String?): ExclusiveBottomNavigationOrnamentSpec? =
		stableId?.let(byStableId::get)

	fun validate(): List<String> {
		val errors = mutableListOf<String>()
		if (presets.size != RankThemeId.entries.size) errors += "navigation ornament/theme count mismatch"
		if (presets.map { it.stableId }.distinct().size != presets.size) errors += "duplicate navigation ornament stable id"
		if (presets.map { it.assetPath }.distinct().size != presets.size) errors += "duplicate navigation ornament asset"
		presets.forEach { spec ->
			if (spec.contentWidthFraction !in 0.76f..0.80f) {
				errors += "invalid responsive navigation width: ${spec.stableId}"
			}
			if (spec.contentHeightFraction !in 0.28f..0.47f) {
				errors += "invalid navigation content region: ${spec.stableId}"
			}
			if (spec.contentInsetTopFraction !in 0.20f..0.36f ||
				spec.contentInsetBottomFraction !in 0.20f..0.36f
			) {
				errors += "invalid vertical navigation inset: ${spec.stableId}"
			}
			val centers = (0 until 5).map(spec::slotCenterFraction)
			if (centers.first() < 0.18f || centers.last() > 0.82f) {
				errors += "navigation edge slots escape inner region: ${spec.stableId}"
			}
			if (kotlin.math.abs(centers[2] - 0.5f) > 0.001f) {
				errors += "middle navigation slot is not centered: ${spec.stableId}"
			}
		}
		RankThemeId.entries.forEach { id ->
			if (resolve(id.stableId) == null) errors += "missing navigation ornament: ${id.stableId}"
		}
		return errors
	}
}
