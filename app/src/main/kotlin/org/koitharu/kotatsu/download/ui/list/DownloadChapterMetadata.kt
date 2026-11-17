package org.koitharu.kotatsu.download.ui.list

import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter

/** Resolves metadata only; downloaded checkmarks still come from the task-scoped local files. */
internal suspend fun resolveDownloadChapterMetadata(
	manga: Manga,
	requestedIds: Set<Long>?,
	findStored: suspend (Long) -> Manga?,
	loadRemote: suspend (Manga) -> Manga?,
): List<MangaChapter>? {
	val current = manga.chapters?.takeIf { it.isNotEmpty() }
	if (current.covers(requestedIds)) return current
	val stored = findStored(manga.id)
		?.takeIf { it.id == manga.id && it.source.name == manga.source.name }
		?.chapters?.takeIf { it.isNotEmpty() }
	if (stored.covers(requestedIds)) return stored
	// A partial snapshot may predate one of the group's requested chapters. Try the source once,
	// retaining available metadata if it is offline or has been uninstalled.
	return loadRemote(manga)?.chapters?.takeIf { it.isNotEmpty() } ?: stored ?: current
}

private fun List<MangaChapter>?.covers(requestedIds: Set<Long>?): Boolean {
	if (isNullOrEmpty()) return false
	if (requestedIds == null) return true
	val ids = mapTo(HashSet()) { it.id }
	return ids.containsAll(requestedIds)
}
