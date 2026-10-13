package org.koitharu.kotatsu.details.domain

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.ChapterEntity
import org.koitharu.kotatsu.core.db.entity.toEntity
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.details.data.releasePredictionDatesQuery
import org.koitharu.kotatsu.favourites.data.FavouriteEntity
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.data.PrivateFavouriteEntity
import org.koitharu.kotatsu.parsers.model.MangaState
import java.time.LocalDate
import java.time.ZoneId

class FavouriteReleasePredictionsTest {
	private val context = InstrumentationRegistry.getInstrumentation().targetContext
	private lateinit var db: MangaDatabase
	private val zone = ZoneId.systemDefault()
	private val latest = LocalDate.of(2026, 10, 3)
	private val now = latest.plusDays(4).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

	@Before fun setup() {
		db = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java).build()
		for (space in 0..1) db.openHelper.writableDatabase.execSQL(
			"INSERT INTO favourite_categories(category_id,created_at,sort_key,title,`order`,`track`,download_new_chapters,show_in_lib,deleted_at,space) VALUES(?,0,0,'Fixture','NEWEST',0,0,1,0,?)",
			arrayOf(space + 1, space),
		)
	}
	@After fun close() { db.close() }

	private suspend fun store(id: Long, space: FavouriteSpace = FavouriteSpace.NORMAL, count: Int = 4, source: String = "FIXTURE", state: MangaState = MangaState.ONGOING, chapterSource: String = source, splitBranches: Boolean = false) {
		val manga = SampleData.manga.copy(id = id, title = "Fixture $id", source = MangaSource(source), state = state, chapters = null)
		db.getMangaDao().upsert(manga.toEntity())
		val chapters = (0 until count).map { index ->
			ChapterEntity(index.toLong(), id, "Actual chapter", index.toFloat(), 0, "/$id/$index", null,
				latest.minusDays(index * 7L).atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
				if (splitBranches && index % 2 == 0) "Spanish" else "English", chapterSource, index)
		}
		db.getChaptersDao().replaceAll(id, chapters)
		if (space == FavouriteSpace.PRIVATE) db.getPrivateFavouritesDao().insert(PrivateFavouriteEntity(id, 2, 0, false, 0, 0))
		else db.getFavouritesDao().insert(FavouriteEntity(id, 1, 0, false, 0, 0))
	}

	@Test fun persistedEligibilityAndPrivacyAreEnforcedWithoutSourceRepositories() = runBlocking {
		store(1)
		store(2, FavouriteSpace.PRIVATE)
		store(3, count = 3)
		store(4, source = "LOCAL")
		store(5, state = MangaState.FINISHED)
		store(6, chapterSource = "UNRELATED_SOURCE")
		store(7, splitBranches = true)
		val useCase = FavouriteReleasePredictionsUseCase(db)
		assertEquals(listOf(1L), useCase(FavouriteSpace.NORMAL, now).map { it.manga.id })
		assertEquals(listOf(2L), useCase(FavouriteSpace.PRIVATE, now).map { it.manga.id })
		assertTrue(useCase(FavouriteSpace.NORMAL, now + 30L * 86400000L).isEmpty())
	}

	@Test fun recentEvidenceLimitCountsLocalDaysBeforeLimitingAndMatchesFullEstimator() = runBlocking {
		store(1, count = 30)
		val initial = db.getChaptersDao().findAll(1)
		val burst = (100L..199L).map { id -> initial.first().copy(chapterId = id, uploadDate = initial.first().uploadDate + (id - 100) * 1000L) }
		db.getChaptersDao().replaceAll(1, initial + burst)
		val branches = db.getChaptersDao().findReleasePredictionBranches(listOf(1), now)
		val dates = db.getChaptersDao().findReleasePredictionDates(releasePredictionDatesQuery(branches, now))
		assertEquals(12, dates.size)
		val item = FavouriteReleasePredictionsUseCase(db)(FavouriteSpace.NORMAL, now).single()
		assertEquals(NextChapterReleaseEstimator.predict((initial + burst).map { it.uploadDate }, now, zone), item.prediction)
		assertEquals(12, item.releaseDates.size)
	}

	@Test fun keysetBatchesIncludeNegativeIdsAndCrossBatchBoundariesWithoutDuplicates() = runBlocking {
		for (id in -2L..103L) store(id)
		val results = FavouriteReleasePredictionsUseCase(db)(FavouriteSpace.NORMAL, now)
		assertEquals(106, results.size)
		assertEquals(106, results.map { it.manga.id }.toSet().size)
		assertEquals((-2L..103L).toSet(), results.map { it.manga.id }.toSet())
		assertTrue(results.all { it.releaseDates.size == 4 && it.manga.chapters == null })
	}
}
