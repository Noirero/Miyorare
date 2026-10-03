package org.koitharu.kotatsu.sync.library

import java.time.Instant
import org.json.JSONObject

abstract class TokenLibrarySyncService(
	final override val id: LibrarySyncServiceId,
	protected val secrets: LibrarySyncSecretStore,
	protected val state: LibrarySyncStateStore,
	intervalMs: Long,
) : LibrarySyncService, LibrarySyncCatalog {
	protected val http = LibrarySyncHttp(id, state, intervalMs)

	protected fun session(): JSONObject =
		secrets.get(id, "session")?.let(::JSONObject) ?: throw LibrarySyncAuthException()

	protected fun saveSession(token: String, userId: String, refresh: String? = null) {
		require(token.isNotBlank() && userId.isNotBlank())
		val value =
			JSONObject()
				.put("access_token", token)
				.put("user_id", userId)
				.put("refresh_token", refresh)
		secrets.put(id, "session", value.toString())
		state.setError(id, null)
	}

	override suspend fun logout() {
		secrets.clear(id)
		state.clear(id)
	}

	override suspend fun lastSyncAt(): Instant? = state.lastSyncAt(id)

	override suspend fun connectionStatus(): LibrarySyncConnectionStatus =
		when {
			secrets.get(id, "session") == null -> LibrarySyncConnectionStatus.DISCONNECTED
			state.isSyncing(id) -> LibrarySyncConnectionStatus.SYNCING
			state.error(id) != null -> LibrarySyncConnectionStatus.ERROR
			else -> LibrarySyncConnectionStatus.CONNECTED
		}
}
