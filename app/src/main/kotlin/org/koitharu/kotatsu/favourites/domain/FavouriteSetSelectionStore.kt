package org.koitharu.kotatsu.favourites.domain

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import org.koitharu.kotatsu.favourites.data.FavouriteCategorySetEntity
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import javax.inject.Inject
import javax.inject.Singleton

/** Navigation only. The database owns all assignments. Keys also survive restore ID remapping. */
@Singleton
class FavouriteSetSelectionStore @Inject constructor(
	@ApplicationContext context: Context,
	private val repository: FavouritesRepository,
) {
	private val prefs = context.getSharedPreferences("favourite_set_navigation", Context.MODE_PRIVATE)
	private val revision = MutableStateFlow(0L)
	private val selectionLock = Any()

	fun select(set: FavouriteCategorySetEntity, space: FavouriteSpace, type: FavouriteContentType) = synchronized(selectionLock) {
		require(set.space == space.dbValue && set.deletedAt == 0L)
		val key = key(space, type)
		if (prefs.getString(key, null) == set.syncKey) return@synchronized
		prefs.edit { putString(key, set.syncKey) }
		revision.update { it + 1 }
	}

	fun observe(space: FavouriteSpace, type: Flow<FavouriteContentType>): Flow<FavouriteCategorySetEntity> = combine(
		repository.observeSets(space), type, revision,
	) { sets, contentType, _ -> sets to contentType }.mapLatest { (sets, contentType) ->
		val preferenceKey = key(space, contentType)
		val saved = prefs.getString(preferenceKey, null)
		// A selection revision can arrive before Room observes a newly committed Set.
		// Confirm a missing key against current data before repairing it to the default.
		val currentSets = if (saved != null && sets.none { it.syncKey == saved }) repository.getSets(space) else sets
		synchronized(selectionLock) {
			// A newer selection won during the suspended read; its revision resolves it.
			if (prefs.getString(preferenceKey, null) != saved) return@synchronized null
			val selected = currentSets.firstOrNull { it.syncKey == saved } ?: checkNotNull(currentSets.firstOrNull())
			select(selected, space, contentType)
			selected
		}
	}.filterNotNull().distinctUntilChanged()

	private fun key(space: FavouriteSpace, type: FavouriteContentType) = "${space.dbValue}:${type.name}"
}
