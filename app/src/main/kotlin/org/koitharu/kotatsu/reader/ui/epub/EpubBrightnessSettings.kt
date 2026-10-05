package org.koitharu.kotatsu.reader.ui.epub

import org.koitharu.kotatsu.core.prefs.AppSettings

/** Global novel-reader brightness persisted in the same SharedPreferences map as AppSettings. */
internal var AppSettings.epubScreenBrightness: Int
	get() = (getAllValues()[KEY_EPUB_SCREEN_BRIGHTNESS] as? Int ?: 0).coerceIn(0, 100)
	set(value) = upsertAll(mapOf(KEY_EPUB_SCREEN_BRIGHTNESS to value.coerceIn(0, 100)))

internal const val KEY_EPUB_SCREEN_BRIGHTNESS = "epub_screen_brightness"
