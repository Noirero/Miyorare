package org.koitharu.kotatsu.favourites.domain

import androidx.room.withTransaction
import dagger.Reusable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.TABLE_FAVOURITES
import org.koitharu.kotatsu.core.db.TABLE_FAVOURITE_CATEGORIES
import org.koitharu.kotatsu.core.db.TABLE_PRIVATE_FAVOURITES
import org.koitharu.kotatsu.core.db.entity.toEntities
import org.koitharu.kotatsu.core.db.entity.toEntity
import org.koitharu.kotatsu.core.db.entity.toMangaList
import org.koitharu.kotatsu.core.model.FavouriteCategory
import org.koitharu.kotatsu.core.model.toMangaSources
import org.koitharu.kotatsu.core.ui.util.ReversibleHandle
import org.koitharu.kotatsu.core.util.ext.mapItems
import org.koitharu.kotatsu.favourites.data.FavouriteCategoryEntity
import org.koitharu.kotatsu.favourites.data.FavouriteEntity
import org.koitharu.kotatsu.favourites.data.FavouriteMembership
import org.koitharu.kotatsu.favourites.data.FavouriteSourceCount
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.data.PrivateFavouriteEntity
import org.koitharu.kotatsu.favourites.data.toFavouriteCategory
import org.koitharu.kotatsu.favourites.data.toMangaList
import org.koitharu.kotatsu.favourites.data.toPrivateMangaList
import org.koitharu.kotatsu.favourites.domain.model.Cover
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.list.domain.ListSortOrder
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.levenshteinDistance
import org.koitharu.kotatsu.search.domain.SearchKind
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@Reusable
class FavouritesRepository @Inject constructor(
	private val db: MangaDatabase,
	private val localObserver: LocalFavoritesObserver,
	private val downloadedContentClassifier: DownloadedContentClassifier,
) {
	/** Count-only access used by the library header; keeps full favourite entities off the hot path. */
	suspend fun getCategoryCounts(
		categoryIds: Collection<Long>,
		space: FavouriteSpace = FavouriteSpace.NORMAL,
	): Map<Long, Int> {
		if (categoryIds.isEmpty()) return emptyMap()
		val rows = if (space == FavouriteSpace.PRIVATE) {
			db.getPrivateFavouritesDao().findCategoryCounts(categoryIds)
		} else {
			db.getFavouritesDao().findCategoryCounts(categoryIds)
		}
		return rows.associate { it.categoryId to it.itemCount }
	}

	suspend fun getDistinctMangaCount(
		categoryIds: Collection<Long>,
		space: FavouriteSpace = FavouriteSpace.NORMAL,
	): Int = if (space == FavouriteSpace.PRIVATE) {
		db.getPrivateFavouritesDao().findDistinctMangaCount(categoryIds)
	} else {
		db.getFavouritesDao().findDistinctMangaCount(categoryIds)
	}

	suspend fun getAllManga(space: FavouriteSpace = FavouriteSpace.NORMAL): List<Manga> {
		return if (space == FavouriteSpace.PRIVATE) {
			db.getPrivateFavouritesDao()
				.observeAll(ListSortOrder.NEWEST, emptySet(), Int.MAX_VALUE)
				.first()
				.toPrivateMangaList()
		} else {
			db.getFavouritesDao().findAll().toMangaList()
		}
	}

	/** Lightweight category membership projection, scoped to the requested library space. */
	suspend fun getMemberships(space: FavouriteSpace = FavouriteSpace.NORMAL): List<FavouriteMembership> =
		if (space == FavouriteSpace.PRIVATE) {
			db.getPrivateFavouritesDao().findMemberships()
		} else {
			db.getFavouritesDao().findMemberships()
		}

	/**
	 * Virtual Downloaded rows share local_index. NORMAL preserves the existing global shelf except
	 * for PRIVATE-only titles; PRIVATE shows only titles that belong to Private Favourites.
	 */
	suspend fun getDownloadedEntries(
		space: FavouriteSpace = FavouriteSpace.NORMAL,
	): List<org.koitharu.kotatsu.favourites.data.FavouriteSearchEntry> {
		val localDownloadedIds = downloadedContentClassifier.getLocalDownloadedIds()
		val base = db.getFavouritesDao().findDownloadedSearchEntries().filter { entry ->
			entry.source != "LOCAL" || entry.mangaId in localDownloadedIds
		}
		val privateIds = db.getPrivateFavouritesDao().findActiveMangaIds().toHashSet()
		if (privateIds.isEmpty()) return if (space == FavouriteSpace.PRIVATE) emptyList() else base
		return if (space == FavouriteSpace.PRIVATE) {
			base.filter { it.mangaId in privateIds }
		} else {
			val normalIds = db.getFavouritesDao().findMemberships().mapTo(HashSet()) { it.mangaId }
			base.filterNot { it.mangaId in privateIds && it.mangaId !in normalIds }
		}
	}

	suspend fun getDownloadedCountsBySource(
		space: FavouriteSpace = FavouriteSpace.NORMAL,
	): List<FavouriteSourceCount> {
		val entries = getDownloadedEntries(space)
		return entries.groupingBy { it.source }.eachCount().map { (source, count) ->
			FavouriteSourceCount(source, count)
		}
	}

	suspend fun getLastManga(limit: Int): List<Manga> {
		return db.getFavouritesDao().findLast(limit).toMangaList()
	}

	suspend fun search(query: String, kind: SearchKind, limit: Int): List<Manga> {
		val dao = db.getFavouritesDao()
		val q = "%$query%"
		val entities = when (kind) {
			SearchKind.SIMPLE,
			SearchKind.TITLE -> dao.searchByTitle(q, limit).sortedBy { it.manga.title.levenshteinDistance(query) }
			SearchKind.AUTHOR -> dao.searchByAuthor(q, limit)
			SearchKind.TAG -> dao.searchByTag(q, limit)
		}
		return entities.toMangaList()
	}

	fun observeDownloaded(
		order: ListSortOrder,
		filterOptions: Set<ListFilterOption>,
		limit: Int,
		pinned: List<Long> = emptyList(),
		space: FavouriteSpace = FavouriteSpace.NORMAL,
	): Flow<List<Manga>> {
		if (space == FavouriteSpace.NORMAL) {
			return combine(
				localObserver.observeDownloaded(order, filterOptions, Int.MAX_VALUE, pinned),
				observePrivateMembershipIds(),
				observeNormalMembershipIds(),
			) { items, privateIds, normalIds ->
				items.asSequence()
					.filterNot { it.id in privateIds && it.id !in normalIds }
					.take(limit)
					.toList()
			}.distinctUntilChanged()
		}
		return combine(
			localObserver.observeDownloaded(order, filterOptions, Int.MAX_VALUE, pinned),
			observePrivateMembershipIds(),
		) { items, privateIds ->
			items.asSequence().filter { it.id in privateIds }.take(limit).toList()
		}.distinctUntilChanged()
	}

	fun observeAll(
		order: ListSortOrder,
		filterOptions: Set<ListFilterOption>,
		limit: Int,
		pinned: List<Long> = emptyList(),
		space: FavouriteSpace = FavouriteSpace.NORMAL,
	): Flow<List<Manga>> {
		if (space == FavouriteSpace.PRIVATE) {
			return db.getPrivateFavouritesDao().observeAll(order, filterOptions, limit, pinned)
				.map { it.toPrivateMangaList() }
		}
		if (ListFilterOption.Downloaded in filterOptions) {
			return localObserver.observeAll(order, filterOptions, limit)
		}
		return db.getFavouritesDao().observeAll(order, filterOptions, limit, pinned).map { it.toMangaList() }
	}

	suspend fun getManga(categoryId: Long, space: FavouriteSpace = FavouriteSpace.NORMAL): List<Manga> {
		return if (space == FavouriteSpace.PRIVATE) {
			db.getPrivateFavouritesDao().findAll(categoryId).toPrivateMangaList()
		} else {
			db.getFavouritesDao().findAll(categoryId).toMangaList()
		}
	}

	fun observeAll(
		categoryId: Long,
		order: ListSortOrder,
		filterOptions: Set<ListFilterOption>,
		limit: Int,
		pinned: List<Long> = emptyList(),
		space: FavouriteSpace = FavouriteSpace.NORMAL,
	): Flow<List<Manga>> {
		if (space == FavouriteSpace.PRIVATE) {
			return db.getPrivateFavouritesDao().observeAll(categoryId, order, filterOptions, limit, pinned)
				.map { it.toPrivateMangaList() }
		}
		if (ListFilterOption.Downloaded in filterOptions) {
			return localObserver.observeAll(categoryId, order, filterOptions, limit)
		}
		return db.getFavouritesDao().observeAll(categoryId, order, filterOptions, limit, pinned).map { it.toMangaList() }
	}

	fun observeAll(
		categoryId: Long,
		filterOptions: Set<ListFilterOption>,
		limit: Int,
		pinned: List<Long> = emptyList(),
		space: FavouriteSpace = FavouriteSpace.NORMAL,
	): Flow<List<Manga>> = observeOrder(categoryId)
		.flatMapLatest { order -> observeAll(categoryId, order, filterOptions, limit, pinned, space) }

	fun observeMangaCount(space: FavouriteSpace = FavouriteSpace.NORMAL): Flow<Int> =
		if (space == FavouriteSpace.PRIVATE) {
			db.getPrivateFavouritesDao().observeMangaCount().distinctUntilChanged()
		} else {
			db.getFavouritesDao().observeMangaCount().distinctUntilChanged()
		}

	fun observeFavouritesChanges(space: FavouriteSpace = FavouriteSpace.NORMAL): Flow<Unit> =
		db.invalidationTracker.createFlow(
			if (space == FavouriteSpace.PRIVATE) TABLE_PRIVATE_FAVOURITES else TABLE_FAVOURITES,
			emitInitialState = true,
		).map { Unit }

	fun observeDownloadedChanges(): Flow<Unit> = db.invalidationTracker.createFlow(
		"local_index",
		emitInitialState = true,
	).map { Unit }

	fun observeCategories(space: FavouriteSpace = FavouriteSpace.NORMAL): Flow<List<FavouriteCategory>> {
		val source = if (space == FavouriteSpace.PRIVATE) {
			db.getFavouriteCategoriesDao().observeAllInSpace(space.dbValue)
		} else {
			db.getFavouriteCategoriesDao().observeAll()
		}
		return source.mapItems { it.toFavouriteCategory() }.distinctUntilChanged()
	}

	fun observeCategoriesForLibrary(space: FavouriteSpace = FavouriteSpace.NORMAL): Flow<List<FavouriteCategory>> {
		val source = if (space == FavouriteSpace.PRIVATE) {
			db.getFavouriteCategoriesDao().observeAllVisibleInSpace(space.dbValue)
		} else {
			db.getFavouriteCategoriesDao().observeAllVisible()
		}
		return source.mapItems { it.toFavouriteCategory() }.onEach { categories ->
			val prefix = space.dbValue.toString() + ':'
			categorySnapshots.keys.removeAll { it.startsWith(prefix) }
			for (category in categories) categorySnapshots[snapshotKey(space, category.id)] = category
		}.distinctUntilChanged()
	}

	fun observeCategoriesWithCovers(
		space: FavouriteSpace = FavouriteSpace.NORMAL,
	): Flow<Map<FavouriteCategory, List<Cover>>> {
		return db.invalidationTracker.createFlow(
			if (space == FavouriteSpace.PRIVATE) TABLE_PRIVATE_FAVOURITES else TABLE_FAVOURITES,
			TABLE_FAVOURITE_CATEGORIES,
			emitInitialState = true,
		).mapLatest {
			db.withTransaction {
				val entities = if (space == FavouriteSpace.PRIVATE) {
					db.getFavouriteCategoriesDao().findAllInSpace(space.dbValue)
				} else {
					db.getFavouriteCategoriesDao().findAll()
				}
				val res = LinkedHashMap<FavouriteCategory, List<Cover>>(entities.size)
				for (entity in entities) {
					val cat = entity.toFavouriteCategory()
					res[cat] = if (space == FavouriteSpace.PRIVATE) {
						db.getPrivateFavouritesDao().findCovers(cat.id, cat.order)
					} else {
						db.getFavouritesDao().findCovers(cat.id, cat.order)
					}
				}
				res
			}
		}.distinctUntilChanged()
	}

	suspend fun getAllFavoritesCovers(
		order: ListSortOrder,
		limit: Int,
		space: FavouriteSpace = FavouriteSpace.NORMAL,
	): List<Cover> = if (space == FavouriteSpace.PRIVATE) {
		db.getPrivateFavouritesDao().findCovers(order, limit)
	} else {
		db.getFavouritesDao().findCovers(order, limit)
	}

	fun observeCategory(id: Long, space: FavouriteSpace = FavouriteSpace.NORMAL): Flow<FavouriteCategory?> = flow {
		categorySnapshots[snapshotKey(space, id)]?.let { emit(it) }
		emitAll(db.getFavouriteCategoriesDao().observe(id).map { it?.toFavouriteCategory() })
	}.distinctUntilChanged()

	fun observeCategories(
		mangaId: Long,
		space: FavouriteSpace = FavouriteSpace.NORMAL,
	): Flow<Set<FavouriteCategory>> {
		val source = if (space == FavouriteSpace.PRIVATE) {
			db.getPrivateFavouritesDao().observeCategories(mangaId)
		} else {
			db.getFavouritesDao().observeCategories(mangaId)
		}
		return source.map { list -> list.mapTo(LinkedHashSet(list.size)) { it.toFavouriteCategory() } }
	}

	suspend fun getCategory(id: Long): FavouriteCategory =
		db.getFavouriteCategoriesDao().find(id.toInt()).toFavouriteCategory()

	suspend fun isFavorite(mangaId: Long, space: FavouriteSpace = FavouriteSpace.NORMAL): Boolean =
		if (space == FavouriteSpace.PRIVATE) {
			db.getPrivateFavouritesDao().findCategoriesCount(mangaId) != 0
		} else {
			db.getFavouritesDao().findCategoriesCount(mangaId) != 0
		}

	suspend fun getCategoriesIds(
		mangaId: Long,
		space: FavouriteSpace = FavouriteSpace.NORMAL,
	): Set<Long> = if (space == FavouriteSpace.PRIVATE) {
		db.getPrivateFavouritesDao().findCategoriesIds(mangaId).toSet()
	} else {
		db.getFavouritesDao().findCategoriesIds(mangaId).toSet()
	}

	suspend fun findPopularSources(
		categoryId: Long,
		limit: Int,
		space: FavouriteSpace = FavouriteSpace.NORMAL,
	): List<MangaSource> {
		val values = if (space == FavouriteSpace.PRIVATE) {
			db.getPrivateFavouritesDao().run {
				if (categoryId == 0L) findPopularSources(limit) else findPopularSources(categoryId, limit)
			}
		} else {
			db.getFavouritesDao().run {
				if (categoryId == 0L) findPopularSources(limit) else findPopularSources(categoryId, limit)
			}
		}
		return values.toMangaSources()
	}

	suspend fun findSources(
		categoryId: Long,
		space: FavouriteSpace = FavouriteSpace.NORMAL,
	): List<MangaSource> = findPopularSources(categoryId, Int.MAX_VALUE, space)

	suspend fun createCategory(
		title: String,
		sortOrder: ListSortOrder,
		isTrackerEnabled: Boolean,
		isNewChaptersDownloadEnabled: Boolean,
		isVisibleOnShelf: Boolean,
		space: FavouriteSpace = FavouriteSpace.NORMAL,
	): FavouriteCategory {
		val entity = FavouriteCategoryEntity(
			title = title,
			createdAt = System.currentTimeMillis(),
			sortKey = db.getFavouriteCategoriesDao().getNextSortKey(space),
			categoryId = 0,
			order = sortOrder.name,
			// Background tracker/download notifications remain NORMAL-only to avoid privacy leaks.
			track = if (space == FavouriteSpace.PRIVATE) false else isTrackerEnabled,
			downloadNewChapters = if (space == FavouriteSpace.PRIVATE) false else isNewChaptersDownloadEnabled,
			deletedAt = 0L,
			isVisibleInLibrary = isVisibleOnShelf,
			space = space.dbValue,
		)
		val id = db.getFavouriteCategoriesDao().insert(entity)
		return entity.toFavouriteCategory(id)
	}

	suspend fun updateCategory(
		id: Long,
		title: String,
		sortOrder: ListSortOrder,
		isTrackerEnabled: Boolean,
		isNewChaptersDownloadEnabled: Boolean,
		isVisibleOnShelf: Boolean,
	) {
		val entity = db.getFavouriteCategoriesDao().find(id.toInt())
		val isPrivate = entity.space == FavouriteSpace.PRIVATE.dbValue
		db.getFavouriteCategoriesDao().update(
			id = id,
			title = title,
			order = sortOrder.name,
			tracker = if (isPrivate) false else isTrackerEnabled,
			downloadNewChapters = if (isPrivate) false else isNewChaptersDownloadEnabled,
			onShelf = isVisibleOnShelf,
		)
	}

	suspend fun updateCategory(id: Long, isVisibleInLibrary: Boolean) {
		db.getFavouriteCategoriesDao().updateVisibility(id, isVisibleInLibrary)
	}

	suspend fun updateCategoryTracking(id: Long, isTrackingEnabled: Boolean) {
		val entity = db.getFavouriteCategoriesDao().find(id.toInt())
		if (entity.space == FavouriteSpace.PRIVATE.dbValue) return
		db.getFavouriteCategoriesDao().updateTracking(id, isTrackingEnabled)
	}

	suspend fun setNewChaptersDownloadCategories(ids: Set<Long>) {
		db.withTransaction {
			val dao = db.getFavouriteCategoriesDao()
			dao.clearNewChaptersDownload()
			for (id in ids) {
				val entity = dao.find(id.toInt())
				if (entity.space == FavouriteSpace.NORMAL.dbValue) dao.updateNewChaptersDownload(id, true)
			}
		}
	}

	suspend fun enableNewChaptersDownloadForTrackedCategories() {
		db.getFavouriteCategoriesDao().enableNewChaptersDownloadForTracked()
	}

	suspend fun isNewChaptersDownloadEnabled(mangaId: Long): Boolean =
		db.getFavouritesDao().isNewChaptersDownloadEnabled(mangaId)

	suspend fun removeCategories(ids: Collection<Long>) {
		db.withTransaction {
			for (id in ids) {
				val category = db.getFavouriteCategoriesDao().find(id.toInt())
				if (category.space == FavouriteSpace.PRIVATE.dbValue) {
					db.getPrivateFavouritesDao().deleteAll(id)
				} else {
					db.getFavouritesDao().deleteAll(id)
				}
				db.getFavouriteCategoriesDao().delete(id)
			}
			db.getChaptersDao().gc()
		}
	}

	suspend fun setCategoryOrder(id: Long, order: ListSortOrder) {
		db.getFavouriteCategoriesDao().updateOrder(id, order.name)
	}

	suspend fun reorderCategories(orderedIds: List<Long>) {
		val dao = db.getFavouriteCategoriesDao()
		db.withTransaction {
			for ((i, id) in orderedIds.withIndex()) dao.updateSortKey(id, i)
		}
	}

	suspend fun addToCategory(categoryId: Long, mangas: Collection<Manga>) {
		val category = db.getFavouriteCategoriesDao().find(categoryId.toInt())
		val privateSpace = category.space == FavouriteSpace.PRIVATE.dbValue
		db.withTransaction {
			for (manga in mangas) {
				val tags = manga.tags.toEntities()
				db.getTagsDao().upsert(tags)
				db.getMangaDao().upsert(manga.toEntity(), tags)
				val now = System.currentTimeMillis()
				if (privateSpace) {
					db.getPrivateFavouritesDao().insert(
						PrivateFavouriteEntity(manga.id, categoryId, 0, false, now, 0L),
					)
				} else {
					db.getFavouritesDao().insert(
						FavouriteEntity(manga.id, categoryId, 0, false, now, 0L),
					)
				}
			}
		}
	}

	suspend fun removeFromFavourites(
		ids: Collection<Long>,
		space: FavouriteSpace = FavouriteSpace.NORMAL,
	): ReversibleHandle {
		db.withTransaction {
			for (id in ids) {
				if (space == FavouriteSpace.PRIVATE) db.getPrivateFavouritesDao().delete(id)
				else db.getFavouritesDao().delete(id)
			}
			db.getChaptersDao().gc()
		}
		return ReversibleHandle { recoverToFavourites(ids, space) }
	}

	suspend fun removeFromCategory(categoryId: Long, ids: Collection<Long>): ReversibleHandle {
		val category = db.getFavouriteCategoriesDao().find(categoryId.toInt())
		val space = FavouriteSpace.fromDb(category.space)
		db.withTransaction {
			for (id in ids) {
				if (space == FavouriteSpace.PRIVATE) db.getPrivateFavouritesDao().delete(id, categoryId)
				else db.getFavouritesDao().delete(categoryId, id)
			}
			db.getChaptersDao().gc()
		}
		return ReversibleHandle { recoverToCategory(categoryId, ids, space) }
	}

	private fun observeOrder(categoryId: Long): Flow<ListSortOrder> = observeCategory(categoryId)
		.filterNotNull()
		.map { it.order }
		.distinctUntilChanged()

	suspend fun getMostUpdatedCategories(limit: Int): List<FavouriteCategory> =
		db.getFavouriteCategoriesDao().getMostUpdatedCategories(limit).map { it.toFavouriteCategory() }

	private suspend fun recoverToFavourites(ids: Collection<Long>, space: FavouriteSpace) {
		db.withTransaction {
			for (id in ids) {
				if (space == FavouriteSpace.PRIVATE) db.getPrivateFavouritesDao().recover(id)
				else db.getFavouritesDao().recover(id)
			}
		}
	}

	private suspend fun recoverToCategory(categoryId: Long, ids: Collection<Long>, space: FavouriteSpace) {
		db.withTransaction {
			for (id in ids) {
				if (space == FavouriteSpace.PRIVATE) db.getPrivateFavouritesDao().recover(categoryId, id)
				else db.getFavouritesDao().recover(categoryId, id)
			}
		}
	}

	private fun observePrivateMembershipIds(): Flow<Set<Long>> =
		db.invalidationTracker.createFlow(TABLE_PRIVATE_FAVOURITES, emitInitialState = true)
			.mapLatest { db.getPrivateFavouritesDao().findActiveMangaIds().toHashSet() }
			.distinctUntilChanged()

	private fun observeNormalMembershipIds(): Flow<Set<Long>> =
		db.invalidationTracker.createFlow(TABLE_FAVOURITES, emitInitialState = true)
			.mapLatest { db.getFavouritesDao().findMemberships().mapTo(HashSet()) { it.mangaId } }
			.distinctUntilChanged()

	private companion object {
		val categorySnapshots = ConcurrentHashMap<String, FavouriteCategory>()
		fun snapshotKey(space: FavouriteSpace, id: Long) = "${space.dbValue}:$id"
	}
}
