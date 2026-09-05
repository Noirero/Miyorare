package org.koitharu.kotatsu.explore.data

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koitharu.kotatsu.core.model.MangaSourceInfo
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.lnreader.model.LnMangaSource
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import javax.inject.Inject
import javax.inject.Singleton

enum class ExploreContentFilter {
	ALL,
	SFW,
	NSFW,
}

enum class ExploreContentClass {
	SFW,
	NSFW,
}

data class ExploreContentState(
	val filter: ExploreContentFilter,
	val isNsfwVisible: Boolean,
)

/**
 * Stores the Explore SFW/NSFW filter, NSFW visibility and explicit user overrides.
 *
 * Mihon overrides are keyed by exact source id so a multi-source APK can contain independently
 * classified catalogues. Package-level keys from older Noirero builds are still read as a fallback
 * so existing user choices are not discarded. LNReader plugins use their stable plugin id.
 */
@Singleton
class ExploreContentPreferences @Inject constructor(
	@ApplicationContext context: Context,
) {

	private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
	private val initialNsfwVisible = prefs.getBoolean(KEY_NSFW_VISIBLE, true)
	private val savedFilter = readFilter()
	private val initialContentState = ExploreContentState(
		filter = savedFilter.takeUnless { !initialNsfwVisible && it == ExploreContentFilter.NSFW }
			?: ExploreContentFilter.ALL,
		isNsfwVisible = initialNsfwVisible,
	)
	private val _isNsfwVisible = MutableStateFlow(initialContentState.isNsfwVisible)
	private val _filter = MutableStateFlow(initialContentState.filter)
	private val _contentState = MutableStateFlow(initialContentState)
	private val _overrides = MutableStateFlow(readOverrides())

	val filter: StateFlow<ExploreContentFilter> = _filter.asStateFlow()
	val isNsfwVisible: StateFlow<Boolean> = _isNsfwVisible.asStateFlow()
	val contentState: StateFlow<ExploreContentState> = _contentState.asStateFlow()
	val overrides: StateFlow<Map<String, ExploreContentClass>> = _overrides.asStateFlow()

	init {
		if (savedFilter != initialContentState.filter) {
			prefs.edit { putString(KEY_FILTER, initialContentState.filter.name) }
		}
	}

	fun setFilter(value: ExploreContentFilter) {
		val current = _contentState.value
		val safeValue = if (!current.isNsfwVisible && value == ExploreContentFilter.NSFW) {
			ExploreContentFilter.ALL
		} else {
			value
		}
		if (current.filter == safeValue) return
		prefs.edit { putString(KEY_FILTER, safeValue.name) }
		_filter.value = safeValue
		_contentState.value = current.copy(filter = safeValue)
	}

	fun setNsfwVisible(value: Boolean) {
		val current = _contentState.value
		if (current.isNsfwVisible == value) return
		val nextFilter = if (!value && current.filter == ExploreContentFilter.NSFW) {
			ExploreContentFilter.ALL
		} else {
			current.filter
		}
		prefs.edit {
			putBoolean(KEY_NSFW_VISIBLE, value)
			if (nextFilter != current.filter) {
				putString(KEY_FILTER, nextFilter.name)
			}
		}
		if (_filter.value != nextFilter) {
			_filter.value = nextFilter
		}
		_isNsfwVisible.value = value
		_contentState.value = ExploreContentState(nextFilter, value)
	}

	fun classify(
		source: MangaSource,
		overrides: Map<String, ExploreContentClass> = _overrides.value,
	): ExploreContentClass {
		return overrides[classificationKey(source)]
			?: legacyClassificationKey(source)?.let(overrides::get)
			?: if (source.isNsfw()) ExploreContentClass.NSFW else ExploreContentClass.SFW
	}

	fun hasOverride(source: MangaSource): Boolean {
		val values = _overrides.value
		return classificationKey(source) in values || legacyClassificationKey(source)?.let { it in values } == true
	}

	fun setOverride(sources: Collection<MangaSource>, value: ExploreContentClass?) {
		if (sources.isEmpty()) return
		val updated = _overrides.value.toMutableMap()
		for (source in sources) {
			val key = classificationKey(source)
			if (value == null) {
				updated.remove(key)
			} else {
				updated[key] = value
			}
		}
		persistOverrides(updated)
	}

	fun resetAll(): Map<String, ExploreContentClass> {
		val before = _overrides.value
		if (before.isEmpty()) return before
		prefs.edit { remove(KEY_OVERRIDES) }
		_overrides.value = emptyMap()
		return before
	}

	fun restore(overrides: Map<String, ExploreContentClass>) {
		persistOverrides(overrides)
	}

	private fun persistOverrides(values: Map<String, ExploreContentClass>) {
		val snapshot = values.toMap()
		prefs.edit {
			if (snapshot.isEmpty()) {
				remove(KEY_OVERRIDES)
			} else {
				putStringSet(
					KEY_OVERRIDES,
					snapshot.mapTo(LinkedHashSet()) { (key, value) -> "$key$SEPARATOR${value.name}" },
				)
			}
		}
		_overrides.value = snapshot
	}

	private fun readFilter(): ExploreContentFilter {
		return prefs.getString(KEY_FILTER, null)
			?.let { raw -> ExploreContentFilter.entries.firstOrNull { it.name == raw } }
			?: ExploreContentFilter.ALL
	}

	private fun readOverrides(): Map<String, ExploreContentClass> {
		return prefs.getStringSet(KEY_OVERRIDES, emptySet()).orEmpty().mapNotNull { entry ->
			val separator = entry.lastIndexOf(SEPARATOR)
			if (separator <= 0) return@mapNotNull null
			val key = entry.substring(0, separator)
			val value = entry.substring(separator + SEPARATOR.length)
				.let { raw -> ExploreContentClass.entries.firstOrNull { it.name == raw } }
				?: return@mapNotNull null
			key to value
		}.toMap()
	}

	private fun classificationKey(source: MangaSource): String = when (source) {
		is MangaSourceInfo -> classificationKey(source.mangaSource)
		is MihonMangaSource -> "mihon:${source.sourceId}"
		is LnMangaSource -> "ln:${source.pluginId}"
		else -> "source:${source.name}"
	}

	/** Package-scoped key used by older builds before Mihon source ids became first-class. */
	private fun legacyClassificationKey(source: MangaSource): String? = when (source) {
		is MangaSourceInfo -> legacyClassificationKey(source.mangaSource)
		is MihonMangaSource -> "mihon:${source.pkgName}"
		else -> null
	}

	private companion object {
		const val KEY_FILTER = "explore_content_filter"
		const val KEY_NSFW_VISIBLE = "explore_nsfw_visible"
		const val KEY_OVERRIDES = "explore_content_overrides"
		const val SEPARATOR = "|"
	}
}