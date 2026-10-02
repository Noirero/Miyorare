package org.koitharu.kotatsu.sync.library

import android.content.Context
import java.time.Instant

class LibrarySyncStateStore(context: Context) {
	private val prefs = context.getSharedPreferences("library_sync_state", Context.MODE_PRIVATE)

	fun lastSyncAt(service: LibrarySyncServiceId): Instant? =
		prefs.getLong("${service.name}:last_sync", 0L).takeIf { it > 0L }?.let(Instant::ofEpochMilli)

	fun markSynced(service: LibrarySyncServiceId, at: Instant = Instant.now()) {
		prefs.edit().putLong("${service.name}:last_sync", at.toEpochMilli()).apply()
	}

	fun directions(service: LibrarySyncServiceId): Set<LibrarySyncDirection> {
		val raw = prefs.getStringSet("${service.name}:directions", null)
			?: return setOf(LibrarySyncDirection.PULL, LibrarySyncDirection.PUSH)
		return raw.mapNotNullTo(linkedSetOf()) { runCatching { LibrarySyncDirection.valueOf(it) }.getOrNull() }
	}

	fun setDirections(service: LibrarySyncServiceId, value: Set<LibrarySyncDirection>) {
		prefs.edit().putStringSet("${service.name}:directions", value.mapTo(linkedSetOf()) { it.name }).apply()
	}
}
