package org.koitharu.kotatsu.readerjourney.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExclusiveThemeContractTest {

	@Test
	fun `registry authoring contract is valid`() {
		assertTrue(RankThemeRegistry.validate().isEmpty())
	}

	@Test
	fun `every rank resolves complete global component roles`() {
		for (definition in RankThemeRegistry.definitions) {
			for (variant in RankThemeVariant.entries) {
				val resolved = ExclusiveThemeContractResolver.resolve(definition, variant)
				val components = listOf(
					resolved.shared,
					resolved.navigation,
					resolved.favourites,
					resolved.settings,
					resolved.details,
				)
				assertEquals(definition.id, resolved.id)
				assertEquals(definition.tokens(variant), resolved.tokens)
				components.forEach { component ->
					assertTrue(component.containerStops.size >= 2)
					assertTrue(component.borderStops.size >= 2)
					assertTrue(component.cardBorderStops.size >= 2)
					assertTrue(component.selectedStops.size >= 2)
					assertTrue(component.glowStops.size >= 2)
					assertTrue(component.iconStops.size >= 2)
					assertTrue(component.containerMix in 0f..1f)
					assertTrue(component.selectedMix in 0f..1f)
					assertTrue(component.iconMix in 0f..1f)
				}
			}
		}
	}

	@Test
	fun `new theme authoring overrides resolve centrally without screen changes`() {
		val base = RankThemeRegistry.resolveOrDefault(RankThemeId.GOLDEN_MANUSCRIPT.stableId)
		val sentinelBorder = listOf(0xFF010203L, 0xFF102030L, 0xFF405060L)
		val sentinelSelected = listOf(0xFF111122L, 0xFF334455L)
		val sentinelInteractive = 0xFFABCDEF
		val authored = base.copy(
			authoring = ExclusiveThemeAuthoringContract(
				navigation = ExclusiveThemeComponentAuthoring(
					borderStops = sentinelBorder,
					selectedStops = sentinelSelected,
					interactiveText = sentinelInteractive,
					selectedMix = 0.37f,
				),
				favourites = ExclusiveThemeComponentAuthoring(
					cardBorderStops = sentinelBorder,
				),
				details = ExclusiveThemeComponentAuthoring(
					interactiveText = sentinelInteractive,
				),
			),
		)

		val resolved = ExclusiveThemeContractResolver.resolve(authored, RankThemeVariant.DARK)

		assertEquals(sentinelBorder, resolved.navigation.borderStops)
		assertEquals(sentinelSelected, resolved.navigation.selectedStops)
		assertEquals(sentinelInteractive, resolved.navigation.interactiveText)
		assertEquals(0.37f, resolved.navigation.selectedMix)
		assertEquals(sentinelBorder, resolved.favourites.cardBorderStops)
		assertEquals(sentinelInteractive, resolved.details.interactiveText)
		// Omitted roles are filled by the central fallback owner.
		assertTrue(resolved.settings.borderStops.size >= 2)
		assertTrue(resolved.shared.selectedStops.size >= 2)
	}

	@Test
	fun `final rank authored roles preserve dedicated navigation and card identity`() {
		val imperial = ExclusiveThemeContractResolver.resolve(
			RankThemeRegistry.resolveOrDefault(RankThemeId.IMPERIAL_AURORA.stableId),
			RankThemeVariant.DARK,
		)
		val imperialNavigation = ExclusiveBottomNavigationRegistry.resolve(RankThemeId.IMPERIAL_AURORA)
		assertEquals(imperialNavigation.borderStops, imperial.navigation.borderStops)
		assertEquals(imperialNavigation.selectedStops, imperial.navigation.selectedStops)
		assertEquals(0.68f, imperial.navigation.selectedMix)

		val eternal = ExclusiveThemeContractResolver.resolve(
			RankThemeRegistry.resolveOrDefault(RankThemeId.ETERNAL_LIBRARY.stableId),
			RankThemeVariant.DARK,
		)
		val eternalNavigation = ExclusiveBottomNavigationRegistry.resolve(RankThemeId.ETERNAL_LIBRARY)
		assertEquals(eternalNavigation.borderStops, eternal.navigation.borderStops)
		assertEquals(eternalNavigation.selectedStops, eternal.navigation.selectedStops)
		assertEquals(0.72f, eternal.navigation.selectedMix)
		assertTrue(
			eternal.favourites.cardBorderStops.all { color ->
				((color ushr 24) and 0xFFL) == 0xB8L
			},
		)
	}

	@Test
	fun `authoring validator rejects malformed theme roles`() {
		val invalid = ExclusiveThemeAuthoringContract(
			navigation = ExclusiveThemeComponentAuthoring(
				borderStops = listOf(0xFFFFFFFFL),
				selectedMix = 1.5f,
			),
		)
		val errors = ExclusiveThemeContractResolver.validateAuthoring(
			RankThemeId.GOLDEN_MANUSCRIPT,
			invalid,
		)
		assertTrue(errors.any { it.contains("borderStops") })
		assertTrue(errors.any { it.contains("selectedMix") })
	}
}
