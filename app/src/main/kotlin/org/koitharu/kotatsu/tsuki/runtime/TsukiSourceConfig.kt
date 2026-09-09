package org.koitharu.kotatsu.tsuki.runtime

import android.content.Context
import okhttp3.HttpUrl
import tsuki.config.ConfigKey
import tsuki.config.MangaSourceConfig
import tsuki.model.MangaSource

/** Preferences are namespaced by provider/plugin/source and never share Mihon/Kotatsu keys. */
internal class TsukiSourceConfig(
	context: Context,
	pluginStorageKey: String,
	private val source: MangaSource,
) : MangaSourceConfig {

	private val prefs = context.getSharedPreferences(
		"tsuki_${sanitize(pluginStorageKey)}_${sanitize(source.name)}",
		Context.MODE_PRIVATE,
	)

	@Suppress("UNCHECKED_CAST")
	override fun <T> get(key: ConfigKey<T>): T = when (key) {
		is ConfigKey.Domain -> prefs.getString(key.key, key.defaultValue)
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

	private fun isValidDomain(value: String): Boolean = runCatching {
		require(value.isNotBlank())
		val parts = value.split(':')
		require(parts.size <= 2)
		HttpUrl.Builder().apply {
			host(parts.first())
			if (parts.size == 2) port(parts[1].toInt())
		}.build()
	}.isSuccess

	private companion object {
		fun sanitize(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")
	}
}
