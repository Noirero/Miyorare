package org.koitharu.kotatsu.sync.domain

/** Small, pure policy helpers shared by foreground and background sync auth state. */
internal object SyncAuthPolicy {

	fun hasAccountSession(hasAccountMetadata: Boolean, hasGoogleAccount: Boolean): Boolean =
		hasAccountMetadata && hasGoogleAccount

	fun isAccountReady(
		hasAccountMetadata: Boolean,
		hasGoogleAccount: Boolean,
		authorizationRequired: Boolean = false,
	): Boolean = hasAccountSession(hasAccountMetadata, hasGoogleAccount) && !authorizationRequired

	fun canLaunchInteractiveRecovery(recoveryAlreadyAttempted: Boolean): Boolean =
		!recoveryAlreadyAttempted
}
