package org.koitharu.kotatsu.sync.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.sync.data.BrowserDriveAuthClient
import org.koitharu.kotatsu.sync.domain.GoogleDriveSyncRepository
import org.koitharu.kotatsu.sync.domain.SyncResult
import org.koitharu.kotatsu.sync.work.SyncWorker
import javax.inject.Inject

/** Controlled return target for the browser helper's one-time app code. */
@AndroidEntryPoint
class BrowserDriveAuthCallbackActivity : ComponentActivity() {

	@Inject
	lateinit var browserAuth: BrowserDriveAuthClient

	@Inject
	lateinit var repository: GoogleDriveSyncRepository

	@Inject
	lateinit var scheduler: SyncWorker.Scheduler

	private var handledCallback: String? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		handleIntent(intent)
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		setIntent(intent)
		handleIntent(intent)
	}

	private fun handleIntent(callbackIntent: Intent) {
		val uri = callbackIntent.data
		val key = uri?.toString()
		if (key == null || key == handledCallback) {
			if (key == null) finish()
			return
		}
		handledCallback = key
		lifecycleScope.launch {
			val message = runCatching {
				browserAuth.completeCallback(uri)
				// Temporary browser credentials must never inherit a periodic lane from GMS.
				scheduler.unschedule()
				when (val result = repository.sync()) {
					SyncResult.Success -> getString(R.string.sync_completed)
					SyncResult.SignInRequired -> getString(R.string.sync_browser_session_expired)
					is SyncResult.Error -> result.message ?: getString(R.string.sync_error)
				}
			}.getOrElse { error ->
				error.message ?: getString(R.string.sync_error)
			}
			Toast.makeText(this@BrowserDriveAuthCallbackActivity, message, Toast.LENGTH_LONG).show()
			startActivity(
				AppRouter.syncSettingsIntent(this@BrowserDriveAuthCallbackActivity).apply {
					addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
				},
			)
			finish()
		}
	}
}
