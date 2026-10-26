package org.koitharu.kotatsu.discovery.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.discovery.data.CatalogAccountUnauthorizedException
import org.koitharu.kotatsu.discovery.data.MangaBakaPersonalHomeRepository
import org.koitharu.kotatsu.discovery.data.TrackerCatalogAccountRepository
import org.koitharu.kotatsu.discovery.domain.CatalogFailure
import org.koitharu.kotatsu.discovery.domain.CatalogFilter
import org.koitharu.kotatsu.discovery.domain.CatalogTrackingEntry
import org.koitharu.kotatsu.discovery.domain.MangaBakaExploreType
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogItem
import org.koitharu.kotatsu.discovery.domain.catalogFailure
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import javax.inject.Inject

enum class MangaBakaSeeAllMode { LIBRARY, EXPLORE }

object MangaBakaSeeAllArguments {
	const val MODE = "mangabaka_see_all_mode"
	const val FILTER = "mangabaka_see_all_filter"
	const val TYPE = "mangabaka_see_all_type"
}

data class MangaBakaSeeAllState(
	val mode: MangaBakaSeeAllMode,
	val filter: CatalogFilter,
	val type: MangaBakaExploreType? = null,
	val items: List<TrackerCatalogItem> = emptyList(),
	val loading: Boolean = false,
	val loadingMore: Boolean = false,
	val nextPage: Int? = null,
	val failure: CatalogFailure? = null,
	val authExpired: Boolean = false,
)

@HiltViewModel
class MangaBakaSeeAllViewModel @Inject constructor(
	private val saved: SavedStateHandle,
	private val repository: MangaBakaPersonalHomeRepository,
	private val accountRepository: TrackerCatalogAccountRepository,
	private val settings: AppSettings,
) : ViewModel() {
	private val mode = runCatching {
		MangaBakaSeeAllMode.valueOf(checkNotNull(saved.get<String>(MangaBakaSeeAllArguments.MODE)))
	}.getOrDefault(MangaBakaSeeAllMode.EXPLORE)
	private val filter = runCatching {
		CatalogFilter.valueOf(checkNotNull(saved.get<String>(MangaBakaSeeAllArguments.FILTER)))
	}.getOrDefault(CatalogFilter.ALL)
	val type: MangaBakaExploreType? = runCatching {
		MangaBakaExploreType.valueOf(checkNotNull(saved.get<String>(MangaBakaSeeAllArguments.TYPE)))
	}.getOrNull()

	private val mutableState = MutableStateFlow(MangaBakaSeeAllState(mode, filter, type))
	val state: StateFlow<MangaBakaSeeAllState> = mutableState.asStateFlow()
	private val mutableKnownTracking = MutableStateFlow<Map<Long, CatalogTrackingEntry>>(emptyMap())
	val knownTracking: StateFlow<Map<Long, CatalogTrackingEntry>> = mutableKnownTracking.asStateFlow()

	private var loadJob: Job? = null
	private var accountJob: Job? = null
	private var generation = 0L
	private var accountGeneration = 0L
	private var adult = !settings.isNsfwContentDisabled

	init {
		reload()
		refreshAccount()
		viewModelScope.launch {
			settings.observeChanges().collect {
				val nextAdult = !settings.isNsfwContentDisabled
				if (adult != nextAdult) {
					adult = nextAdult
					reload()
				}
			}
		}
	}

	fun reload() = load(page = 1, append = false)

	fun nextPage() {
		if (state.value.loading || state.value.loadingMore) return
		state.value.nextPage?.let { load(it, append = true) }
	}

	fun retry() {
		if (state.value.authExpired) return
		if (state.value.items.isNotEmpty() && state.value.nextPage != null) nextPage() else reload()
	}

	fun refreshAccount() {
		val token = ++accountGeneration
		accountJob?.cancel()
		val snapshot = accountRepository.snapshot(ScrobblerService.MANGABAKA)
		if (!snapshot.authorized) {
			mutableKnownTracking.value = emptyMap()
			if (mode == MangaBakaSeeAllMode.LIBRARY) {
				generation++
				loadJob?.cancel()
				mutableState.update {
					it.copy(items = emptyList(), loading = false, loadingMore = false, nextPage = null, failure = null, authExpired = true)
				}
			}
			return
		}
		accountJob = viewModelScope.launch {
			try {
				accountRepository.refreshAccount(ScrobblerService.MANGABAKA)
				val known = accountRepository.knownTracking(ScrobblerService.MANGABAKA)
				if (token == accountGeneration) {
					mutableKnownTracking.value = known
					if (mode == MangaBakaSeeAllMode.LIBRARY && mutableState.value.authExpired) reload()
				}
			} catch (e: CancellationException) {
				throw e
			} catch (_: Exception) {
				// Public Explore remains usable. Library loading owns its own auth/error truth.
			}
		}
	}

	private fun load(page: Int, append: Boolean) {
		val token = ++generation
		loadJob?.cancel()
		mutableState.update {
			it.copy(
				items = if (append) it.items else emptyList(),
				loading = !append,
				loadingMore = append,
				failure = null,
				authExpired = false,
			)
		}
		loadJob = viewModelScope.launch {
			try {
				when (mode) {
					MangaBakaSeeAllMode.LIBRARY -> {
						val result = repository.libraryPage(filter, adult, page)
						if (token != generation) return@launch
						mutableKnownTracking.update { old ->
							old + result.items.associate { it.tracking.targetId to it.tracking }
						}
						mutableState.update { old ->
							old.copy(
								items = ((if (append) old.items else emptyList()) + result.items.map { it.item }).distinctBy { it.key },
								nextPage = result.nextPage?.takeIf { it > page },
								loading = false,
								loadingMore = false,
							)
						}
					}
					MangaBakaSeeAllMode.EXPLORE -> {
						val selectedType = type ?: error("MangaBaka Explore See-all requires a provider type")
						val result = repository.explorePage(selectedType, adult, page)
						if (token != generation) return@launch
						mutableState.update { old ->
							old.copy(
								items = ((if (append) old.items else emptyList()) + result.items).distinctBy { it.key },
								nextPage = result.nextPage?.takeIf { it > page },
								loading = false,
								loadingMore = false,
							)
						}
					}
				}
			} catch (e: CancellationException) {
				throw e
			} catch (_: CatalogAccountUnauthorizedException) {
				if (token == generation) {
					mutableKnownTracking.value = emptyMap()
					mutableState.update {
						it.copy(items = emptyList(), loading = false, loadingMore = false, nextPage = null, failure = null, authExpired = true)
					}
				}
			} catch (e: Exception) {
				if (token == generation) {
					mutableState.update { it.copy(loading = false, loadingMore = false, failure = e.catalogFailure()) }
				}
			}
		}
	}
}
