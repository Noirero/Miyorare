package org.koitharu.kotatsu.alternatives.domain

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toEntity
import org.koitharu.kotatsu.core.model.MissingMangaSource
import org.koitharu.kotatsu.details.data.ChapterPersonalEntity
import org.koitharu.kotatsu.parsers.model.MangaChapter

class ChapterPersonalCompatibilityMigrationRuntimeTest {
	private val context = InstrumentationRegistry.getInstrumentation().targetContext

	@Test
	fun compatibilityMigrationPersistsChapterNoteAndRatingOnReplacementIdentity() = runTest {
		val database = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java).build()
		try {
			val oldSource = MissingMangaSource("ANNOTATION_OLD")
			val newSource = MissingMangaSource("ANNOTATION_NEW")
			val oldChapter = chapter(995101L, oldSource, "/old/7")
			val newChapter = chapter(995201L, newSource, "/new/7")
			val oldManga = SampleData.mangaDetails.copy(
				id = 995001L,
				source = oldSource,
				url = "/old",
				chapters = listOf(oldChapter),
			)
			val newManga = SampleData.mangaDetails.copy(
				id = 995002L,
				source = newSource,
				url = "/new",
				chapters = listOf(newChapter),
			)
			database.getMangaDao().upsert(oldManga.toEntity())
			database.getMangaDao().upsert(newManga.toEntity())
			database.getChapterPersonalDao().upsert(
				ChapterPersonalEntity(oldManga.id, oldSource.name, oldChapter.url, 5, "Migration note"),
			)

			database.withTransaction {
				migrateChapterPersonalCompatibility(
					database = database,
					oldManga = oldManga,
					newManga = newManga,
					chapterIds = mapOf(oldChapter.id to newChapter.id),
				)
			}

			assertNull(database.getChapterPersonalDao().find(oldManga.id, oldSource.name, oldChapter.url))
			val migrated = database.getChapterPersonalDao().find(newManga.id, newSource.name, newChapter.url)
			assertEquals(5, migrated?.rating)
			assertEquals("Migration note", migrated?.note)
		} finally {
			database.close()
		}
	}

	private fun chapter(id: Long, source: MissingMangaSource, url: String) = MangaChapter(
		id = id,
		title = "Chapter 7",
		number = 7f,
		volume = 0,
		url = url,
		scanlator = null,
		uploadDate = 100L,
		branch = "main",
		source = source,
	)
}
