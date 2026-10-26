package org.koitharu.kotatsu.discovery.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.discovery.data.CatalogAccountUnauthorizedException
import org.koitharu.kotatsu.discovery.data.MangaBakaPersonalHomeRepository
import org.koitharu.kotatsu.discovery.data.TrackerCatalogAccountRepository
import org.koitharu.kotatsu.discovery.data.TrackerCatalogRepository
import org.koitharu.kotatsu.discovery.domain.*
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import javax.inject.Inject

@HiltViewModel
class DiscoveryHubViewModel @Inject constructor(
	private val repository: TrackerCatalogRepository,
	private val accountRepository: TrackerCatalogAccountRepository,
	private val mangaBakaPersonalRepository: MangaBakaPersonalHomeRepository,
	private val settings: AppSettings,
) : ViewModel() {
	val services = repository.services.toList()
	private fun request(): CatalogHomeRequest {
		val service = runCatching { ScrobblerService.valueOf(settings.discoveryProvider) }.getOrNull()?.takeIf { it in services } ?: ScrobblerService.ANILIST
		val filter = runCatching { CatalogFilter.valueOf(settings.discoveryFilter) }.getOrDefault(CatalogFilter.ALL)
		return CatalogHomeRequest(service, filter, !settings.isNsfwContentDisabled)
	}
	private val loader = DiscoveryHubController(repository, viewModelScope, request())
	val state = loader.state
	private val mutableAccount = MutableStateFlow(accountRepository.snapshot(request().service))
	val account = mutableAccount.asStateFlow()
	private val mutableKnownTracking = MutableStateFlow<Map<Long, CatalogTrackingEntry>>(emptyMap())
	val knownTracking = mutableKnownTracking.asStateFlow()
	private val mutableMangaBakaPersonal = MutableStateFlow(MangaBakaPersonalHomeState())
	val mangaBakaPersonal = mutableMangaBakaPersonal.asStateFlow()
	private var accountJob: Job? = null
	private var personalJob: Job? = null
	private var exploreJob: Job? = null
	@Volatile private var accountGeneration = 0L
	@Volatile private var personalGeneration = 0L
	@Volatile private var exploreGeneration = 0L

	init {
		loader.load()
		refreshAccount(request().service)
		viewModelScope.launch {
			settings.observeChanges().collect {
				val next = request()
				if (next != state.value.request) {
					val providerChanged = next.service != state.value.request.service
					loader.load(next)
					if (providerChanged) {
						refreshAccount(next.service)
					} else if (next.service == ScrobblerService.MANGABAKA && mutableAccount.value.authorized) {
						refreshMangaBakaPersonal(next)
					}
				}
			}
		}
	}

	fun selectProvider(service: ScrobblerService) {
		if (service !in services || service == state.value.request.service) return
		settings.discoveryProvider = service.name
		loader.load(request())
		refreshAccount(service)
	}

	fun selectFilter(filter: CatalogFilter) {
		if (filter == state.value.request.filter) return
		settings.discoveryFilter = filter.name
		val next = request()
		loader.load(next)
		if (next.service == ScrobblerService.MANGABAKA && mutableAccount.value.authorized) {
			refreshMangaBakaPersonal(next)
		}
	}

	fun refresh() {
		loader.load(force = true)
		val current = state.value.request
		if (current.service == ScrobblerService.MANGABAKA && mutableAccount.value.authorized) {
			refreshMangaBakaPersonal(current)
		}
	}

	fun refreshAccount() = refreshAccount(state.value.request.service)

	private fun refreshAccount(service: ScrobblerService) {
		val token = ++accountGeneration
		accountJob?.cancel()
		if (service != ScrobblerService.MANGABAKA) clearMangaBakaPersonal()
		val snapshot = accountRepository.snapshot(service)
		mutableAccount.value = snapshot.copy(loading = snapshot.authorized && snapshot.user == null, failed = false)
		if (!snapshot.authorized) {
			mutableKnownTracking.value = emptyMap()
			if (service == ScrobblerService.MANGABAKA) clearMangaBakaPersonal()
			return
		}
		accountJob = viewModelScope.launch {
			try {
				val refreshed = accountRepository.refreshAccount(service)
				val known = accountRepository.knownTracking(service)
				if (token == accountGeneration && state.value.request.service == service) {
					mutableAccount.value = refreshed
					mutableKnownTracking.value = known
					if (service == ScrobblerService.MANGABAKA) {
						refreshMangaBakaPersonal(state.value.request)
					}
				}
			} catch (e: CancellationException) {
				throw e
			} catch (_: Exception) {
				if (token == accountGeneration && state.value.request.service == service) {
					mutableAccount.value = snapshot.copy(loading = false, failed = true)
					mutableKnownTracking.value = emptyMap()
					if (service == ScrobblerService.MANGABAKA) clearMangaBakaPersonal()
				}
			}
		}
	}

	fun retryMangaBakaPersonal() {
		val current = state.value.request
		if (current.service == ScrobblerService.MANGABAKA && mutableAccount.value.authorized) {
			refreshMangaBakaPersonal(current)
		}
	}

	private fun refreshMangaBakaPersonal(request: CatalogHomeRequest) {
		if (request.service != ScrobblerService.MANGABAKA || !mutableAccount.value.authorized) {
			clearMangaBakaPersonal()
			return
		}
		val token = ++personalGeneration
		personalJob?.cancel()
		exploreJob?.cancel()
		exploreGeneration++
		mutableMangaBakaPersonal.value = mutableMangaBakaPersonal.value.copy(
			loading = true,
			failed = false,
			authExpired = false,
			partial = false,
			selectedType = null,
			exploreItems = emptyList(),
			exploreLoading = false,
			exploreFailed = false,
		)
		personalJob = viewModelScope.launch {
			try {
				val snapshot = mangaBakaPersonalRepository.load(request.filter, request.allowAdult)
				if (token == personalGeneration && state.value.request == request) {
					mutableMangaBakaPersonal.value = mutableMangaBakaPersonal.value.copy(
						loading = false,
						library = snapshot.library,
						recommendations = snapshot.recommendations,
						partial = snapshot.partial,
						failed = false,
						authExpired = false,
					)
					// The bulk remote library is authoritative for MangaBaka Home badges. This also
					// removes stale locally-known entries that were deleted on MangaBaka elsewhere.
					mutableKnownTracking.value = snapshot.library.associate { it.tracking.targetId to it.tracking }
				}
			} catch (e: CancellationException) {
				throw e
			} catch (_: CatalogAccountUnauthorizedException) {
				if (token == personalGeneration && state.value.request == request) {
					// An expired session can no longer establish remote truth, so do not keep stale
					// badges sourced from an earlier account snapshot.
					mutableKnownTracking.value = emptyMap()
					mutableMangaBakaPersonal.value = mutableMangaBakaPersonal.value.copy(
						loading = false,
						failed = true,
						authExpired = true,
					)
				}
			} catch (_: Exception) {
				if (token == personalGeneration && state.value.request == request) {
					mutableMangaBakaPersonal.value = mutableMangaBakaPersonal.value.copy(loading = false, failed = true)
				}
			}
		}
	}

	fun selectMangaBakaExploreType(type: MangaBakaExploreType) {
		val request = state.value.request
		if (
			request.service != ScrobblerService.MANGABAKA ||
			!mutableAccount.value.authorized ||
			!type.isVisibleFor(request.filter)
		) return
		val token = ++exploreGeneration
		exploreJob?.cancel()
		mutableMangaBakaPersonal.value = mutableMangaBakaPersonal.value.copy(
			selectedType = type,
			exploreItems = emptyList(),
			exploreLoading = true,
			exploreFailed = false,
		)
		exploreJob = viewModelScope.launch {
			try {
				val items = mangaBakaPersonalRepository.explore(type, request.allowAdult)
				if (token == exploreGeneration && state.value.request == request) {
					mutableMangaBakaPersonal.value = mutableMangaBakaPersonal.value.copy(
						exploreItems = items,
						exploreLoading = false,
						exploreFailed = false,
					)
				}
			} catch (e: CancellationException) {
				throw e
			} catch (_: Exception) {
				if (token == exploreGeneration && state.value.request == request) {
					mutableMangaBakaPersonal.value = mutableMangaBakaPersonal.value.copy(exploreLoading = false, exploreFailed = true)
				}
			}
		}
	}

	private fun clearMangaBakaPersonal() {
		personalGeneration++
		exploreGeneration++
		personalJob?.cancel()
		exploreJob?.cancel()
		mutableMangaBakaPersonal.value = MangaBakaPersonalHomeState()
	}

	fun logoutAccount() {
		val service = state.value.request.service
		accountGeneration++
		accountJob?.cancel()
		accountRepository.logout(service)
		mutableAccount.value = CatalogAccountState(service)
		mutableKnownTracking.value = emptyMap()
		if (service == ScrobblerService.MANGABAKA) clearMangaBakaPersonal()
	}
}
