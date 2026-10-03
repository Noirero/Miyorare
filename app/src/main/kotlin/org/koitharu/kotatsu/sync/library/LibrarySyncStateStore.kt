package org.koitharu.kotatsu.sync.library

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibrarySyncStateStore @Inject constructor(@ApplicationContext context: Context) {
	private val syncing = java.util.concurrent.ConcurrentHashMap.newKeySet<LibrarySyncServiceId>()

	fun isSyncing(service: LibrarySyncServiceId) = service in syncing

	fun setSyncing(service: LibrarySyncServiceId, value: Boolean) {
		if (value) syncing.add(service) else syncing.remove(service)
	}

	private val prefs = context.getSharedPreferences("library_sync_state", Context.MODE_PRIVATE)

	fun error(service: LibrarySyncServiceId): String? =
		prefs.getString("${service.name}:error", null)

	fun setError(service: LibrarySyncServiceId, message: String?) {
		prefs.edit().putString("${service.name}:error", message).apply()
	}

	fun clear(service: LibrarySyncServiceId) {
		prefs
			.edit()
			.also { edit ->
				prefs.all.keys.filter { it.startsWith("${service.name}:") }.forEach(edit::remove)
			}
			.apply()
	}

	fun retryAt(service: LibrarySyncServiceId): Long = prefs.getLong("${service.name}:retry_at", 0)

	fun setRetryAt(service: LibrarySyncServiceId, at: Long) {
		prefs.edit().putLong("${service.name}:retry_at", at).apply()
	}

	fun lastSyncAt(service: LibrarySyncServiceId): Instant? =
		prefs
			.getLong("${service.name}:last_sync", 0L)
			.takeIf { it > 0L }
			?.let(Instant::ofEpochMilli)

	fun markSynced(service: LibrarySyncServiceId, at: Instant = Instant.now()) {
		prefs.edit().putLong("${service.name}:last_sync", at.toEpochMilli()).apply()
	}

	fun directions(service: LibrarySyncServiceId): Set<LibrarySyncDirection> {
		val raw =
			prefs.getStringSet("${service.name}:directions", null)
				?: return setOf(LibrarySyncDirection.PULL, LibrarySyncDirection.PUSH)
		return raw.mapNotNullTo(linkedSetOf()) {
			runCatching { LibrarySyncDirection.valueOf(it) }.getOrNull()
		}
	}

	fun setDirections(service: LibrarySyncServiceId, value: Set<LibrarySyncDirection>) {
		prefs
			.edit()
			.putStringSet("${service.name}:directions", value.mapTo(linkedSetOf()) { it.name })
			.apply()
	}
}
