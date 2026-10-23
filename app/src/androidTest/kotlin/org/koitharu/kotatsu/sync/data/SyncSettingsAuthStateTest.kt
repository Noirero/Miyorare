package org.koitharu.kotatsu.sync.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncSettingsAuthStateTest {

	private val context: Context = ApplicationProvider.getApplicationContext()

	@Before
	fun resetSyncPreferences() {
		context.getSharedPreferences("sync", Context.MODE_PRIVATE).edit().clear().commit()
	}

	@Test
	fun authorizationPausePersistsUntilForegroundRecoveryOrSignOut() {
		val first = SyncSettings(context)
		first.accountEmail = "reader@example.com"
		first.isAuthorizationRequired = true

		val afterRestart = SyncSettings(context)
		assertTrue(afterRestart.hasAccountMetadata)
		assertTrue(afterRestart.isAuthorizationRequired)

		afterRestart.clearAccount()

		val afterSignOut = SyncSettings(context)
		assertFalse(afterSignOut.hasAccountMetadata)
		assertFalse(afterSignOut.isAuthorizationRequired)
	}

	@Test
	fun browserModeMayPersistButCredentialDoesNotRestoreFromSyncPreferences() {
		val first = SyncSettings(context)
		first.accountEmail = "reader@example.com"
		first.authMode = DriveAuthMode.BROWSER_TEMPORARY

		val afterRestart = SyncSettings(context)
		assertEquals(DriveAuthMode.BROWSER_TEMPORARY, afterRestart.authMode)
		assertTrue(afterRestart.hasAccountMetadata)

		val freshProcessSession = BrowserDriveAuthSession()
		assertFalse(freshProcessSession.snapshot().isUsable)
		assertNull(freshProcessSession.snapshot().expiresAt)

		val persisted = context.getSharedPreferences("sync", Context.MODE_PRIVATE).all
		assertFalse(persisted.keys.any { it.contains("access_token", ignoreCase = true) })
		assertFalse(persisted.keys.any { it.contains("refresh_token", ignoreCase = true) })
		assertFalse(persisted.keys.any { it.contains("code_verifier", ignoreCase = true) })
	}
}
