package org.koitharu.kotatsu.alternatives.domain

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toEntity
import org.koitharu.kotatsu.core.model.MissingMangaSource
import org.koitharu.kotatsu.favourites.data.*

class MigrationBatchVisibilityTest {
	private val context = InstrumentationRegistry.getInstrumentation().targetContext
	private fun category(id: Int, space: Int) = FavouriteCategoryEntity(id, 1, 0, "Fixture", "NAME", false, false, true, 0, space)
	@Test fun projectionAndAdmissionFollowLiveNormalPrivateAndIsolationState() = runTest {
		val database = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java).build()
		try {
			val id = 985001L
			database.getMangaDao().upsert(SampleData.manga.copy(id = id, source = MissingMangaSource("BATCH_OLD")).toEntity())
			database.getFavouriteCategoriesDao().upsert(category(985101, 0))
			database.getFavouriteCategoriesDao().upsert(category(985102, 1))
			database.getFavouritesDao().upsert(FavouriteEntity(id, 985101, 0, false, 1, 0))
			assertTrue(database.getMangaDao().isMigrationEntryVisible(id))
			assertEquals(listOf(id), database.getMangaDao().observeLibraryMangaBySources(listOf("BATCH_OLD")).first().map { it.manga.id })
			database.getPrivateFavouritesDao().upsert(PrivateFavouriteEntity(id, 985102, 0, false, 1, 0))
			assertTrue(database.getPrivateFavouritesDao().observePrivateOnlyIds().first().isEmpty())
			database.getFavouritesDao().delete(id)
			assertFalse(database.getMangaDao().isMigrationEntryVisible(id))
			assertTrue(database.getMangaDao().observeLibraryMangaBySources(listOf("BATCH_OLD")).first().isEmpty())
			assertEquals(listOf(id), database.getPrivateFavouritesDao().observePrivateOnlyIds().first())
			// Existing isolation-disabled semantics stay unchanged; Private-only is then public by choice.
			database.getFavouriteCategoriesDao().upsert(category(-2147483000, -1))
			assertTrue(database.getPrivateFavouritesDao().observePrivateOnlyIds().first().isEmpty())
		} finally { database.close() }
	}
}
