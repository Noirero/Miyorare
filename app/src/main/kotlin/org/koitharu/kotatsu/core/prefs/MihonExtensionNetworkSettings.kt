package org.koitharu.kotatsu.core.prefs

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * User-facing connection timeout policy for Mihon-compatible extensions.
 *
 * This deliberately does not affect native Kotatsu/Miyorare parsers. The extension HTTP client
 * reads the effective value at the start of every request, so a preference change applies to the
 * next request without rebuilding the client or restarting the app.
 */
object MihonExtensionNetworkSettings {

	const val KEY_CONNECT_TIMEOUT_MODE = "mihon_extension_connect_timeout_mode"
	const val KEY_CUSTOM_CONNECT_TIMEOUT_SECONDS = "mihon_extension_custom_connect_timeout_seconds"

	const val MODE_AGGRESSIVE = "aggressive"
	const val MODE_STANDARD = "standard"
	const val MODE_TOLERANT = "tolerant"
	const val MODE_CUSTOM = "custom"

	const val MIN_SECONDS = 5
	const val MAX_SECONDS = 60
	const val AGGRESSIVE_SECONDS = 15
	const val DEFAULT_SECONDS = 30
	const val TOLERANT_SECONDS = 45

	fun normalizeMode(mode: String?): String = when (mode) {
		MODE_AGGRESSIVE, MODE_STANDARD, MODE_TOLERANT, MODE_CUSTOM -> mode
		else -> MODE_STANDARD
	}

	fun resolveSeconds(mode: String?, customSeconds: String?): Int = when (normalizeMode(mode)) {
		MODE_AGGRESSIVE -> AGGRESSIVE_SECONDS
		MODE_TOLERANT -> TOLERANT_SECONDS
		MODE_CUSTOM -> customSeconds
			?.toIntOrNull()
			?.coerceIn(MIN_SECONDS, MAX_SECONDS)
			?: DEFAULT_SECONDS
		else -> DEFAULT_SECONDS
	}

	fun getConnectTimeoutSeconds(context: Context): Int {
		val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
		return resolveSeconds(
			mode = prefs.getString(KEY_CONNECT_TIMEOUT_MODE, MODE_STANDARD),
			customSeconds = prefs.getString(
				KEY_CUSTOM_CONNECT_TIMEOUT_SECONDS,
				DEFAULT_SECONDS.toString(),
			),
		)
	}
}
