package org.koitharu.kotatsu.stats.ui

import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
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
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyCosmeticLoadout
import org.koitharu.kotatsu.readerjourney.domain.ReaderProfileStore
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyRules
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyCosmeticMode
import org.koitharu.kotatsu.readerjourney.theme.RankThemeId
import org.koitharu.kotatsu.stats.data.StatsRepository
import org.koitharu.kotatsu.stats.domain.ReadingStats
import org.koitharu.kotatsu.stats.domain.StatsContentScope
import org.koitharu.kotatsu.stats.domain.StatsMatureMode
import org.koitharu.kotatsu.stats.domain.StatsPeriod
import org.koitharu.kotatsu.stats.domain.YearInReview
import java.time.LocalDate
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

	val yearInReview = MutableStateFlow(
		YearInReview(year = LocalDate.now().year),
	)

	private val membershipChanges = merge(
		favouritesRepository.observeFavouritesChanges(FavouriteSpace.NORMAL),
		favouritesRepository.observeFavouritesChanges(FavouriteSpace.PRIVATE),
	)

	private val dashboardInvalidations = merge(
		membershipChanges.map { Unit },
		repository.observeReaderJourneyChanges(),
	).onStart { emit(Unit) }

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
		launchJob(Dispatchers.Default) {
			dashboardInvalidations.collectLatest {
				yearInReview.value = repository.getYearInReview(LocalDate.now().year)
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

	fun updateReaderCosmetics(loadout: ReaderJourneyCosmeticLoadout) {
		val currentRank = ReaderJourneyRules.progress(stats.value.lifetimeXp).rank
		val selectedTheme = RankThemeId.fromStableId(loadout.selectedThemeId)
			?.takeIf { it.rank.minLevel <= currentRank.minLevel }
		val sanitized = loadout.copy(
			mode = if (
				loadout.mode == ReaderJourneyCosmeticMode.FULL_SET &&
				selectedTheme == null
			) ReaderJourneyCosmeticMode.AUTO else loadout.mode,
			selectedThemeId = selectedTheme?.stableId,
			frame = loadout.frame?.takeIf { it.minLevel <= currentRank.minLevel },
			glow = loadout.glow?.takeIf { it.minLevel <= currentRank.minLevel },
			background = loadout.background?.takeIf { it.minLevel <= currentRank.minLevel },
			progressBar = loadout.progressBar?.takeIf { it.minLevel <= currentRank.minLevel },
			favoriteThemeIds = loadout.favoriteThemeIds.filterTo(LinkedHashSet()) { stableId ->
				RankThemeId.fromStableId(stableId)?.rank?.minLevel?.let { it <= currentRank.minLevel } == true
			},
		)
		profileStore.updateCosmetics(sanitized)
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
