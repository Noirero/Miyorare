package org.koitharu.kotatsu.readerjourney.theme

/**
 * Geometry and lightweight ornament language for the 12 Reader Journey Exclusive bottom-navigation
 * concepts. The renderer stays generic: rank/theme identity is resolved here once and consumers
 * never branch on concrete [RankThemeId] values.
 *
 * The ordinal mapping intentionally follows Reader Journey rank order. Concept/display names are
 * presentation-only and can be renamed later without changing persisted stable IDs.
 */
enum class ExclusiveNavigationSilhouette {
	CAPSULE,
	ANGULAR,
	NOTCHED,
	AGGRESSIVE,
	BEVELED,
	ORNAMENTAL,
	PRISM,
	CELESTIAL,
}

enum class ExclusiveNavigationActiveShape {
	SOFT_HALO,
	RING,
	ORBIT_RING,
	HEX_GEM,
	DOUBLE_HALO,
	BUBBLE,
	EMBER_RING,
	MEDALLION,
	CROWN_MEDALLION,
	PRISM_DOUBLE_RING,
	LUMINOUS_ORB,
}

enum class ExclusiveNavigationOrnament {
	NONE,
	TOP_FLARE,
	ORBIT,
	SIDE_LINES,
	DIAMONDS,
	STARS,
	NEBULA_STARS,
	EMBERS,
	MANUSCRIPT,
	GOLD_FINIALS,
	PRISM_SHARDS,
	INFINITY_ARCS,
}

enum class ExclusiveNavigationIndicator {
	UNDERLINE,
	LIGHT_SEED,
	NONE,
}

/**
 * Motion identity from the combined implementation guide + animation addon.
 * Keep motion authored as data so the renderer remains one engine instead of twelve components.
 */
enum class ExclusiveNavigationMotion {
	CLEAN_REVEAL,
	BLUE_PULSE,
	CYAN_ORBIT,
	EMERALD_PULSE,
	ARCANE_SHIMMER,
	VIOLET_HALO,
	ROSE_NEBULA,
	CRIMSON_EMBER,
	AMBER_SWEEP,
	GOLDEN_MEDALLION,
	PRISM_SHIMMER,
	CELESTIAL_INFINITY,
}

data class ExclusiveBottomNavigationSpec(
	val stableId: String,
	val conceptName: String,
	val heightDp: Float,
	val cornerRadiusDp: Float,
	val borderWidthDp: Float,
	val activeDiameterDp: Float,
	val silhouette: ExclusiveNavigationSilhouette,
	val activeShape: ExclusiveNavigationActiveShape,
	val ornament: ExclusiveNavigationOrnament,
	val motion: ExclusiveNavigationMotion,
	val indicator: ExclusiveNavigationIndicator = ExclusiveNavigationIndicator.UNDERLINE,
	val innerHighlight: Boolean = true,
	val doubleBorder: Boolean = false,
	val staticDotCount: Int = 0,
	val topFlare: Boolean = false,
	val bottomFlare: Boolean = false,
	val selectionDurationMs: Int = 200,
	/** Duration of the short one-shot accent (flare/pulse/glint/gem) after a real tab selection. */
	val selectionAccentDurationMs: Int = 320,
	/** Optional long traveling highlight. This must never stretch the one-shot accent timeline. */
	val selectionSweepDurationMs: Int? = null,
	/** Null means fully static ambient decoration. Otherwise this is deliberately slow. */
	val ambientCycleMs: Int? = null,
	val containerStops: List<Long>,
	val borderStops: List<Long>,
	val selectedStops: List<Long>,
	val glowStops: List<Long>,
	val iconStops: List<Long>,
	val content: Long = 0xFFFFFFFFL,
	val mutedContent: Long = 0xB8F5F7FFL,
	val interactiveText: Long,
	val containerMix: Float = 0.22f,
	val selectedMix: Float = 0.62f,
	val iconMix: Float = 0.48f,
) {
	init {
		require(heightDp in 72f..86f)
		require(cornerRadiusDp in 20f..32f)
		require(borderWidthDp in 1f..1.5f)
		require(activeDiameterDp in 42f..56f)
		require(selectionDurationMs in 160..240)
		require(selectionAccentDurationMs in 160..1_000)
		require(selectionSweepDurationMs == null || selectionSweepDurationMs in 1_200..1_600)
		require(ambientCycleMs == null || ambientCycleMs >= 5_000)
		require(containerStops.size >= 2)
		require(borderStops.size >= 2)
		require(selectedStops.size >= 2)
		require(glowStops.size >= 2)
		require(iconStops.size >= 2)
	}

	fun navigationAuthoring(): ExclusiveThemeComponentAuthoring = ExclusiveThemeComponentAuthoring(
		containerStops = containerStops,
		borderStops = borderStops,
		selectedStops = selectedStops,
		glowStops = glowStops,
		iconStops = iconStops,
		content = content,
		mutedContent = mutedContent,
		interactiveText = interactiveText,
		containerMix = containerMix,
		selectedMix = selectedMix,
		iconMix = iconMix,
	)
}

/**
 * One engine + 12 presets. No navigation screen owns rank-specific color or geometry decisions.
 */
object ExclusiveBottomNavigationRegistry {

	val presets: List<ExclusiveBottomNavigationSpec> = listOf(
		// 01 — First Page Silver
		ExclusiveBottomNavigationSpec(
			stableId = RankThemeId.FIRST_PAGE.stableId,
			conceptName = "First Page Silver",
			heightDp = 76f,
			cornerRadiusDp = 30f,
			borderWidthDp = 1.25f,
			activeDiameterDp = 46f,
			silhouette = ExclusiveNavigationSilhouette.CAPSULE,
			activeShape = ExclusiveNavigationActiveShape.SOFT_HALO,
			ornament = ExclusiveNavigationOrnament.NONE,
			motion = ExclusiveNavigationMotion.CLEAN_REVEAL,
			topFlare = true,
			selectionAccentDurationMs = 200,
			containerStops = listOf(0xFF11151BL, 0xFF171C23L, 0xFF11151BL),
			borderStops = listOf(0xFF8693A3L, 0xFFF3F6FAL, 0xFFBFC8D4L),
			selectedStops = listOf(0x334F5966L, 0x66F3F6FAL, 0x334F5966L),
			glowStops = listOf(0x33BFC8D4L, 0x66F3F6FAL, 0x33BFC8D4L),
			iconStops = listOf(0xFFF3F6FAL, 0xFFBFC8D4L),
			interactiveText = 0xFFF3F6FAL,
			containerMix = 0.12f,
			selectedMix = 0.52f,
			selectionDurationMs = 180,
		),
		// 02 — First Light Blue
		ExclusiveBottomNavigationSpec(
			stableId = RankThemeId.FIRST_LIGHT.stableId,
			conceptName = "First Light Blue",
			heightDp = 76f,
			cornerRadiusDp = 30f,
			borderWidthDp = 1.25f,
			activeDiameterDp = 48f,
			silhouette = ExclusiveNavigationSilhouette.CAPSULE,
			activeShape = ExclusiveNavigationActiveShape.RING,
			ornament = ExclusiveNavigationOrnament.TOP_FLARE,
			motion = ExclusiveNavigationMotion.BLUE_PULSE,
			topFlare = true,
			selectionAccentDurationMs = 320,
			containerStops = listOf(0xFF0B1220L, 0xFF0D1830L, 0xFF0B1220L),
			borderStops = listOf(0xFF3478FFL, 0xFF8DC5FFL, 0xFF3478FFL),
			selectedStops = listOf(0x553478FFL, 0x994DA3FFL, 0x553478FFL),
			glowStops = listOf(0x334DA3FFL, 0x668DC5FFL, 0x334DA3FFL),
			iconStops = listOf(0xFFFFFFFFL, 0xFF8DC5FFL),
			interactiveText = 0xFF8DC5FFL,
			selectionDurationMs = 190,
		),
		// 03 — Cyan Orbit
		ExclusiveBottomNavigationSpec(
			stableId = RankThemeId.CYAN_CODEX.stableId,
			conceptName = "Cyan Orbit",
			heightDp = 78f,
			cornerRadiusDp = 30f,
			borderWidthDp = 1.25f,
			activeDiameterDp = 50f,
			silhouette = ExclusiveNavigationSilhouette.CAPSULE,
			activeShape = ExclusiveNavigationActiveShape.ORBIT_RING,
			ornament = ExclusiveNavigationOrnament.ORBIT,
			motion = ExclusiveNavigationMotion.CYAN_ORBIT,
			staticDotCount = 3,
			selectionAccentDurationMs = 200,
			containerStops = listOf(0xFF061419L, 0xFF082129L, 0xFF061419L),
			borderStops = listOf(0xFF0A5563L, 0xFF00DDF5L, 0xFF60F6FFL),
			selectedStops = listOf(0x440A5563L, 0x8800DDF5L, 0x4460F6FFL),
			glowStops = listOf(0x3300DDF5L, 0x6660F6FFL, 0x3300DDF5L),
			iconStops = listOf(0xFF60F6FFL, 0xFFFFFFFFL),
			interactiveText = 0xFF60F6FFL,
			selectionDurationMs = 200,
			ambientCycleMs = 10_000,
		),
		// 04 — Emerald Pulse
		ExclusiveBottomNavigationSpec(
			stableId = RankThemeId.EMERALD_COMPASS.stableId,
			conceptName = "Emerald Pulse",
			heightDp = 78f,
			cornerRadiusDp = 27f,
			borderWidthDp = 1.25f,
			activeDiameterDp = 48f,
			silhouette = ExclusiveNavigationSilhouette.ANGULAR,
			activeShape = ExclusiveNavigationActiveShape.RING,
			ornament = ExclusiveNavigationOrnament.SIDE_LINES,
			motion = ExclusiveNavigationMotion.EMERALD_PULSE,
			topFlare = true,
			selectionAccentDurationMs = 320,
			ambientCycleMs = 6_000,
			containerStops = listOf(0xFF071511L, 0xFF0A211AL, 0xFF071511L),
			borderStops = listOf(0xFF0B382CL, 0xFF12C99BL, 0xFF67FFD5L),
			selectedStops = listOf(0x440B382CL, 0x8812C99BL, 0x4467FFD5L),
			glowStops = listOf(0x3312C99BL, 0x6667FFD5L, 0x3312C99BL),
			iconStops = listOf(0xFF67FFD5L, 0xFFFFFFFFL),
			interactiveText = 0xFF67FFD5L,
			selectionDurationMs = 200,
		),
		// 05 — Arcane Scholar (existing persisted identity: Violet Vault)
		ExclusiveBottomNavigationSpec(
			stableId = RankThemeId.VIOLET_VAULT.stableId,
			conceptName = "Arcane Scholar",
			heightDp = 80f,
			cornerRadiusDp = 24f,
			borderWidthDp = 1.25f,
			activeDiameterDp = 50f,
			silhouette = ExclusiveNavigationSilhouette.NOTCHED,
			activeShape = ExclusiveNavigationActiveShape.HEX_GEM,
			ornament = ExclusiveNavigationOrnament.DIAMONDS,
			motion = ExclusiveNavigationMotion.ARCANE_SHIMMER,
			selectionAccentDurationMs = 900,
			containerStops = listOf(0xFF120C1DL, 0xFF1C102CL, 0xFF120C1DL),
			borderStops = listOf(0xFF7B3FE4L, 0xFFA259FFL, 0xFFD0A8FFL),
			selectedStops = listOf(0x557B3FE4L, 0x99A259FFL, 0x55D0A8FFL),
			glowStops = listOf(0x337B3FE4L, 0x66A259FFL, 0x33D0A8FFL),
			iconStops = listOf(0xFFD0A8FFL, 0xFFFFFFFFL),
			interactiveText = 0xFFD0A8FFL,
			selectionDurationMs = 200,
			ambientCycleMs = 10_000,
		),
		// 06 — Violet Halo (existing persisted identity: Arcane Scholar)
		ExclusiveBottomNavigationSpec(
			stableId = RankThemeId.ARCANE_SCHOLAR.stableId,
			conceptName = "Violet Halo",
			heightDp = 78f,
			cornerRadiusDp = 30f,
			borderWidthDp = 1.25f,
			activeDiameterDp = 50f,
			silhouette = ExclusiveNavigationSilhouette.CAPSULE,
			activeShape = ExclusiveNavigationActiveShape.DOUBLE_HALO,
			ornament = ExclusiveNavigationOrnament.STARS,
			motion = ExclusiveNavigationMotion.VIOLET_HALO,
			staticDotCount = 2,
			selectionAccentDurationMs = 360,
			ambientCycleMs = 8_000,
			topFlare = true,
			containerStops = listOf(0xFF120A1AL, 0xFF1B0D27L, 0xFF120A1AL),
			borderStops = listOf(0xFF874BFFL, 0xFFB068FFL, 0xFFE1C6FFL),
			selectedStops = listOf(0x55874BFFL, 0x99B068FFL, 0x55E1C6FFL),
			glowStops = listOf(0x33874BFFL, 0x66B068FFL, 0x33E1C6FFL),
			iconStops = listOf(0xFFE1C6FFL, 0xFFFFFFFFL),
			interactiveText = 0xFFE1C6FFL,
			selectionDurationMs = 200,
		),
		// 07 — Rose Nebula
		ExclusiveBottomNavigationSpec(
			stableId = RankThemeId.NEON_ARCHIVE.stableId,
			conceptName = "Rose Nebula",
			heightDp = 78f,
			cornerRadiusDp = 30f,
			borderWidthDp = 1.25f,
			activeDiameterDp = 50f,
			silhouette = ExclusiveNavigationSilhouette.CAPSULE,
			activeShape = ExclusiveNavigationActiveShape.BUBBLE,
			ornament = ExclusiveNavigationOrnament.NEBULA_STARS,
			motion = ExclusiveNavigationMotion.ROSE_NEBULA,
			staticDotCount = 3,
			selectionAccentDurationMs = 220,
			ambientCycleMs = 10_000,
			containerStops = listOf(0xFF190913L, 0xFF271020L, 0xFF190913L),
			borderStops = listOf(0xFFD9419CL, 0xFFFF5AB8L, 0xFFFFB6DFL),
			selectedStops = listOf(0x55D9419CL, 0x99FF5AB8L, 0x55FFB6DFL),
			glowStops = listOf(0x33D9419CL, 0x66FF5AB8L, 0x33FFB6DFL),
			iconStops = listOf(0xFFFFB6DFL, 0xFFFFFFFFL),
			interactiveText = 0xFFFFB6DFL,
			selectionDurationMs = 210,
		),
		// 08 — Crimson Ember
		ExclusiveBottomNavigationSpec(
			stableId = RankThemeId.CRIMSON_LIBRARY.stableId,
			conceptName = "Crimson Ember",
			heightDp = 78f,
			cornerRadiusDp = 26f,
			borderWidthDp = 1.25f,
			activeDiameterDp = 48f,
			silhouette = ExclusiveNavigationSilhouette.AGGRESSIVE,
			activeShape = ExclusiveNavigationActiveShape.EMBER_RING,
			ornament = ExclusiveNavigationOrnament.EMBERS,
			motion = ExclusiveNavigationMotion.CRIMSON_EMBER,
			staticDotCount = 3,
			selectionAccentDurationMs = 420,
			topFlare = true,
			bottomFlare = true,
			containerStops = listOf(0xFF160707L, 0xFF260B0BL, 0xFF160707L),
			borderStops = listOf(0xFFE2363FL, 0xFFFF5A3CL, 0xFFFFB08AL),
			selectedStops = listOf(0x55E2363FL, 0x99FF5A3CL, 0x55FFB08AL),
			glowStops = listOf(0x33E2363FL, 0x66FF5A3CL, 0x33FFB08AL),
			iconStops = listOf(0xFFFFB08AL, 0xFFFFFFFFL),
			interactiveText = 0xFFFFB08AL,
			selectionDurationMs = 190,
		),
		// 09 — Amber Manuscript
		ExclusiveBottomNavigationSpec(
			stableId = RankThemeId.EMBER_VETERAN.stableId,
			conceptName = "Amber Manuscript",
			heightDp = 80f,
			cornerRadiusDp = 24f,
			borderWidthDp = 1.5f,
			activeDiameterDp = 50f,
			silhouette = ExclusiveNavigationSilhouette.BEVELED,
			activeShape = ExclusiveNavigationActiveShape.MEDALLION,
			ornament = ExclusiveNavigationOrnament.MANUSCRIPT,
			motion = ExclusiveNavigationMotion.AMBER_SWEEP,
			doubleBorder = true,
			selectionAccentDurationMs = 320,
			selectionSweepDurationMs = 1_400,
			containerStops = listOf(0xFF171006L, 0xFF281A08L, 0xFF171006L),
			borderStops = listOf(0xFF6A4208L, 0xFFF4C55AL, 0xFFD49723L, 0xFF6A4208L),
			selectedStops = listOf(0x556A4208L, 0x99D49723L, 0x66F4C55AL),
			glowStops = listOf(0x336A4208L, 0x66F4C55AL, 0x33D49723L),
			iconStops = listOf(0xFFF4C55AL, 0xFFFFFFFFL),
			interactiveText = 0xFFF4C55AL,
			selectionDurationMs = 200,
			ambientCycleMs = 10_000,
		),
		// 10 — Golden Manuscript Deluxe
		ExclusiveBottomNavigationSpec(
			stableId = RankThemeId.GOLDEN_MANUSCRIPT.stableId,
			conceptName = "Golden Manuscript Deluxe",
			heightDp = 82f,
			cornerRadiusDp = 26f,
			borderWidthDp = 1.5f,
			activeDiameterDp = 54f,
			silhouette = ExclusiveNavigationSilhouette.ORNAMENTAL,
			activeShape = ExclusiveNavigationActiveShape.CROWN_MEDALLION,
			ornament = ExclusiveNavigationOrnament.GOLD_FINIALS,
			motion = ExclusiveNavigationMotion.GOLDEN_MEDALLION,
			doubleBorder = true,
			selectionAccentDurationMs = 320,
			selectionSweepDurationMs = 1_400,
			ambientCycleMs = 10_000,
			topFlare = true,
			containerStops = listOf(0xFF120B03L, 0xFF241605L, 0xFF120B03L),
			borderStops = listOf(0xFF8B5B12L, 0xFFFFE08AL, 0xFFF0B737L, 0xFF8B5B12L),
			selectedStops = listOf(0x558B5B12L, 0xAAFFE08AL, 0x77FFF4C8L),
			glowStops = listOf(0x338B5B12L, 0x77FFE08AL, 0x33F0B737L),
			iconStops = listOf(0xFFFFF4C8L, 0xFFFFE08AL),
			interactiveText = 0xFFFFE08AL,
			selectionDurationMs = 200,
		),
		// 11 — Eternal Library Prism (existing persisted identity: Imperial Aurora)
		ExclusiveBottomNavigationSpec(
			stableId = RankThemeId.IMPERIAL_AURORA.stableId,
			conceptName = "Eternal Library Prism",
			heightDp = 84f,
			cornerRadiusDp = 30f,
			borderWidthDp = 1.5f,
			activeDiameterDp = 54f,
			silhouette = ExclusiveNavigationSilhouette.PRISM,
			activeShape = ExclusiveNavigationActiveShape.PRISM_DOUBLE_RING,
			ornament = ExclusiveNavigationOrnament.PRISM_SHARDS,
			motion = ExclusiveNavigationMotion.PRISM_SHIMMER,
			doubleBorder = true,
			selectionAccentDurationMs = 300,
			selectionSweepDurationMs = 1_400,
			staticDotCount = 4,
			topFlare = true,
			bottomFlare = true,
			containerStops = listOf(0xFF050912L, 0xFF091426L, 0xFF050912L),
			borderStops = listOf(
				0xFFF7FBFFL, 0xFF54E9FFL, 0xFF5A7CFFL, 0xFF8B61FFL,
				0xFFFF6BD5L, 0xFFFFE7A3L, 0xFF54E9FFL,
			),
			selectedStops = listOf(0xFF54E9FFL, 0xFF5A7CFFL, 0xFF8B61FFL, 0xFFFF6BD5L, 0xFFFFE7A3L),
			glowStops = listOf(0x6654E9FFL, 0x555A7CFFL, 0x55FF6BD5L, 0x44FFE7A3L),
			iconStops = listOf(0xFFF7FBFFL, 0xFF54E9FFL, 0xFFFFE7A3L),
			interactiveText = 0xFF54E9FFL,
			content = 0xFFFFFFFFL,
			mutedContent = 0xB8F7FBFFL,
			containerMix = 0.18f,
			selectedMix = 0.68f,
			selectionDurationMs = 210,
			ambientCycleMs = 12_000,
		),
		// 12 — Celestial Infinity (existing persisted identity: Eternal Library)
		ExclusiveBottomNavigationSpec(
			stableId = RankThemeId.ETERNAL_LIBRARY.stableId,
			conceptName = "Celestial Infinity",
			heightDp = 86f,
			cornerRadiusDp = 30f,
			borderWidthDp = 1.5f,
			activeDiameterDp = 56f,
			silhouette = ExclusiveNavigationSilhouette.CELESTIAL,
			activeShape = ExclusiveNavigationActiveShape.LUMINOUS_ORB,
			ornament = ExclusiveNavigationOrnament.INFINITY_ARCS,
			motion = ExclusiveNavigationMotion.CELESTIAL_INFINITY,
			indicator = ExclusiveNavigationIndicator.LIGHT_SEED,
			selectionAccentDurationMs = 300,
			selectionSweepDurationMs = 1_400,
			doubleBorder = true,
			staticDotCount = 3,
			topFlare = true,
			bottomFlare = true,
			containerStops = listOf(0xFF03060DL, 0xFF071022L, 0xFF03060DL),
			borderStops = listOf(0xFFFFFFFFL, 0xFF9BEAFFL, 0xFFD9FAFFL, 0xFFA8A0FFL, 0xFFFFE3A1L, 0xFFFFFFFFL),
			selectedStops = listOf(0xFFFFFFFFL, 0xFFD9FAFFL, 0xFF9BEAFFL, 0xFFFFE3A1L),
			glowStops = listOf(0x66D9FAFFL, 0x559BEAFFL, 0x44FFFFFFL),
			iconStops = listOf(0xFFFFFFFFL, 0xFFD9FAFFL, 0xFFFFE3A1L),
			interactiveText = 0xFFD9FAFFL,
			content = 0xFFFFFFFFL,
			mutedContent = 0xB8D9FAFFL,
			containerMix = 0.16f,
			selectedMix = 0.72f,
			selectionDurationMs = 210,
			ambientCycleMs = 16_000,
		),
	)

	private val byStableId = presets.associateBy { it.stableId }

	fun resolve(stableId: String?): ExclusiveBottomNavigationSpec? =
		stableId?.let(byStableId::get)

	fun resolve(id: RankThemeId): ExclusiveBottomNavigationSpec =
		checkNotNull(byStableId[id.stableId]) { "Missing Exclusive bottom navigation for ${id.stableId}" }

	fun navigationAuthoring(id: RankThemeId): ExclusiveThemeComponentAuthoring =
		resolve(id).navigationAuthoring()

	fun validate(): List<String> {
		val errors = mutableListOf<String>()
		if (presets.size != RankThemeId.entries.size) errors += "navigation preset/theme count mismatch"
		if (presets.map { it.stableId }.distinct().size != presets.size) errors += "duplicate navigation stable id"
		RankThemeId.entries.forEach { id ->
			if (resolve(id.stableId) == null) errors += "missing navigation preset: ${id.stableId}"
		}
		presets.forEach { spec ->
			if (spec.staticDotCount !in 0..4) errors += "${spec.stableId}: too many static dots"
			if (spec.ambientCycleMs != null && spec.ambientCycleMs < 5_000) {
				errors += "${spec.stableId}: ambient loop too fast"
			}
		}
		return errors
	}
}
