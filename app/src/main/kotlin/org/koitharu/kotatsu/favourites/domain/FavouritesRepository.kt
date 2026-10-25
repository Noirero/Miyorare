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
import org.koitharu.kotatsu.favourites.data.ensureCategorySets
import org.koitharu.kotatsu.favourites.data.FavouriteCategorySetEntity
import org.koitharu.kotatsu.favourites.data.FavouriteCategorySetAssignmentEntity
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

	/** True only for the explicit "keep in Private, remove all isolation" mode. */
	suspend fun isPrivateIsolationDisabled(): Boolean =
		db.getPrivateFavouritesDao().isIsolationDisabled()

	/**
	 * Virtual Downloaded rows share local_index, but each favourites space owns only the physical
	 * download roots assigned to that space. Private membership/isolation is applied after the path
	 * boundary so Normal and Private destinations are never merged accidentally.
	 */
	suspend fun getDownloadedEntries(
		space: FavouriteSpace = FavouriteSpace.NORMAL,
	): List<org.koitharu.kotatsu.favourites.data.FavouriteSearchEntry> {
		val downloadedIds = downloadedContentClassifier.getDownloadedIds(space)
		if (downloadedIds.isEmpty()) return emptyList()
		val base = db.getFavouritesDao().findDownloadedSearchEntries().filter { entry ->
			entry.mangaId in downloadedIds
		}
		val privateDao = db.getPrivateFavouritesDao()
		val privateIds = privateDao.findAllActiveMangaIds().toHashSet()
		if (space == FavouriteSpace.PRIVATE) {
			return if (privateIds.isEmpty()) emptyList() else base.filter { it.mangaId in privateIds }
		}
		if (privateIds.isEmpty() || privateDao.isIsolationDisabled()) return base
		val normalIds = db.getFavouritesDao().findMemberships().mapTo(HashSet()) { it.mangaId }
		return base.filterNot { it.mangaId in privateIds && it.mangaId !in normalIds }
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

	/** Global favourites search includes Private membership only after its app-wide isolation is removed. */
	suspend fun search(query: String, kind: SearchKind, limit: Int): List<Manga> {
		val normalDao = db.getFavouritesDao()
		val privateDao = db.getPrivateFavouritesDao()
		val q = "%$query%"
		val normal = when (kind) {
			SearchKind.SIMPLE,
			SearchKind.TITLE -> normalDao.searchByTitle(q, limit)
			SearchKind.AUTHOR -> normalDao.searchByAuthor(q, limit)
			SearchKind.TAG -> normalDao.searchByTag(q, limit)
		}
		if (!privateDao.isIsolationDisabled()) {
			val ordered = if (kind == SearchKind.SIMPLE || kind == SearchKind.TITLE) {
				normal.sortedBy { it.manga.title.levenshteinDistance(query) }
			} else {
				normal
			}
			return ordered.take(limit).toMangaList()
		}
		val private = when (kind) {
			SearchKind.SIMPLE,
			SearchKind.TITLE -> privateDao.searchByTitle(q, limit)
			SearchKind.AUTHOR -> privateDao.searchByAuthor(q, limit)
			SearchKind.TAG -> privateDao.searchByTag(q, limit)
		}
		val merged = (normal + private).distinctBy { it.manga.id }
		val ordered = if (kind == SearchKind.SIMPLE || kind == SearchKind.TITLE) {
			merged.sortedBy { it.manga.title.levenshteinDistance(query) }
		} else {
			merged
		}
		return ordered.take(limit).toMangaList()
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
				localObserver.observeDownloaded(order, filterOptions, Int.MAX_VALUE, pinned, space),
				observePrivateMembershipIds(),
				observeNormalMembershipIds(),
				db.getPrivateFavouritesDao().observeIsolationDisabled(),
			) { items, privateIds, normalIds, isolationDisabled ->
				val sequence = items.asSequence()
					.let { values ->
						if (isolationDisabled) values
						else values.filterNot { it.id in privateIds && it.id !in normalIds }
					}
				sequence.take(limit).toList()
			}.distinctUntilChanged()
		}
		return combine(
			localObserver.observeDownloaded(order, filterOptions, Int.MAX_VALUE, pinned, space),
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

	fun observeInCategories(
		categoryIds: Collection<Long>, order: ListSortOrder, filters: Set<ListFilterOption>,
		limit: Int, pinned: List<Long>, space: FavouriteSpace,
	): Flow<List<Manga>> = if (space == FavouriteSpace.PRIVATE) {
		db.getPrivateFavouritesDao().observeInCategories(categoryIds, order, filters, limit, pinned).map { it.toPrivateMangaList() }
	} else {
		db.getFavouritesDao().observeInCategories(categoryIds, order, filters, limit, pinned).map { it.toMangaList() }
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
		tables = arrayOf("local_index", "favourite_download_index"),
		emitInitialState = true,
	).map { Unit }

	/** Global APIs below remain flattened; only navigation callers opt into this overload. */
	fun observeSets(space: FavouriteSpace): Flow<List<FavouriteCategorySetEntity>> =
		flow { getSets(space); emitAll(db.getFavouriteCategorySetsDao().observeSets(space.dbValue)) }.distinctUntilChanged()

	suspend fun getSets(space: FavouriteSpace): List<FavouriteCategorySetEntity> = db.ensureCategorySets(space)

	fun observeSetCategoryCounts(space: FavouriteSpace): Flow<Map<Long, Int>> =
		db.getFavouriteCategorySetsDao().observeCategoryCounts(space.dbValue)
			.map { counts -> counts.associate { it.setId to it.categoryCount } }.distinctUntilChanged()

	suspend fun resolveSet(id: Long?, space: FavouriteSpace): FavouriteCategorySetEntity {
		val sets = getSets(space)
		return sets.firstOrNull { it.setId == id } ?: checkNotNull(sets.firstOrNull()) { "Missing Favourite Set" }
	}

	fun observeCategoriesInSet(setId: Long, space: FavouriteSpace): Flow<List<FavouriteCategory>> =
		db.getFavouriteCategorySetsDao().observeCategories(setId, space.dbValue)
			.mapItems { it.toFavouriteCategory() }.distinctUntilChanged()

	fun observeCategoriesForLibrary(setId: Long, space: FavouriteSpace): Flow<List<FavouriteCategory>> =
		observeCategoriesInSet(setId, space).map { list -> list.filter { it.isVisibleInLibrary } }

	suspend fun getCategorySet(categoryId: Long, space: FavouriteSpace): FavouriteCategorySetEntity {
		val category = db.getFavouriteCategoriesDao().find(categoryId.toInt())
		check(category.space == space.dbValue)
		return resolveSet(db.getFavouriteCategorySetsDao().getAssignment(categoryId)?.setId, space)
	}

	suspend fun createSet(title: String, space: FavouriteSpace): FavouriteCategorySetEntity = db.withTransaction {
		val sets = getSets(space)
		validateSetTitle(title, sets)
		val now = System.currentTimeMillis()
		val entity = FavouriteCategorySetEntity(
			syncKey = java.util.UUID.randomUUID().toString(), title = title.trim(),
			sortKey = (sets.maxOfOrNull { it.sortKey } ?: -1) + 1,
			createdAt = now, updatedAt = now, space = space.dbValue,
		)
		entity.copy(setId = db.getFavouriteCategorySetsDao().insert(entity))
	}

	suspend fun renameSet(id: Long, title: String, space: FavouriteSpace) = db.withTransaction {
		val entity = requireSet(id, space)
		validateSetTitle(title, getSets(space).filterNot { it.setId == id })
		db.getFavouriteCategorySetsDao().upsert(entity.copy(title = title.trim(), updatedAt = nextTimestamp(entity.updatedAt)))
	}

	suspend fun reorderSets(ids: List<Long>, space: FavouriteSpace) = db.withTransaction {
		val sets = getSets(space)
		require(ids.size == sets.size && ids.toSet() == sets.mapTo(HashSet()) { it.setId })
		for ((index, id) in ids.withIndex()) {
			val entity = sets.first { it.setId == id }
			if (entity.sortKey != index) db.getFavouriteCategorySetsDao().upsert(
				entity.copy(sortKey = index, updatedAt = nextTimestamp(entity.updatedAt)),
			)
		}
	}

	/** Moving is an assignment update; category rows and membership rows are never recreated. */
	suspend fun moveCategoriesToSet(ids: Collection<Long>, targetId: Long, space: FavouriteSpace, sourceSetId: Long? = null) = db.withTransaction {
		requireSet(targetId, space)
		val dao = db.getFavouriteCategorySetsDao()
		val targetCategories = dao.getCategories(targetId, space.dbValue)
		var position = (targetCategories.maxOfOrNull { it.sortKey } ?: -1) + 1
		for (id in ids.distinct()) {
			val category = db.getFavouriteCategoriesDao().find(id.toInt())
			require(category.space == space.dbValue)
			val assignment = checkNotNull(dao.getAssignment(id))
			if (assignment.setId == targetId || (sourceSetId != null && assignment.setId != sourceSetId)) continue
			// Legacy duplicate rows are retained. Moving must not merge or delete them either.
			db.getFavouriteCategoriesDao().updateSortKey(id, position++)
			dao.assign(assignment.copy(setId = targetId, updatedAt = nextTimestamp(checkNotNull(dao.getAssignment(id)).updatedAt)))
		}
	}

	/** The caller must explicitly choose destructive=true after the second confirmation. */
	suspend fun deleteSet(id: Long, space: FavouriteSpace, moveTo: Long? = null, destructive: Boolean = false) = db.withTransaction {
		val entity = requireSet(id, space)
		require(getSets(space).size > 1) { "Cannot delete the last Set" }
		val categories = db.getFavouriteCategorySetsDao().getCategories(id, space.dbValue)
		if (destructive) {
			removeCategories(categories.map { it.categoryId.toLong() })
		} else {
			require(moveTo != null && moveTo != id)
			moveCategoriesToSet(categories.map { it.categoryId.toLong() }, moveTo, space)
		}
		db.getFavouriteCategorySetsDao().upsert(entity.copy(deletedAt = nextTimestamp(entity.updatedAt), updatedAt = nextTimestamp(entity.updatedAt)))
	}

	suspend fun reorderCategories(ids: List<Long>, setId: Long, space: FavouriteSpace) = db.withTransaction {
		requireSet(setId, space)
		val visible = db.getFavouriteCategorySetsDao().getCategories(setId, space.dbValue)
		require(ids.distinct().size == ids.size && ids.all { id -> visible.any { it.categoryId.toLong() == id } })
		// Replace only the selected type's positions; hidden Manga/Novel rows keep their slots.
		val positions = visible.filter { it.categoryId.toLong() in ids }.map { it.sortKey }.sorted()
		for ((index, id) in ids.withIndex()) db.getFavouriteCategoriesDao().updateSortKey(id, positions[index])
	}

	private suspend fun requireSet(id: Long, space: FavouriteSpace): FavouriteCategorySetEntity =
		checkNotNull(db.getFavouriteCategorySetsDao().findSet(id)).also {
			require(it.space == space.dbValue && it.deletedAt == 0L) { "Invalid Favourite Set" }
		}

	private fun validateSetTitle(title: String, sets: List<FavouriteCategorySetEntity>) {
		require(title.isNotBlank())
		if (sets.any { CategorySetIdentity.normalizeTitle(it.title) == CategorySetIdentity.normalizeTitle(title) }) {
			throw DuplicateCategoryTitleException()
		}
	}

	private suspend fun validateCategoryTitle(title: String, setId: Long, space: FavouriteSpace, exceptId: Long = 0) {
		require(title.isNotBlank())
		if (db.getFavouriteCategorySetsDao().getCategories(setId, space.dbValue).any {
			it.categoryId.toLong() != exceptId && CategorySetIdentity.normalizeTitle(it.title) == CategorySetIdentity.normalizeTitle(title)
		}) throw DuplicateCategoryTitleException()
	}

	private fun nextTimestamp(previous: Long): Long = maxOf(previous + 1, System.currentTimeMillis())

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
		setId: Long? = null,
	): Flow<Map<FavouriteCategory, List<Cover>>> {
		return db.invalidationTracker.createFlow(
			if (space == FavouriteSpace.PRIVATE) TABLE_PRIVATE_FAVOURITES else TABLE_FAVOURITES,
			TABLE_FAVOURITE_CATEGORIES,
			"favourite_category_set_assignments",
			"favourite_category_sets",
			emitInitialState = true,
		).mapLatest {
			db.withTransaction {
				val entities = if (setId != null) {
					db.getFavouriteCategorySetsDao().getCategories(setId, space.dbValue)
				} else if (space == FavouriteSpace.PRIVATE) {
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
		emitAll(db.getFavouriteCategoriesDao().observe(id).map { it?.takeIf { entity -> entity.space == space.dbValue }?.toFavouriteCategory() })
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

	/** Category membership counts for a selection, used by the multi-select category dialog. */
	suspend fun getCategoryCountsForMangaIds(
		mangaIds: Collection<Long>,
		space: FavouriteSpace = FavouriteSpace.NORMAL,
	): Map<Long, Int> {
		if (mangaIds.isEmpty()) return emptyMap()
		val rows = if (space == FavouriteSpace.PRIVATE) {
			db.getPrivateFavouritesDao().findCategoryCountsForMangaIds(mangaIds)
		} else {
			db.getFavouritesDao().findCategoryCountsForMangaIds(mangaIds)
		}
		return rows.associate { it.categoryId to it.itemCount }
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
		setId: Long? = null,
	): FavouriteCategory = db.withTransaction {
		val target = resolveSet(setId, space)
		validateCategoryTitle(title, target.setId, space)
		val globalFeaturesAllowed = space != FavouriteSpace.PRIVATE || isPrivateIsolationDisabled()
		val entity = FavouriteCategoryEntity(
			title = title.trim(),
			createdAt = System.currentTimeMillis(),
			sortKey = (db.getFavouriteCategorySetsDao().getCategories(target.setId, space.dbValue).maxOfOrNull { it.sortKey } ?: -1) + 1,
			categoryId = 0,
			order = sortOrder.name,
			track = globalFeaturesAllowed && isTrackerEnabled,
			downloadNewChapters = globalFeaturesAllowed && isNewChaptersDownloadEnabled,
			deletedAt = 0L,
			isVisibleInLibrary = isVisibleOnShelf,
			space = space.dbValue,
		)
		val id = db.getFavouriteCategoriesDao().insert(entity)
		db.getFavouriteCategorySetsDao().assign(FavouriteCategorySetAssignmentEntity(id, target.setId, java.util.UUID.randomUUID().toString(), entity.createdAt))
		entity.toFavouriteCategory(id)
	}

	suspend fun updateCategory(
		id: Long,
		title: String,
		sortOrder: ListSortOrder,
		isTrackerEnabled: Boolean,
		isNewChaptersDownloadEnabled: Boolean,
		isVisibleOnShelf: Boolean,
	) = db.withTransaction {
		val entity = db.getFavouriteCategoriesDao().find(id.toInt())
		val space = FavouriteSpace.fromDb(entity.space)
		val set = getCategorySet(id, space)
		if (title != entity.title) validateCategoryTitle(title, set.setId, space, id)
		val globalFeaturesAllowed = entity.space != FavouriteSpace.PRIVATE.dbValue || isPrivateIsolationDisabled()
		db.getFavouriteCategoriesDao().update(
			id = id,
			title = title,
			order = sortOrder.name,
			tracker = globalFeaturesAllowed && isTrackerEnabled,
			downloadNewChapters = globalFeaturesAllowed && isNewChaptersDownloadEnabled,
			onShelf = isVisibleOnShelf,
		)
	}

	suspend fun updateCategory(id: Long, isVisibleInLibrary: Boolean) {
		db.getFavouriteCategoriesDao().updateVisibility(id, isVisibleInLibrary)
	}

	suspend fun updateCategoryTracking(id: Long, isTrackingEnabled: Boolean) {
		val entity = db.getFavouriteCategoriesDao().find(id.toInt())
		if (entity.space == FavouriteSpace.PRIVATE.dbValue && !isPrivateIsolationDisabled()) return
		db.getFavouriteCategoriesDao().updateTracking(id, isTrackingEnabled)
	}

	suspend fun setNewChaptersDownloadCategories(ids: Set<Long>) {
		db.withTransaction {
			val dao = db.getFavouriteCategoriesDao()
			val privateFeaturesAllowed = db.getPrivateFavouritesDao().isIsolationDisabled()
			dao.clearNewChaptersDownload()
			for (id in ids) {
				val entity = dao.find(id.toInt())
				if (
					entity.space == FavouriteSpace.NORMAL.dbValue ||
					(privateFeaturesAllowed && entity.space == FavouriteSpace.PRIVATE.dbValue)
				) {
					dao.updateNewChaptersDownload(id, true)
				}
			}
		}
	}

	suspend fun enableNewChaptersDownloadForTrackedCategories() {
		db.getFavouriteCategoriesDao().enableNewChaptersDownloadForTracked()
	}

	suspend fun isNewChaptersDownloadEnabled(mangaId: Long): Boolean =
		db.getFavouritesDao().isNewChaptersDownloadEnabled(mangaId) ||
			db.getPrivateFavouritesDao().isNewChaptersDownloadEnabled(mangaId)

	/** Recheck the database scope atomically; a stale selection must never act on a hidden Set. */
	suspend fun removeCategoriesInSet(ids: Collection<Long>, setId: Long, space: FavouriteSpace): List<Long> = db.withTransaction {
		val scoped = db.getFavouriteCategorySetsDao().getCategories(setId, space.dbValue).map { it.categoryId.toLong() }.filter { it in ids }
		removeCategories(scoped)
		scoped
	}

	suspend fun setCategoriesVisibleInSet(ids: Collection<Long>, visible: Boolean, setId: Long, space: FavouriteSpace) = db.withTransaction {
		for (category in db.getFavouriteCategorySetsDao().getCategories(setId, space.dbValue)) {
			if (category.categoryId.toLong() in ids) db.getFavouriteCategoriesDao().updateVisibility(category.categoryId.toLong(), visible)
		}
	}

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

	/**
	 * Apply all category checkbox changes for one or many manga as one atomic database operation.
	 *
	 * The category picker previously persisted each category change separately.
	 * Every add then re-upserted the same manga/tags and every remove opened its own transaction +
	 * chapter GC. Selecting several categories therefore multiplied writes, invalidations and list
	 * refreshes. Keep the same membership semantics, but persist manga metadata once, batch the
	 * membership rows, soft-delete all removed memberships with one query and run chapter GC once.
	 */
	suspend fun updateCategoryMemberships(
		mangas: Collection<Manga>,
		changes: Map<Long, Boolean>,
		space: FavouriteSpace = FavouriteSpace.NORMAL,
	) {
		if (mangas.isEmpty() || changes.isEmpty()) return

		val mangaById = LinkedHashMap<Long, Manga>(mangas.size)
		for (manga in mangas) mangaById[manga.id] = manga
		val mangaIds = mangaById.keys.toList()
		db.withTransaction {
			// Fail closed if a caller accidentally mixes Normal/Private category ids.
			val validChanges = LinkedHashMap<Long, Boolean>()
			for ((categoryId, checked) in changes) {
				require(categoryId.toInt().toLong() == categoryId)
				val category = db.getFavouriteCategoriesDao().findOrNull(categoryId.toInt()) ?: continue
				check(category.space == space.dbValue) {
					"Category $categoryId belongs to space ${category.space}, expected ${space.dbValue}"
				}
				validChanges[categoryId] = checked
			}
			val addedCategoryIds = validChanges.filterValues { it }.keys.toList()
			val removedCategoryIds = validChanges.filterValues { !it }.keys.toList()

			if (addedCategoryIds.isNotEmpty()) {
				// Metadata is category-independent. Persist it once per manga rather than once for every
				// checked category.
				for (manga in mangaById.values) {
					val tags = manga.tags.toEntities()
					db.getTagsDao().upsert(tags)
					db.getMangaDao().upsert(manga.toEntity(), tags)
				}
				val now = System.currentTimeMillis()
				if (space == FavouriteSpace.PRIVATE) {
					val rows = ArrayList<PrivateFavouriteEntity>(mangaIds.size * addedCategoryIds.size)
					for (categoryId in addedCategoryIds) {
						for (mangaId in mangaIds) {
							rows += PrivateFavouriteEntity(mangaId, categoryId, 0, false, now, 0L)
						}
					}
					db.getPrivateFavouritesDao().insert(rows)
				} else {
					val rows = ArrayList<FavouriteEntity>(mangaIds.size * addedCategoryIds.size)
					for (categoryId in addedCategoryIds) {
						for (mangaId in mangaIds) {
							rows += FavouriteEntity(mangaId, categoryId, 0, false, now, 0L)
						}
					}
					db.getFavouritesDao().insert(rows)
				}
			}

			if (removedCategoryIds.isNotEmpty()) {
				if (space == FavouriteSpace.PRIVATE) {
					db.getPrivateFavouritesDao().delete(mangaIds, removedCategoryIds)
				} else {
					db.getFavouritesDao().delete(mangaIds, removedCategoryIds)
				}
				// The old per-category path ran this once for every unchecked category.
				db.getChaptersDao().gc(mangaIds)
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
			// Only the changed titles can have become unreferenced. Avoid a full chapters-table GC on
			// every single/batch favourites action, especially on large Private libraries.
			db.getChaptersDao().gc(ids)
		}
		return ReversibleHandle { recoverToFavourites(ids, space) }
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

	/** Actual Private membership is retained even when app-wide isolation is disabled. */
	private fun observePrivateMembershipIds(): Flow<Set<Long>> =
		db.invalidationTracker.createFlow(TABLE_PRIVATE_FAVOURITES, emitInitialState = true)
			.mapLatest { db.getPrivateFavouritesDao().findAllActiveMangaIds().toHashSet() }
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
