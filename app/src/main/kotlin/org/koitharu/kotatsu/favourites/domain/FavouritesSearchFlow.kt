package org.koitharu.kotatsu.favourites.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Shared search-input policy for the Favourites container and its pages.
 *
 * Favourites are stored locally, so search input is emitted immediately. Trimming and
 * distinctUntilChanged keep whitespace-only/duplicate updates from triggering redundant filtering
 * without adding a visible delay between typing and results.
 */
fun Flow<String>.debounceFavouritesSearch(): Flow<String> =
	map { it.trim() }
		.distinctUntilChanged()
