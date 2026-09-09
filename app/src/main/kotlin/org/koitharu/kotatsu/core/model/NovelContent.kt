package org.koitharu.kotatsu.core.model

import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource

/**
 * Stable content classification for prose/novel flows.
 *
 * This deliberately stays separate from `Manga.isEpub`: EPUB is a storage/rendering format, while
 * a novel can be remote prose from an LNReader/Tsundoku source. Keeping one classifier here avoids
 * Favourites, Details, Search, and History silently disagreeing about whether an entry is a novel.
 */
val MangaSource.isNovelContentSource: Boolean
	get() {
		if (isNovelSource) return true
		val source = unwrap()
		// LN_ is exclusively the LNReader novel namespace. Preserve the content kind even when the
		// plugin is temporarily missing, otherwise restored favourites can fall back into Manga state.
		return source is MissingMangaSource && source.name.startsWith("LN_")
	}

/**
 * Lightweight path classifier shared by Local, Downloaded, and search flows.
 * Query/fragment suffixes are ignored because local chapter URLs may point inside an archive/book.
 */
fun String.isNovelContentPath(): Boolean {
	val normalized = replace('\\', '/')
	val clean = normalized.substringBefore('#').substringBefore('?')
	return normalized.contains("/00.Novel/", ignoreCase = true) ||
		normalized.startsWith("00.Novel/", ignoreCase = true) ||
		clean.endsWith("/00.Novel", ignoreCase = true) ||
		clean.endsWith(".epub", ignoreCase = true)
}

/** True when this item belongs to the Novel domain rather than the Manga domain. */
val Manga.isNovelContent: Boolean
	get() {
		if (source.isNovelContentSource) return true
		if (!source.isLocal) return false
		return url.isNovelContentPath()
	}
