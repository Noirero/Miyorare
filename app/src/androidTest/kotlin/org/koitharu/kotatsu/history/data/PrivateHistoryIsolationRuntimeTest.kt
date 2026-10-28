package org.koitharu.kotatsu.history.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.list.domain.ListSortOrder
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class PrivateHistoryIsolationRuntimeTest {

	@get:Rule
	val hilt = HiltAndroidRule(this)

	@Inject lateinit var database: MangaDatabase
	@Inject lateinit var favouritesRepository: FavouritesRepository

	@Before
	fun prepare() = runBlocking {
		hilt.inject()
		database.clearAllTables()
	}

	@Test
	fun dualMembershipStaysPrivateInHistoryUntilPrivateMembershipIsRemoved() = runBlocking {
		val manga = SampleData.manga
		val normalCategory = favouritesRepository.createCategory(
			title = "Normal history regression",
			sortOrder = ListSortOrder.NEWEST,
			isTrackerEnabled = false,
			isNewChaptersDownloadEnabled = false,
			isVisibleOnShelf = true,
			space = FavouriteSpace.NORMAL,
		)
		val privateCategory = favouritesRepository.createCategory(
			title = "Private history regression",
			sortOrder = ListSortOrder.NEWEST,
			isTrackerEnabled = false,
			isNewChaptersDownloadEnabled = false,
			isVisibleOnShelf = true,
			space = FavouriteSpace.PRIVATE,
		)
		favouritesRepository.updateCategoryMemberships(
			mangas = listOf(manga),
			changes = mapOf(normalCategory.id to true),
			space = FavouriteSpace.NORMAL,
		)
		favouritesRepository.updateCategoryMemberships(
			mangas = listOf(manga),
			changes = mapOf(privateCategory.id to true),
			space = FavouriteSpace.PRIVATE,
		)

		val dao = database.getHistoryDao()
		dao.insert(
			HistoryEntity(
				mangaId = manga.id,
				createdAt = 1L,
				updatedAt = 2L,
				chapterId = 3L,
				page = 0,
				scroll = 0f,
				percent = 0.5f,
				deletedAt = 0L,
				chaptersCount = 10,
			),
		)

		assertTrue(dao.observeAll(ListSortOrder.NEWEST, emptySet(), 10).first().isEmpty())
		assertEquals(
			listOf(manga.id),
			dao.observeAllPrivate(ListSortOrder.NEWEST, emptySet(), 10).first().map { it.history.mangaId },
		)
		assertFalse(dao.findAllForSync().any { it.mangaId == manga.id })
		assertNotNull(dao.find(manga.id))

		favouritesRepository.updateCategoryMemberships(
			mangas = listOf(manga),
			changes = mapOf(privateCategory.id to false),
			space = FavouriteSpace.PRIVATE,
		)

		assertEquals(
			listOf(manga.id),
			dao.observeAll(ListSortOrder.NEWEST, emptySet(), 10).first().map { it.history.mangaId },
		)
	}
}
