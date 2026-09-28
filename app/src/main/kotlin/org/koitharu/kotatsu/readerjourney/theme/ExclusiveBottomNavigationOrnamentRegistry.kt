package org.koitharu.kotatsu.readerjourney.theme

/**
 * Approved runtime ornament assets for the 12 Reader Journey Exclusive bottom-navigation themes.
 *
 * The WebP canvas is artwork-only. Geometry metadata separates:
 * - visible alpha bounds, used to keep the complete ornament on-screen; and
 * - the authored inner/body band, used for native icon/label/indicator placement.
 *
 * Asset bytes remain authoritative and must not be cropped, trimmed, resized or recompressed.
 */
data class ExclusiveBottomNavigationOrnamentSpec(
	val stableId: String,
	val assetPath: String,
	val visibleInsetStartFraction: Float,
	val visibleInsetEndFraction: Float,
	val visibleInsetTopFraction: Float,
	val visibleInsetBottomFraction: Float,
	val contentInsetTopFraction: Float,
	val contentInsetBottomFraction: Float,
) {
	val visibleWidthFraction: Float
		get() = 1f - visibleInsetStartFraction - visibleInsetEndFraction
	val visibleHeightFraction: Float
		get() = 1f - visibleInsetTopFraction - visibleInsetBottomFraction

	val contentInsetStartFraction: Float
		get() = visibleInsetStartFraction + ExclusiveBottomNavigationOrnamentRegistry.CONTENT_HORIZONTAL_GUARD_FRACTION
	val contentInsetEndFraction: Float
		get() = visibleInsetEndFraction + ExclusiveBottomNavigationOrnamentRegistry.CONTENT_HORIZONTAL_GUARD_FRACTION
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
	const val CONTENT_HORIZONTAL_GUARD_FRACTION = 0.015f
	const val MIN_TOUCH_TARGET_DP = 48f
	private const val ASSET_ROOT = "navigation/themes"

	/*
	 * Visible insets were measured from the alpha bounds of the approved runtime WebP files.
	 * Content top/bottom insets were measured from the continuous central body/frame band rather
	 * than decorative crystals, flares or glow. This metadata does not transform the bitmaps.
	 */
	val presets: List<ExclusiveBottomNavigationOrnamentSpec> = listOf(
		ExclusiveBottomNavigationOrnamentSpec(
			RankThemeId.FIRST_PAGE.stableId, "$ASSET_ROOT/01_First_Page_Silver.webp",
			0.109f, 0.110f, 0.300f, 0.312f, 0.349f, 0.355f,
		),
		ExclusiveBottomNavigationOrnamentSpec(
			RankThemeId.FIRST_LIGHT.stableId, "$ASSET_ROOT/02_First_Light_Blue.webp",
			0.105f, 0.104f, 0.238f, 0.262f, 0.283f, 0.324f,
		),
		ExclusiveBottomNavigationOrnamentSpec(
			RankThemeId.CYAN_CODEX.stableId, "$ASSET_ROOT/03_Cyan_Orbit.webp",
			0.123f, 0.108f, 0.275f, 0.262f, 0.314f, 0.305f,
		),
		ExclusiveBottomNavigationOrnamentSpec(
			RankThemeId.EMERALD_COMPASS.stableId, "$ASSET_ROOT/04_Emerald_Pulse.webp",
			0.108f, 0.109f, 0.253f, 0.275f, 0.311f, 0.311f,
		),
		ExclusiveBottomNavigationOrnamentSpec(
			RankThemeId.VIOLET_VAULT.stableId, "$ASSET_ROOT/05_Arcane_Scholar.webp",
			0.104f, 0.105f, 0.200f, 0.225f, 0.280f, 0.283f,
		),
		ExclusiveBottomNavigationOrnamentSpec(
			RankThemeId.ARCANE_SCHOLAR.stableId, "$ASSET_ROOT/06_Violet_Halo.webp",
			0.106f, 0.105f, 0.253f, 0.272f, 0.277f, 0.302f,
		),
		ExclusiveBottomNavigationOrnamentSpec(
			RankThemeId.NEON_ARCHIVE.stableId, "$ASSET_ROOT/07_Rose_Nebula.webp",
			0.108f, 0.108f, 0.234f, 0.225f, 0.296f, 0.255f,
		),
		ExclusiveBottomNavigationOrnamentSpec(
			RankThemeId.CRIMSON_LIBRARY.stableId, "$ASSET_ROOT/08_Crimson_Ember.webp",
			0.108f, 0.108f, 0.212f, 0.225f, 0.255f, 0.283f,
		),
		ExclusiveBottomNavigationOrnamentSpec(
			RankThemeId.EMBER_VETERAN.stableId, "$ASSET_ROOT/09_Amber_Manuscript.webp",
			0.110f, 0.108f, 0.225f, 0.275f, 0.308f, 0.311f,
		),
		ExclusiveBottomNavigationOrnamentSpec(
			RankThemeId.GOLDEN_MANUSCRIPT.stableId, "$ASSET_ROOT/10_Golden_Manuscript_Deluxe.webp",
			0.106f, 0.104f, 0.225f, 0.250f, 0.299f, 0.305f,
		),
		ExclusiveBottomNavigationOrnamentSpec(
			RankThemeId.IMPERIAL_AURORA.stableId, "$ASSET_ROOT/11_Eternal_Library_Prism.webp",
			0.117f, 0.116f, 0.272f, 0.234f, 0.336f, 0.327f,
		),
		ExclusiveBottomNavigationOrnamentSpec(
			RankThemeId.ETERNAL_LIBRARY.stableId, "$ASSET_ROOT/12_Celestial_Infinity.webp",
			0.118f, 0.120f, 0.269f, 0.219f, 0.330f, 0.274f,
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
			if (spec.visibleWidthFraction !in 0.70f..0.82f) {
				errors += "invalid visible navigation width: ${spec.stableId}"
			}
			if (spec.visibleHeightFraction !in 0.35f..0.60f) {
				errors += "invalid visible navigation height: ${spec.stableId}"
			}
			if (spec.contentWidthFraction <= 0f || spec.contentHeightFraction !in 0.28f..0.47f) {
				errors += "invalid navigation content region: ${spec.stableId}"
			}
			if (
				spec.contentInsetTopFraction <= spec.visibleInsetTopFraction ||
				spec.contentInsetBottomFraction <= spec.visibleInsetBottomFraction
			) {
				errors += "content region must stay inside visible artwork: ${spec.stableId}"
			}
			val firstCenter = spec.slotCenterFraction(0)
			val lastCenter = spec.slotCenterFraction(4)
			if (firstCenter < 0.18f || lastCenter > 0.82f) {
				errors += "navigation edge slots escape inner region: ${spec.stableId}"
			}
		}
		RankThemeId.entries.forEach { id ->
			if (resolve(id.stableId) == null) errors += "missing navigation ornament: ${id.stableId}"
		}
		return errors
	}
}
