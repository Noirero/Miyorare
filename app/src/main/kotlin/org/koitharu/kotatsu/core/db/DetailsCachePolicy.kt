package org.koitharu.kotatsu.core.db

import java.util.concurrent.TimeUnit

/**
 * Retains a small durable window of successfully loaded Details snapshots even when the title has
 * not been added to Favourites or History yet. This makes Explore/Extension -> Details reopen from
 * Room instead of falling back to a cold source load after unrelated cache maintenance.
 *
 * User-owned data remains pinned by Favourites/History/Bookmarks/etc. independently of this window.
 */
object DetailsCachePolicy {

	val RECENT_DETAILS_RETENTION_MS: Long = TimeUnit.DAYS.toMillis(7)

	fun recentDetailsCutoff(now: Long = System.currentTimeMillis()): Long =
		now - RECENT_DETAILS_RETENTION_MS
}
