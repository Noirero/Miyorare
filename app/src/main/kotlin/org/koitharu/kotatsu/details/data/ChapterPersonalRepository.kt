package org.koitharu.kotatsu.details.data

import androidx.room.withTransaction
import dagger.Reusable
import javax.inject.Inject
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toEntities
import org.koitharu.kotatsu.core.db.entity.toEntity
import org.koitharu.kotatsu.parsers.model.Manga

@Reusable
class ChapterPersonalRepository @Inject constructor(private val database: MangaDatabase) {
	fun observe(mangaId: Long) = database.getChapterPersonalDao().observe(mangaId).map { entries ->
		entries.associate { it.key() to it.toMetadata() }
	}

	suspend fun get(mangaId: Long, key: ChapterPersonalKey): ChapterPersonalMetadata =
		database.getChapterPersonalDao().find(mangaId, key.source, key.url)?.toMetadata()
			?: ChapterPersonalMetadata()

	suspend fun set(manga: Manga, key: ChapterPersonalKey, rating: Int?, note: String?) {
		require(key.source.isNotBlank() && key.url.isNotBlank())
		val metadata = ChapterPersonalMetadata.normalized(rating, note)
		database.withTransaction {
			val dao = database.getChapterPersonalDao()
			if (metadata.isEmpty) {
				dao.delete(manga.id, key.source, key.url)
			} else {
				// As with bookmarks, a user annotation pins its manga even when it is not a favourite.
				val tags = manga.tags.toEntities()
				database.getTagsDao().upsert(tags)
				database.getMangaDao().upsert(manga.toEntity(), tags)
				dao.upsert(ChapterPersonalEntity(manga.id, key.source, key.url, metadata.rating, metadata.note))
			}
		}
	}
}
