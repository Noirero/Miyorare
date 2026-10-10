package org.koitharu.kotatsu.scrobbling.mangaupdates.data

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerUser
import java.io.File
import java.util.UUID

class MangaUpdatesSessionStoreTest {
	private val app = InstrumentationRegistry.getInstrumentation().targetContext
	private val directory = File(app.noBackupFilesDir, "mu-session-test-${UUID.randomUUID()}").apply { mkdirs() }
	private val context = object : ContextWrapper(app) { override fun getNoBackupFilesDir() = directory }
	private val user = ScrobblerUser(17360452316L, "Fixture User", "https://image.invalid/avatar", ScrobblerService.MANGAUPDATES)
	@After fun cleanup() { directory.deleteRecursively() }

	@Test fun sessionSurvivesRecreationAsCiphertextOutsideBackedUpStorage() {
		val store = EncryptedMangaUpdatesSessionStore(context)
		assertTrue(store.save("fixture-only-private-token", user, store.generation.value))
		val bytes = File(directory, "mangaupdates-session").readText()
		assertFalse(bytes.contains("fixture-only-private-token"))
		assertFalse(bytes.contains(user.nickname))
		assertFalse(bytes.contains(user.id.toString()))
		val restored = EncryptedMangaUpdatesSessionStore(context).snapshot()!!
		assertEquals("fixture-only-private-token", restored.token)
		assertEquals(user, restored.user)
		assertEquals(directory.canonicalFile, File(directory, "mangaupdates-session").parentFile.canonicalFile)
		assertFalse(File(app.filesDir, "mangaupdates-session").exists())
	}

	@Test fun logoutAndAccountReplacementRejectOldGenerationAndRemoveCredentials() {
		val store = EncryptedMangaUpdatesSessionStore(context)
		store.save("fixture-one", user, store.generation.value)
		val old = store.generation.value
		store.save("fixture-two", user.copy(id = 2), old)
		assertFalse(store.clear(old))
		assertFalse(store.save("fixture-stale", user, old))
		assertEquals(2L, store.snapshot()?.user?.id)
		store.clear()
		assertNull(store.snapshot())
		assertNull(EncryptedMangaUpdatesSessionStore(context).snapshot())
		assertFalse(File(directory, "mangaupdates-session").exists())
	}

	@Test fun malformedOrTamperedCiphertextFailsClosedAndAdvancesGeneration() {
		val store = EncryptedMangaUpdatesSessionStore(context)
		store.save("fixture-session", user, store.generation.value)
		File(directory, "mangaupdates-session").writeText("{\"iv\":\"AA==\",\"data\":\"AA==\"}")
		val restored = EncryptedMangaUpdatesSessionStore(context)
		assertNull(restored.snapshot())
		assertTrue(restored.generation.value > 0)
		assertFalse(File(directory, "mangaupdates-session").exists())
	}

	@Test fun malformedTokenNeverPersistsOrBecomesAuthorized() {
		val store = EncryptedMangaUpdatesSessionStore(context)
		try { store.save("fixture\nheader", user, store.generation.value); fail("Expected invalid session") } catch (_: IllegalArgumentException) { }
		assertNull(store.snapshot())
		assertFalse(File(directory, "mangaupdates-session").exists())
	}
}
