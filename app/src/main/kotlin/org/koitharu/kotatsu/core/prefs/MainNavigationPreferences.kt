package org.koitharu.kotatsu.core.prefs

/** One deterministic migration for old, corrupt and current primary-navigation preferences. */
object MainNavigationPreferences {
	const val CONFIGURABLE_COUNT = 4
	val fixed = setOf(NavItem.FAVORITES, NavItem.READER_JOURNEY)
	private val fallback = listOf(NavItem.FEED, NavItem.HISTORY, NavItem.EXPLORE, NavItem.LOCAL, NavItem.BOOKMARKS, NavItem.SUGGESTIONS, NavItem.UPDATED)

	fun configurable(raw: List<String>?, available: Set<NavItem> = NavItem.entries.toSet()): List<NavItem> {
		val selected = raw.orEmpty().mapNotNull { name -> NavItem.entries.firstOrNull { it.name == name.trim() } }
			.filterNot { it in fixed }
			.distinct()
		val eligible = (selected + fallback).filter { it !in fixed && it in available }.distinct()
		// Configurations written while Discovery Hub was a fixed root hold at most three slot
		// names and never the hub itself. Keep the hub in the bar for those users, in its old
		// centre position, instead of silently dropping it. A configuration that already lists
		// four slots is current, even when one of its features is temporarily unavailable.
		val isLegacyConfig = selected.size <= 3 && NavItem.DISCOVERY_HUB !in selected
		val slots = if (isLegacyConfig && NavItem.DISCOVERY_HUB in available) {
			val base = eligible.take(3).toMutableList()
			base.add(minOf(1, base.size), NavItem.DISCOVERY_HUB)
			(base + eligible).distinct().take(CONFIGURABLE_COUNT)
		} else {
			eligible.take(CONFIGURABLE_COUNT)
		}
		return slots.also { require(it.size == CONFIGURABLE_COUNT) }
	}

	fun primary(raw: List<String>?, available: Set<NavItem> = NavItem.entries.toSet()): List<NavItem> {
		return listOf(NavItem.FAVORITES) + configurable(raw, available)
	}
}
