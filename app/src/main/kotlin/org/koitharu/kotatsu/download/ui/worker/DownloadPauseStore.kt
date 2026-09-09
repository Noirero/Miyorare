package org.koitharu.kotatsu.download.ui.worker

import android.content.Context
import java.util.UUID

/**
 * Tiny durable override for a WorkManager download's paused state.
 *
 * DownloadTask carries the initial state, while this store records user changes made after enqueue.
 * Keeping both true and false is intentional: a resumed task that was initially enqueued paused must
 * stay resumed after process death instead of falling back to the stale inputData value.
 */
internal object DownloadPauseStore {

	fun getPaused(context: Context, id: UUID): Boolean? {
		val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
		val key = id.toString()
		return if (prefs.contains(key)) prefs.getBoolean(key, false) else null
	}

	fun setPaused(context: Context, id: UUID, paused: Boolean) {
		context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
			.edit()
			.putBoolean(id.toString(), paused)
			.commit()
	}

	fun clear(context: Context, id: UUID) {
		context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
			.edit()
			.remove(id.toString())
			.apply()
	}

	fun clearAll(context: Context) {
		context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
			.edit()
			.clear()
			.apply()
	}

	private const val PREFS = "download_pause_state"
}
