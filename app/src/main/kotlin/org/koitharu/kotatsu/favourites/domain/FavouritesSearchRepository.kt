package org.koitharu.kotatsu.favourites.domain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.favourites.data.FavouriteMembership
import org.koitharu.kotatsu.favourites.data.FavouriteSearchEntry
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** Lightweight cached metadata used by Favourites search/counting, isolated per library space. */
@Singleton
class FavouritesSearchRepository @Inject constructor(
	private val db: MangaDatabase,
) {
	private val mutex = Mutex()
	private val generation = AtomicLong(0L)
	private val cachedEntries = mutableMapOf<FavouriteSpace, List<FavouriteSearchEntry>>()
	private val cachedMemberships = mutableMapOf<FavouriteSpace, List<FavouriteMembership>>()

	fun invalidate() {
		generation.incrementAndGet()
		cachedEntries.clear()
		cachedMemberships.clear()
	}

	suspend fun getEntries(space: FavouriteSpace = FavouriteSpace.NORMAL): List<FavouriteSearchEntry> {
		cachedEntries[space]?.let { return it }
		return mutex.withLock {
			cachedEntries[space]?.let { return@withLock it }
			val expectedGeneration = generation.get()
			val loaded = if (space == FavouriteSpace.PRIVATE) {
				db.getPrivateFavouritesDao().findSearchEntries()
			} else {
				db.getFavouritesDao().findSearchEntries()
			}
			if (generation.get() == expectedGeneration) cachedEntries[space] = loaded
			loaded
		}
	}

	suspend fun getMemberships(space: FavouriteSpace = FavouriteSpace.NORMAL): List<FavouriteMembership> {
		cachedMemberships[space]?.let { return it }
		return mutex.withLock {
			cachedMemberships[space]?.let { return@withLock it }
			val expectedGeneration = generation.get()
			val loaded = if (space == FavouriteSpace.PRIVATE) {
				db.getPrivateFavouritesDao().findMemberships()
			} else {
				db.getFavouritesDao().findMemberships()
			}
			if (generation.get() == expectedGeneration) cachedMemberships[space] = loaded
			loaded
		}
	}
}
