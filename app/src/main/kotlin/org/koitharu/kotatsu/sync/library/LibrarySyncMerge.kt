package org.koitharu.kotatsu.sync.library

/** Library-sync conflict policy. Newest updatedAt wins; on an exact tie the local entry wins. */
object LibrarySyncMerge {
	fun lastWriteWins(local: List<SyncEntry>, remote: List<SyncEntry>): List<SyncEntry> {
		val merged = LinkedHashMap<Pair<LibrarySyncServiceId, String>, SyncEntry>()
		for (entry in remote + local) {
			val key = entry.service to entry.externalId
			val current = merged[key]
			if (current == null || entry.updatedAt >= current.updatedAt) {
				merged[key] = entry
			}
		}
		return merged.values.toList()
	}
}
