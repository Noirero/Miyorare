package org.koitharu.kotatsu.favourites.domain

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.favourites.data.FavouriteCategoryEntity
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.groups.domain.LibraryGroupsRepository
import java.util.Locale
import javax.inject.Inject

/**
 * Copies Private favourites into the Normal library without copying manga metadata or downloaded files.
 * The destination is always verified before Private rows can be removed, so a move cannot lose the
 * source membership when the copy is incomplete.
 */
class TransferFavouritesToNormalUseCase @Inject constructor(
	private val db: MangaDatabase,
	private val contentTypeStore: FavouriteContentTypeStore,
	private val libraryGroupsRepository: LibraryGroupsRepository,
) {

	suspend fun getNormalCategories(): List<NormalTransferCategory> = withContext(Dispatchers.IO) {
		db.getFavouriteCategoriesDao()
			.findAll()
			.map { NormalTransferCategory(it.categoryId.toLong(), it.title) }
	}

	suspend fun transfer(
		ids: Set<Long>,
		destination: NormalTransferDestination,
		onProgress: (PrivateTransferProgress) -> Unit = {},
	): PrivateTransferResult = withContext(Dispatchers.IO) {
		if (ids.isEmpty()) return@withContext PrivateTransferResult.EMPTY

		val snapshot = collectPrivateSnapshot(ids)
		if (snapshot.mangaIds.isEmpty()) {
			return@withContext PrivateTransferResult(
				requestedCount = ids.size,
				sourceCount = 0,
				verifiedCount = 0,
				failedCount = 0,
				createdCategoryCount = 0,
			)
		}

		val createdCategories = when (destination) {
			NormalTransferDestination.PreserveCategories -> {
				val mapping = ensureNormalCategoryMapping(snapshot.categoryIds)
				copyPreservingCategories(snapshot.mangaIds, mapping, onProgress)
				mapping.createdCount
			}

			is NormalTransferDestination.NormalCategories -> {
				val targetIds = validateNormalCategories(destination.categoryIds)
				copyToNormalCategories(snapshot.mangaIds, targetIds, onProgress)
				0
			}
		}

		val verified = findNormalIds(snapshot.mangaIds)
		PrivateTransferResult(
			requestedCount = ids.size,
			sourceCount = snapshot.mangaIds.size,
			verifiedCount = verified.size,
			failedCount = snapshot.mangaIds.size - verified.size,
			createdCategoryCount = createdCategories,
		)
	}

	/** Remove the titles from Private only after the caller has verified a complete Normal copy. */
	suspend fun removeFromPrivate(ids: Set<Long>) = withContext(Dispatchers.IO) {
		if (ids.isEmpty()) return@withContext
		val timestamp = System.currentTimeMillis()
		for (chunk in ids.chunked(SQL_CHUNK_SIZE)) {
			db.withTransaction {
				val placeholders = placeholders(chunk.size)
				val args = ArrayList<Any?>(chunk.size + 1).apply {
					add(timestamp)
					addAll(chunk)
				}.toTypedArray()
				db.openHelper.writableDatabase.execSQL(
					"UPDATE private_favourites SET deleted_at = ? WHERE deleted_at = 0 AND manga_id IN ($placeholders)",
					args,
				)
			}
		}
		db.getChaptersDao().gc()
		libraryGroupsRepository.repairInvalidGroups(FavouriteSpace.PRIVATE)
	}

	private suspend fun collectPrivateSnapshot(ids: Set<Long>): PrivateSnapshot {
		val mangaIds = LinkedHashSet<Long>(ids.size)
		val categoryIds = LinkedHashSet<Long>()
		for (chunk in ids.chunked(SQL_CHUNK_SIZE)) {
			val query = SimpleSQLiteQuery(
				"SELECT manga_id, category_id FROM private_favourites WHERE deleted_at = 0 AND manga_id IN (${placeholders(chunk.size)})",
				chunk.map { it as Any }.toTypedArray(),
			)
			db.openHelper.readableDatabase.query(query).use { cursor ->
				while (cursor.moveToNext()) {
					mangaIds += cursor.getLong(0)
					categoryIds += cursor.getLong(1)
				}
			}
		}
		return PrivateSnapshot(mangaIds, categoryIds)
	}

	private suspend fun ensureNormalCategoryMapping(sourceCategoryIds: Set<Long>): CategoryMapping {
		val dao = db.getFavouriteCategoriesDao()
		val privateCategories = dao.findAllInSpace(FavouriteSpace.PRIVATE.dbValue)
			.filter { it.categoryId.toLong() in sourceCategoryIds }
			.sortedBy { it.sortKey }
		val normalCategories = dao.findAll().toMutableList()
		val mapping = LinkedHashMap<Long, Long>(privateCategories.size)
		val targetTypes = LinkedHashMap<Long, FavouriteContentType>()
		var createdCount = 0
		var nextSortKey = dao.getNextSortKey(FavouriteSpace.NORMAL)

		db.withTransaction {
			for (source in privateCategories) {
				val type = categoryType(source.categoryId.toLong())
				val key = categoryKey(source.title, type)
				val existing = normalCategories.firstOrNull {
					categoryKey(it.title, categoryType(it.categoryId.toLong())) == key
				}
				val targetId = if (existing != null) {
					existing.categoryId.toLong()
				} else {
					val clone = FavouriteCategoryEntity(
						categoryId = 0,
						createdAt = source.createdAt,
						sortKey = nextSortKey++,
						title = source.title,
						order = source.order,
						track = false,
						downloadNewChapters = false,
						isVisibleInLibrary = source.isVisibleInLibrary,
						deletedAt = 0L,
						space = FavouriteSpace.NORMAL.dbValue,
					)
					val id = dao.insert(clone)
					normalCategories += clone.copy(categoryId = id.toInt())
					createdCount++
					id
				}
				mapping[source.categoryId.toLong()] = targetId
				targetTypes[targetId] = type
			}
		}

		for ((targetId, type) in targetTypes) contentTypeStore.setCategoryType(targetId, type)
		return CategoryMapping(mapping, createdCount)
	}

	private suspend fun validateNormalCategories(ids: Set<Long>): Set<Long> {
		require(ids.isNotEmpty()) { "At least one Normal category is required" }
		val dao = db.getFavouriteCategoriesDao()
		return ids.mapTo(LinkedHashSet(ids.size)) { id ->
			val category = dao.find(id.toInt())
			require(category.space == FavouriteSpace.NORMAL.dbValue) { "Category $id is not Normal" }
			id
		}
	}

	private suspend fun copyPreservingCategories(
		mangaIds: Set<Long>,
		mapping: CategoryMapping,
		onProgress: (PrivateTransferProgress) -> Unit,
	) {
		require(mapping.ids.isNotEmpty()) { "No active Private categories were found for the selected manga" }
		var processed = 0
		for (chunk in mangaIds.chunked(SQL_CHUNK_SIZE)) {
			db.withTransaction {
				val writable = db.openHelper.writableDatabase
				val placeholders = placeholders(chunk.size)
				for ((sourceCategoryId, targetCategoryId) in mapping.ids) {
					val updateArgs = ArrayList<Any?>(chunk.size + 1).apply {
						add(targetCategoryId)
						addAll(chunk)
					}.toTypedArray()
					writable.execSQL(
						"UPDATE favourites SET deleted_at = 0 WHERE category_id = ? AND manga_id IN ($placeholders)",
						updateArgs,
					)

					val insertArgs = ArrayList<Any?>(chunk.size + 2).apply {
						add(targetCategoryId)
						add(sourceCategoryId)
						addAll(chunk)
					}.toTypedArray()
					writable.execSQL(
						"""
						INSERT OR IGNORE INTO favourites
							(manga_id, category_id, sort_key, pinned, created_at, deleted_at)
						SELECT manga_id, ?, sort_key, pinned, created_at, 0
						FROM private_favourites
						WHERE deleted_at = 0 AND category_id = ? AND manga_id IN ($placeholders)
						""".trimIndent(),
						insertArgs,
					)
				}
			}
			processed += chunk.size
			onProgress(PrivateTransferProgress(processed, mangaIds.size))
		}
	}

	private suspend fun copyToNormalCategories(
		mangaIds: Set<Long>,
		targetCategoryIds: Set<Long>,
		onProgress: (PrivateTransferProgress) -> Unit,
	) {
		var processed = 0
		for (chunk in mangaIds.chunked(SQL_CHUNK_SIZE)) {
			db.withTransaction {
				val writable = db.openHelper.writableDatabase
				val placeholders = placeholders(chunk.size)
				for (targetCategoryId in targetCategoryIds) {
					val updateArgs = ArrayList<Any?>(chunk.size + 1).apply {
						add(targetCategoryId)
						addAll(chunk)
					}.toTypedArray()
					writable.execSQL(
						"UPDATE favourites SET deleted_at = 0 WHERE category_id = ? AND manga_id IN ($placeholders)",
						updateArgs,
					)

					val insertArgs = ArrayList<Any?>(chunk.size + 1).apply {
						add(targetCategoryId)
						addAll(chunk)
					}.toTypedArray()
					writable.execSQL(
						"""
						INSERT OR IGNORE INTO favourites
							(manga_id, category_id, sort_key, pinned, created_at, deleted_at)
						SELECT manga_id, ?, 0, 0, MIN(created_at), 0
						FROM private_favourites
						WHERE deleted_at = 0 AND manga_id IN ($placeholders)
						GROUP BY manga_id
						""".trimIndent(),
						insertArgs,
					)
				}
			}
			processed += chunk.size
			onProgress(PrivateTransferProgress(processed, mangaIds.size))
		}
	}

	private fun findNormalIds(ids: Set<Long>): Set<Long> {
		val result = LinkedHashSet<Long>(ids.size)
		for (chunk in ids.chunked(SQL_CHUNK_SIZE)) {
			val query = SimpleSQLiteQuery(
				"SELECT DISTINCT manga_id FROM favourites WHERE deleted_at = 0 AND manga_id IN (${placeholders(chunk.size)})",
				chunk.map { it as Any }.toTypedArray(),
			)
			db.openHelper.readableDatabase.query(query).use { cursor ->
				while (cursor.moveToNext()) result += cursor.getLong(0)
			}
		}
		return result
	}

	private fun categoryType(categoryId: Long): FavouriteContentType =
		if (contentTypeStore.isCategoryForType(categoryId, FavouriteContentType.NOVEL)) {
			FavouriteContentType.NOVEL
		} else {
			FavouriteContentType.MANGA
		}

	private fun categoryKey(title: String, type: FavouriteContentType): String =
		"${title.trim().lowercase(Locale.ROOT)}:${type.name}"

	private fun placeholders(size: Int): String = List(size) { "?" }.joinToString(",")

	private data class PrivateSnapshot(
		val mangaIds: LinkedHashSet<Long>,
		val categoryIds: LinkedHashSet<Long>,
	)

	private data class CategoryMapping(
		val ids: LinkedHashMap<Long, Long>,
		val createdCount: Int,
	)

	private companion object {
		const val SQL_CHUNK_SIZE = 300
	}
}

sealed interface NormalTransferDestination {
	data object PreserveCategories : NormalTransferDestination
	data class NormalCategories(val categoryIds: Set<Long>) : NormalTransferDestination
}

data class NormalTransferCategory(
	val id: Long,
	val title: String,
)
