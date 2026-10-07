package org.koitharu.kotatsu.local.data

import androidx.core.net.toUri
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
) : MangaRepository by legacy {

	override suspend fun getDetails(manga: Manga): Manga {
		if (!manga.url.isManagedLocalUri()) return legacy.getDetails(manga)
		return requireNotNull(library.details(manga.id)) { "On-device title is no longer indexed" }
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> =
		if (chapter.url.isManagedLocalUri()) library.pages(chapter) else legacy.getPages(chapter)

	override suspend fun getChapterHtml(chapter: MangaChapter): String? =
		if (chapter.url.isManagedLocalUri()) library.chapterHtml(chapter) else legacy.getChapterHtml(chapter)

	private fun String.isManagedLocalUri(): Boolean = toUri().scheme == LOCAL_LIBRARY_SCHEME
}
