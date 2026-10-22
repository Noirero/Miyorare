package org.koitharu.kotatsu.sync.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncAuthPolicyTest {

	@Test
	fun accountReadyRequiresMetadataAndLiveGoogleAccount() {
		assertTrue(SyncAuthPolicy.isAccountReady(hasAccountMetadata = true, hasGoogleAccount = true))
		assertFalse(SyncAuthPolicy.isAccountReady(hasAccountMetadata = true, hasGoogleAccount = false))
		assertFalse(SyncAuthPolicy.isAccountReady(hasAccountMetadata = false, hasGoogleAccount = true))
		assertFalse(SyncAuthPolicy.isAccountReady(hasAccountMetadata = false, hasGoogleAccount = false))
	}

	@Test
	fun interactiveRecoveryIsBoundedToOneLaunch() {
		assertTrue(SyncAuthPolicy.canLaunchInteractiveRecovery(recoveryAlreadyAttempted = false))
		assertFalse(SyncAuthPolicy.canLaunchInteractiveRecovery(recoveryAlreadyAttempted = true))
	}
}
