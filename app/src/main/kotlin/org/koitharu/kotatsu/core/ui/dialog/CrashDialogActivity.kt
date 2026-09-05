package org.koitharu.kotatsu.core.ui.dialog

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.acra.ReportField
import org.acra.dialog.CrashReportDialogHelper
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.logs.CrashLogStore
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle
import org.koitharu.kotatsu.core.util.ext.copyToClipboard

/**
 * Replaces ACRA's stock crash dialog with the app's M3 Expressive card.
 * No report sender is configured, so crash details can be copied or exported as a text file.
 * A private copy is retained by CrashLogStore so it can be surfaced once more after the normal app
 * process starts again if the user has not dismissed the recovered report there yet.
 */
class CrashDialogActivity : ComponentActivity() {

	private val exportTextLauncher = registerForActivityResult(
		ActivityResultContracts.CreateDocument("text/plain"),
	) { uri ->
		if (uri != null) {
			lifecycleScope.launch(Dispatchers.IO) {
				val saved = CrashLogStore.exportText(this@CrashDialogActivity, uri)
				withContext(Dispatchers.Main) {
					Toast.makeText(
						this@CrashDialogActivity,
						if (saved) "Crash log saved as .txt" else "Failed to save crash log",
						Toast.LENGTH_SHORT,
					).show()
				}
			}
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		val settings = AppSettings(this)
		setTheme(settings.colorScheme.styleResId)
		if (settings.miyorareDesignStyle == MiyorareDesignStyle.MODERN) {
			setTheme(R.style.ThemeOverlay_MiyorareModern)
		}
		if (settings.isAmoledTheme) {
			setTheme(R.style.ThemeOverlay_Kotatsu_Amoled)
		}
		super.onCreate(savedInstanceState)
		val helper = try {
			CrashReportDialogHelper(this, intent)
		} catch (e: IllegalArgumentException) {
			finish()
			return
		}
		lifecycleScope.launch {
			// report is a file on disk, and it is the only thing this screen has to show
			val report = withContext(Dispatchers.Default) {
				runCatching { helper.reportData }.getOrNull()
			}
			val reportJson = report?.toJSON()?.takeIf { it.isNotEmpty() }
			val logText = if (reportJson != null) {
				withContext(Dispatchers.IO) {
					runCatching { CrashLogStore.saveAcraCrash(this@CrashDialogActivity, reportJson) }
					CrashLogStore.pendingLog(this@CrashDialogActivity) ?: reportJson
				}
			} else {
				null
			}
			showDialog(helper, logText, report?.getString(ReportField.STACK_TRACE))
		}
	}

	private fun showDialog(helper: CrashReportDialogHelper, logText: String?, stackTrace: String?) {
		showComposeDialog(this, cancelable = false) { dismiss ->
			ExpressiveDialogCard(
				icon = painterResource(R.drawable.ic_alert_outline),
				title = stringResource(R.string.error_occurred),
				message = listOfNotNull(
					stringResource(R.string.crash_text),
					stackTrace?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() },
				).joinToString("\n\n"),
			) {
				if (logText != null) {
					ExpressivePillButton(
						text = "Copy text",
						icon = painterResource(R.drawable.ic_content_copy),
						onClick = { copyToClipboard(getString(R.string.error_details), logText) },
					)
					Spacer(Modifier.height(8.dp))
					ExpressiveDialogTextButton(
						text = "Save .txt",
						onClick = {
							runCatching {
								exportTextLauncher.launch(CrashLogStore.suggestedTextFileName())
							}.onFailure {
								Toast.makeText(
									this@CrashDialogActivity,
									"Unable to open file picker",
									Toast.LENGTH_SHORT,
								).show()
							}
						},
					)
					Spacer(Modifier.height(8.dp))
				}
				ExpressiveDialogTextButton(
					text = stringResource(R.string.close),
					onClick = {
						helper.cancelReports()
						dismiss()
						finish()
					},
				)
			}
		}
	}
}
