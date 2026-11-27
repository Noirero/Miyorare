package org.koitharu.kotatsu.favourites.domain

import android.content.SharedPreferences
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/** Startup is independent of the resume preference; the first ON_START is part of startup. */
internal class NormalDownloadScanLifecycleObserver(
	private val prefs: SharedPreferences,
	private val startup: () -> Unit,
	private val resume: () -> Unit,
) : DefaultLifecycleObserver {
	private var firstStart = true

	override fun onCreate(owner: LifecycleOwner) { startup() }

	override fun onStart(owner: LifecycleOwner) {
		if (firstStart) {
			firstStart = false
			return
		}
		if (prefs.getBoolean(NormalDownloadScanCoordinator.KEY_SCAN_ON_RESUME, true)) resume()
	}
}
