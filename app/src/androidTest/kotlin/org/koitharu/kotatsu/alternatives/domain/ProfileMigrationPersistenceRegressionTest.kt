package org.koitharu.kotatsu.alternatives.domain

import android.graphics.Bitmap
import androidx.preference.PreferenceManager
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.flow.flowOf
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.os.AppShortcutManager
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaLinkResolver
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.details.data.MangaNotesRepository
import org.koitharu.kotatsu.reader.ui.config.MangaReaderProfileStore
import org.koitharu.kotatsu.reader.ui.config.ReaderSettings
import javax.inject.Provider

/**
 * Persistence regression for the non-Room half of source migration.
 *
 * Recreating the repositories between prepare/finalize steps approximates process recreation:
 * the destination metadata must already be readable before the source copy is removed.
 */
@RunWith(AndroidJUnit4::class)
class ProfileMigrationPersistenceRegressionTest {

	private val context = InstrumentationRegistry.getInstrumentation().targetContext

	@Before
	fun setUp() = clearStores()

	@After
	fun tearDown() = clearStores()


	@Test
	fun readerSettingsFirstValueUsesPersistedMangaProfileInsteadOfGlobalFallback() {
		val mangaId = 90_001L
		val appSettings = AppSettings(context)
		val profileStore = MangaReaderProfileStore(context)

		// Persist a profile while 32-bit color is disabled, then change the global preference.
		// The first Producer value must still reflect the manga profile (RGB_565), not the newer
		// global setting (ARGB_8888).
		PreferenceManager.getDefaultSharedPreferences(context).edit()
			.putBoolean(AppSettings.KEY_32BIT_COLOR, false)
			.commit()
		profileStore.saveCurrent(mangaId, appSettings)
		PreferenceManager.getDefaultSharedPreferences(context).edit()
			.putBoolean(AppSettings.KEY_32BIT_COLOR, true)
			.commit()

		val database = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java).build()
		try {
			val repository = MangaDataRepository(
				db = database,
				resolverProvider = unusedProvider("MangaLinkResolver"),
				appShortcutManagerProvider = unusedProvider("AppShortcutManager"),
			)
			val producer = ReaderSettings.Producer(
				mangaId = flowOf(mangaId),
				initialMangaId = mangaId,
				settings = appSettings,
				mangaDataRepository = repository,
				profileStore = profileStore,
			)

			assertEquals(Bitmap.Config.RGB_565, producer.value.bitmapConfig)
		} finally {
			database.close()
			PreferenceManager.getDefaultSharedPreferences(context).edit()
				.remove(AppSettings.KEY_32BIT_COLOR)
				.commit()
		}
	}

	@Test
	fun preparedReaderProfileAndNoteSurviveStoreRecreationBeforeSourceCleanup() {
		val oldId = 91_001L
		val newId = 91_002L
		val settings = AppSettings(context)
		val profileStore = MangaReaderProfileStore(context)
		val notes = MangaNotesRepository(context)

		profileStore.saveCurrent(oldId, settings)
		notes.set(oldId, "migration-note")
		val expectedProfile = requireNotNull(profileStore.get(oldId))

		assertTrue(profileStore.prepareMove(oldId, newId))
		assertTrue(notes.prepareMove(oldId, newId))

		// New instances read only persisted SharedPreferences state.
		val reopenedProfiles = MangaReaderProfileStore(context)
		val reopenedNotes = MangaNotesRepository(context)
		assertEquals(expectedProfile, reopenedProfiles.get(newId))
		assertEquals("migration-note", reopenedNotes.get(newId))
		assertNotNull(reopenedProfiles.get(oldId))
		assertEquals("migration-note", reopenedNotes.get(oldId))

		reopenedProfiles.finishPreparedMove(oldId, newId)
		reopenedNotes.finishPreparedMove(oldId, newId)

		val afterCleanupProfiles = MangaReaderProfileStore(context)
		val afterCleanupNotes = MangaNotesRepository(context)
		assertEquals(expectedProfile, afterCleanupProfiles.get(newId))
		assertEquals("migration-note", afterCleanupNotes.get(newId))
		assertNull(afterCleanupProfiles.get(oldId))
		assertNull(afterCleanupNotes.get(oldId))
	}

	@Test
	fun failedRoomMigrationRollbackRemovesOnlyPreparedDestinationCopy() {
		val oldId = 92_001L
		val newId = 92_002L
		val profileStore = MangaReaderProfileStore(context)
		val notes = MangaNotesRepository(context)

		profileStore.saveCurrent(oldId, AppSettings(context))
		notes.set(oldId, "keep-source")

		assertTrue(profileStore.prepareMove(oldId, newId))
		assertTrue(notes.prepareMove(oldId, newId))
		profileStore.rollbackPreparedMove(newId)
		notes.rollbackPreparedMove(newId)

		val reopenedProfiles = MangaReaderProfileStore(context)
		val reopenedNotes = MangaNotesRepository(context)
		assertNotNull(reopenedProfiles.get(oldId))
		assertEquals("keep-source", reopenedNotes.get(oldId))
		assertNull(reopenedProfiles.get(newId))
		assertNull(reopenedNotes.get(newId))
	}

	@Test
	fun existingDestinationMetadataWinsAndIsNeverOwnedByRollback() {
		val oldId = 93_001L
		val newId = 93_002L
		val notes = MangaNotesRepository(context)
		notes.set(oldId, "source-note")
		notes.set(newId, "destination-note")

		assertFalse(notes.prepareMove(oldId, newId))
		assertEquals("destination-note", MangaNotesRepository(context).get(newId))
	}


	private fun <T> unusedProvider(name: String): Provider<T> = object : Provider<T> {
		override fun get(): T = error("$name is not used by ReaderSettings first-value regression")
	}

	private fun clearStores() {
		context.getSharedPreferences("manga_reader_profiles", 0).edit().clear().commit()
		context.getSharedPreferences("manga_notes", 0).edit().clear().commit()
	}
}
