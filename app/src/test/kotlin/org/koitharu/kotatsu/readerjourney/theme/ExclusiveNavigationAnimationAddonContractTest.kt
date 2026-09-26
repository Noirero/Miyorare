package org.koitharu.kotatsu.readerjourney.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Motion contract merged from:
 * - MIYORARE_12_BOTTOM_NAVIGATION_IMPLEMENTATION_GUIDE
 * - MIYORARE_12_BOTTOM_NAVIGATION_ANIMATION_ADDON
 *
 * The addon extends the guide; it does not replace each theme's original visual identity.
 */
class ExclusiveNavigationAnimationAddonContractTest {

	@Test
	fun `all twelve themes keep a distinct authored motion identity`() {
		val expected = listOf(
			ExclusiveNavigationMotion.CLEAN_REVEAL,
			ExclusiveNavigationMotion.BLUE_PULSE,
			ExclusiveNavigationMotion.CYAN_ORBIT,
			ExclusiveNavigationMotion.EMERALD_PULSE,
			ExclusiveNavigationMotion.ARCANE_SHIMMER,
			ExclusiveNavigationMotion.VIOLET_HALO,
			ExclusiveNavigationMotion.ROSE_NEBULA,
			ExclusiveNavigationMotion.CRIMSON_EMBER,
			ExclusiveNavigationMotion.AMBER_SWEEP,
			ExclusiveNavigationMotion.GOLDEN_MEDALLION,
			ExclusiveNavigationMotion.PRISM_SHIMMER,
			ExclusiveNavigationMotion.CELESTIAL_INFINITY,
		)
		assertEquals(expected, ExclusiveBottomNavigationRegistry.presets.map { it.motion })
		assertEquals(12, ExclusiveBottomNavigationRegistry.presets.map { it.motion }.distinct().size)
	}

	@Test
	fun `selection and ambient timings satisfy the two documents together`() {
		val firstPage = ExclusiveBottomNavigationRegistry.resolve(RankThemeId.FIRST_PAGE)
		assertTrue(firstPage.selectionAccentDurationMs in 160..200)
		assertNull(firstPage.ambientCycleMs)

		val firstLight = ExclusiveBottomNavigationRegistry.resolve(RankThemeId.FIRST_LIGHT)
		assertTrue(firstLight.selectionAccentDurationMs in 280..340)

		val cyan = ExclusiveBottomNavigationRegistry.resolve(RankThemeId.CYAN_CODEX)
		assertTrue(cyan.ambientCycleMs != null && cyan.ambientCycleMs in 10_000..12_000)

		val emerald = ExclusiveBottomNavigationRegistry.resolve(RankThemeId.EMERALD_COMPASS)
		assertTrue(emerald.selectionAccentDurationMs in 1_800..2_400)
		assertTrue(emerald.ambientCycleMs != null && emerald.ambientCycleMs in 8_000..10_000)

		val arcane = ExclusiveBottomNavigationRegistry.resolve(RankThemeId.VIOLET_VAULT)
		assertTrue(arcane.selectionAccentDurationMs in 700..1_000)
		assertTrue(arcane.ambientCycleMs != null && arcane.ambientCycleMs in 8_000..10_000)

		val violet = ExclusiveBottomNavigationRegistry.resolve(RankThemeId.ARCANE_SCHOLAR)
		assertTrue(violet.selectionAccentDurationMs in 320..420)
		assertTrue(violet.ambientCycleMs != null && violet.ambientCycleMs in 8_000..9_000)

		val rose = ExclusiveBottomNavigationRegistry.resolve(RankThemeId.NEON_ARCHIVE)
		assertTrue(rose.selectionAccentDurationMs in 180..240)
		assertTrue(rose.ambientCycleMs != null && rose.ambientCycleMs in 8_000..12_000)

		val crimson = ExclusiveBottomNavigationRegistry.resolve(RankThemeId.CRIMSON_LIBRARY)
		assertTrue(crimson.selectionAccentDurationMs in 350..500)
		assertNull(crimson.ambientCycleMs)

		val amber = ExclusiveBottomNavigationRegistry.resolve(RankThemeId.EMBER_VETERAN)
		assertTrue(amber.selectionAccentDurationMs in 1_200..1_500)
		assertTrue(amber.ambientCycleMs != null && amber.ambientCycleMs in 10_000..12_000)

		val gold = ExclusiveBottomNavigationRegistry.resolve(RankThemeId.GOLDEN_MANUSCRIPT)
		assertTrue(gold.selectionAccentDurationMs in 1_200..1_800)
		assertTrue(gold.ambientCycleMs != null && gold.ambientCycleMs in 8_000..12_000)

		val prism = ExclusiveBottomNavigationRegistry.resolve(RankThemeId.IMPERIAL_AURORA)
		assertTrue(prism.selectionAccentDurationMs in 1_200..1_600)
		assertTrue(prism.ambientCycleMs != null && prism.ambientCycleMs in 10_000..14_000)

		val celestial = ExclusiveBottomNavigationRegistry.resolve(RankThemeId.ETERNAL_LIBRARY)
		assertTrue(celestial.selectionAccentDurationMs in 1_200..1_600)
		assertTrue(celestial.ambientCycleMs != null && celestial.ambientCycleMs in 14_000..20_000)
	}

	@Test
	fun `continuous motion stays limited to themes with authored idle behavior`() {
		val idleMotions = ExclusiveBottomNavigationRegistry.presets
			.filter { it.ambientCycleMs != null }
			.map { it.motion }
			.toSet()

		assertTrue(ExclusiveNavigationMotion.CYAN_ORBIT in idleMotions)
		assertTrue(ExclusiveNavigationMotion.AMBER_SWEEP in idleMotions)
		assertTrue(ExclusiveNavigationMotion.PRISM_SHIMMER in idleMotions)
		assertTrue(ExclusiveNavigationMotion.CELESTIAL_INFINITY in idleMotions)
		assertTrue(ExclusiveNavigationMotion.CLEAN_REVEAL !in idleMotions)
		assertTrue(ExclusiveNavigationMotion.CRIMSON_EMBER !in idleMotions)
	}
}
