package org.koitharu.kotatsu.tsuki.runtime

import android.content.Context
import androidx.core.content.edit
import okhttp3.HttpUrl
import tsuki.config.ConfigKey
import tsuki.config.MangaSourceConfig
import tsuki.model.MangaSource

/** Preferences are namespaced by provider/plugin/source and never share Mihon/Kotatsu keys. */
internal class TsukiSourceConfig(
	context: Context,
	pluginStorageKey: String,
	private val source: MangaSource,
	private val transientDomainOverride: String? = null,
) : MangaSourceConfig {

	private val prefs = context.getSharedPreferences(
		storageName(pluginStorageKey, source.name),
		Context.MODE_PRIVATE,
	)

	@Suppress("UNCHECKED_CAST")
	override fun <T> get(key: ConfigKey<T>): T = when (key) {
		is ConfigKey.Domain -> transientDomainOverride
			?.takeIf(::isValidDomain)
			?: prefs.getString(key.key, key.defaultValue)
				?.trim()
				?.takeIf(::isValidDomain)
			?: key.defaultValue

		is ConfigKey.UserAgent -> prefs.getString(key.key, key.defaultValue)
			?.replace('\r', ' ')
			?.replace('\n', ' ')
			?.trim()
			?.takeIf { it.isNotEmpty() }
			?: key.defaultValue

		is ConfigKey.ShowSuspiciousContent -> prefs.getBoolean(key.key, key.defaultValue)
		is ConfigKey.SplitByTranslations -> prefs.getBoolean(key.key, key.defaultValue)
		is ConfigKey.PreferredImageServer -> prefs.getString(key.key, key.defaultValue)
	} as T

	fun getDomainOverride(key: ConfigKey.Domain): String? = prefs.getString(key.key, null)
		?.trim()
		?.takeIf(::isValidDomain)

	fun setDomain(key: ConfigKey.Domain, value: String) {
		require(isValidDomain(value)) { "Invalid source domain: $value" }
		prefs.edit { putString(key.key, value) }
		clearDiagnostic()
	}

	fun resetDomain(key: ConfigKey.Domain) {
		prefs.edit { remove(key.key) }
		clearDiagnostic()
	}

	var autoMirrorEnabled: Boolean
		get() = prefs.getBoolean(KEY_AUTO_MIRROR, true)
		set(value) = prefs.edit { putBoolean(KEY_AUTO_MIRROR, value) }

	var requestProfile: OfficialGlobalRequestProfile
		get() = prefs.getString(KEY_REQUEST_PROFILE, null)
			?.let { value -> OfficialGlobalRequestProfile.entries.firstOrNull { it.name == value } }
			?: OfficialGlobalRequestProfile.AUTO
		set(value) = prefs.edit { putString(KEY_REQUEST_PROFILE, value.name) }

	fun readDiagnostic(): OfficialGlobalDiagnostic? {
		val domain = prefs.getString(KEY_DIAGNOSTIC_DOMAIN, null)?.takeIf { it.isNotBlank() } ?: return null
		val status = prefs.getString(KEY_DIAGNOSTIC_STATUS, null)
			?.let { value -> OfficialGlobalDiagnosticStatus.entries.firstOrNull { it.name == value } }
			?: return null
		val stage = prefs.getString(KEY_DIAGNOSTIC_STAGE, null)
			?.let { value -> OfficialGlobalDiagnosticStage.entries.firstOrNull { it.name == value } }
			?: OfficialGlobalDiagnosticStage.NONE
		val httpStatus = prefs.getInt(KEY_DIAGNOSTIC_HTTP_STATUS, NO_HTTP_STATUS).takeIf { it >= 100 }
		val testedAt = prefs.getLong(KEY_DIAGNOSTIC_TESTED_AT, 0L).takeIf { it > 0L } ?: return null
		return OfficialGlobalDiagnostic(
			domain = domain,
			status = status,
			stage = stage,
			httpStatus = httpStatus,
			testedAt = testedAt,
			message = prefs.getString(KEY_DIAGNOSTIC_MESSAGE, null),
		)
	}

	fun writeDiagnostic(value: OfficialGlobalDiagnostic) {
		prefs.edit {
			putString(KEY_DIAGNOSTIC_DOMAIN, value.domain)
			putString(KEY_DIAGNOSTIC_STATUS, value.status.name)
			putString(KEY_DIAGNOSTIC_STAGE, value.stage.name)
			putInt(KEY_DIAGNOSTIC_HTTP_STATUS, value.httpStatus ?: NO_HTTP_STATUS)
			putLong(KEY_DIAGNOSTIC_TESTED_AT, value.testedAt)
			if (value.message == null) {
				remove(KEY_DIAGNOSTIC_MESSAGE)
			} else {
				putString(KEY_DIAGNOSTIC_MESSAGE, value.message)
			}
		}
	}

	fun clearDiagnostic() {
		prefs.edit {
			remove(KEY_DIAGNOSTIC_DOMAIN)
			remove(KEY_DIAGNOSTIC_STATUS)
			remove(KEY_DIAGNOSTIC_STAGE)
			remove(KEY_DIAGNOSTIC_HTTP_STATUS)
			remove(KEY_DIAGNOSTIC_TESTED_AT)
			remove(KEY_DIAGNOSTIC_MESSAGE)
		}
	}

	private fun isValidDomain(value: String): Boolean = runCatching {
		require(value.isNotBlank())
		val parts = value.split(':')
		require(parts.size <= 2)
		// Calling build() without a scheme rejects every otherwise-valid host. We only need the
		// builder's host/port validation here, matching Miyorare's native SourceSettings behavior.
		HttpUrl.Builder().apply {
			host(parts.first())
			if (parts.size == 2) port(parts[1].toInt())
		}
	}.isSuccess

	private companion object {
		const val KEY_AUTO_MIRROR = "miyorare_global_auto_mirror"
		const val KEY_REQUEST_PROFILE = "miyorare_global_request_profile"
		const val KEY_DIAGNOSTIC_DOMAIN = "miyorare_global_diagnostic_domain"
		const val KEY_DIAGNOSTIC_STATUS = "miyorare_global_diagnostic_status"
		const val KEY_DIAGNOSTIC_STAGE = "miyorare_global_diagnostic_stage"
		const val KEY_DIAGNOSTIC_HTTP_STATUS = "miyorare_global_diagnostic_http_status"
		const val KEY_DIAGNOSTIC_TESTED_AT = "miyorare_global_diagnostic_tested_at"
		const val KEY_DIAGNOSTIC_MESSAGE = "miyorare_global_diagnostic_message"
		const val NO_HTTP_STATUS = -1

		fun sanitize(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")
		fun storageName(pluginStorageKey: String, sourceName: String): String =
			"tsuki_${sanitize(pluginStorageKey)}_${sanitize(sourceName)}"
	}
}