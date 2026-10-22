package org.koitharu.kotatsu.sync.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncAuthPolicyTest {

	@Test
	fun accountSessionRequiresMetadataAndLiveGoogleAccount() {
		assertTrue(SyncAuthPolicy.hasAccountSession(hasAccountMetadata = true, hasGoogleAccount = true))
		assertFalse(SyncAuthPolicy.hasAccountSession(hasAccountMetadata = true, hasGoogleAccount = false))
		assertFalse(SyncAuthPolicy.hasAccountSession(hasAccountMetadata = false, hasGoogleAccount = true))
		assertFalse(SyncAuthPolicy.hasAccountSession(hasAccountMetadata = false, hasGoogleAccount = false))
	}

	@Test
	fun backgroundReadinessAlsoRequiresNoPendingAuthorization() {
		assertTrue(
			SyncAuthPolicy.isAccountReady(
				hasAccountMetadata = true,
				hasGoogleAccount = true,
				authorizationRequired = false,
			),
		)
		assertFalse(
			SyncAuthPolicy.isAccountReady(
				hasAccountMetadata = true,
				hasGoogleAccount = true,
				authorizationRequired = true,
			),
		)
	}

	@Test
	fun interactiveRecoveryIsBoundedToOneLaunch() {
		assertTrue(SyncAuthPolicy.canLaunchInteractiveRecovery(recoveryAlreadyAttempted = false))
		assertFalse(SyncAuthPolicy.canLaunchInteractiveRecovery(recoveryAlreadyAttempted = true))
	}
}
