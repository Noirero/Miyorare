package org.koitharu.kotatsu.stats.ui

import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.merge
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.FavouriteCategory
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.ui.util.ReversibleAction
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.stats.data.StatsRepository
import org.koitharu.kotatsu.stats.domain.ReadingStats
import org.koitharu.kotatsu.stats.domain.StatsPeriod
import javax.inject.Inject

@HiltViewModel
class StatsViewModel @Inject constructor(
	private val repository: StatsRepository,
	favouritesRepository: FavouritesRepository,
) : BaseViewModel() {

	val period = MutableStateFlow(StatsPeriod.ALL)
	val selectedCategories = MutableStateFlow<Set<Long>>(emptySet())
	val onActionDone = MutableEventFlow<ReversibleAction>()
	val favoriteCategories = favouritesRepository.observeCategories()

	val stats = MutableStateFlow(ReadingStats())

	private val membershipChanges = merge(
		favouritesRepository.observeFavouritesChanges(FavouriteSpace.NORMAL),
		favouritesRepository.observeFavouritesChanges(FavouriteSpace.PRIVATE),
	)

	init {
		launchJob(Dispatchers.Default) {
			combine(period, selectedCategories, membershipChanges) { p, categories, _ ->
				p to categories
			}.collectLatest { (p, categories) ->
				// Global Stats queries already exclude Private-only rows. Re-running them on membership
				// changes prevents a title/aggregate that was visible as Normal from remaining in a stale
				// in-memory snapshot after it is moved into the Private vault.
				stats.value = withLoading { repository.getStatsSnapshot(p, categories) }
			}
		}
	}

	fun toggleCategory(category: FavouriteCategory) {
		val snapshot = selectedCategories.value
		selectedCategories.value = if (category.id in snapshot) {
			snapshot - category.id
		} else {
			snapshot + category.id
		}
	}

	fun clearCategories() {
		selectedCategories.value = emptySet()
	}

	fun clearStats() {
		launchLoadingJob(Dispatchers.Default) {
			repository.clearStats()
			stats.value = ReadingStats(period = period.value)
			onActionDone.call(ReversibleAction(R.string.stats_cleared, null))
		}
	}
}
