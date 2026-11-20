package org.koitharu.kotatsu.settings.search

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.list.ui.model.ListModel
import java.text.Normalizer
import javax.inject.Inject

@HiltViewModel
class SettingsSearchViewModel @Inject constructor(
	private val searchHelper: SettingsSearchHelper,
) : BaseViewModel() {

	private val query = MutableStateFlow<String?>(null)
	private val allSettings by lazy {
		searchHelper.inflatePreferences()
	}

	val content = query.map { q ->
		if (q == null) {
			emptyList()
		} else {
			val normalizedQuery = q.normalizeSearchText()
			if (normalizedQuery.isBlank()) {
				buildBrowseList()
			} else {
				val tokens = normalizedQuery.split(' ').filter { it.isNotBlank() }
				allSettings.asSequence()
					.mapNotNull { item ->
						item.matchScore(normalizedQuery, tokens)?.let { score -> item to score }
					}
					.sortedWith(
						compareBy<Pair<SettingsItem, Int>> { it.second }
							.thenBy { it.first.breadcrumbs.joinToString(" > ") }
							.thenBy { it.first.title.toString() },
					)
					.map { it.first }
					.toList()
			}
		}
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, emptyList())

	val isSearchActive = query.map {
		it != null
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, query.value != null)

	val onNavigateToPreference = MutableEventFlow<SettingsItem>()
	val currentQuery: String
		get() = query.value.orEmpty()

	fun onQueryChanged(value: String) {
		if (query.value != null) {
			query.value = value
		}
	}

	fun discardSearch() {
		query.value = null
	}

	fun startSearch() {
		query.value = query.value.orEmpty()
	}

	fun navigateToPreference(item: SettingsItem) {
		discardSearch()
		onNavigateToPreference.call(item)
	}

	private fun buildBrowseList(): List<ListModel> {
		val result = ArrayList<ListModel>(allSettings.size + 16)
		var currentSection: String? = null
		for (item in allSettings) {
			val section = item.breadcrumbs.firstOrNull().orEmpty()
			if (section != currentSection) {
				currentSection = section
				if (section.isNotEmpty()) {
					result.add(ListHeader(section))
				}
			}
			result.add(item)
		}
		return result
	}

	private fun SettingsItem.matchScore(query: String, tokens: List<String>): Int? {
		val normalizedTitle = title.toString().normalizeSearchText()
		val normalizedText = searchText.normalizeSearchText()
		return when {
			normalizedTitle == query -> 0
			normalizedTitle.startsWith(query) -> 1
			normalizedText.startsWith(query) -> 2
			tokens.all { normalizedTitle.contains(it) } -> 3
			tokens.all { normalizedText.contains(it) } -> 4
			else -> null
		}
	}

	private fun String.normalizeSearchText(): String {
		val decomposed = Normalizer.normalize(this, Normalizer.Form.NFD)
		return buildString(decomposed.length) {
			var lastWasSpace = true
			decomposed.forEach { char ->
				when {
					Character.getType(char) == Character.NON_SPACING_MARK.toInt() -> Unit
					char.isLetterOrDigit() -> {
						append(char.lowercaseChar())
						lastWasSpace = false
					}
					!lastWasSpace -> {
						append(' ')
						lastWasSpace = true
					}
				}
			}
		}.trim()
	}
}
