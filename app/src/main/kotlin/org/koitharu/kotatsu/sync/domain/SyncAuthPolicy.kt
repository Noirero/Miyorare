package org.koitharu.kotatsu.sync.domain

/** Small, pure policy helpers shared by foreground and background sync auth state. */
internal object SyncAuthPolicy {

	fun isAccountReady(hasAccountMetadata: Boolean, hasGoogleAccount: Boolean): Boolean =
		hasAccountMetadata && hasGoogleAccount

	fun canLaunchInteractiveRecovery(recoveryAlreadyAttempted: Boolean): Boolean =
		!recoveryAlreadyAttempted
}
