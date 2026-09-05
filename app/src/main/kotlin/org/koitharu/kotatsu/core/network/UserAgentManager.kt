package org.koitharu.kotatsu.core.network

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import org.koitharu.kotatsu.core.prefs.AppSettings
import javax.inject.Inject
import javax.inject.Singleton

enum class UserAgentMode(val storageValue: String) {
	DEFAULT("default"),
	CUSTOM("custom"),
	RANDOM("random");

	companion object {
		fun fromStorage(value: String?): UserAgentMode =
			entries.firstOrNull { it.storageValue == value } ?: DEFAULT
	}
}

/**
 * Owns the app-wide default User-Agent override.
 *
 * Random mode intentionally chooses one UA per app process and keeps it stable for the whole
 * session. Changing it for every request breaks cookies/Cloudflare sessions because the UA no
 * longer matches the browser identity that created them.
 *
 * [AppSettings.KEY_MIHON_USER_AGENT] remains mirrored with the effective override for backwards
 * compatibility with code that still reads Mihon's legacy preference directly.
 */
@Singleton
class UserAgentManager @Inject constructor(
	@ApplicationContext context: Context,
) {
	private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

	@Volatile
	private var sessionRandomUserAgent: String

	init {
		val legacyOverride = prefs.getString(AppSettings.KEY_MIHON_USER_AGENT, null).orEmpty().trim()
		val hadStoredMode = prefs.contains(KEY_MODE)
		val initialMode = if (hadStoredMode) {
			UserAgentMode.fromStorage(prefs.getString(KEY_MODE, null))
		} else if (legacyOverride.isNotEmpty()) {
			UserAgentMode.CUSTOM
		} else {
			UserAgentMode.DEFAULT
		}
		val storedCustom = prefs.getString(KEY_CUSTOM, null).orEmpty().trim()
		val custom = when {
			storedCustom.isNotEmpty() -> storedCustom
			!hadStoredMode || initialMode == UserAgentMode.CUSTOM -> legacyOverride
			else -> ""
		}
		val previousRandom = prefs.getString(KEY_RANDOM, null).orEmpty().trim()
		// Starting a new process while Random is active starts a new random-UA session. In every
		// other mode keep the prepared value so switching to Random is instant and predictable.
		sessionRandomUserAgent = if (initialMode == UserAgentMode.RANDOM) {
			pickRandomUserAgent(previousRandom.takeIf { it.isNotEmpty() })
		} else {
			previousRandom.ifEmpty { pickRandomUserAgent(null) }
		}
		persist(
			mode = initialMode,
			custom = custom,
			random = sessionRandomUserAgent,
		)
	}

	val mode: UserAgentMode
		get() = UserAgentMode.fromStorage(prefs.getString(KEY_MODE, null))

	val customUserAgent: String
		get() = prefs.getString(KEY_CUSTOM, null).orEmpty().trim()

	val randomUserAgent: String
		get() = sessionRandomUserAgent

	/** Null means use the device WebView/default UA. Read on every request so changes are live. */
	val effectiveOverride: String?
		get() = when (mode) {
			UserAgentMode.DEFAULT -> null
			UserAgentMode.CUSTOM -> customUserAgent.takeIf { it.isNotEmpty() }
			UserAgentMode.RANDOM -> sessionRandomUserAgent.takeIf { it.isNotEmpty() }
		}

	@Synchronized
	fun apply(
		mode: UserAgentMode,
		customUserAgent: String,
		randomUserAgent: String,
	) {
		val custom = customUserAgent.trim()
		val random = randomUserAgent.trim().ifEmpty {
			pickRandomUserAgent(sessionRandomUserAgent.takeIf { it.isNotEmpty() })
		}
		sessionRandomUserAgent = random
		persist(mode, custom, random)
	}

	companion object {
		private const val KEY_MODE = "user_agent_mode"
		private const val KEY_CUSTOM = "user_agent_custom"
		private const val KEY_RANDOM = "user_agent_random"

		private val RANDOM_USER_AGENTS = listOf(
			"Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36",
			"Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36",
			"Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36",
			"Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36",
			"Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
		)

		fun newRandomUserAgent(exclude: String? = null): String = pickRandomUserAgent(exclude)

		fun describe(userAgent: String): String {
			val chromeVersion = Regex("Chrome/(\\d+)").find(userAgent)?.groupValues?.getOrNull(1)
			return if (chromeVersion != null) "Chrome Android $chromeVersion" else "Mobile browser"
		}

		private fun pickRandomUserAgent(exclude: String?): String {
			val candidates = RANDOM_USER_AGENTS.filterNot { it == exclude }.ifEmpty { RANDOM_USER_AGENTS }
			return candidates.random()
		}
	}

	private fun persist(mode: UserAgentMode, custom: String, random: String) {
		val effective = when (mode) {
			UserAgentMode.DEFAULT -> ""
			UserAgentMode.CUSTOM -> custom
			UserAgentMode.RANDOM -> random
		}
		prefs.edit {
			putString(KEY_MODE, mode.storageValue)
			putString(KEY_CUSTOM, custom)
			putString(KEY_RANDOM, random)
			putString(AppSettings.KEY_MIHON_USER_AGENT, effective)
		}
	}
}
