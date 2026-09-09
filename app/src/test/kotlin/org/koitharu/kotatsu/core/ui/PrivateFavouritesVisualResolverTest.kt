package org.koitharu.kotatsu.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.prefs.PrivateFavouritesThemePreset

class PrivateFavouritesVisualResolverTest {

	@Test
	fun `follow normal has no dedicated private artwork`() {
		assertNull(PrivateFavouritesVisualResolver.resolve(PrivateFavouritesThemePreset.FOLLOW_NORMAL))
	}

	@Test
	fun `all explicit private themes own unique artwork resources`() {
		val explicitThemes = PrivateFavouritesThemePreset.entries.filterNot {
			it == PrivateFavouritesThemePreset.FOLLOW_NORMAL
		}
		val specs = explicitThemes.mapNotNull(PrivateFavouritesVisualResolver::resolve)

		assertEquals(explicitThemes.size, specs.size)
		assertEquals(specs.size, specs.map { it.artworkRes }.toSet().size)
		assertTrue(specs.all { it.theme != PrivateFavouritesThemePreset.FOLLOW_NORMAL })
	}
}
