package org.koitharu.kotatsu.sync.domain

import org.koitharu.kotatsu.sync.data.DriveAuthMode

/** Small, pure policy helpers shared by foreground and background sync auth state. */
internal object SyncAuthPolicy {

	fun hasAccountSession(
		mode: DriveAuthMode?,
		hasAccountMetadata: Boolean,
		hasGoogleAccount: Boolean,
		hasBrowserSession: Boolean,
	): Boolean = when (mode) {
		DriveAuthMode.GMS -> hasAccountMetadata && hasGoogleAccount
		DriveAuthMode.BROWSER_TEMPORARY -> hasAccountMetadata && hasBrowserSession
		null -> false
	}

	fun isBackgroundReady(
		mode: DriveAuthMode?,
		hasAccountMetadata: Boolean,
		hasGoogleAccount: Boolean,
		authorizationRequired: Boolean = false,
	): Boolean = mode == DriveAuthMode.GMS &&
		hasAccountMetadata &&
		hasGoogleAccount &&
		!authorizationRequired

	fun canLaunchInteractiveRecovery(recoveryAlreadyAttempted: Boolean): Boolean =
		!recoveryAlreadyAttempted
}
