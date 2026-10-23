package org.koitharu.kotatsu.sync.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.main.ui.welcome.OnboardingActivity
import org.koitharu.kotatsu.sync.data.BrowserAuthOrigin
import org.koitharu.kotatsu.sync.data.BrowserDriveAuthClient
import org.koitharu.kotatsu.sync.domain.GoogleDriveSyncRepository
import org.koitharu.kotatsu.sync.domain.SyncResult
import org.koitharu.kotatsu.sync.work.SyncWorker
import javax.inject.Inject

/**
 * Single controlled entry point for the browser helper's return URI.
 *
 * The URI contains only a short-lived one-time app code and transaction id. The Google access token
 * is obtained later over HTTPS after the app proves possession of its process-local verifier.
 */
@AndroidEntryPoint
class BrowserDriveAuthCallbackActivity : ComponentActivity() {

	@Inject
	lateinit var browserAuth: BrowserDriveAuthClient

	@Inject
	lateinit var repository: GoogleDriveSyncRepository

	@Inject
	lateinit var scheduler: SyncWorker.Scheduler

	private var handledIntentUri: String? = null

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
		if (key == null || key == handledIntentUri) {
			if (key == null) finish()
			return
		}
		handledIntentUri = key
		lifecycleScope.launch {
			var origin = BrowserAuthOrigin.SYNC_SETTINGS
			val message = runCatching {
				val completion = browserAuth.completeCallback(uri)
				origin = completion.origin
				// Browser credentials are intentionally not background-capable. Cancel any periodic lane
				// inherited from a previous GMS session before running the one foreground/manual sync.
				scheduler.unschedule()
				when (val result = repository.sync()) {
					SyncResult.Success -> getString(R.string.sync_completed)
					SyncResult.SignInRequired -> getString(R.string.sync_sign_in_required)
					is SyncResult.Error -> result.message ?: getString(R.string.sync_error)
				}
			}.getOrElse { error ->
				error.message ?: getString(R.string.sync_error)
			}
			Toast.makeText(this@BrowserDriveAuthCallbackActivity, message, Toast.LENGTH_LONG).show()
			withContext(Dispatchers.Main.immediate) {
				val destination = when (origin) {
					BrowserAuthOrigin.ONBOARDING -> Intent(
						this@BrowserDriveAuthCallbackActivity,
						OnboardingActivity::class.java,
					).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
					BrowserAuthOrigin.SYNC_SETTINGS -> AppRouter.syncSettingsIntent(this@BrowserDriveAuthCallbackActivity)
				}
				startActivity(destination)
				finish()
			}
		}
	}
}
