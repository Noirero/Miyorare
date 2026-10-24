package org.koitharu.kotatsu.favourites.domain

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.map
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

	fun select(set: FavouriteCategorySetEntity, space: FavouriteSpace, type: FavouriteContentType) {
		require(set.space == space.dbValue && set.deletedAt == 0L)
		val key = key(space, type)
		if (prefs.getString(key, null) == set.syncKey) return
		prefs.edit { putString(key, set.syncKey) }
		revision.update { it + 1 }
	}

	fun observe(space: FavouriteSpace, type: Flow<FavouriteContentType>): Flow<FavouriteCategorySetEntity> = combine(
		repository.observeSets(space), type, revision,
	) { sets, contentType, _ ->
		val saved = prefs.getString(key(space, contentType), null)
		val selected = sets.firstOrNull { it.syncKey == saved } ?: checkNotNull(sets.firstOrNull())
		contentType to selected
	}.onEach { (contentType, set) -> select(set, space, contentType) }
		.map { it.second }.distinctUntilChanged()

	private fun key(space: FavouriteSpace, type: FavouriteContentType) = "${space.dbValue}:${type.name}"
}
