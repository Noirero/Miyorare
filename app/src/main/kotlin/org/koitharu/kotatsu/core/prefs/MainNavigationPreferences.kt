package org.koitharu.kotatsu.core.prefs

/** One deterministic migration for old, corrupt and current primary-navigation preferences. */
object MainNavigationPreferences {
	const val CONFIGURABLE_COUNT = 3
	val fixed = setOf(NavItem.FAVORITES, NavItem.DISCOVERY_HUB, NavItem.READER_JOURNEY)
	private val fallback = listOf(NavItem.FEED, NavItem.HISTORY, NavItem.EXPLORE, NavItem.LOCAL, NavItem.BOOKMARKS, NavItem.SUGGESTIONS, NavItem.UPDATED)

	fun configurable(raw: List<String>?, available: Set<NavItem> = NavItem.entries.toSet()): List<NavItem> {
		val selected = raw.orEmpty().mapNotNull { name -> NavItem.entries.firstOrNull { it.name == name.trim() } }
		return (selected + fallback).asSequence().filter { it !in fixed && it in available }
			.distinct().take(CONFIGURABLE_COUNT).toList().also { require(it.size == CONFIGURABLE_COUNT) }
	}

	fun primary(raw: List<String>?, available: Set<NavItem> = NavItem.entries.toSet()): List<NavItem> {
		val slots = configurable(raw, available)
		return listOf(NavItem.FAVORITES, slots[0], NavItem.DISCOVERY_HUB, slots[1], slots[2])
	}
}
