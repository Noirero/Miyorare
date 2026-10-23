package org.koitharu.kotatsu.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.download.domain.maintenance.*

class DownloadMaintenanceSettingsTest {
	@get:Rule val compose = createComposeRule()
	private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

	@Test fun runningActionIsDisabledAndProgressIsVisible() {
		val state = MutableStateFlow(DownloadMaintenanceState(running = true, phase = DownloadMaintenancePhase.VERIFYING, scanned = 3, total = 10))
		var starts = 0
		compose.setContent { MaterialTheme { DownloadMaintenanceSettingsItem(state, MaterialTheme.shapes.medium) { starts++ } } }
		compose.onNodeWithText(context.getString(R.string.download_maintenance_title)).assertIsNotEnabled()
		compose.onNodeWithText(context.getString(R.string.download_maintenance_progress, 3, 10)).assertExists()
		assertEquals(0, starts)
	}

	@Test fun zeroRepairsStillReportsHealthyAndDetailsProtectPrivatePaths() {
		val items = listOf(
			DownloadMaintenanceItem("healthy", DownloadMaintenanceStatus.HEALTHY, DownloadMaintenanceReason.VERIFIED),
			DownloadMaintenanceItem("secret-private-path", DownloadMaintenanceStatus.AMBIGUOUS, DownloadMaintenanceReason.IDENTITY_UNKNOWN, isPrivate = true),
		)
		val state = MutableStateFlow(DownloadMaintenanceState(result = DownloadMaintenanceResult(items)))
		compose.setContent { MaterialTheme { DownloadMaintenanceSettingsItem(state, MaterialTheme.shapes.medium) {} } }
		compose.onNodeWithText(context.getString(R.string.download_maintenance_summary, 0, 1, 1, 0)).assertExists()
		compose.onNodeWithText(context.getString(R.string.download_maintenance_details)).performClick()
		compose.onNodeWithText(context.getString(R.string.download_maintenance_private_item)).assertExists()
		compose.onNodeWithText("secret-private-path").assertDoesNotExist()
	}
}
