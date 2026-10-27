package org.koitharu.kotatsu.alternatives.domain

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Small, migration-only preference authority mirroring Yokai's remembered pre-migration setup. */
@Singleton
class MigrationSessionPreferences @Inject constructor(
	@ApplicationContext context: Context,
) {
	private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
	private val json = Json { ignoreUnknownKeys = true }

	var selectedSourceNames: List<String>
		get() = prefs.getString(KEY_SOURCES, null)
			?.let { raw -> runCatching { json.decodeFromString<List<String>>(raw) }.getOrNull() }
			.orEmpty()
		set(value) = prefs.edit { putString(KEY_SOURCES, json.encodeToString(value.distinct())) }

	var sourceStrategy: MigrationSourceStrategy
		get() = prefs.getString(KEY_STRATEGY, null)
			?.let { raw -> MigrationSourceStrategy.entries.find { it.name == raw } }
			?: MigrationSourceStrategy.FIRST_MATCH
		set(value) = prefs.edit { putString(KEY_STRATEGY, value.name) }

	var extraSearchParams: String?
		get() = prefs.getString(KEY_EXTRA_SEARCH, null)?.trim()?.takeIf(String::isNotEmpty)
		set(value) = prefs.edit { putString(KEY_EXTRA_SEARCH, value?.trim()?.takeIf(String::isNotEmpty)) }

	var skipSetup: Boolean
		get() = prefs.getBoolean(KEY_SKIP_SETUP, false)
		set(value) = prefs.edit { putBoolean(KEY_SKIP_SETUP, value) }

	fun save(
		sources: List<String>,
		strategy: MigrationSourceStrategy,
		extraSearchParams: String?,
		skipSetup: Boolean,
	) {
		prefs.edit {
			putString(KEY_SOURCES, json.encodeToString(sources.distinct()))
			putString(KEY_STRATEGY, strategy.name)
			putString(KEY_EXTRA_SEARCH, extraSearchParams?.trim()?.takeIf(String::isNotEmpty))
			putBoolean(KEY_SKIP_SETUP, skipSetup)
		}
	}

	private companion object {
		const val KEY_SOURCES = "migration_yokai_sources"
		const val KEY_STRATEGY = "migration_yokai_strategy"
		const val KEY_EXTRA_SEARCH = "migration_yokai_extra_search"
		const val KEY_SKIP_SETUP = "migration_yokai_skip_setup"
	}
}
