package org.koitharu.kotatsu.discovery.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.discovery.data.TrackerCatalogRepository
import org.koitharu.kotatsu.discovery.domain.*
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import javax.inject.Inject

@HiltViewModel
class DiscoveryHubViewModel @Inject constructor(
	private val repository: TrackerCatalogRepository,
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
	init {
		loader.load()
		viewModelScope.launch { settings.observeChanges().collect { if (request() != state.value.request) loader.load(request()) } }
	}
	fun selectProvider(service: ScrobblerService) {
		if (service !in services || service == state.value.request.service) return
		settings.discoveryProvider = service.name
		loader.load(request())
	}
	fun selectFilter(filter: CatalogFilter) {
		if (filter == state.value.request.filter) return
		settings.discoveryFilter = filter.name
		loader.load(request())
	}
	fun refresh() = loader.load(force = true)
}
