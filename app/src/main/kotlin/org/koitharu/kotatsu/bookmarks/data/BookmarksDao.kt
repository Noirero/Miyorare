package org.koitharu.kotatsu.bookmarks.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import org.koitharu.kotatsu.core.db.entity.MangaWithTags

@Dao
abstract class BookmarksDao {

	@Query("SELECT * FROM bookmarks WHERE page_id = :pageId")
	abstract suspend fun find(pageId: Long): BookmarkEntity?

	@Transaction
	@Query(
		"""
		SELECT * FROM manga JOIN bookmarks ON bookmarks.manga_id = manga.manga_id
		WHERE EXISTS(SELECT 1 FROM favourite_categories private_isolation_mode WHERE private_isolation_mode.category_id = -2147483000 AND private_isolation_mode.space = -1 AND private_isolation_mode.deleted_at = 0)
			OR NOT EXISTS(SELECT 1 FROM private_favourites pf WHERE pf.manga_id = manga.manga_id AND pf.deleted_at = 0)
			OR EXISTS(SELECT 1 FROM favourites f WHERE f.manga_id = manga.manga_id AND f.deleted_at = 0)
		ORDER BY percent LIMIT :limit OFFSET :offset
		""",
	)
	abstract suspend fun findAll(offset: Int, limit: Int): Map<MangaWithTags, List<BookmarkEntity>>

	@Query("SELECT * FROM bookmarks WHERE manga_id = :mangaId AND chapter_id = :chapterId AND page = :page ORDER BY percent")
	abstract fun observe(mangaId: Long, chapterId: Long, page: Int): Flow<BookmarkEntity?>

	@Query("SELECT * FROM bookmarks WHERE manga_id = :mangaId")
	abstract suspend fun findAll(mangaId: Long): List<BookmarkEntity>

	@Query("SELECT * FROM bookmarks WHERE manga_id = :mangaId ORDER BY percent")
	abstract fun observe(mangaId: Long): Flow<List<BookmarkEntity>>

	@Transaction
	@Query(
		"""
		SELECT * FROM manga JOIN bookmarks ON bookmarks.manga_id = manga.manga_id
		WHERE EXISTS(SELECT 1 FROM favourite_categories private_isolation_mode WHERE private_isolation_mode.category_id = -2147483000 AND private_isolation_mode.space = -1 AND private_isolation_mode.deleted_at = 0)
			OR NOT EXISTS(SELECT 1 FROM private_favourites pf WHERE pf.manga_id = manga.manga_id AND pf.deleted_at = 0)
			OR EXISTS(SELECT 1 FROM favourites f WHERE f.manga_id = manga.manga_id AND f.deleted_at = 0)
		ORDER BY percent, bookmarks.rowid DESC LIMIT :limit
		""",
	)
	abstract fun observe(limit: Int): Flow<Map<MangaWithTags, List<BookmarkEntity>>>

	/**
	 * Private workspace projection. It follows actual Private membership even when the user has
	 * deliberately disabled Private isolation with KEEP_PRIVATE; that mode changes security/global
	 * visibility, not which collection the Private workspace represents.
	 */
	@Transaction
	@Query(
		"""
		SELECT * FROM manga JOIN bookmarks ON bookmarks.manga_id = manga.manga_id
		WHERE EXISTS(
			SELECT 1 FROM private_favourites pf
			WHERE pf.manga_id = manga.manga_id AND pf.deleted_at = 0
		)
		ORDER BY percent, bookmarks.rowid DESC LIMIT :limit
		""",
	)
	abstract fun observePrivate(limit: Int): Flow<Map<MangaWithTags, List<BookmarkEntity>>>

	@Insert
	abstract suspend fun insert(entity: BookmarkEntity)

	@Delete
	abstract suspend fun delete(entity: BookmarkEntity)

	@Query("DELETE FROM bookmarks WHERE page_id = :pageId")
	abstract suspend fun delete(pageId: Long): Int

	@Query("DELETE FROM bookmarks WHERE manga_id = :mangaId")
	abstract suspend fun deleteAll(mangaId: Long): Int

	@Query("DELETE FROM bookmarks WHERE manga_id = :mangaId AND chapter_id = :chapterId AND page = :page")
	abstract suspend fun delete(mangaId: Long, chapterId: Long, page: Int): Int

	@Upsert
	abstract suspend fun upsert(bookmarks: Collection<BookmarkEntity>)

	fun dump(): Flow<Pair<MangaWithTags, List<BookmarkEntity>>> = flow {
		val window = 32
		var offset = 0
		while (currentCoroutineContext().isActive) {
			val list = findAll(offset, window)
			if (list.isEmpty()) break
			offset += window
			list.forEach { emit(it.key to it.value) }
		}
	}
}
