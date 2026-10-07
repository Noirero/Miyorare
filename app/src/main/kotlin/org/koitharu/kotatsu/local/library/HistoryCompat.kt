package org.koitharu.kotatsu.local.library

import org.koitharu.kotatsu.history.data.HistoryEntity

/**
 * Batch 12 compatibility bridge for the current Amain2 history schema.
 * HistoryEntity.updatedAt is the timestamp updated by reader progress writes.
 */
internal val HistoryEntity.lastReaderActivityAt: Long
	get() = updatedAt
