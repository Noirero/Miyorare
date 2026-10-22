package org.koitharu.kotatsu.sync.data

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.auth.UserRecoverableAuthException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GoogleDriveAuthRecoveryTest {

	@Test
	fun recoverableAuthExceptionPreservesGoogleRecoveryIntent() {
		val recovery = Intent("org.koitharu.kotatsu.TEST_GOOGLE_DRIVE_RECOVERY")
		val source = UserRecoverableAuthException("Drive authorization required", recovery)

		val mapped = source.toSyncSignInRequiredException()
		val mappedRecovery = requireNotNull(mapped.recoveryIntent)

		// Play Services may return an equivalent Intent instance rather than the same object identity.
		// The contract we need to preserve is the launch semantics, not JVM reference identity.
		assertTrue(recovery.filterEquals(mappedRecovery))
		assertEquals(recovery.action, mappedRecovery.action)
		assertEquals("Drive authorization required", mapped.message)
	}
}
