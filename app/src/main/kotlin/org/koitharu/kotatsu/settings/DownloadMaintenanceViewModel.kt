package org.koitharu.kotatsu.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.download.domain.maintenance.DownloadMaintenanceUseCase
import org.koitharu.kotatsu.favourites.domain.ExhentaiPresenceDiscovery
import org.koitharu.kotatsu.favourites.domain.NormalDownloadScanCoordinator
import javax.inject.Inject

/** Fragment-scoped ViewModel retains progress/result across ordinary configuration changes. */
@HiltViewModel
class DownloadMaintenanceViewModel @Inject internal constructor(
	private val maintenance: DownloadMaintenanceUseCase,
	private val exhentaiPresenceDiscovery: ExhentaiPresenceDiscovery,
	private val normalCoordinator: NormalDownloadScanCoordinator,
) : ViewModel() {
	val state = maintenance.state
	val presenceState = exhentaiPresenceDiscovery.state
	internal val normalScanState = normalCoordinator.state
	fun scanNormalDownloadsFull() { normalCoordinator.requestFull() }
	fun scanExhentaiPresenceFull() { viewModelScope.launch { exhentaiPresenceDiscovery.run(full = true) } }
	fun start() { viewModelScope.launch { maintenance.run() } }
}
