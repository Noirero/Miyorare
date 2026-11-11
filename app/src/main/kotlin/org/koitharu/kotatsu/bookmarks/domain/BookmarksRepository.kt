package org.koitharu.kotatsu.bookmarks.domain

import android.database.SQLException
import androidx.room.withTransaction
import dagger.Reusable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.bookmarks.data.BookmarkEntity
import org.koitharu.kotatsu.bookmarks.data.toBookmark
import org.koitharu.kotatsu.bookmarks.data.toBookmarks
import org.koitharu.kotatsu.bookmarks.data.toEntity
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toEntities
import org.koitharu.kotatsu.core.db.entity.toEntity
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.ui.util.ReversibleHandle
import org.koitharu.kotatsu.core.util.ext.mapItems
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.parsers.model.Manga
import javax.inject.Inject

/** Addition never takes ownership of a Reader bookmark at the same page. */
enum class SwipeBookmarkWriteResult { ADDED, ALREADY_SWIPED, PAGE_ALREADY_BOOKMARKED }

@Reusable
class BookmarksRepository @Inject constructor(
	private val db: MangaDatabase,
) {

	fun observeBookmark(manga: Manga, chapterId: Long, page: Int): Flow<Bookmark?> {
		return db.getBookmarksDao().observe(manga.id, chapterId, page).map { it?.toBookmark(manga) }
	}

	fun observeBookmarks(manga: Manga): Flow<List<Bookmark>> {
		return db.getBookmarksDao().observe(manga.id).mapItems { it.toBookmark(manga) }
	}

	fun observeBookmarks(
		limit: Int,
		space: FavouriteSpace = FavouriteSpace.NORMAL,
	): Flow<Map<Manga, List<Bookmark>>> {
		val source = if (space == FavouriteSpace.PRIVATE) {
			db.getBookmarksDao().observePrivate(limit)
		} else {
			db.getBookmarksDao().observe(limit)
		}
		return source.map { map ->
			val res = LinkedHashMap<Manga, List<Bookmark>>(map.size)
			for ((k, v) in map) {
				val manga = k.toManga()
				res[manga] = v.toBookmarks(manga)
			}
			res
		}
	}

	suspend fun addBookmark(bookmark: Bookmark) {
		db.withTransaction {
			val tags = bookmark.manga.tags.toEntities()
			db.getTagsDao().upsert(tags)
			db.getMangaDao().upsert(bookmark.manga.toEntity(), tags)
			db.getBookmarksDao().insert(bookmark.toEntity())
		}
	}

	suspend fun updateBookmark(bookmark: Bookmark, imageUrl: String) {
        db.withTransaction {
            val dao = db.getBookmarksDao()
            val current = dao.findForMangaPage(bookmark.manga.id, bookmark.pageId) ?: return@withTransaction
            dao.upsert(listOf(current.copy(imageUrl = imageUrl)))
        }
	}

	suspend fun updateBookmarkNote(bookmark: Bookmark, note: String?) {
        db.withTransaction {
            val dao = db.getBookmarksDao()
            val current = dao.findForMangaPage(bookmark.manga.id, bookmark.pageId) ?: return@withTransaction
            val updatedNote = note?.trim()?.takeIf(String::isNotEmpty)
            // A manually annotated swipe bookmark becomes Reader-owned permanently.
            dao.upsert(listOf(current.copy(
                note = updatedNote,
                origin = if (updatedNote != null) BOOKMARK_ORIGIN_READER else current.origin,
            )))
        }
	}

    /** Removes only bookmarks owned by Details swipe, never Reader bookmarks. */
    suspend fun removeSwipeBookmark(mangaId: Long, chapterId: Long): Boolean = db.withTransaction {
        db.getBookmarksDao().deleteChapterOrigin(mangaId, chapterId, BOOKMARK_ORIGIN_DETAILS_SWIPE) > 0
    }

    /** Caller resolves a real MangaPage first; check collisions inside the same DB transaction. */
    suspend fun addSwipeBookmark(bookmark: Bookmark): SwipeBookmarkWriteResult = db.withTransaction {
        val dao = db.getBookmarksDao()
        if (dao.findByChapterOrigin(bookmark.manga.id, bookmark.chapterId, BOOKMARK_ORIGIN_DETAILS_SWIPE) != null) {
            return@withTransaction SwipeBookmarkWriteResult.ALREADY_SWIPED
        }
        if (dao.findForMangaPage(bookmark.manga.id, bookmark.pageId) != null) {
            return@withTransaction SwipeBookmarkWriteResult.PAGE_ALREADY_BOOKMARKED
        }
        val tags = bookmark.manga.tags.toEntities()
        db.getTagsDao().upsert(tags)
        db.getMangaDao().upsert(bookmark.manga.toEntity(), tags)
        dao.insert(bookmark.copy(origin = BOOKMARK_ORIGIN_DETAILS_SWIPE).toEntity())
        SwipeBookmarkWriteResult.ADDED
    }

	suspend fun removeBookmark(mangaId: Long, chapterId: Long, page: Int) {
		check(db.getBookmarksDao().delete(mangaId, chapterId, page) != 0) {
			"Bookmark not found"
		}
	}

	suspend fun removeBookmark(bookmark: Bookmark) {
		removeBookmark(bookmark.manga.id, bookmark.chapterId, bookmark.page)
	}

	suspend fun removeBookmarks(ids: Set<Long>): ReversibleHandle {
		val entities = ArrayList<BookmarkEntity>(ids.size)
		db.withTransaction {
			val dao = db.getBookmarksDao()
			for (pageId in ids) {
				val e = dao.find(pageId)
				if (e != null) {
					entities.add(e)
				}
				dao.delete(pageId)
			}
		}
		return BookmarksRestorer(entities)
	}

	private inner class BookmarksRestorer(
		private val entities: Collection<BookmarkEntity>,
	) : ReversibleHandle {

		override suspend fun reverse() {
			db.withTransaction {
				for (e in entities) {
					try {
						db.getBookmarksDao().insert(e)
					} catch (e: SQLException) {
						e.printStackTraceDebug()
					}
				}
			}
		}
	}
}
