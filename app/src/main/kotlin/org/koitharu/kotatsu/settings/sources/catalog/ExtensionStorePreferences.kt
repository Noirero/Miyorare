package org.koitharu.kotatsu.settings.sources.catalog

import org.koitharu.kotatsu.core.prefs.AppSettings

private const val KEY_EXTENSION_STORE_ANIME_VISIBLE = "extension_store_anime_visible"

/**
 * Persist the optional Anime catalog visibility without importing unrelated AppSettings changes.
 */
var AppSettings.isAnimeExtensionStoreVisible: Boolean
	get() = getAllValues()[KEY_EXTENSION_STORE_ANIME_VISIBLE] as? Boolean ?: true
	set(value) = upsertAll(mapOf(KEY_EXTENSION_STORE_ANIME_VISIBLE to value))
