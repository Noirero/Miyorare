package org.koitharu.kotatsu.sync.ui

import android.content.Context
import android.content.Intent
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.sync.data.GoogleDriveAuth
import org.koitharu.kotatsu.sync.data.SyncSettings
import org.koitharu.kotatsu.sync.data.model.SyncContent
import org.koitharu.kotatsu.sync.domain.GoogleDriveSyncRepository
import org.koitharu.kotatsu.sync.domain.SyncAuthPolicy
import org.koitharu.kotatsu.sync.domain.SyncResult
import org.koitharu.kotatsu.sync.domain.SyncSignInRequiredException
import org.koitharu.kotatsu.sync.work.SyncWorker
import javax.inject.Inject

data class SyncUiState(
	val isSignedIn: Boolean = false,
	val accountEmail: String? = null,
	val accountName: String? = null,
	val accountPhotoUrl: String? = null,
	val isEmailHidden: Boolean = false,
	val intervalMinutes: Int = 0,
	val isWifiOnly: Boolean = false,
	val isSyncOnStart: Boolean = false,
	val isDeletionSyncDisabled: Boolean = true,
	val enabledContent: Set<String> = SyncContent.DEFAULT,
	val lastSyncTimestamp: Long = 0L,
	val lastError: String? = null,
)

sealed interface SyncEvent {
	/** Launches either the account chooser or a Google-provided recoverable authorization Intent. */
	data class LaunchSignIn(val intent: Intent) : SyncEvent
	data class Message(val resId: Int) : SyncEvent
	data class Error(val message: String?) : SyncEvent
}

@HiltViewModel
class SyncSettingsViewModel @Inject constructor(
	@ApplicationContext private val context: Context,
	private val syncSettings: SyncSettings,
	private val repository: GoogleDriveSyncRepository,
	private val auth: GoogleDriveAuth,
	private val scheduler: SyncWorker.Scheduler,
) : BaseViewModel() {

	private enum class ForegroundAction {
		SYNC,
		SYNC_AFTER_SIGN_IN,
		DELETE_REMOTE,
	}

	private data class PendingAccount(
		val email: String?,
		val displayName: String?,
		val photoUrl: String?,
	)

	val uiState = MutableStateFlow(readState())
	val isSyncing = repository.isSyncing
	val events = MutableEventFlow<SyncEvent>()

	private var pendingRecoveryAction: ForegroundAction? = null
	private var pendingAccount: PendingAccount? = null

	init {
		// Refresh the displayed "last synced" / error info whenever a background sync finishes.
		viewModelScope.launch {
			isSyncing.drop(1).collect { running ->
				if (!running) refresh()
			}
		}
	}

	fun signIn() {
		pendingRecoveryAction = null
		pendingAccount = null
		events.call(SyncEvent.LaunchSignIn(auth.signInIntent))
	}

	fun onSignInResult(data: Intent?) {
		val recoveryAction = pendingRecoveryAction
		if (recoveryAction != null) {
			pendingRecoveryAction = null
			launchLoadingJob(Dispatchers.Default) {
				performForegroundAction(recoveryAction, recoveryAlreadyAttempted = true)
			}
			return
		}

		launchLoadingJob(Dispatchers.Default) {
			val account = try {
				auth.accountFromIntent(data)
			} catch (e: Exception) {
				events.call(SyncEvent.Error(e.message ?: e.javaClass.simpleName))
				return@launchLoadingJob
			}
			// Do not persist "signed in" metadata until Drive authorization itself is proven usable.
			pendingAccount = PendingAccount(account.email, account.displayName, account.photoUrl?.toString())
			performForegroundAction(ForegroundAction.SYNC_AFTER_SIGN_IN, recoveryAlreadyAttempted = false)
		}
	}

	private suspend fun performForegroundAction(
		action: ForegroundAction,
		recoveryAlreadyAttempted: Boolean,
	) {
		if (!ensureDriveAuthorization(action, recoveryAlreadyAttempted)) return

		if (action == ForegroundAction.SYNC_AFTER_SIGN_IN) {
			val account = pendingAccount ?: run {
				events.call(SyncEvent.Error(context.getString(R.string.sync_sign_in_required)))
				return
			}
			repository.onSignedIn(account.email, account.displayName, account.photoUrl)
			pendingAccount = null
			refresh()
			scheduler.schedule()
		}

		when (action) {
			ForegroundAction.SYNC,
			ForegroundAction.SYNC_AFTER_SIGN_IN -> runSync()

			ForegroundAction.DELETE_REMOTE -> runDeleteRemote()
		}
		refresh()
	}

	/**
	 * Foreground-only preflight: this is deliberately where recoverable Google consent is surfaced.
	 * The repository remains the final sync authority and obtains its own (normally cached) token.
	 */
	private suspend fun ensureDriveAuthorization(
		action: ForegroundAction,
		recoveryAlreadyAttempted: Boolean,
	): Boolean = try {
		auth.requireAccessToken()
		true
	} catch (e: SyncSignInRequiredException) {
		val recoveryIntent = e.recoveryIntent
		if (recoveryIntent != null && SyncAuthPolicy.canLaunchInteractiveRecovery(recoveryAlreadyAttempted)) {
			pendingRecoveryAction = action
			events.call(SyncEvent.LaunchSignIn(recoveryIntent))
		} else {
			if (action == ForegroundAction.SYNC_AFTER_SIGN_IN) {
				// The account chooser completed but Drive consent did not. Do not leave a half-signed-in UI.
				pendingAccount = null
				repository.signOut()
				scheduler.unschedule()
			}
			events.call(SyncEvent.Error(context.getString(R.string.sync_sign_in_required)))
		}
		refresh()
		false
	} catch (e: Exception) {
		if (action == ForegroundAction.SYNC_AFTER_SIGN_IN) {
			pendingAccount = null
			repository.signOut()
			scheduler.unschedule()
		}
		events.call(SyncEvent.Error(e.message ?: e.javaClass.simpleName))
		refresh()
		false
	}

	/** Runs a sync in the foreground and reports the outcome to the user. */
	private suspend fun runSync() {
		when (val result = repository.sync()) {
			is SyncResult.Success -> events.call(SyncEvent.Message(R.string.sync_completed))
			is SyncResult.SignInRequired -> events.call(
				SyncEvent.Error(context.getString(R.string.sync_sign_in_required)),
			)
			is SyncResult.Error -> events.call(SyncEvent.Error(result.message))
		}
	}

	private suspend fun runDeleteRemote() {
		when (val result = repository.deleteRemoteData()) {
			is SyncResult.Success -> events.call(SyncEvent.Message(R.string.sync_data_deleted))
			is SyncResult.SignInRequired -> events.call(
				SyncEvent.Error(context.getString(R.string.sync_sign_in_required)),
			)
			is SyncResult.Error -> events.call(SyncEvent.Error(result.message))
		}
	}

	fun signOut() {
		launchJob(Dispatchers.Default) {
			pendingRecoveryAction = null
			pendingAccount = null
			repository.signOut()
			scheduler.unschedule()
			refresh()
		}
	}

	fun syncNow() {
		if (!SyncAuthPolicy.isAccountReady(syncSettings.hasAccountMetadata, auth.hasSignedInAccount())) return
		launchLoadingJob(Dispatchers.Default) {
			performForegroundAction(ForegroundAction.SYNC, recoveryAlreadyAttempted = false)
		}
	}

	fun setInterval(minutes: Int) {
		syncSettings.intervalMinutes = minutes
		launchJob(Dispatchers.Default) { scheduler.schedule() }
		refresh()
	}

	fun setWifiOnly(value: Boolean) {
		syncSettings.isWifiOnly = value
		launchJob(Dispatchers.Default) { scheduler.schedule() }
		refresh()
	}

	fun setSyncOnStart(value: Boolean) {
		syncSettings.isSyncOnStart = value
		refresh()
	}

	fun setDeletionSyncDisabled(value: Boolean) {
		syncSettings.isDeletionSyncDisabled = value
		refresh()
	}

	fun setEmailHidden(value: Boolean) {
		syncSettings.isEmailHidden = value
		refresh()
	}

	fun setEnabledContent(content: Set<String>) {
		syncSettings.enabledContent = content
		refresh()
	}

	fun deleteAllData() {
		if (!SyncAuthPolicy.isAccountReady(syncSettings.hasAccountMetadata, auth.hasSignedInAccount())) return
		launchLoadingJob(Dispatchers.Default) {
			performForegroundAction(ForegroundAction.DELETE_REMOTE, recoveryAlreadyAttempted = false)
		}
	}

	private fun refresh() {
		uiState.value = readState()
	}

	private fun readState() = SyncUiState(
		isSignedIn = SyncAuthPolicy.isAccountReady(syncSettings.hasAccountMetadata, auth.hasSignedInAccount()),
		accountEmail = syncSettings.accountEmail,
		accountName = syncSettings.accountName,
		accountPhotoUrl = syncSettings.accountPhotoUrl,
		isEmailHidden = syncSettings.isEmailHidden,
		intervalMinutes = syncSettings.intervalMinutes,
		isWifiOnly = syncSettings.isWifiOnly,
		isSyncOnStart = syncSettings.isSyncOnStart,
		isDeletionSyncDisabled = syncSettings.isDeletionSyncDisabled,
		enabledContent = syncSettings.enabledContent,
		lastSyncTimestamp = syncSettings.lastSyncTimestamp,
		lastError = syncSettings.lastSyncError,
	)
}
