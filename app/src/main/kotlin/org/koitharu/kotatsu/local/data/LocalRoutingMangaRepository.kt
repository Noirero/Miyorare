package org.koitharu.kotatsu.local.data

import android.content.ContentResolver
import android.content.Context
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import okio.source
import okio.use
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.local.library.LOCAL_LIBRARY_SCHEME
import org.koitharu.kotatsu.local.library.SmartLocalLibrary
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes only Smart Local identities to the selected-root index. Every legacy Local identity keeps
 * using [LocalMangaRepository], including Batch 11 downloaded/favourites compatibility paths.
 */
@Singleton
class LocalRoutingMangaRepository @Inject constructor(
	private val legacy: LocalMangaRepository,
	private val library: SmartLocalLibrary,
	@ApplicationContext private val context: Context,
	@PageCache private val pageCache: LocalStorageCache,
) : MangaRepository by legacy {

	val smartLocalChanges get() = library.changes

	suspend fun getIndexedBook(id: Long) = library.book(id)
	suspend fun hideIndexedBook(id: Long) = library.hide(setOf(id))
	suspend fun deleteIndexedBook(id: Long) = library.deleteFromDevice(setOf(id))
	suspend fun deleteIndexedChapters(id: Long, chapters: Set<Long>) = library.deleteChapters(id, chapters)

	override suspend fun getDetails(manga: Manga): Manga {
		if (!manga.url.isManagedLocalUri()) return legacy.getDetails(manga)
		return requireNotNull(library.details(manga.id)) { "On-device title is no longer indexed" }
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> =
		if (chapter.url.isManagedLocalUri()) library.pages(chapter) else legacy.getPages(chapter)

	override suspend fun getPageUrl(page: MangaPage): String {
		val uri = page.url.toUri()
		if (uri.scheme != ContentResolver.SCHEME_CONTENT) return legacy.getPageUrl(page)
		pageCache[page.url]?.let { return it.toUri().toString() }
		val input = requireNotNull(context.contentResolver.openInputStream(uri)) {
			"Cannot open on-device page: $uri"
		}
		return input.source().use { source -> pageCache.set(page.url, source, null).toUri().toString() }
	}

	override suspend fun getChapterHtml(chapter: MangaChapter): String? =
		if (chapter.url.isManagedLocalUri()) library.chapterHtml(chapter) else legacy.getChapterHtml(chapter)

	suspend fun getChapterImage(chapter: MangaChapter, href: String): Any? =
		if (chapter.url.isManagedLocalUri()) library.chapterImage(chapter.url, href) else null

	private fun String.isManagedLocalUri(): Boolean = toUri().scheme == LOCAL_LIBRARY_SCHEME
}
