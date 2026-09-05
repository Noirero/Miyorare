package org.koitharu.kotatsu

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.acra.ACRA
import org.koitharu.kotatsu.core.BaseApp
import org.koitharu.kotatsu.core.logs.CrashLogStore
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.util.ext.copyToClipboard
import org.koitharu.kotatsu.core.util.ext.processLifecycleScope
import org.koitharu.kotatsu.main.ui.MainActivity

class KotatsuApp : BaseApp() {

	@Volatile
	private var recoveredCrashDialogShown = false
	private val exitRecoveryComplete = CompletableDeferred<Unit>()
	private var crashLogExportLauncher: ActivityResultLauncher<String>? = null

	override fun onCreate() {
		super.onCreate()
		if (ACRA.isACRASenderServiceProcess()) return

		// Reading ApplicationExitInfo (and especially an ANR trace) can touch disk and return a large
		// payload. Never do it on the main thread: diagnostics must not make startup jank or create a
		// new ANR while trying to explain the previous one.
		processLifecycleScope.launch(Dispatchers.IO) {
			try {
				CrashLogStore.capturePreviousSystemExit(this@KotatsuApp)
			} finally {
				exitRecoveryComplete.complete(Unit)
			}
		}
		registerActivityLifecycleCallbacks(RecoveredCrashDialogCallbacks())
	}

	private inner class RecoveredCrashDialogCallbacks : Application.ActivityLifecycleCallbacks {
		override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
			if (activity !is MainActivity) return
			// Register against MainActivity's ActivityResultRegistry before the activity starts. Using
			// Android's CreateDocument contract lets the user choose any writable folder without broad
			// storage permissions or exposing the private diagnostics directory through a FileProvider.
			crashLogExportLauncher?.unregister()
			crashLogExportLauncher = activity.activityResultRegistry.register(
				CRASH_LOG_EXPORT_KEY,
				ActivityResultContracts.CreateDocument("text/plain"),
			) { uri ->
				// A recreated MainActivity resets the dialog visibility flag. Re-assert it while handling a
				// restored picker result so onActivityResumed cannot open a duplicate recovered-log dialog.
				recoveredCrashDialogShown = true
				if (uri == null) {
					// Save closes the recovered-log dialog before the picker opens. If the picker is cancelled,
					// make the still-pending report available again instead of hiding it for this whole process.
					retryRecoveredCrashDialog(activity)
				} else {
					processLifecycleScope.launch(Dispatchers.IO) {
						val saved = CrashLogStore.exportText(activity, uri)
						if (saved) {
							CrashLogStore.clearPending(activity)
						}
						activity.runOnUiThread {
							if (!activity.isFinishing && !activity.isDestroyed) {
								Toast.makeText(
									activity,
									if (saved) "Crash log saved as .txt" else "Failed to save crash log",
									Toast.LENGTH_SHORT,
								).show()
								if (!saved) {
									// Keep Copy/Save/Close reachable after a provider/write failure too.
									retryRecoveredCrashDialog(activity)
								}
							}
						}
					}
				}
			}
		}

		override fun onActivityResumed(activity: Activity) {
			if (activity is MainActivity) {
				showRecoveredCrashDialogIfPending(activity)
			}
		}

		private fun retryRecoveredCrashDialog(activity: MainActivity) {
			recoveredCrashDialogShown = false
			// A button callback dismisses the current AlertDialog after returning. Defer the retry by one
			// UI turn so a new dialog never overlaps the old one while it is still being removed.
			activity.window.decorView.post {
				showRecoveredCrashDialogIfPending(activity)
			}
		}

		private fun showRecoveredCrashDialogIfPending(activity: MainActivity) {
			if (recoveredCrashDialogShown || activity.isFinishing || activity.isDestroyed) return
			processLifecycleScope.launch(Dispatchers.IO) {
				// MainActivity can resume before ApplicationExitInfo recovery finishes. Waiting here avoids
				// missing an ANR/native-crash report until the user happens to leave and return later.
				exitRecoveryComplete.await()
				val log = CrashLogStore.pendingLog(activity) ?: return@launch
				activity.runOnUiThread {
					if (recoveredCrashDialogShown || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
					recoveredCrashDialogShown = true
					val preview = log.take(MAX_DIALOG_LOG_CHARS).let {
						if (log.length > it.length) {
							"$it\n\n… Full log is available through Copy text or Save .txt."
						} else {
							it
						}
					}
					buildAlertDialog(activity) {
						setTitle(R.string.error_occurred)
						setMessage(preview)
						setCancelable(false)
						setPositiveButton("Copy text") { _, _ ->
							activity.copyToClipboard(activity.getString(R.string.error_details), log)
							CrashLogStore.clearPending(activity)
						}
						setNeutralButton("Save .txt") { _, _ ->
							val launcher = crashLogExportLauncher
							if (launcher == null) {
								Toast.makeText(activity, "Unable to open file picker", Toast.LENGTH_SHORT).show()
								retryRecoveredCrashDialog(activity)
							} else {
								runCatching {
									launcher.launch(CrashLogStore.suggestedTextFileName())
								}.onFailure {
									Toast.makeText(activity, "Unable to open file picker", Toast.LENGTH_SHORT).show()
									retryRecoveredCrashDialog(activity)
								}
							}
						}
						setNegativeButton(R.string.close) { _, _ ->
							CrashLogStore.clearPending(activity)
						}
					}.show()
				}
			}
		}

		override fun onActivityStarted(activity: Activity) = Unit
		override fun onActivityPaused(activity: Activity) = Unit
		override fun onActivityStopped(activity: Activity) = Unit
		override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

		override fun onActivityDestroyed(activity: Activity) {
			if (activity is MainActivity) {
				// The window-owned dialog disappears with this Activity. Keep the process-wide flag aligned
				// with reality so a still-pending report can be shown by the replacement MainActivity.
				recoveredCrashDialogShown = false
				crashLogExportLauncher?.unregister()
				crashLogExportLauncher = null
			}
		}
	}

	private companion object {
		const val MAX_DIALOG_LOG_CHARS = 12_000
		const val CRASH_LOG_EXPORT_KEY = "recovered_crash_log_export"
	}
}