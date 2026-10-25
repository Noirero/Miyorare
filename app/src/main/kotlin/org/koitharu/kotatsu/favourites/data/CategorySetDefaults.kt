package org.koitharu.kotatsu.favourites.data

import androidx.room.withTransaction
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.favourites.domain.CategorySetIdentity

/** Shared by navigation and snapshot writers, including an empty/reset library. */
suspend fun MangaDatabase.ensureCategorySets(space: FavouriteSpace): List<FavouriteCategorySetEntity> = withTransaction {
	val dao = getFavouriteCategorySetsDao()
	val sets = dao.getSets(space.dbValue)
	if (sets.isNotEmpty()) return@withTransaction sets
	// A retained tombstone must never be resurrected by an ordinary read/default initialization.
	check(dao.findSetByKey(CategorySetIdentity.defaultSet(space.dbValue), space.dbValue) == null)
	dao.insert(FavouriteCategorySetEntity(syncKey = CategorySetIdentity.defaultSet(space.dbValue), title = "Utama", sortKey = 0, createdAt = 0, updatedAt = 0, space = space.dbValue))
	dao.getSets(space.dbValue)
}
