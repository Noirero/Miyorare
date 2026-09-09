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

/** True when this item belongs to the Novel domain rather than the Manga domain. */
val Manga.isNovelContent: Boolean
	get() {
		if (source.isNovelContentSource) return true
		if (!source.isLocal) return false
		val normalizedUrl = url.replace('\\', '/')
		val cleanUrl = normalizedUrl.substringBefore('#').substringBefore('?')
		return normalizedUrl.contains("/00.Novel/", ignoreCase = true) ||
			cleanUrl.endsWith(".epub", ignoreCase = true)
	}
