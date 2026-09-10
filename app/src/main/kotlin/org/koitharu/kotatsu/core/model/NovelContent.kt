package org.koitharu.kotatsu.core.model

import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource

/**
 * Stable source classification for prose/novel flows.
 *
 * Source identity is centralized in [isNovelSource], including missing LNReader/Tsundoku sources.
 * Keep this domain-named alias for call sites that are explicitly separating Manga and Novel state.
 */
val MangaSource.isNovelContentSource: Boolean
	get() = isNovelSource

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
