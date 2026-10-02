package org.koitharu.kotatsu.sync.library

import java.time.Instant

class LibrarySyncBlockedService(
	override val id: LibrarySyncServiceId,
	val reason: String,
) : LibrarySyncService {
	override suspend fun login(credentials: LibrarySyncCredentials) = blocked()

	override suspend fun logout() = Unit

	override suspend fun pullLibrary(): List<SyncEntry> = blocked()

	override suspend fun pushLibrary(entries: List<SyncEntry>) = blocked()

	override suspend fun lastSyncAt(): Instant? = null

	override suspend fun connectionStatus() = LibrarySyncConnectionStatus.BLOCKED

	private fun blocked(): Nothing = throw UnsupportedOperationException(reason)
}
