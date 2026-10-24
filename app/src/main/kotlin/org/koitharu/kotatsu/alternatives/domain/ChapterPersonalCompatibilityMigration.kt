package org.koitharu.kotatsu.alternatives.domain

import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.details.data.ChapterPersonalEntity
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter

/**
 * Moves per-chapter notes and star ratings for the legacy/compatibility migration path.
 *
 * Bookmark migration already decides chapter identity by [chapterIds]. Reuse that exact mapping so
 * personal chapter metadata follows the same replacement chapter instead of inventing a second
 * matching rule. Existing destination metadata wins field-by-field; source metadata only fills gaps.
 */
internal suspend fun migrateChapterPersonalCompatibility(
	database: MangaDatabase,
	oldManga: Manga,
	newManga: Manga,
	chapterIds: Map<Long, Long>,
) {
	val dao = database.getChapterPersonalDao()
	val sourceEntries = dao.findAll(listOf(oldManga.id))
	if (sourceEntries.isEmpty()) return
	val targetEntries = dao.findAll(listOf(newManga.id))
	for (
		entry in mapChapterPersonalCompatibility(
			oldMangaId = oldManga.id,
			newMangaId = newManga.id,
			oldChapters = oldManga.chapters.orEmpty(),
			newChapters = newManga.chapters.orEmpty(),
			chapterIds = chapterIds,
			sourceEntries = sourceEntries,
			targetEntries = targetEntries,
		)
	) {
		dao.upsert(entry)
	}
	// Match bookmark compatibility semantics: once source replacement commits, metadata that could
	// not be mapped no longer owns/pins the obsolete manga identity.
	for (entry in sourceEntries) {
		dao.delete(oldManga.id, entry.source, entry.url)
	}
}

internal fun mapChapterPersonalCompatibility(
	oldMangaId: Long,
	newMangaId: Long,
	oldChapters: List<MangaChapter>,
	newChapters: List<MangaChapter>,
	chapterIds: Map<Long, Long>,
	sourceEntries: Collection<ChapterPersonalEntity>,
	targetEntries: Collection<ChapterPersonalEntity>,
): List<ChapterPersonalEntity> {
	if (oldMangaId == newMangaId || sourceEntries.isEmpty() || chapterIds.isEmpty()) return emptyList()
	val sourceByKey = sourceEntries.associateBy { it.source to it.url }
	val targetChapterById = newChapters.associateBy { it.id }
	val existingByKey = targetEntries.associateBy { it.source to it.url }
	val merged = LinkedHashMap<Pair<String, String>, ChapterPersonalEntity>()

	// Iterate source chapters rather than DAO row order so duplicate chapter-number mappings remain
	// deterministic and follow the same source ordering used by the migration details snapshot.
	for (oldChapter in oldChapters) {
		val source = sourceByKey[oldChapter.source.name to oldChapter.url] ?: continue
		val targetId = chapterIds[oldChapter.id] ?: continue
		val targetChapter = targetChapterById[targetId] ?: continue
		val key = targetChapter.source.name to targetChapter.url
		val current = merged[key] ?: existingByKey[key]
		merged[key] = ChapterPersonalEntity(
			mangaId = newMangaId,
			source = key.first,
			url = key.second,
			rating = current?.rating ?: source.rating,
			note = current?.note?.takeIf { it.isNotBlank() } ?: source.note,
		)
	}
	return merged.values.filter { it.rating != null || !it.note.isNullOrBlank() }
}
