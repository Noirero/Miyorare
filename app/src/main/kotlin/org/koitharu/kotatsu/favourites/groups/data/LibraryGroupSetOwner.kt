package org.koitharu.kotatsu.favourites.groups.data

import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.domain.CategorySetIdentity

/**
 * Compatibility fallback for legacy Group creation/restore until all entry points carry a Set.
 * Do not infer ownership from the current UI selection or another device's numeric Set ID.
 */
internal suspend fun MangaDatabase.defaultLibraryGroupSetId(space: FavouriteSpace): Long {
    val sets = getFavouriteCategorySetsDao().getSets(space.dbValue)
    return checkNotNull(
        sets.firstOrNull { it.syncKey == CategorySetIdentity.defaultSet(space.dbValue) }
            ?: sets.firstOrNull()
    ) { "No active Favourite Set in ${space.name}" }.setId
}
