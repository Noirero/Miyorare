package org.koitharu.kotatsu.details.domain

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.MangaEntity
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity
import org.koitharu.kotatsu.scrobbling.common.domain.model.*

class TrackerRecommendationMappingTest {
	private val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, MangaDatabase::class.java).build()
	private val item = TrackerRecommendation(TrackerTarget(ScrobblerService.MANGAUPDATES, "17360452316"), "Recommendation title", null, TrackerRecommendationKind.RECOMMENDATION)
	@After fun close() { db.close() }
	private suspend fun associate(id: Long, service: ScrobblerService = ScrobblerService.MANGAUPDATES) {
		db.getMangaDao().upsert(MangaEntity(id, "Actual local title $id", null, "file:///actual-$id.cbz", "file:///actual-$id.cbz", 0f, false, null, "", null, null, null, null, "LOCAL", null))
		db.getScrobblingDao().upsert(ScrobblingEntity(service.id, 0, id, 17360452316, "read", 0, null, 0f))
	}
	private suspend fun resolve() = resolveTrackerRecommendation(item, { target ->
		db.getScrobblingDao().findByTarget(target.service.id, target.id.toLong()).mapNotNull { db.getMangaDao().find(it.mangaId)?.manga }
	}, MangaEntity::id, { true })!!
	private fun count(table: String) = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }

	@Test fun deterministicAssociationRetainsGenuineLocalIdentityAndSourceURL() = runBlocking {
		associate(41)
		val result = resolve()
		assertEquals(41L, result.candidates.single().id)
		assertEquals("Actual local title 41", result.candidates.single().title)
		assertEquals("file:///actual-41.cbz", result.candidates.single().url)
		assertNotEquals(item.target.id.toLong(), result.candidates.single().id)
		assertEquals(0, count("favourites")); assertEquals(1, count("scrobblings"))
	}
	@Test fun severalPersistedSourceCandidatesRemainAmbiguous() = runBlocking {
		associate(41); associate(42)
		assertEquals(listOf(41L, 42L), resolve().candidates.map { it.id })
		assertEquals(0, count("favourites")); assertEquals(2, count("scrobblings"))
	}
	@Test fun missingAssociationPreservesRealRecommendationTitleForSearchWithoutWrites() = runBlocking {
		val result = resolve()
		assertTrue(result.candidates.isEmpty())
		assertEquals("Recommendation title", result.recommendation.title)
		assertEquals(0, count("favourites")); assertEquals(0, count("scrobblings"))
	}
	@Test fun equalNumericIDsInAnotherProviderNeverResolveThisProviderTarget() = runBlocking {
		associate(41, ScrobblerService.MAL)
		assertTrue(resolve().candidates.isEmpty())
		assertEquals(1, count("scrobblings"))
	}
}
