package org.koitharu.kotatsu.scrobbling.common.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerUser

class TrackerDetailsSessionTest {
	private val app = InstrumentationRegistry.getInstrumentation().targetContext
	private val context = object : ContextWrapper(app) {
		override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("b2-test-$name", mode)
	}
	private val service = ScrobblerService.ANILIST
	@After fun cleanup() { context.getSharedPreferences(service.name, Context.MODE_PRIVATE).edit().clear().commit() }

	@Test fun tokenAccountAndLogoutAdvanceGenerationWithoutChangingStorageFormat() {
		cleanup()
		val storage = ScrobblerStorage(context, service)
		val initial = storage.sessionGeneration.value
		storage.accessToken = "fixture-only-token"
		assertTrue(storage.sessionGeneration.value > initial)
		val tokenGeneration = storage.sessionGeneration.value
		storage.user = ScrobblerUser(1, "Fixture", null, service)
		assertTrue(storage.sessionGeneration.value > tokenGeneration)
		val accountGeneration = storage.sessionGeneration.value
		storage.user = ScrobblerUser(1, "Updated display name", null, service)
		assertEquals(accountGeneration, storage.sessionGeneration.value)
		storage.user = ScrobblerUser(2, "Another Fixture", null, service)
		assertTrue(storage.sessionGeneration.value > accountGeneration)
		val replacementGeneration = storage.sessionGeneration.value
		storage.clear()
		assertTrue(storage.sessionGeneration.value > replacementGeneration)
		assertNull(storage.accessToken); assertNull(storage.user)
	}

	@Test fun restoredTokenStartsNewInMemoryGenerationAndMalformedOldUserDoesNotBreakReplacement() {
		cleanup()
		val prefs = context.getSharedPreferences(service.name, Context.MODE_PRIVATE)
		prefs.edit().putString("access_token", "fixture-only-token").putString("user", "malformed").commit()
		val storage = ScrobblerStorage(context, service)
		assertEquals(0L, storage.sessionGeneration.value)
		assertEquals("fixture-only-token", storage.accessToken)
		storage.user = ScrobblerUser(1, "Fixture", null, service)
		assertEquals(1L, storage.sessionGeneration.value)
		assertEquals(1L, storage.user?.id)
	}
}
