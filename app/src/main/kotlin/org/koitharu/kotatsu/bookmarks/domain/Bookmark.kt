package org.koitharu.kotatsu.bookmarks.domain

import org.koitharu.kotatsu.core.util.MimeTypes
import org.koitharu.kotatsu.core.util.ext.isImage
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaPage
import java.time.Instant
import java.util.Base64

data class Bookmark(
	val manga: Manga,
	val pageId: Long,
	val chapterId: Long,
	val page: Int,
	val scroll: Int,
	val imageUrl: String,
	val createdAt: Instant,
	val percent: Float,
	val note: String? = null,
) : ListModel {

	override fun areItemsTheSame(other: ListModel): Boolean {
		return other is Bookmark &&
			manga.id == other.manga.id &&
			chapterId == other.chapterId &&
			page == other.page
	}

	fun toMangaPage() = MangaPage(
		id = pageId,
		url = imageUrl,
		preview = imageUrl.takeIf {
			MimeTypes.getMimeTypeFromUrl(it)?.isImage == true
		},
		source = manga.source,
	)
}

data class EpubHighlight(
	val start: Int,
	val end: Int,
	val text: String,
)

val Bookmark.epubHighlight: EpubHighlight?
	get() = decodeEpubHighlight(page, imageUrl)

internal fun decodeEpubHighlight(start: Int, url: String): EpubHighlight? {
	if (!url.startsWith(EPUB_HIGHLIGHT_PREFIX)) return null
	val payload = url.removePrefix(EPUB_HIGHLIGHT_PREFIX)
	val separator = payload.indexOf(':').takeIf { it > 0 } ?: return null
	val end = payload.substring(0, separator).toIntOrNull() ?: return null
	val text = runCatching {
		String(Base64.getUrlDecoder().decode(payload.substring(separator + 1)), Charsets.UTF_8)
	}.getOrNull() ?: return null
	return EpubHighlight(start, end, text).takeIf { it.start in 0 until it.end }
}

fun epubHighlightUrl(end: Int, text: String): String {
	val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray(Charsets.UTF_8))
	return "$EPUB_HIGHLIGHT_PREFIX$end:$encoded"
}

private const val EPUB_HIGHLIGHT_PREFIX = "epub-highlight:"
