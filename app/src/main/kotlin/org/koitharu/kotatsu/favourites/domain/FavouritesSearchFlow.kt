package org.koitharu.kotatsu.favourites.domain

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

private const val FAVOURITES_SEARCH_DEBOUNCE_MS = 250L

/**
 * Shared search-input policy for the Favourites container and its pages.
 *
 * The raw EditText state remains immediate, while expensive filtering/counting waits briefly as the
 * user types. A new character cancels the previous wait; clearing is emitted immediately so leaving
 * search never leaves stale results visible. Trimming also suppresses whitespace-only re-runs.
 */
fun Flow<String>.debounceFavouritesSearch(): Flow<String> =
	map { it.trim() }
		.distinctUntilChanged()
		.flatMapLatest { query ->
			flow {
				if (query.isNotEmpty()) delay(FAVOURITES_SEARCH_DEBOUNCE_MS)
				emit(query)
			}
		}
