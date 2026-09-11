package org.koitharu.kotatsu.favourites.domain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.favourites.data.FavouriteMembership
import org.koitharu.kotatsu.favourites.data.FavouriteSearchEntry
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** Lightweight cached metadata used by Favourites search/counting, isolated per library space. */
@Singleton
class FavouritesSearchRepository @Inject constructor(
	private val db: MangaDatabase,
) {
	private val mutexes = mapOf(
		FavouriteSpace.NORMAL to Mutex(),
		FavouriteSpace.PRIVATE to Mutex(),
	)
	private val generations = mapOf(
		FavouriteSpace.NORMAL to AtomicLong(0L),
		FavouriteSpace.PRIVATE to AtomicLong(0L),
	)
	private val cachedEntries = ConcurrentHashMap<FavouriteSpace, List<FavouriteSearchEntry>>()
	private val cachedMemberships = ConcurrentHashMap<FavouriteSpace, List<FavouriteMembership>>()

	/**
	 * Invalidate only the library space that changed. Passing null remains available for callers that
	 * truly change shared metadata. Per-space generations prevent an in-flight load from publishing a
	 * stale snapshot after its own space has been invalidated.
	 */
	fun invalidate(space: FavouriteSpace? = null) {
		if (space == null) {
			for (item in generations.values) item.incrementAndGet()
			cachedEntries.clear()
			cachedMemberships.clear()
			return
		}
		generation(space).incrementAndGet()
		cachedEntries.remove(space)
		cachedMemberships.remove(space)
	}

	suspend fun getEntries(space: FavouriteSpace = FavouriteSpace.NORMAL): List<FavouriteSearchEntry> {
		cachedEntries[space]?.let { return it }
		return mutex(space).withLock {
			cachedEntries[space]?.let { return@withLock it }
			val expectedGeneration = generation(space).get()
			val loaded = if (space == FavouriteSpace.PRIVATE) {
				db.getPrivateFavouritesDao().findSearchEntries()
			} else {
				db.getFavouritesDao().findSearchEntries()
			}
			if (generation(space).get() == expectedGeneration) cachedEntries[space] = loaded
			loaded
		}
	}

	suspend fun getMemberships(space: FavouriteSpace = FavouriteSpace.NORMAL): List<FavouriteMembership> {
		cachedMemberships[space]?.let { return it }
		return mutex(space).withLock {
			cachedMemberships[space]?.let { return@withLock it }
			val expectedGeneration = generation(space).get()
			val loaded = if (space == FavouriteSpace.PRIVATE) {
				db.getPrivateFavouritesDao().findMemberships()
			} else {
				db.getFavouritesDao().findMemberships()
			}
			if (generation(space).get() == expectedGeneration) cachedMemberships[space] = loaded
			loaded
		}
	}

	private fun mutex(space: FavouriteSpace): Mutex = checkNotNull(mutexes[space])
	private fun generation(space: FavouriteSpace): AtomicLong = checkNotNull(generations[space])
}
