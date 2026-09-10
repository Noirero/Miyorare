package org.koitharu.kotatsu.download.ui.worker

import android.content.Context
import androidx.preference.PreferenceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Performance knobs for the downloader. Values are stored as strings because the Compose settings
 * helpers use List/slider-backed string preferences, while workers consume clamped integers.
 */
@Singleton
class DownloadPerformanceSettings @Inject constructor(
	@ApplicationContext context: Context,
) {
	private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

	val parallelPageLimit: Int
		get() = prefs.getString(KEY_PARALLEL_PAGES, DEFAULT_PARALLEL_PAGES.toString())
			?.toIntOrNull()
			?.coerceIn(MIN_PARALLEL_PAGES, MAX_PARALLEL_PAGES)
			?: DEFAULT_PARALLEL_PAGES

	val parallelSourceLimit: Int
		get() = prefs.getString(KEY_PARALLEL_SOURCES, DEFAULT_PARALLEL_SOURCES.toString())
			?.toIntOrNull()
			?.coerceIn(MIN_PARALLEL_SOURCES, MAX_PARALLEL_SOURCES)
			?: DEFAULT_PARALLEL_SOURCES

	companion object {
		const val KEY_PARALLEL_PAGES = "downloads_parallel_pages"
		const val KEY_PARALLEL_SOURCES = "downloads_parallel_sources"

		const val MIN_PARALLEL_PAGES = 1
		const val MAX_PARALLEL_PAGES = 20
		// Conservative defaults keep enough parallelism to saturate normal connections without making
		// several simultaneous workers fan out into dozens of requests. Explicit user choices still win.
		const val DEFAULT_PARALLEL_PAGES = 8

		const val MIN_PARALLEL_SOURCES = 1
		const val MAX_PARALLEL_SOURCES = 10
		const val DEFAULT_PARALLEL_SOURCES = 3
	}
}
