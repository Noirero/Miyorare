package org.koitharu.kotatsu.core.logs

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.koitharu.kotatsu.core.diagnostics.PerformanceDiagnostics
import java.io.File

@RunWith(AndroidJUnit4::class)
class AppLoggerStopRecoveryRuntimeTest {
	@get:Rule val directory = TemporaryFolder(ApplicationProvider.getApplicationContext<Context>().cacheDir)

	@Test
	fun failedStopThenDeleteDoesNotResumeAtStartup() = runBlocking {
		val noBackup = directory.newFolder()
		val regularFiles = directory.newFolder()
		val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
			override fun getNoBackupFilesDir(): File = noBackup
			override fun getFilesDir(): File = regularFiles
		}
		val logger = AppLogger(context)
		try {
			logger.initialize()
			logger.awaitPhase(AppLogger.Phase.IDLE)
			logger.setEnabled(true)
			logger.awaitPhase(AppLogger.Phase.RECORDING)
			val files = File(noBackup, "verbose-diagnostics")
			assertTrue(VerboseLogStore(files).canResume)
			assertTrue(File(files, "last.txt").mkdir())

			logger.setEnabled(false)
			val stopped = logger.awaitPhase(AppLogger.Phase.IDLE)
			assertTrue(stopped.failed)
			assertTrue(stopped.hasSavedLog)
			assertTrue(VerboseLogStore(files).hasStoppedCheckpoint)
			assertTrue(logger.deleteSaved())

			val reopened = AppLogger(context)
			reopened.initialize()
			val restored = withTimeout(15_000) {
				reopened.state.first {
					it.phase != AppLogger.Phase.INITIALIZING && it.phase != AppLogger.Phase.STARTING
				}
			}
			assertEquals(AppLogger.Phase.IDLE, restored.phase)
			assertFalse(restored.hasSavedLog)
			assertFalse(PerformanceDiagnostics.enabled)
		} finally {
			PerformanceDiagnostics.stop()
		}
	}

	private suspend fun AppLogger.awaitPhase(phase: AppLogger.Phase): AppLogger.State =
		withTimeout(15_000) { state.first { it.phase == phase } }
}
