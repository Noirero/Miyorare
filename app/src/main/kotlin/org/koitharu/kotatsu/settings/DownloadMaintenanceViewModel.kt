package org.koitharu.kotatsu.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.download.domain.maintenance.DownloadMaintenanceUseCase
import org.koitharu.kotatsu.favourites.domain.ExhentaiPresenceDiscovery
import javax.inject.Inject

/** Fragment-scoped ViewModel retains progress/result across ordinary configuration changes. */
@HiltViewModel
class DownloadMaintenanceViewModel @Inject constructor(
	private val maintenance: DownloadMaintenanceUseCase,
	private val exhentaiPresenceDiscovery: ExhentaiPresenceDiscovery,
) : ViewModel() {
	val state = maintenance.state
	val presenceState = exhentaiPresenceDiscovery.state
	fun scanExhentaiPresenceFull() { viewModelScope.launch { exhentaiPresenceDiscovery.run(full = true) } }
	fun start() { viewModelScope.launch { maintenance.run() } }
}
