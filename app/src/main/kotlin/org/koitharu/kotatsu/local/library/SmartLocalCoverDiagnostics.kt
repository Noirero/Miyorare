package org.koitharu.kotatsu.local.library

import javax.inject.Inject
import javax.inject.Singleton

/** Bounded process-local measurements. Never retains source paths, URIs, titles or payloads. */
@Singleton
class SmartLocalCoverDiagnostics @Inject constructor() {
    enum class Reason {
        CACHE_HIT, MISS_ABSENT, MISS_FINGERPRINT, MISS_CORRUPT, NOT_CACHEABLE,
        EVICT_BYTES, EVICT_ENTRY_COUNT, EVICT_IDLE, GENERATED, PUBLISHED, CLEAR, CLEAR_FAILED,
        SOURCE_OPEN, PDF_DESCRIPTOR_OPEN, PDF_RENDERER_OPEN, PDF_RENDER, ENCODE,
        ARCHIVE_INDEXED, ARCHIVE_STREAMING, GENERATION_FAILED, CANCELLED, PUBLICATION_FAILED, CLEAR_REJECTED,
    }

    data class Event(
        val reason: Reason,
        val cacheKey: String? = null,
        val sourceKind: String? = null,
        val candidateIndex: Int? = null,
        val entryBytes: Long = 0,
        val totalBytes: Long = 0,
        val entryCount: Int = 0,
        val elapsedNanos: Long = 0,
        val sourceOpened: Boolean = false,
        val fingerprintMismatch: Boolean = false,
    )

    data class Snapshot(val counts: Map<Reason, Long>, val recent: List<Event>) {
        fun count(reason: Reason): Long = counts[reason] ?: 0
    }

    private val counts = LongArray(Reason.entries.size)
    private val recent = ArrayDeque<Event>()

    @Synchronized internal fun record(event: Event) {
        counts[event.reason.ordinal]++
        if (recent.size == MAX_EVENTS) recent.removeFirst()
        recent.addLast(event)
    }

    @Synchronized fun snapshot(): Snapshot = Snapshot(
        Reason.entries.associateWith { counts[it.ordinal] }, recent.toList(),
    )

    /** Reset the measurement window, independently of either cache or library data. */
    @Synchronized fun reset() { counts.fill(0); recent.clear() }

    private companion object { const val MAX_EVENTS = 256 }
}

data class SmartLocalCoverCacheStats(
    val bytes: Long,
    val entries: Int,
    val medianEntryBytes: Long,
    val p90EntryBytes: Long,
    val p95EntryBytes: Long,
    val maxBytes: Long,
    val maxEntries: Int,
) {
    /** Capacity estimate, not a claim about a collection that has not been measured. */
    fun projectedP95Bytes(titles: Int): Long = p95EntryBytes * titles.coerceAtLeast(0)
}

data class SmartLocalCoverCacheReport(
    val storage: SmartLocalCoverCacheStats,
    val diagnostics: SmartLocalCoverDiagnostics.Snapshot,
)
