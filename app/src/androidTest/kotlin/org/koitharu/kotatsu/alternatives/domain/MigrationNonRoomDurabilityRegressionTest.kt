package org.koitharu.kotatsu.alternatives.domain

import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ChapterReadOverridesMigrationStore

@RunWith(AndroidJUnit4::class)
class MigrationNonRoomDurabilityRegressionTest {

	private val context = InstrumentationRegistry.getInstrumentation().targetContext
	private val oldId = 983_001L
	private val newId = 983_002L
	private val oldChapterId = 983_101L
	private val newChapterId = 983_201L

	@Before
	fun setUp() = clearOverrides()

	@After
	fun tearDown() = clearOverrides()

	@Test
	fun preparedReadOverrideCopySurvivesStoreRecreationAndRetryCleanup() {
		val settings = AppSettings(context)
		val durability = ChapterReadOverridesMigrationStore(context)

		settings.setChapterReadOverrides(oldId, mapOf(oldChapterId to true))
		durability.persistCurrent(oldId)

		// Simulate the lossless prepare step: destination is updated in memory, then synchronously
		// persisted before Room is allowed to commit.
		settings.setChapterReadOverrides(newId, mapOf(newChapterId to true))
		durability.persistCurrent(newId)

		// Recreate stores as an approximation of process restart after prepare/Room commit.
		val reopened = AppSettings(context)
		assertEquals(mapOf(oldChapterId to true), reopened.getChapterReadOverrides(oldId))
		assertEquals(mapOf(newChapterId to true), reopened.getChapterReadOverrides(newId))

		// A retry that did not create the destination copy must leave it intact. Successful cleanup may
		// then remove only the obsolete source copy and make that removal durable as well.
		val retryDurability = ChapterReadOverridesMigrationStore(context)
		reopened.setChapterReadOverrides(oldId, emptyMap())
		retryDurability.persistCurrent(oldId)

		val afterCleanup = AppSettings(context)
		assertTrue(afterCleanup.getChapterReadOverrides(oldId).isEmpty())
		assertEquals(mapOf(newChapterId to true), afterCleanup.getChapterReadOverrides(newId))
	}

	@Test
	fun failedRoomAttemptCanDurablyRollbackOnlyItsPreparedDestinationCopy() {
		val settings = AppSettings(context)
		val durability = ChapterReadOverridesMigrationStore(context)

		settings.setChapterReadOverrides(oldId, mapOf(oldChapterId to false))
		durability.persistCurrent(oldId)

		settings.setChapterReadOverrides(newId, mapOf(newChapterId to false))
		durability.persistCurrent(newId)

		// Simulate MigrateUseCase rollback after Room fails during the same attempt.
		settings.setChapterReadOverrides(newId, emptyMap())
		durability.persistCurrent(newId)

		val reopened = AppSettings(context)
		assertEquals(mapOf(oldChapterId to false), reopened.getChapterReadOverrides(oldId))
		assertTrue(reopened.getChapterReadOverrides(newId).isEmpty())
	}

	private fun clearOverrides() {
		PreferenceManager.getDefaultSharedPreferences(context).edit()
			.remove("chapter_read_overrides_$oldId")
			.remove("chapter_read_overrides_$newId")
			.commit()
	}
}
