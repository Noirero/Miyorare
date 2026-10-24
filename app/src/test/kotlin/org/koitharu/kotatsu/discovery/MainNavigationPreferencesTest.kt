package org.koitharu.kotatsu.discovery

import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.core.prefs.MainNavigationPreferences as Policy
import org.koitharu.kotatsu.core.prefs.NavItem

class MainNavigationPreferencesTest {
	@Test fun `new install fixes Favorites and Home around three editable slots`() {
		assertEquals(listOf(NavItem.FAVORITES, NavItem.FEED, NavItem.DISCOVERY_HUB, NavItem.HISTORY, NavItem.EXPLORE), Policy.primary(null))
	}
	@Test fun `old four slot custom order preserves first three eligible choices`() {
		assertEquals(listOf(NavItem.FAVORITES, NavItem.LOCAL, NavItem.DISCOVERY_HUB, NavItem.EXPLORE, NavItem.BOOKMARKS), Policy.primary(listOf("LOCAL", "FAVORITES", "EXPLORE", "BOOKMARKS")))
	}
	@Test fun `corrupt unknown duplicate empty and fixed entries cannot remove roots`() {
		for (raw in listOf(emptyList(), listOf("", "garbage"), listOf("HISTORY", "HISTORY", "READER_JOURNEY", "FAVORITES", "DISCOVERY_HUB"))) {
			val result = Policy.primary(raw)
			assertEquals(5, result.size); assertEquals(5, result.distinct().size)
			assertEquals(NavItem.FAVORITES, result.first()); assertEquals(NavItem.DISCOVERY_HUB, result[2]); assertFalse(NavItem.READER_JOURNEY in result)
		}
	}
	@Test fun `disabled feed suggestions and updated are replaced with usable roots`() {
		val available = NavItem.entries.toSet() - setOf(NavItem.FEED, NavItem.SUGGESTIONS, NavItem.UPDATED)
		assertEquals(listOf(NavItem.FAVORITES, NavItem.HISTORY, NavItem.DISCOVERY_HUB, NavItem.EXPLORE, NavItem.LOCAL), Policy.primary(listOf("FEED", "SUGGESTIONS", "UPDATED"), available))
	}
	@Test fun `normalized preference is stable and never stores a fixed root`() {
		val slots = Policy.configurable(listOf(" EXPLORE ", "LOCAL", "HISTORY"))
		assertEquals(slots, Policy.configurable(slots.map { it.name }))
		assertTrue(slots.none { it in Policy.fixed })
	}
}
