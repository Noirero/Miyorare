package org.koitharu.kotatsu.sync.library

import java.time.Instant

interface LibrarySyncService {
	val id: LibrarySyncServiceId

	suspend fun login(credentials: LibrarySyncCredentials)

	suspend fun logout()

	suspend fun pullLibrary(): List<SyncEntry>

	suspend fun pushLibrary(entries: List<SyncEntry>)

	suspend fun lastSyncAt(): Instant?

	suspend fun connectionStatus(): LibrarySyncConnectionStatus
}

enum class LibrarySyncServiceId {
	MANGADEX,
	MANGAUPDATES,
	ANILIST,
	KITSU,
	NOVELUPDATES,
	RANOBEDB,
}

enum class LibrarySyncConnectionStatus {
	DISCONNECTED,
	CONNECTED,
	SYNCING,
	ERROR,
	BLOCKED,
}

class LibrarySyncCredentials(
	val accessToken: String? = null,
	val refreshToken: String? = null,
	val username: String? = null,
	val password: String? = null,
	val clientId: String? = null,
)

data class SyncEntry(
	val service: LibrarySyncServiceId,
	val externalId: String,
	val localMangaId: Long?,
	val title: String,
	val progress: Int,
	val status: String?,
	val updatedAt: Instant,
	val remoteEntryId: String? = null,
)

enum class LibrarySyncDirection {
	PULL,
	PUSH,
}
