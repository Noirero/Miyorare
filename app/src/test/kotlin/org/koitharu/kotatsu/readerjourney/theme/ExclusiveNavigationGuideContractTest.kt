package org.koitharu.kotatsu.readerjourney.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract distilled from MIYORARE_12_BOTTOM_NAVIGATION_IMPLEMENTATION_GUIDE.
 *
 * The purpose is not to freeze every tuning number forever; it freezes the authored identity
 * that must survive even when motion/glow are reduced: silhouette, selected-state language,
 * ornament family and required final-tier hierarchy.
 */
class ExclusiveNavigationGuideContractTest {

	@Test
	fun `01 through 04 keep the guide identity progression`() {
		val firstPage = ExclusiveBottomNavigationRegistry.resolve(RankThemeId.FIRST_PAGE)
		assertEquals(ExclusiveNavigationSilhouette.CAPSULE, firstPage.silhouette)
		assertEquals(ExclusiveNavigationActiveShape.SOFT_HALO, firstPage.activeShape)
		assertTrue(firstPage.topFlare)
		assertTrue(firstPage.cornerRadiusDp in 28f..32f)
		assertTrue(firstPage.activeDiameterDp in 42f..46f)

		val firstLight = ExclusiveBottomNavigationRegistry.resolve(RankThemeId.FIRST_LIGHT)
		assertEquals(ExclusiveNavigationSilhouette.CAPSULE, firstLight.silhouette)
		assertEquals(ExclusiveNavigationActiveShape.RING, firstLight.activeShape)
		assertEquals(ExclusiveNavigationOrnament.TOP_FLARE, firstLight.ornament)

		val cyan = ExclusiveBottomNavigationRegistry.resolve(RankThemeId.CYAN_CODEX)
		assertEquals(ExclusiveNavigationSilhouette.CAPSULE, cyan.silhouette)
		assertEquals(ExclusiveNavigationActiveShape.ORBIT_RING, cyan.activeShape)
		assertEquals(ExclusiveNavigationOrnament.ORBIT, cyan.ornament)
		assertTrue(cyan.staticDotCount in 2..4)
		assertTrue(cyan.ambientCycleMs != null && cyan.ambientCycleMs in 8_000..12_000)

		val emerald = ExclusiveBottomNavigationRegistry.resolve(RankThemeId.EMERALD_COMPASS)
		assertEquals(ExclusiveNavigationSilhouette.ANGULAR, emerald.silhouette)
		assertEquals(ExclusiveNavigationActiveShape.RING, emerald.activeShape)
		assertEquals(ExclusiveNavigationOrnament.SIDE_LINES, emerald.ornament)
		assertTrue(emerald.topFlare)
	}

	@Test
	fun `05 through 08 cannot degrade into circular recolours`() {
		val arcane = ExclusiveBottomNavigationRegistry.resolve(RankThemeId.VIOLET_VAULT)
		assertEquals(ExclusiveNavigationSilhouette.NOTCHED, arcane.silhouette)
		assertEquals(ExclusiveNavigationActiveShape.HEX_GEM, arcane.activeShape)
		assertEquals(ExclusiveNavigationOrnament.DIAMONDS, arcane.ornament)

		val violet = ExclusiveBottomNavigationRegistry.resolve(RankThemeId.ARCANE_SCHOLAR)
		assertEquals(ExclusiveNavigationSilhouette.CAPSULE, violet.silhouette)
		assertEquals(ExclusiveNavigationActiveShape.DOUBLE_HALO, violet.activeShape)
		assertEquals(ExclusiveNavigationOrnament.STARS, violet.ornament)
		assertTrue(violet.staticDotCount in 1..2)

		val rose = ExclusiveBottomNavigationRegistry.resolve(RankThemeId.NEON_ARCHIVE)
		assertEquals(ExclusiveNavigationSilhouette.CAPSULE, rose.silhouette)
		assertEquals(ExclusiveNavigationActiveShape.BUBBLE, rose.activeShape)
		assertEquals(ExclusiveNavigationOrnament.NEBULA_STARS, rose.ornament)
		assertTrue(rose.staticDotCount in 2..3)

		val crimson = ExclusiveBottomNavigationRegistry.resolve(RankThemeId.CRIMSON_LIBRARY)
		assertEquals(ExclusiveNavigationSilhouette.AGGRESSIVE, crimson.silhouette)
		assertEquals(ExclusiveNavigationActiveShape.EMBER_RING, crimson.activeShape)
		assertEquals(ExclusiveNavigationOrnament.EMBERS, crimson.ornament)
		assertTrue(crimson.topFlare && crimson.bottomFlare)
	}

	@Test
	fun `09 through 10 preserve manuscript and royal collectible structure`() {
		val amber = ExclusiveBottomNavigationRegistry.resolve(RankThemeId.EMBER_VETERAN)
		assertEquals(ExclusiveNavigationSilhouette.BEVELED, amber.silhouette)
		assertEquals(ExclusiveNavigationActiveShape.MEDALLION, amber.activeShape)
		assertEquals(ExclusiveNavigationOrnament.MANUSCRIPT, amber.ornament)
		assertTrue(amber.doubleBorder)
		assertTrue(amber.ambientCycleMs != null && amber.ambientCycleMs >= 7_000)

		val gold = ExclusiveBottomNavigationRegistry.resolve(RankThemeId.GOLDEN_MANUSCRIPT)
		assertEquals(ExclusiveNavigationSilhouette.ORNAMENTAL, gold.silhouette)
		assertEquals(ExclusiveNavigationActiveShape.CROWN_MEDALLION, gold.activeShape)
		assertEquals(ExclusiveNavigationOrnament.GOLD_FINIALS, gold.ornament)
		assertTrue(gold.doubleBorder)
		assertTrue(gold.topFlare)
		assertTrue(gold.activeDiameterDp in 48f..54f)
	}

	@Test
	fun `11 and 12 stay structurally recognizable without relying on brightness`() {
		val prism = ExclusiveBottomNavigationRegistry.resolve(RankThemeId.IMPERIAL_AURORA)
		assertEquals(ExclusiveNavigationSilhouette.PRISM, prism.silhouette)
		assertEquals(ExclusiveNavigationActiveShape.PRISM_DOUBLE_RING, prism.activeShape)
		assertEquals(ExclusiveNavigationOrnament.PRISM_SHARDS, prism.ornament)
		assertTrue(prism.doubleBorder)
		assertTrue(prism.staticDotCount in 2..4)
		assertTrue(prism.ambientCycleMs != null && prism.ambientCycleMs in 8_000..14_000)

		val celestial = ExclusiveBottomNavigationRegistry.resolve(RankThemeId.ETERNAL_LIBRARY)
		assertEquals(ExclusiveNavigationSilhouette.CELESTIAL, celestial.silhouette)
		assertEquals(ExclusiveNavigationActiveShape.LUMINOUS_ORB, celestial.activeShape)
		assertEquals(ExclusiveNavigationOrnament.INFINITY_ARCS, celestial.ornament)
		assertEquals(ExclusiveNavigationIndicator.LIGHT_SEED, celestial.indicator)
		assertTrue(celestial.activeDiameterDp in 52f..56f)
		assertTrue(celestial.ambientCycleMs != null && celestial.ambientCycleMs in 14_000..20_000)

		assertTrue(prism.silhouette != celestial.silhouette)
		assertTrue(prism.activeShape != celestial.activeShape)
		assertTrue(prism.ornament != celestial.ornament)
	}

	@Test
	fun `all themes stay inside the guide performance and geometry budget`() {
		ExclusiveBottomNavigationRegistry.presets.forEachIndexed { index, spec ->
			val maxHeight = if (index >= 10) 86f else 82f
			assertTrue(spec.heightDp in 72f..maxHeight)
			assertTrue(spec.cornerRadiusDp in 20f..32f)
			assertTrue(spec.borderWidthDp in 1f..1.5f)
			assertTrue(spec.activeDiameterDp in 42f..56f)
			assertTrue(spec.selectionDurationMs in 160..240)
			assertTrue(spec.staticDotCount <= 4)
			assertTrue(spec.ambientCycleMs == null || spec.ambientCycleMs >= 5_000)
		}
	}
}
