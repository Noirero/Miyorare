package org.koitharu.kotatsu.readerjourney.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExclusiveBottomNavigationSpecTest {

	@Test
	fun `registry contains exactly one production preset for every persisted theme identity`() {
		assertTrue(ExclusiveBottomNavigationRegistry.validate().isEmpty())
		assertEquals(RankThemeId.entries.size, ExclusiveBottomNavigationRegistry.presets.size)
		assertEquals(
			RankThemeId.entries.map { it.stableId }.toSet(),
			ExclusiveBottomNavigationRegistry.presets.map { it.stableId }.toSet(),
		)
	}

	@Test
	fun `all presets stay inside navigation geometry and motion budgets`() {
		ExclusiveBottomNavigationRegistry.presets.forEach { spec ->
			assertTrue(spec.heightDp in 72f..86f)
			assertTrue(spec.cornerRadiusDp in 20f..32f)
			assertTrue(spec.borderWidthDp in 1f..1.5f)
			assertTrue(spec.activeDiameterDp in 42f..56f)
			assertTrue(spec.selectionDurationMs in 160..240)
			assertTrue(spec.staticDotCount <= 4)
			assertTrue(spec.ambientCycleMs == null || spec.ambientCycleMs >= 8_000)
		}
	}


	@Test
	fun `authored body silhouettes cannot collapse back into one recoloured capsule`() {
		assertEquals(
			ExclusiveNavigationSilhouette.ANGULAR,
			ExclusiveBottomNavigationRegistry.resolve(RankThemeId.EMERALD_COMPASS).silhouette,
		)
		assertEquals(
			ExclusiveNavigationSilhouette.NOTCHED,
			ExclusiveBottomNavigationRegistry.resolve(RankThemeId.VIOLET_VAULT).silhouette,
		)
		assertEquals(
			ExclusiveNavigationSilhouette.AGGRESSIVE,
			ExclusiveBottomNavigationRegistry.resolve(RankThemeId.CRIMSON_LIBRARY).silhouette,
		)
		assertEquals(
			ExclusiveNavigationSilhouette.BEVELED,
			ExclusiveBottomNavigationRegistry.resolve(RankThemeId.EMBER_VETERAN).silhouette,
		)
		assertEquals(
			ExclusiveNavigationSilhouette.ORNAMENTAL,
			ExclusiveBottomNavigationRegistry.resolve(RankThemeId.GOLDEN_MANUSCRIPT).silhouette,
		)
		assertEquals(
			ExclusiveNavigationSilhouette.PRISM,
			ExclusiveBottomNavigationRegistry.resolve(RankThemeId.IMPERIAL_AURORA).silhouette,
		)
		assertEquals(
			ExclusiveNavigationSilhouette.CELESTIAL,
			ExclusiveBottomNavigationRegistry.resolve(RankThemeId.ETERNAL_LIBRARY).silhouette,
		)

		assertEquals(
			ExclusiveNavigationActiveShape.HEX_GEM,
			ExclusiveBottomNavigationRegistry.resolve(RankThemeId.VIOLET_VAULT).activeShape,
		)
		assertEquals(
			ExclusiveNavigationActiveShape.CROWN_MEDALLION,
			ExclusiveBottomNavigationRegistry.resolve(RankThemeId.GOLDEN_MANUSCRIPT).activeShape,
		)
		assertEquals(
			ExclusiveNavigationActiveShape.PRISM_DOUBLE_RING,
			ExclusiveBottomNavigationRegistry.resolve(RankThemeId.IMPERIAL_AURORA).activeShape,
		)
		assertEquals(
			ExclusiveNavigationActiveShape.LUMINOUS_ORB,
			ExclusiveBottomNavigationRegistry.resolve(RankThemeId.ETERNAL_LIBRARY).activeShape,
		)
	}

	@Test
	fun `every resolved exclusive theme consumes its authored navigation preset`() {
		RankThemeRegistry.definitions.forEach { definition ->
			val spec = ExclusiveBottomNavigationRegistry.resolve(definition.id)
			val resolved = ExclusiveThemeContractResolver.resolve(definition, RankThemeVariant.DARK)
			assertEquals(spec.containerStops, resolved.navigation.containerStops)
			assertEquals(spec.borderStops, resolved.navigation.borderStops)
			assertEquals(spec.selectedStops, resolved.navigation.selectedStops)
			assertEquals(spec.glowStops, resolved.navigation.glowStops)
			assertEquals(spec.iconStops, resolved.navigation.iconStops)
			assertEquals(spec.interactiveText, resolved.navigation.interactiveText)
		}
	}

	@Test
	fun `final two ranks remain recognizable by structure rather than brightness alone`() {
		val rank90 = ExclusiveBottomNavigationRegistry.resolve(RankThemeId.IMPERIAL_AURORA)
		val rank100 = ExclusiveBottomNavigationRegistry.resolve(RankThemeId.ETERNAL_LIBRARY)

		assertEquals(ExclusiveNavigationSilhouette.PRISM, rank90.silhouette)
		assertEquals(ExclusiveNavigationActiveShape.PRISM_DOUBLE_RING, rank90.activeShape)
		assertEquals(ExclusiveNavigationOrnament.PRISM_SHARDS, rank90.ornament)

		assertEquals(ExclusiveNavigationSilhouette.CELESTIAL, rank100.silhouette)
		assertEquals(ExclusiveNavigationActiveShape.LUMINOUS_ORB, rank100.activeShape)
		assertEquals(ExclusiveNavigationOrnament.INFINITY_ARCS, rank100.ornament)
		assertEquals(ExclusiveNavigationIndicator.LIGHT_SEED, rank100.indicator)

		assertNotEquals(rank90.silhouette, rank100.silhouette)
		assertNotEquals(rank90.activeShape, rank100.activeShape)
		assertNotEquals(rank90.ornament, rank100.ornament)
	}

	@Test
	fun `all twelve guide signatures stay structurally distinct and authored`() {
		data class Signature(
			val id: RankThemeId,
			val silhouette: ExclusiveNavigationSilhouette,
			val active: ExclusiveNavigationActiveShape,
			val ornament: ExclusiveNavigationOrnament,
			val requiresTopFlare: Boolean = false,
		)

		val expected = listOf(
			Signature(RankThemeId.FIRST_PAGE, ExclusiveNavigationSilhouette.CAPSULE, ExclusiveNavigationActiveShape.SOFT_HALO, ExclusiveNavigationOrnament.NONE, true),
			Signature(RankThemeId.FIRST_LIGHT, ExclusiveNavigationSilhouette.CAPSULE, ExclusiveNavigationActiveShape.RING, ExclusiveNavigationOrnament.TOP_FLARE, true),
			Signature(RankThemeId.CYAN_CODEX, ExclusiveNavigationSilhouette.CAPSULE, ExclusiveNavigationActiveShape.ORBIT_RING, ExclusiveNavigationOrnament.ORBIT),
			Signature(RankThemeId.EMERALD_COMPASS, ExclusiveNavigationSilhouette.ANGULAR, ExclusiveNavigationActiveShape.RING, ExclusiveNavigationOrnament.SIDE_LINES, true),
			Signature(RankThemeId.VIOLET_VAULT, ExclusiveNavigationSilhouette.NOTCHED, ExclusiveNavigationActiveShape.HEX_GEM, ExclusiveNavigationOrnament.DIAMONDS),
			Signature(RankThemeId.ARCANE_SCHOLAR, ExclusiveNavigationSilhouette.CAPSULE, ExclusiveNavigationActiveShape.DOUBLE_HALO, ExclusiveNavigationOrnament.STARS, true),
			Signature(RankThemeId.NEON_ARCHIVE, ExclusiveNavigationSilhouette.CAPSULE, ExclusiveNavigationActiveShape.BUBBLE, ExclusiveNavigationOrnament.NEBULA_STARS),
			Signature(RankThemeId.CRIMSON_LIBRARY, ExclusiveNavigationSilhouette.AGGRESSIVE, ExclusiveNavigationActiveShape.EMBER_RING, ExclusiveNavigationOrnament.EMBERS, true),
			Signature(RankThemeId.EMBER_VETERAN, ExclusiveNavigationSilhouette.BEVELED, ExclusiveNavigationActiveShape.MEDALLION, ExclusiveNavigationOrnament.MANUSCRIPT),
			Signature(RankThemeId.GOLDEN_MANUSCRIPT, ExclusiveNavigationSilhouette.ORNAMENTAL, ExclusiveNavigationActiveShape.CROWN_MEDALLION, ExclusiveNavigationOrnament.GOLD_FINIALS, true),
			Signature(RankThemeId.IMPERIAL_AURORA, ExclusiveNavigationSilhouette.PRISM, ExclusiveNavigationActiveShape.PRISM_DOUBLE_RING, ExclusiveNavigationOrnament.PRISM_SHARDS, true),
			Signature(RankThemeId.ETERNAL_LIBRARY, ExclusiveNavigationSilhouette.CELESTIAL, ExclusiveNavigationActiveShape.LUMINOUS_ORB, ExclusiveNavigationOrnament.INFINITY_ARCS, true),
		)

		expected.forEach { signature ->
			val spec = ExclusiveBottomNavigationRegistry.resolve(signature.id)
			assertEquals(signature.silhouette, spec.silhouette)
			assertEquals(signature.active, spec.activeShape)
			assertEquals(signature.ornament, spec.ornament)
			if (signature.requiresTopFlare) assertTrue(spec.topFlare)
		}
	}

	@Test
	fun `concept mapping follows rank order while persisted names remain migration safe`() {
		val concepts = ExclusiveBottomNavigationRegistry.presets.map { it.conceptName }
		assertEquals(
			listOf(
				"First Page Silver",
				"First Light Blue",
				"Cyan Orbit",
				"Emerald Pulse",
				"Arcane Scholar",
				"Violet Halo",
				"Rose Nebula",
				"Crimson Ember",
				"Amber Manuscript",
				"Golden Manuscript Deluxe",
				"Eternal Library Prism",
				"Celestial Infinity",
			),
			concepts,
		)
		assertEquals("Violet Vault", RankThemeId.VIOLET_VAULT.displayName)
		assertEquals("Imperial Aurora", RankThemeId.IMPERIAL_AURORA.displayName)
		assertEquals("Eternal Library", RankThemeId.ETERNAL_LIBRARY.displayName)
	}
}
