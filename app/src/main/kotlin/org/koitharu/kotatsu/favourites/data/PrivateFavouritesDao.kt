package org.koitharu.kotatsu.favourites.data

import android.database.DatabaseUtils.sqlEscapeString
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import org.intellij.lang.annotations.Language
import org.koitharu.kotatsu.core.db.MangaQueryBuilder
import org.koitharu.kotatsu.core.db.TABLE_PRIVATE_FAVOURITES
import org.koitharu.kotatsu.core.db.entity.MangaEntity
import org.koitharu.kotatsu.favourites.domain.model.Cover
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.list.domain.ListSortOrder
import org.koitharu.kotatsu.list.domain.ReadingProgress.Companion.PROGRESS_COMPLETED
import org.koitharu.kotatsu.list.domain.toOrderBy

/** Queries only private-space membership. It deliberately never participates in normal sync APIs. */
@Dao
abstract class PrivateFavouritesDao : MangaQueryBuilder.ConditionCallback {

	@Query(
		"SELECT private_favourites.manga_id AS manga_id, private_favourites.category_id AS category_id, manga.source AS source " +
			"FROM private_favourites INNER JOIN manga ON manga.manga_id = private_favourites.manga_id " +
			"WHERE private_favourites.deleted_at = 0",
	)
	abstract suspend fun findMemberships(): List<FavouriteMembership>

	@Query(
		"SELECT category_id, COUNT(DISTINCT manga_id) AS item_count FROM private_favourites " +
			"WHERE deleted_at = 0 AND category_id IN (:categoryIds) GROUP BY category_id",
	)
	abstract suspend fun findCategoryCounts(categoryIds: Collection<Long>): List<FavouriteCategoryCount>

	@Query(
		"SELECT COUNT(DISTINCT manga_id) FROM private_favourites " +
			"WHERE deleted_at = 0 AND category_id IN (:categoryIds)",
	)
	abstract suspend fun findDistinctMangaCount(categoryIds: Collection<Long>): Int

	@Query(
		"SELECT DISTINCT manga.manga_id AS manga_id, manga.title AS title, manga.author AS author, manga.source AS source " +
			"FROM private_favourites INNER JOIN manga ON manga.manga_id = private_favourites.manga_id " +
			"WHERE private_favourites.deleted_at = 0",
	)
	abstract suspend fun findSearchEntries(): List<FavouriteSearchEntry>

	@Query("SELECT DISTINCT manga_id FROM private_favourites WHERE deleted_at = 0")
	abstract suspend fun findActiveMangaIds(): List<Long>

	@Transaction
	@Query(
		"SELECT * FROM private_favourites WHERE category_id = :categoryId AND deleted_at = 0 " +
			"GROUP BY manga_id ORDER BY created_at DESC",
	)
	abstract suspend fun findAll(categoryId: Long): List<PrivateFavouriteManga>

	fun observeAll(
		categoryId: Long,
		order: ListSortOrder,
		filterOptions: Set<ListFilterOption>,
		limit: Int,
		pinned: List<Long> = emptyList(),
	): Flow<List<PrivateFavouriteManga>> = observeAllImpl(
		MangaQueryBuilder(TABLE_PRIVATE_FAVOURITES, this)
			.join("LEFT JOIN manga ON private_favourites.manga_id = manga.manga_id")
			.where("private_favourites.deleted_at = 0")
			.where(
				if (categoryId != 0L) {
					"private_favourites.category_id = $categoryId AND " +
						"EXISTS(SELECT 1 FROM favourite_categories c WHERE c.category_id = private_favourites.category_id AND c.space = 1 AND c.deleted_at = 0)"
				} else {
					"EXISTS(SELECT 1 FROM favourite_categories c WHERE c.category_id = private_favourites.category_id AND c.space = 1 AND c.show_in_lib = 1 AND c.deleted_at = 0)"
				},
			)
			.filters(filterOptions)
			.apply { if (categoryId == 0L) groupBy("private_favourites.manga_id") }
			.orderBy(getOrderBy(order, pinned))
			.limit(limit)
			.build(),
	)

	fun observeAll(
		order: ListSortOrder,
		filterOptions: Set<ListFilterOption>,
		limit: Int,
		pinned: List<Long> = emptyList(),
	): Flow<List<PrivateFavouriteManga>> = observeAll(0L, order, filterOptions, limit, pinned)

	suspend fun findCovers(categoryId: Long, order: ListSortOrder): List<Cover> {
		@Language("RoomSql")
		val query = SimpleSQLiteQuery(
			"SELECT manga.cover_url AS url, manga.source AS source FROM private_favourites " +
				"LEFT JOIN manga ON private_favourites.manga_id = manga.manga_id " +
				"WHERE private_favourites.category_id = ? AND private_favourites.deleted_at = 0 " +
				"ORDER BY ${getOrderBy(order)}",
			arrayOf<Any>(categoryId),
		)
		return findCoversImpl(query)
	}

	suspend fun findCovers(order: ListSortOrder, limit: Int): List<Cover> {
		@Language("RoomSql")
		val query = SimpleSQLiteQuery(
			"SELECT manga.cover_url AS url, manga.source AS source FROM private_favourites " +
				"LEFT JOIN manga ON private_favourites.manga_id = manga.manga_id " +
				"WHERE private_favourites.deleted_at = 0 AND " +
				"EXISTS(SELECT 1 FROM favourite_categories c WHERE c.category_id = private_favourites.category_id AND c.space = 1 AND c.show_in_lib = 1 AND c.deleted_at = 0) " +
				"GROUP BY manga.manga_id ORDER BY ${getOrderBy(order)} LIMIT ?",
			arrayOf<Any>(limit),
		)
		return findCoversImpl(query)
	}

	@Query("SELECT COUNT(DISTINCT manga_id) FROM private_favourites WHERE deleted_at = 0")
	abstract fun observeMangaCount(): Flow<Int>

	@Query(
		"SELECT favourite_categories.* FROM private_favourites " +
			"LEFT JOIN favourite_categories ON favourite_categories.category_id = private_favourites.category_id " +
			"WHERE private_favourites.manga_id = :mangaId AND private_favourites.deleted_at = 0 " +
			"AND favourite_categories.deleted_at = 0 AND favourite_categories.space = 1",
	)
	abstract fun observeCategories(mangaId: Long): Flow<List<FavouriteCategoryEntity>>

	@Query("SELECT DISTINCT category_id FROM private_favourites WHERE manga_id = :mangaId AND deleted_at = 0 ORDER BY created_at ASC")
	abstract suspend fun findCategoriesIds(mangaId: Long): List<Long>

	@Query("SELECT COUNT(category_id) FROM private_favourites WHERE manga_id = :mangaId AND deleted_at = 0")
	abstract suspend fun findCategoriesCount(mangaId: Long): Int

	@Query(
		"SELECT manga.source AS count FROM private_favourites LEFT JOIN manga ON manga.manga_id = private_favourites.manga_id " +
			"WHERE private_favourites.deleted_at = 0 AND " +
			"EXISTS(SELECT 1 FROM favourite_categories c WHERE c.category_id = private_favourites.category_id AND c.space = 1 AND c.show_in_lib = 1 AND c.deleted_at = 0) " +
			"GROUP BY manga.source ORDER BY COUNT(manga.source) DESC LIMIT :limit",
	)
	abstract suspend fun findPopularSources(limit: Int): List<String>

	@Query(
		"SELECT manga.source AS count FROM private_favourites LEFT JOIN manga ON manga.manga_id = private_favourites.manga_id " +
			"WHERE private_favourites.category_id = :categoryId AND private_favourites.deleted_at = 0 " +
			"GROUP BY manga.source ORDER BY COUNT(manga.source) DESC LIMIT :limit",
	)
	abstract suspend fun findPopularSources(categoryId: Long, limit: Int): List<String>

	@Transaction
	@Query("SELECT * FROM private_favourites WHERE deleted_at = 0 ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
	abstract suspend fun findAllRaw(offset: Int, limit: Int): List<PrivateFavouriteManga>

	fun dump(): Flow<PrivateFavouriteManga> = flow {
		val window = 10
		var offset = 0
		while (currentCoroutineContext().isActive) {
			val list = findAllRaw(offset, window)
			if (list.isEmpty()) break
			offset += window
			list.forEach { emit(it) }
		}
	}

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	abstract suspend fun insert(favourite: PrivateFavouriteEntity)

	@Upsert
	abstract suspend fun upsert(entity: PrivateFavouriteEntity)

	suspend fun delete(mangaId: Long) = setDeletedAt(mangaId, System.currentTimeMillis())
	suspend fun delete(mangaId: Long, categoryId: Long) = setDeletedAt(categoryId, mangaId, System.currentTimeMillis())
	suspend fun deleteAll(categoryId: Long) = setDeletedAtAll(categoryId, System.currentTimeMillis())
	suspend fun recover(mangaId: Long) = setDeletedAt(mangaId, 0L)
	suspend fun recover(categoryId: Long, mangaId: Long) = setDeletedAt(categoryId, mangaId, 0L)

	@Query("DELETE FROM private_favourites WHERE deleted_at != 0 AND deleted_at < :maxDeletionTime")
	abstract suspend fun gc(maxDeletionTime: Long)

	@Transaction
	@RawQuery(observedEntities = [PrivateFavouriteEntity::class])
	protected abstract fun observeAllImpl(query: SupportSQLiteQuery): Flow<List<PrivateFavouriteManga>>

	@RawQuery
	protected abstract suspend fun findCoversImpl(query: SupportSQLiteQuery): List<Cover>

	@Query("UPDATE private_favourites SET deleted_at = :deletedAt WHERE manga_id = :mangaId")
	protected abstract suspend fun setDeletedAt(mangaId: Long, deletedAt: Long)

	@Query("UPDATE private_favourites SET deleted_at = :deletedAt WHERE manga_id = :mangaId AND category_id = :categoryId")
	protected abstract suspend fun setDeletedAt(categoryId: Long, mangaId: Long, deletedAt: Long)

	@Query("UPDATE private_favourites SET deleted_at = :deletedAt WHERE category_id = :categoryId AND deleted_at = 0")
	protected abstract suspend fun setDeletedAtAll(categoryId: Long, deletedAt: Long)

	private fun getOrderBy(sortOrder: ListSortOrder, pinned: List<Long>): String {
		val orderBy = getOrderBy(sortOrder)
		if (pinned.isEmpty()) return orderBy
		val case = buildString {
			append("CASE private_favourites.manga_id")
			pinned.forEachIndexed { index, id -> append(" WHEN $id THEN $index") }
			append(" ELSE ${pinned.size} END")
		}
		return "$case, $orderBy"
	}

	private fun getOrderBy(sortOrder: ListSortOrder) = sortOrder.toOrderBy(
		dateAdded = "private_favourites.created_at",
		lastRead = "IFNULL((SELECT updated_at FROM history WHERE history.manga_id = manga.manga_id), 0)",
		progress = "IFNULL((SELECT percent FROM history WHERE history.manga_id = manga.manga_id), 0)",
	)

	override fun getCondition(option: ListFilterOption): String? = when (option) {
		ListFilterOption.Macro.COMPLETED -> "EXISTS(SELECT * FROM history WHERE history.manga_id = private_favourites.manga_id AND history.percent >= $PROGRESS_COMPLETED)"
		ListFilterOption.Macro.NEW_CHAPTERS -> "(SELECT chapters_new FROM tracks WHERE tracks.manga_id = private_favourites.manga_id) > 0"
		ListFilterOption.Macro.NSFW -> "manga.nsfw = 1"
		is ListFilterOption.ReadingProgress -> getReadingProgressCondition(option, "private_favourites.manga_id")
		is ListFilterOption.Tag -> "EXISTS(SELECT * FROM manga_tags WHERE private_favourites.manga_id = manga_tags.manga_id AND tag_id = ${option.tagId})"
		ListFilterOption.Downloaded -> "EXISTS(SELECT * FROM local_index WHERE local_index.manga_id = private_favourites.manga_id)"
		is ListFilterOption.Source -> "manga.source = ${sqlEscapeString(option.mangaSource.name)}"
		is ListFilterOption.State -> option.state?.let { "manga.state = ${sqlEscapeString(it.name)}" }
		else -> null
	}

	private fun getReadingProgressCondition(option: ListFilterOption.ReadingProgress, mangaId: String): String = when (option) {
		ListFilterOption.ReadingProgress.UNREAD ->
			"NOT EXISTS(SELECT 1 FROM history WHERE history.manga_id = $mangaId AND history.percent > 0)"
		ListFilterOption.ReadingProgress.IN_PROGRESS ->
			"EXISTS(SELECT 1 FROM history WHERE history.manga_id = $mangaId AND history.percent > 0 AND history.percent < $PROGRESS_COMPLETED)"
		ListFilterOption.ReadingProgress.COMPLETED ->
			"EXISTS(SELECT 1 FROM history WHERE history.manga_id = $mangaId AND history.percent >= $PROGRESS_COMPLETED)"
	}
}
