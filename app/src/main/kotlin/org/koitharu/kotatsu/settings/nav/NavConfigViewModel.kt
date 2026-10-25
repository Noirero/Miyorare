package org.koitharu.kotatsu.settings.nav

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.NavItem
import org.koitharu.kotatsu.core.prefs.MainNavigationPreferences
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.ui.util.ActivityRecreationHandle
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.main.ui.MainActivity
import org.koitharu.kotatsu.parsers.util.move
import org.koitharu.kotatsu.settings.nav.model.NavItemConfigModel
import javax.inject.Inject

@HiltViewModel
class NavConfigViewModel @Inject constructor(
	private val settings: AppSettings,
	private val activityRecreationHandle: ActivityRecreationHandle,
) : BaseViewModel() {

	private val items = MutableStateFlow(
		settings.mainNavItems.filterNot { it in MainNavigationPreferences.fixed },
	)
	val content: StateFlow<List<ListModel>> = items.map { snapshot ->
		snapshot.map { NavItemConfigModel(it, getUnavailabilityHint(it)) }
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.WhileSubscribed(5000), emptyList())

	private var commitJob: Job? = null

	val availableItems
		get() = NavItem.entries.filter { it !in MainNavigationPreferences.fixed && it !in items.value && it.isAvailable(settings) }

	fun reorder(fromPos: Int, toPos: Int) {
		if (fromPos !in items.value.indices || toPos !in items.value.indices) return
		items.value = items.value.toMutableList().apply { move(fromPos, toPos); commit(this) }
	}

	fun replaceItem(old: NavItem, replacement: NavItem) {
		if (replacement !in availableItems) return
		val index = items.value.indexOf(old)
		if (index < 0) return
		items.value = items.value.toMutableList().apply { set(index, replacement); commit(this) }
	}

	private fun commit(value: List<NavItem>) {
		settings.mainNavItems = value
		val prevJob = commitJob
		commitJob = launchJob {
			prevJob?.cancelAndJoin()
			delay(500)
			activityRecreationHandle.recreate(MainActivity::class.java)
		}
	}

	private fun getUnavailabilityHint(item: NavItem) = if (item.isAvailable(settings)) {
		0
	} else when (item) {
		NavItem.FEED -> R.string.check_for_new_chapters_disabled
		NavItem.SUGGESTIONS -> R.string.suggestions_unavailable_text
		else -> 0
	}
}
