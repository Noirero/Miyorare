package org.koitharu.kotatsu.settings.about

import android.net.Uri
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import org.koitharu.kotatsu.core.github.AppUpdateRepository
import org.koitharu.kotatsu.core.github.AppVersion
import org.koitharu.kotatsu.core.logs.AppLogger
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import javax.inject.Inject

@HiltViewModel
class AboutSettingsViewModel @Inject constructor(
	private val appUpdateRepository: AppUpdateRepository,
	private val appLogger: AppLogger,
) : BaseViewModel() {

	val isUpdateSupported = flow {
		emit(appUpdateRepository.isUpdateSupported())
	}.stateIn(viewModelScope, SharingStarted.Eagerly, false)

	val onUpdateAvailable = MutableEventFlow<AppVersion?>()

	val loggingState = appLogger.state
	val onLogActionResult = MutableEventFlow<LogActionResult>()

	enum class LogActionResult { SAVED, DELETED, FAILED }

	fun checkForUpdates() {
		launchLoadingJob {
			val update = appUpdateRepository.fetchUpdateOrThrow()
			onUpdateAvailable.call(update)
		}
	}

	fun setVerboseLogging(enabled: Boolean) = appLogger.setEnabled(enabled)

	fun saveLog(uri: Uri) {
		launchLoadingJob {
			onLogActionResult.call(if (appLogger.export(uri)) LogActionResult.SAVED else LogActionResult.FAILED)
		}
	}

	fun deleteLog() {
		launchLoadingJob {
			onLogActionResult.call(if (appLogger.deleteSaved()) LogActionResult.DELETED else LogActionResult.FAILED)
		}
	}
}
