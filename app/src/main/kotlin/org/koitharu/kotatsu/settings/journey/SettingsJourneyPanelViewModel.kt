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
import javax.inject.Inject

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
				}
		}
	}

	fun setModel(value: JourneyPanelModel) {
		if (model.value == value) return
		settings.journeyPanelModel = value.key
		model.value = value
	}
}
