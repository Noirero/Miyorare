package org.koitharu.kotatsu.settings.journey

import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onStart
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.readerjourney.domain.ReaderProfileSettings
import org.koitharu.kotatsu.readerjourney.domain.ReaderProfileStore
import org.koitharu.kotatsu.stats.data.StatsRepository
import org.koitharu.kotatsu.stats.domain.ReadingStats
import org.koitharu.kotatsu.stats.domain.StatsContentScope
import org.koitharu.kotatsu.stats.domain.StatsMatureMode
import org.koitharu.kotatsu.stats.domain.StatsPeriod
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

/** Snapshot of the weekly reading goal shown on the panel's Overview tab. */
data class WeeklyGoalState(
	val goal: Int = 0,
	val thisWeek: Long = 0,
	val lastWeek: Long? = null,
) {
	val reached: Boolean
		get() = goal > 0 && thisWeek >= goal

	val fraction: Float
		get() = if (goal <= 0) 0f else (thisWeek.toFloat() / goal).coerceIn(0f, 1f)
}

/**
 * Data for the Settings swipe-in Reader Journey panel.
 *
 * Deliberately reuses the existing Journey data path (StatsRepository snapshot over
 * StatsPeriod.ALL plus the shared ReaderProfileStore) instead of introducing a second
 * statistics implementation. Only the presentation is new: the panel renders the same
 * numbers through one of several selectable visual models.
 */
@HiltViewModel
class SettingsJourneyPanelViewModel @Inject constructor(
	private val repository: StatsRepository,
	private val settings: AppSettings,
	profileStore: ReaderProfileStore,
) : BaseViewModel() {

	val profile: StateFlow<ReaderProfileSettings> = profileStore.profile

	private val matureMode = StatsMatureMode.fromPreference(settings.statsMatureMode)

	val stats = MutableStateFlow(
		ReadingStats(
			period = StatsPeriod.ALL,
			scope = StatsContentScope.OVERVIEW,
			matureMode = matureMode,
		),
	)

	val model = MutableStateFlow(JourneyPanelModel.fromKey(settings.journeyPanelModel))

	/**
	 * Presentation of the Settings Journey page itself. Independent from the main
	 * Reader Journey screen's display mode: the page starts on Kartu and remembers
	 * only its own last choice.
	 */
	val displayMode = MutableStateFlow(
		JourneyDisplayMode.fromKey(settings.journeyPanelDisplayMode, JourneyDisplayMode.KARTU),
	)

	val weeklyGoal = MutableStateFlow(WeeklyGoalState(goal = settings.journeyWeeklyGoal))

	init {
		launchJob(Dispatchers.Default) {
			repository.observeReaderJourneyChanges()
				.onStart { emit(Unit) }
				.collectLatest {
					stats.value = withLoading {
						repository.getStatsSnapshot(
							period = StatsPeriod.ALL,
							categories = emptySet(),
							scope = StatsContentScope.OVERVIEW,
							matureMode = matureMode,
						)
					}
					resolveWeeklyGoal(stats.value.journeyCompletedChapters)
				}
		}
	}

	fun setModel(value: JourneyPanelModel) {
		if (model.value == value) return
		settings.journeyPanelModel = value.key
		model.value = value
	}

	fun setDisplayMode(value: JourneyDisplayMode) {
		if (displayMode.value == value) return
		settings.journeyPanelDisplayMode = value.key
		displayMode.value = value
	}

	fun setWeeklyGoal(value: Int) {
		settings.journeyWeeklyGoal = value
		resolveWeeklyGoal(stats.value.journeyCompletedChapters)
	}

	/**
	 * The weekly goal counts verified Journey chapters inside the current
	 * Monday–Sunday week. The app stores no per-day chapter history, so the count
	 * is anchored: at the start of a week the lifetime chapter total is saved as
	 * the baseline, and in-week progress is the lifetime delta since then. When a
	 * week rolls over, the finished week's delta becomes last week's count — but
	 * only when the stored anchor is exactly the previous week; after a longer
	 * absence the previous count is unknown and the trend stays hidden instead of
	 * being guessed.
	 */
	private fun resolveWeeklyGoal(lifetimeChapters: Long) {
		val goal = settings.journeyWeeklyGoal
		if (goal <= 0) {
			weeklyGoal.value = WeeklyGoalState(goal = 0)
			return
		}
		val monday = currentWeekMonday()
		val storedStart = settings.journeyGoalWeekStart
		when {
			storedStart == 0L -> anchorGoalWeek(monday, lifetimeChapters, lastWeek = -1L)
			monday > storedStart -> {
				val finished = (lifetimeChapters - settings.journeyGoalBaseline).coerceAtLeast(0L)
				anchorGoalWeek(
					monday,
					lifetimeChapters,
					lastWeek = if (monday - storedStart == 7L) finished else -1L,
				)
			}
		}
		val lastWeek = settings.journeyGoalLastWeek
		weeklyGoal.value = WeeklyGoalState(
			goal = goal,
			thisWeek = (lifetimeChapters - settings.journeyGoalBaseline).coerceAtLeast(0L),
			lastWeek = if (lastWeek >= 0L) lastWeek else null,
		)
	}

	private fun anchorGoalWeek(monday: Long, lifetimeChapters: Long, lastWeek: Long) {
		settings.journeyGoalWeekStart = monday
		settings.journeyGoalBaseline = lifetimeChapters
		settings.journeyGoalLastWeek = lastWeek
	}

	private fun currentWeekMonday(): Long = LocalDate.now()
		.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
		.toEpochDay()
}
