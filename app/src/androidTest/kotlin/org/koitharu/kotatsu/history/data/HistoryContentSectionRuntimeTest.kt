package org.koitharu.kotatsu.history.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toEntity
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.model.MangaNovelSection
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.list.domain.ListSortOrder

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class HistoryContentSectionRuntimeTest {

	@get:Rule val hilt = HiltAndroidRule(this)
	@Inject lateinit var db: MangaDatabase

	@Before fun setup() = runBlocking {
		hilt.inject()
		db.clearAllTables()
	}

	@Test fun mangaAndNovelPaginationFiltersBeforeLimitAndRespectsPrivateSpace() = runBlocking {
		val comic = SampleData.manga.copy(
			id = 88001L, source = MangaSource("UNKNOWN"), url = "/manga/comic",
		)
		val novel = SampleData.manga.copy(
			id = 88002L, source = MangaSource("LN_regression"), url = "/books/novel",
		)
		val localNovel = SampleData.manga.copy(
			id = 88003L, source = LocalMangaSource, url = "/books/00.Novel/volume.epub",
		)
		val privateNovel = SampleData.manga.copy(
			id = 88004L, source = MangaSource("LN_regression"), url = "/books/private",
		)
		val localMangaQuery = SampleData.manga.copy(
			id = 88005L, source = LocalMangaSource, url = "/books/comic?download=volume.epub",
		)
		for (item in listOf(comic, novel, localNovel, privateNovel, localMangaQuery)) {
			db.getMangaDao().upsert(item.toEntity())
		}
		val dao = db.getHistoryDao()
		for ((item, stamp, space) in listOf(
			Triple(comic, 400L, FavouriteSpace.NORMAL),
			Triple(novel, 300L, FavouriteSpace.NORMAL),
			Triple(localNovel, 200L, FavouriteSpace.NORMAL),
			Triple(privateNovel, 500L, FavouriteSpace.PRIVATE),
			Triple(localMangaQuery, 100L, FavouriteSpace.NORMAL),
		)) {
			dao.insert(HistoryEntity(
				mangaId = item.id,
				createdAt = stamp,
				updatedAt = stamp,
				chapterId = 1L,
				page = 0,
				scroll = 0f,
				percent = 0.3f,
				deletedAt = 0L,
				chaptersCount = 2,
				space = space.dbValue,
			))
		}

		val novelPredicate = historyContentSectionPredicate(MangaNovelSection.NOVEL, listOf("LN_regression"))
		val mangaPredicate = historyContentSectionPredicate(MangaNovelSection.MANGA, listOf("LN_regression"))
		// The newest NORMAL title is manga. The NOVEL query must still find the next item at LIMIT 1.
		assertEquals(
			listOf(novel.id),
			dao.observeAll(ListSortOrder.NEWEST, emptySet(), 1, contentPredicate = novelPredicate)
				.first().map { it.history.mangaId },
		)
		assertEquals(
			listOf(comic.id),
			dao.observeAll(ListSortOrder.NEWEST, emptySet(), 1, contentPredicate = mangaPredicate)
				.first().map { it.history.mangaId },
		)
		assertEquals(
			listOf(novel.id, localNovel.id),
			dao.observeAll(ListSortOrder.NEWEST, emptySet(), 10, contentPredicate = novelPredicate)
				.first().map { it.history.mangaId },
		)
		assertEquals(
			listOf(comic.id, localMangaQuery.id),
			dao.observeAll(ListSortOrder.NEWEST, emptySet(), 10, contentPredicate = mangaPredicate)
				.first().map { it.history.mangaId },
		)
		// Private source must never appear in Normal; it must remain available in Private's Novel tab.
		assertEquals(
			listOf(privateNovel.id),
			dao.observeAllPrivate(ListSortOrder.NEWEST, emptySet(), 10, contentPredicate = novelPredicate)
				.first().map { it.history.mangaId },
		)
		assertTrue(
			dao.observeAllPrivate(ListSortOrder.NEWEST, emptySet(), 10, contentPredicate = mangaPredicate).first().isEmpty(),
		)
	}
}
