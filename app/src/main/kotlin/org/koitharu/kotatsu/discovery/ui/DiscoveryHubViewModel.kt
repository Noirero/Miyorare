package org.koitharu.kotatsu.discovery.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.discovery.data.TrackerCatalogAccountRepository
import org.koitharu.kotatsu.discovery.data.TrackerCatalogRepository
import org.koitharu.kotatsu.discovery.domain.*
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import javax.inject.Inject

@HiltViewModel
class DiscoveryHubViewModel @Inject constructor(
	private val repository: TrackerCatalogRepository,
	private val accountRepository: TrackerCatalogAccountRepository,
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
	private var accountJob: Job? = null
	@Volatile private var accountGeneration = 0L

	init {
		loader.load()
		refreshAccount(request().service)
		viewModelScope.launch {
			settings.observeChanges().collect {
				val next = request()
				if (next != state.value.request) {
					val providerChanged = next.service != state.value.request.service
					loader.load(next)
					if (providerChanged) refreshAccount(next.service)
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
		loader.load(request())
	}

	fun refresh() = loader.load(force = true)

	fun refreshAccount() = refreshAccount(state.value.request.service)

	private fun refreshAccount(service: ScrobblerService) {
		val token = ++accountGeneration
		accountJob?.cancel()
		val snapshot = accountRepository.snapshot(service)
		mutableAccount.value = snapshot.copy(loading = snapshot.authorized && snapshot.user == null, failed = false)
		if (!snapshot.authorized) {
			mutableKnownTracking.value = emptyMap()
			return
		}
		accountJob = viewModelScope.launch {
			try {
				val refreshed = accountRepository.refreshAccount(service)
				val known = accountRepository.knownTracking(service)
				if (token == accountGeneration && state.value.request.service == service) {
					mutableAccount.value = refreshed
					mutableKnownTracking.value = known
				}
			} catch (_: Exception) {
				if (token == accountGeneration && state.value.request.service == service) {
					mutableAccount.value = snapshot.copy(loading = false, failed = true)
					mutableKnownTracking.value = emptyMap()
				}
			}
		}
	}

	fun logoutAccount() {
		val service = state.value.request.service
		accountGeneration++
		accountJob?.cancel()
		accountRepository.logout(service)
		mutableAccount.value = CatalogAccountState(service)
		mutableKnownTracking.value = emptyMap()
	}
}
