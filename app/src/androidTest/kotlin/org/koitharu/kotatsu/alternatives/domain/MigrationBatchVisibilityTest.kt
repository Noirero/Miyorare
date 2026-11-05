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
    @Test fun historyOnlyIsNotAnActiveNormalMigrationCandidateOrExecutableBatch() = runTest {
        val database = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java).build()
        val sourceName = "MISSING_EXTENSION_HISTORY_ONLY"
        val mangaId = 985011L
        try {
            val manga = SampleData.manga.copy(id = mangaId, source = MissingMangaSource(sourceName))
            database.getMangaDao().upsert(manga.toEntity())
            database.getHistoryDao().insert(
                org.koitharu.kotatsu.history.data.HistoryEntity(mangaId, 1, 2, 0L, 3, 0.2f, 0.5f, 0, 4),
            )
            // The legacy history-inclusive query intentionally remains unchanged.
            assertTrue(database.getMangaDao().isMigrationEntryVisible(mangaId))
            assertFalse(database.getMangaDao().isNormalMigrationFavourite(mangaId))
            assertTrue(database.getMangaDao().observeNormalMigrationFavouritesBySources(listOf(sourceName)).first().isEmpty())
            assertTrue(database.getMangaDao().observeNormalMigrationSourceUsage().first().none { it.source == sourceName })

            val destination = MigrationSelection("DESTINATION", 985012L, "stale-selection")
            val blocked = MigrationBatchPolicy.process(
                MigrationBatchRequest(mangaId, sourceName, MigrationBatchAction.MIGRATE, destination),
                canAccess = { database.getMangaDao().isNormalMigrationFavourite(mangaId) },
                discover = { error("Stale migration must not search") },
                migrate = { error("Stale migration must not transfer") },
            )
            assertEquals(MigrationBatchStatus.INTERRUPTED, blocked.status)
            val blockedCheck = MigrationBatchPolicy.process(
                MigrationBatchRequest(mangaId, sourceName, MigrationBatchAction.CHECK),
                canAccess = { database.getMangaDao().isNormalMigrationFavourite(mangaId) },
                discover = { error("History-only must not become READY") },
                migrate = { error("History-only must not migrate") },
            )
            assertEquals(MigrationBatchStatus.INTERRUPTED, blockedCheck.status)
            assertNotNull(database.getHistoryDao().findIncludingDeleted(mangaId))
            assertNull(database.getMangaDao().preservedDestination(mangaId))
            assertFalse(database.getMangaDao().isNormalMigrationFavourite(mangaId))
        } finally {
            database.close()
        }
    }

    @Test fun missingExtensionNormalFavouriteRemainsEligibleAndPrivateOnlyRemainsHidden() = runTest {
        val database = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java).build()
        try {
            val old = 985021L
            val privateOnly = 985022L
            val sourceName = "MIHON_MISSING_EXTENSION_SOURCE"
            for (id in listOf(old, privateOnly)) {
                database.getMangaDao().upsert(
                    SampleData.manga.copy(id = id, source = MissingMangaSource(sourceName)).toEntity(),
                )
            }
            database.getFavouriteCategoriesDao().upsert(category(985121, 0))
            database.getFavouriteCategoriesDao().upsert(category(985122, 1))
            database.getFavouritesDao().upsert(FavouriteEntity(old, 985121, 0, false, 1, 0))
            database.getPrivateFavouritesDao().upsert(PrivateFavouriteEntity(privateOnly, 985122, 0, false, 1, 0))
            val dao = database.getMangaDao()
            assertTrue(dao.isNormalMigrationFavourite(old))
            assertFalse(dao.isNormalMigrationFavourite(privateOnly))
            assertEquals(listOf(old), dao.observeNormalMigrationFavouritesBySources(listOf(sourceName))
                .first().map { it.manga.id })
            assertEquals(1, dao.observeNormalMigrationSourceUsage().first()
                .single { it.source == sourceName }.mangaCount)

            // Disabling isolation does not silently promote Private ownership to Normal.
            database.getFavouriteCategoriesDao().upsert(category(-2147483000, -1))
            assertFalse(dao.isNormalMigrationFavourite(privateOnly))
            assertEquals(listOf(old), dao.observeNormalMigrationFavouritesBySources(listOf(sourceName))
                .first().map { it.manga.id })
        } finally {
            database.close()
        }
    }

}
