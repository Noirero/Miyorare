package org.koitharu.kotatsu.sync.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.sync.data.DriveAuthMode

class SyncAuthPolicyTest {

	@Test
	fun accountSessionRequiresProviderSpecificLiveCredential() {
		assertTrue(
			SyncAuthPolicy.hasAccountSession(
				DriveAuthMode.GMS,
				hasAccountMetadata = true,
				hasGoogleAccount = true,
				hasBrowserSession = false,
			),
		)
		assertTrue(
			SyncAuthPolicy.hasAccountSession(
				DriveAuthMode.BROWSER_TEMPORARY,
				hasAccountMetadata = true,
				hasGoogleAccount = false,
				hasBrowserSession = true,
			),
		)
		assertFalse(
			SyncAuthPolicy.hasAccountSession(
				DriveAuthMode.BROWSER_TEMPORARY,
				hasAccountMetadata = true,
				hasGoogleAccount = false,
				hasBrowserSession = false,
			),
		)
	}

	@Test
	fun onlyGmsSessionCanBeBackgroundReady() {
		assertTrue(
			SyncAuthPolicy.isBackgroundReady(
				DriveAuthMode.GMS,
				hasAccountMetadata = true,
				hasGoogleAccount = true,
				authorizationRequired = false,
			),
		)
		assertFalse(
			SyncAuthPolicy.isBackgroundReady(
				DriveAuthMode.BROWSER_TEMPORARY,
				hasAccountMetadata = true,
				hasGoogleAccount = true,
				authorizationRequired = false,
			),
		)
		assertFalse(
			SyncAuthPolicy.isBackgroundReady(
				DriveAuthMode.GMS,
				hasAccountMetadata = true,
				hasGoogleAccount = true,
				authorizationRequired = true,
			),
		)
	}

	@Test
	fun legacyGmsHelpersPreservePreviousBehavior() {
		assertTrue(SyncAuthPolicy.hasAccountSession(hasAccountMetadata = true, hasGoogleAccount = true))
		assertFalse(SyncAuthPolicy.hasAccountSession(hasAccountMetadata = true, hasGoogleAccount = false))
		assertTrue(
			SyncAuthPolicy.isAccountReady(
				hasAccountMetadata = true,
				hasGoogleAccount = true,
				authorizationRequired = false,
			),
		)
	}

	@Test
	fun interactiveRecoveryIsBoundedToOneLaunch() {
		assertTrue(SyncAuthPolicy.canLaunchInteractiveRecovery(recoveryAlreadyAttempted = false))
		assertFalse(SyncAuthPolicy.canLaunchInteractiveRecovery(recoveryAlreadyAttempted = true))
	}
}
