package org.koitharu.kotatsu.readerjourney.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide power-save signal shared by Exclusive cosmetic renderers.
 * The application receiver is registered once; individual catalog items only observe this StateFlow.
 */
internal object ExclusivePowerSaveModeRuntime {
	private val mutableState = MutableStateFlow(false)
	val state: StateFlow<Boolean> = mutableState.asStateFlow()

	@Volatile
	private var initialized = false

	@Synchronized
	fun ensureInitialized(context: Context) {
		if (initialized) return
		val appContext = context.applicationContext
		val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
		mutableState.value = powerManager.isPowerSaveMode
		appContext.registerReceiver(
			object : BroadcastReceiver() {
				override fun onReceive(context: Context?, intent: Intent?) {
					mutableState.value = powerManager.isPowerSaveMode
				}
			},
			IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
		)
		initialized = true
	}
}
