package org.koitharu.kotatsu.core.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager

/**
 * Live network policy for Mihon-compatible extensions.
 *
 * Native Miyorare/Kotatsu parsers are intentionally excluded. The extension client resolves a
 * policy at the start of every request, so profile changes affect the next request without an app
 * restart and without interrupting a request that is already in flight.
 */
object MihonExtensionNetworkSettings {

	const val KEY_PROFILE = "mihon_extension_network_profile"
	const val KEY_CUSTOM_CONNECT_TIMEOUT_SECONDS = "mihon_extension_custom_connect_timeout_seconds"
	const val KEY_CUSTOM_RETRY_COUNT = "mihon_extension_custom_retry_count"
	const val KEY_CUSTOM_RETRY_DELAY_SECONDS = "mihon_extension_custom_retry_delay_seconds"
	const val KEY_CUSTOM_BACKOFF_ENABLED = "mihon_extension_custom_backoff_enabled"
	const val KEY_CUSTOM_MAX_BACKOFF_SECONDS = "mihon_extension_custom_max_backoff_seconds"

	// Kept for a compatibility read from the short-lived timeout-only setting shipped on beta.
	const val KEY_CONNECT_TIMEOUT_MODE = "mihon_extension_connect_timeout_mode"

	private const val KEY_HOST_PROFILE_PREFIX = "mihon_extension_host_profile_"

	const val PROFILE_ADAPTIVE = "adaptive"
	const val PROFILE_STANDARD = "standard"
	const val PROFILE_AGGRESSIVE = "aggressive"
	const val PROFILE_CUSTOM = "custom"

	// Legacy timeout-only mode names.
	const val MODE_AGGRESSIVE = "aggressive"
	const val MODE_STANDARD = "standard"
	const val MODE_TOLERANT = "tolerant"
	const val MODE_CUSTOM = "custom"

	const val MIN_CONNECT_TIMEOUT_SECONDS = 5
	const val MAX_CONNECT_TIMEOUT_SECONDS = 60
	const val MIN_RETRY_COUNT = 0
	const val MAX_RETRY_COUNT = 3
	const val MIN_RETRY_DELAY_SECONDS = 0
	const val MAX_RETRY_DELAY_SECONDS = 5
	const val MIN_MAX_BACKOFF_SECONDS = 5
	const val MAX_MAX_BACKOFF_SECONDS = 60

	const val AGGRESSIVE_CONNECT_TIMEOUT_SECONDS = 15
	const val STANDARD_CONNECT_TIMEOUT_SECONDS = 30
	const val ADAPTIVE_CONNECT_TIMEOUT_SECONDS = 30
	const val ADAPTIVE_RETRY_COUNT = 1
	const val ADAPTIVE_RETRY_DELAY_SECONDS = 1
	const val ADAPTIVE_MAX_BACKOFF_SECONDS = 30

	// Compatibility aliases for code/tests that still refer to the timeout-only constants.
	const val MIN_SECONDS = MIN_CONNECT_TIMEOUT_SECONDS
	const val MAX_SECONDS = MAX_CONNECT_TIMEOUT_SECONDS
	const val AGGRESSIVE_SECONDS = AGGRESSIVE_CONNECT_TIMEOUT_SECONDS
	const val DEFAULT_SECONDS = STANDARD_CONNECT_TIMEOUT_SECONDS
	const val TOLERANT_SECONDS = 45

	data class Policy(
		val profile: String,
		val connectTimeoutSeconds: Int,
		/** Extra host-level retries added by Miyorare. OkHttp/Mihon keeps its own built-in behavior. */
		val retryCount: Int,
		val retryDelayMillis: Long,
		val backoffEnabled: Boolean,
		val maxBackoffMillis: Long,
		/** True only for the adaptive preset: keeps a small transient-error state per host. */
		val adaptive: Boolean,
	)

	fun normalizeProfile(profile: String?): String = when (profile) {
		PROFILE_ADAPTIVE, PROFILE_STANDARD, PROFILE_AGGRESSIVE, PROFILE_CUSTOM -> profile
		else -> PROFILE_ADAPTIVE
	}

	fun getGlobalProfile(context: Context): String = resolveGlobalProfile(preferences(context))

	fun resolvePolicy(context: Context, host: String? = null): Policy {
		val prefs = preferences(context)
		val globalProfile = resolveGlobalProfile(prefs)
		val hostProfile = host
			?.let(::normalizeHost)
			?.takeIf { it.isNotEmpty() }
			?.let { prefs.getString(hostProfileKey(it), null) }
			?.let(::normalizeHostOverride)
		val profile = hostProfile ?: globalProfile
		return policyFor(profile, prefs)
	}

	fun getHostOverrides(context: Context): Map<String, String> {
		val prefs = preferences(context)
		return prefs.all.asSequence()
			.filter { (key, _) -> key.startsWith(KEY_HOST_PROFILE_PREFIX) }
			.mapNotNull { (key, value) ->
				val host = key.removePrefix(KEY_HOST_PROFILE_PREFIX)
				val profile = normalizeHostOverride(value as? String) ?: return@mapNotNull null
				host to profile
			}
			.sortedBy { it.first }
			.toMap(linkedMapOf())
	}

	fun setHostProfileOverride(context: Context, host: String, profile: String?) {
		val normalizedHost = normalizeHost(host)
		if (normalizedHost.isEmpty()) return
		val prefs = preferences(context)
		val normalizedProfile = normalizeHostOverride(profile)
		prefs.edit {
			if (normalizedProfile == null) {
				remove(hostProfileKey(normalizedHost))
			} else {
				putString(hostProfileKey(normalizedHost), normalizedProfile)
			}
		}
	}

	private fun resolveGlobalProfile(prefs: SharedPreferences): String {
		prefs.getString(KEY_PROFILE, null)?.let { return normalizeProfile(it) }
		// Preserve an explicit choice made in the timeout-only beta UI. If that key never existed,
		// the new recommended default is Adaptive.
		return when (prefs.getString(KEY_CONNECT_TIMEOUT_MODE, null)) {
			MODE_AGGRESSIVE -> PROFILE_AGGRESSIVE
			MODE_STANDARD -> PROFILE_STANDARD
			MODE_TOLERANT, MODE_CUSTOM -> PROFILE_CUSTOM
			else -> PROFILE_ADAPTIVE
		}
	}

	private fun policyFor(profile: String, prefs: SharedPreferences): Policy = when (profile) {
		PROFILE_STANDARD -> Policy(
			profile = PROFILE_STANDARD,
			connectTimeoutSeconds = STANDARD_CONNECT_TIMEOUT_SECONDS,
			retryCount = 0,
			retryDelayMillis = 0L,
			backoffEnabled = false,
			maxBackoffMillis = 0L,
			adaptive = false,
		)

		PROFILE_AGGRESSIVE -> Policy(
			profile = PROFILE_AGGRESSIVE,
			connectTimeoutSeconds = AGGRESSIVE_CONNECT_TIMEOUT_SECONDS,
			retryCount = 0,
			retryDelayMillis = 0L,
			backoffEnabled = false,
			maxBackoffMillis = 0L,
			adaptive = false,
		)

		PROFILE_CUSTOM -> {
			val legacyTolerant = prefs.getString(KEY_PROFILE, null) == null &&
				prefs.getString(KEY_CONNECT_TIMEOUT_MODE, null) == MODE_TOLERANT
			val connectTimeout = prefs.getString(KEY_CUSTOM_CONNECT_TIMEOUT_SECONDS, null)
				?.toIntOrNull()
				?: if (legacyTolerant) TOLERANT_SECONDS else STANDARD_CONNECT_TIMEOUT_SECONDS
			val retryCount = prefs.getString(KEY_CUSTOM_RETRY_COUNT, null)?.toIntOrNull()
				?: ADAPTIVE_RETRY_COUNT
			val retryDelaySeconds = prefs.getString(KEY_CUSTOM_RETRY_DELAY_SECONDS, null)?.toIntOrNull()
				?: ADAPTIVE_RETRY_DELAY_SECONDS
			val backoffEnabled = prefs.getString(KEY_CUSTOM_BACKOFF_ENABLED, null)?.toBooleanStrictOrNull()
				?: true
			val maxBackoffSeconds = prefs.getString(KEY_CUSTOM_MAX_BACKOFF_SECONDS, null)?.toIntOrNull()
				?: ADAPTIVE_MAX_BACKOFF_SECONDS
			Policy(
				profile = PROFILE_CUSTOM,
				connectTimeoutSeconds = connectTimeout.coerceIn(
					MIN_CONNECT_TIMEOUT_SECONDS,
					MAX_CONNECT_TIMEOUT_SECONDS,
				),
				retryCount = retryCount.coerceIn(MIN_RETRY_COUNT, MAX_RETRY_COUNT),
				retryDelayMillis = retryDelaySeconds
					.coerceIn(MIN_RETRY_DELAY_SECONDS, MAX_RETRY_DELAY_SECONDS) * 1_000L,
				backoffEnabled = backoffEnabled,
				maxBackoffMillis = maxBackoffSeconds
					.coerceIn(MIN_MAX_BACKOFF_SECONDS, MAX_MAX_BACKOFF_SECONDS) * 1_000L,
				adaptive = false,
			)
		}

		else -> Policy(
			profile = PROFILE_ADAPTIVE,
			connectTimeoutSeconds = ADAPTIVE_CONNECT_TIMEOUT_SECONDS,
			retryCount = ADAPTIVE_RETRY_COUNT,
			retryDelayMillis = ADAPTIVE_RETRY_DELAY_SECONDS * 1_000L,
			backoffEnabled = true,
			maxBackoffMillis = ADAPTIVE_MAX_BACKOFF_SECONDS * 1_000L,
			adaptive = true,
		)
	}

	private fun normalizeHostOverride(profile: String?): String? = when (profile) {
		PROFILE_ADAPTIVE, PROFILE_STANDARD, PROFILE_AGGRESSIVE, PROFILE_CUSTOM -> profile
		else -> null
	}

	private fun normalizeHost(host: String): String = host.trim().trimEnd('.').lowercase()

	private fun hostProfileKey(host: String) = KEY_HOST_PROFILE_PREFIX + host

	private fun preferences(context: Context): SharedPreferences =
		PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

	// Compatibility helpers for the previous timeout-only implementation.
	fun normalizeMode(mode: String?): String = when (mode) {
		MODE_AGGRESSIVE, MODE_STANDARD, MODE_TOLERANT, MODE_CUSTOM -> mode
		else -> MODE_STANDARD
	}

	fun resolveSeconds(mode: String?, customSeconds: String?): Int = when (normalizeMode(mode)) {
		MODE_AGGRESSIVE -> AGGRESSIVE_SECONDS
		MODE_TOLERANT -> TOLERANT_SECONDS
		MODE_CUSTOM -> customSeconds?.toIntOrNull()?.coerceIn(MIN_SECONDS, MAX_SECONDS) ?: DEFAULT_SECONDS
		else -> DEFAULT_SECONDS
	}

	fun getConnectTimeoutSeconds(context: Context): Int = resolvePolicy(context).connectTimeoutSeconds
}
