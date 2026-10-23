package org.koitharu.kotatsu.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.download.domain.maintenance.DownloadMaintenanceUseCase
import javax.inject.Inject

/** Fragment-scoped ViewModel retains progress/result across ordinary configuration changes. */
@HiltViewModel
class DownloadMaintenanceViewModel @Inject constructor(
	private val maintenance: DownloadMaintenanceUseCase,
) : ViewModel() {
	val state = maintenance.state
	fun start() { viewModelScope.launch { maintenance.run() } }
}
