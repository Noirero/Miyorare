package org.koitharu.kotatsu.stats.ui

import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.FavouriteCategory
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.ui.util.ReversibleAction
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.readerjourney.domain.ReaderAchievementId
import org.koitharu.kotatsu.readerjourney.domain.ReaderProfileStore
import org.koitharu.kotatsu.stats.data.StatsRepository
import org.koitharu.kotatsu.stats.domain.ReadingStats
import org.koitharu.kotatsu.stats.domain.StatsContentScope
import org.koitharu.kotatsu.stats.domain.StatsMatureMode
import org.koitharu.kotatsu.stats.domain.StatsPeriod
import javax.inject.Inject

@HiltViewModel
class StatsViewModel @Inject constructor(
	private val repository: StatsRepository,
	private val settings: AppSettings,
	private val profileStore: ReaderProfileStore,
	favouritesRepository: FavouritesRepository,
) : BaseViewModel() {

	val period = MutableStateFlow(StatsPeriod.ALL)
	val scope = MutableStateFlow(StatsContentScope.OVERVIEW)
	val matureMode = MutableStateFlow(StatsMatureMode.fromPreference(settings.statsMatureMode))
	val selectedCategories = MutableStateFlow<Set<Long>>(emptySet())
	val onActionDone = MutableEventFlow<ReversibleAction>()
	val favoriteCategories = favouritesRepository.observeCategories()
	val readerProfile = profileStore.profile

	val stats = MutableStateFlow(
		ReadingStats(
			period = period.value,
			scope = scope.value,
			matureMode = matureMode.value,
		),
	)

	private val membershipChanges = merge(
		favouritesRepository.observeFavouritesChanges(FavouriteSpace.NORMAL),
		favouritesRepository.observeFavouritesChanges(FavouriteSpace.PRIVATE),
	)

	private val dashboardInvalidations = merge(
		membershipChanges.map { Unit },
		repository.observeReaderJourneyChanges(),
	)

	init {
		launchJob(Dispatchers.Default) {
			combine(period, selectedCategories, scope, matureMode) { p, categories, contentScope, privacy ->
				StatsQuery(
					period = p,
					categories = categories,
					scope = contentScope,
					matureMode = privacy,
				)
			}.combine(dashboardInvalidations) { query, _ ->
				query
			}.collectLatest { query ->
				stats.value = withLoading {
					repository.getStatsSnapshot(
						period = query.period,
						categories = query.categories,
						scope = query.scope,
						matureMode = query.matureMode,
					)
				}
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

	fun setMatureMode(mode: StatsMatureMode) {
		if (matureMode.value == mode) return
		settings.statsMatureMode = mode.name
		matureMode.value = mode
	}

	fun updateReaderProfile(
		displayName: String,
		selectedTitle: ReaderAchievementId?,
		showcase: List<ReaderAchievementId>,
	) {
		val unlocked = stats.value.achievements
			.asSequence()
			.filter { it.isUnlocked }
			.mapTo(HashSet()) { it.id }
		profileStore.update(
			displayName = displayName,
			selectedTitle = selectedTitle?.takeIf { it in unlocked },
			showcase = showcase.filter { it in unlocked },
		)
	}

	fun clearStats() {
		launchLoadingJob(Dispatchers.Default) {
			repository.clearStats()
			stats.value = ReadingStats(
				period = period.value,
				scope = scope.value,
				matureMode = matureMode.value,
			)
			onActionDone.call(ReversibleAction(R.string.stats_cleared, null))
		}
	}
}

private data class StatsQuery(
	val period: StatsPeriod,
	val categories: Set<Long>,
	val scope: StatsContentScope,
	val matureMode: StatsMatureMode,
)
