package org.koitharu.kotatsu.search.domain

import android.content.Context
import androidx.core.content.edit
import androidx.core.os.ConfigurationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.lnreader.model.LnMangaSource
import org.koitharu.kotatsu.lnreader.model.langCode
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

enum class SearchSourceMode {
	PINNED_ONLY,
	PREFERRED_LANGUAGES,
	ALL_SOURCES,
}

@Singleton
class SearchSourcePreferences @Inject constructor(
	@ApplicationContext private val context: Context,
) {

	private val preferences = context.getSharedPreferences(STORAGE_NAME, Context.MODE_PRIVATE)

	val defaultPreferredLanguages: Set<String>
		get() = linkedSetOf(
			ConfigurationCompat.getLocales(context.resources.configuration).get(0)?.toLanguageTag().orEmpty()
				.normalizedLanguageCode(),
			"en",
			LANGUAGE_OTHER,
		).filterTo(LinkedHashSet()) { it.isNotBlank() }

	var preferredLanguages: Set<String>
		get() = preferences.getStringSet(KEY_LANGUAGES, null)
			?.mapTo(LinkedHashSet()) { it.normalizedLanguageCode() }
			?.filterTo(LinkedHashSet()) { it.isNotBlank() }
			?.ifEmpty { defaultPreferredLanguages }
			?: defaultPreferredLanguages
		set(value) = preferences.edit {
			putStringSet(KEY_LANGUAGES, value.mapTo(LinkedHashSet()) { it.normalizedLanguageCode() })
		}

	var globalMode: SearchSourceMode
		get() = enumValue(KEY_GLOBAL_MODE, SearchSourceMode.ALL_SOURCES)
		set(value) = putEnum(KEY_GLOBAL_MODE, value)

	var alternativeMode: SearchSourceMode
		get() = enumValue(KEY_ALTERNATIVE_MODE, SearchSourceMode.PREFERRED_LANGUAGES)
		set(value) = putEnum(KEY_ALTERNATIVE_MODE, value)

	var globalHasResultsOnly: Boolean
		get() = preferences.getBoolean(KEY_GLOBAL_HAS_RESULTS, true)
		set(value) = preferences.edit { putBoolean(KEY_GLOBAL_HAS_RESULTS, value) }

	var alternativeHasResultsOnly: Boolean
		get() = preferences.getBoolean(KEY_ALTERNATIVE_HAS_RESULTS, true)
		set(value) = preferences.edit { putBoolean(KEY_ALTERNATIVE_HAS_RESULTS, value) }

	var globalFlatView: Boolean
		get() = preferences.getBoolean(KEY_GLOBAL_FLAT_VIEW, false)
		set(value) = preferences.edit { putBoolean(KEY_GLOBAL_FLAT_VIEW, value) }

	var globalHideLibrary: Boolean
		get() = preferences.getBoolean(KEY_GLOBAL_HIDE_LIBRARY, false)
		set(value) = preferences.edit { putBoolean(KEY_GLOBAL_HIDE_LIBRARY, value) }

	fun resetGlobal() {
		preferences.edit {
			putString(KEY_GLOBAL_MODE, SearchSourceMode.ALL_SOURCES.name)
			putStringSet(KEY_LANGUAGES, defaultPreferredLanguages)
			putBoolean(KEY_GLOBAL_HAS_RESULTS, true)
			putBoolean(KEY_GLOBAL_FLAT_VIEW, false)
			putBoolean(KEY_GLOBAL_HIDE_LIBRARY, false)
		}
	}

	fun resetAlternative() {
		preferences.edit {
			putString(KEY_ALTERNATIVE_MODE, SearchSourceMode.PREFERRED_LANGUAGES.name)
			putStringSet(KEY_LANGUAGES, defaultPreferredLanguages)
			putBoolean(KEY_ALTERNATIVE_HAS_RESULTS, true)
		}
	}

	private inline fun <reified T : Enum<T>> enumValue(key: String, default: T): T {
		val raw = preferences.getString(key, null) ?: return default
		return enumValues<T>().firstOrNull { it.name == raw } ?: default
	}

	private fun putEnum(key: String, value: Enum<*>) {
		preferences.edit { putString(key, value.name) }
	}

	private companion object {
		const val STORAGE_NAME = "noirero_search_preferences"
		const val KEY_LANGUAGES = "preferred_languages"
		const val KEY_GLOBAL_MODE = "global_mode"
		const val KEY_ALTERNATIVE_MODE = "alternative_mode"
		const val KEY_GLOBAL_HAS_RESULTS = "global_has_results_only"
		const val KEY_ALTERNATIVE_HAS_RESULTS = "alternative_has_results_only"
		const val KEY_GLOBAL_FLAT_VIEW = "global_flat_view"
		const val KEY_GLOBAL_HIDE_LIBRARY = "global_hide_library"
	}
}

fun MangaSource.searchLanguageCode(): String = when (this) {
	is MihonMangaSource -> language.normalizedLanguageCode().ifBlank { LANGUAGE_OTHER }
	is LnMangaSource -> plugin.langCode.normalizedLanguageCode().ifBlank { LANGUAGE_OTHER }
	LocalMangaSource -> LANGUAGE_LOCAL
	else -> LANGUAGE_OTHER
}

fun MangaSource.matchesPreferredLanguage(preferredLanguages: Set<String>): Boolean {
	val sourceLanguage = searchLanguageCode()
	val sourceBaseLanguage = sourceLanguage.substringBefore('-')
	return preferredLanguages.any { preferred ->
		val normalized = preferred.normalizedLanguageCode()
		normalized == sourceLanguage ||
			(('-' !in normalized || '-' !in sourceLanguage) &&
				normalized.substringBefore('-') == sourceBaseLanguage)
	}
}

fun String.normalizedLanguageCode(): String = trim()
	.lowercase(Locale.ROOT)
	.replace('_', '-')

const val LANGUAGE_OTHER = "other"
const val LANGUAGE_LOCAL = "local"
