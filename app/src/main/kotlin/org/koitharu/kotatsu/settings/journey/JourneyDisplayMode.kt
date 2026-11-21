package org.koitharu.kotatsu.settings.journey

/**
 * Which presentation the main Reader Journey screen shows, chosen from its
 * overflow menu and remembered across visits.
 *
 * UTAMA is the original statistics presentation; KARTU is the card presentation
 * built for the Settings swipe-in panel (four selectable visual models, tabbed
 * body, weekly goal), rendered full-screen in place of the statistics content.
 */
enum class JourneyDisplayMode(
	val key: String,
) {
	UTAMA("utama"),
	KARTU("kartu");

	companion object {
		fun fromKey(
			key: String?,
			default: JourneyDisplayMode = UTAMA,
		): JourneyDisplayMode = entries.firstOrNull { it.key == key } ?: default
	}
}
