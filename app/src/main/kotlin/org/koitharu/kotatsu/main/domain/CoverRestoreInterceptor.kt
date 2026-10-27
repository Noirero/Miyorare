package org.koitharu.kotatsu.main.domain

import androidx.collection.ArraySet
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import org.koitharu.kotatsu.bookmarks.domain.Bookmark
import org.koitharu.kotatsu.bookmarks.domain.BookmarksRepository
import org.koitharu.kotatsu.core.image.StoredMangaCoverPipeline
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.util.ext.bookmarkKey
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.util.findById
import org.koitharu.kotatsu.parsers.util.ifNullOrEmpty
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.util.Collections
import javax.inject.Inject

class CoverRestoreInterceptor @Inject constructor(
	private val bookmarksRepository: BookmarksRepository,
	private val repositoryFactory: MangaRepository.Factory,
	private val storedMangaCoverPipeline: StoredMangaCoverPipeline,
) : Interceptor {

	private val blacklist = Collections.synchronizedSet(ArraySet<String>())

	override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
		val request = chain.request
		val result = storedMangaCoverPipeline.proceed(chain)
		if (result is ErrorResult && result.throwable.shouldRestore()) {
			// Note: manga covers are intentionally NOT restored through repo.find() here. Collection
			// covers are served by the compact stored-cover pipeline, while browse/search remains
			// disposable. Refreshing Details updates a durable cover only if it was already stored.
			request.extras[bookmarkKey]?.let {
				return if (restoreBookmark(it)) {
					chain.withRequest(request.newBuilder().build()).proceed()
				} else {
					result
				}
			}
		}
		return result
	}

	private suspend fun restoreBookmark(bookmark: Bookmark): Boolean {
		val key = bookmark.imageUrl
		if (!blacklist.add(key)) {
			return false
		}
		val restored = runCatchingCancellable {
			restoreBookmarkImpl(bookmark)
		}.onFailure { e ->
			e.printStackTraceDebug()
		}.getOrDefault(false)
		if (restored) {
			blacklist.remove(key)
		}
		return restored
	}

	private suspend fun restoreBookmarkImpl(bookmark: Bookmark): Boolean {
		if (bookmark.manga.isLocal) {
			return false
		}
		val repo = repositoryFactory.create(bookmark.manga.source)
		val chapter = repo.getDetails(bookmark.manga).chapters?.findById(bookmark.chapterId) ?: return false
		val page = repo.getPages(chapter)[bookmark.page]
		val imageUrl = page.preview.ifNullOrEmpty { page.url }
		return if (imageUrl != bookmark.imageUrl) {
			bookmarksRepository.updateBookmark(bookmark, imageUrl)
			true
		} else {
			false
		}
	}

	private fun Throwable.shouldRestore(): Boolean {
		return this is Exception // any Exception but not Error
	}
}
