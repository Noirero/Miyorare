package org.koitharu.kotatsu.core.logs

import android.content.Context
import android.net.Uri
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.core.diagnostics.PerformanceDiagnostics
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppLogger @Inject constructor(
	@ApplicationContext private val context: Context,
) {
	enum class Phase { INITIALIZING, IDLE, STARTING, RECORDING, STOPPING, STOP_FAILED }

	data class State(
		val phase: Phase = Phase.INITIALIZING,
		val hasSavedLog: Boolean = false,
		val capturePartial: Boolean = false,
		val savedPartial: Boolean = false,
		val failed: Boolean = false,
	)

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val files = Mutex()
	private val initialized = AtomicBoolean()
	private val store = VerboseLogStore(File(context.noBackupFilesDir, "verbose-diagnostics"))
	private val mutableState = MutableStateFlow(State())
	val state: StateFlow<State> = mutableState
	private var checkpoint: Job? = null
	private var unsavedReport: String? = null

	fun initialize() {
		if (!initialized.compareAndSet(false, true)) return
		scope.launch {
			files.withLock {
				val legacy = File(context.filesDir, "logs")
				listOf("session.log", "session.log.old", "previous_session.log").forEach { File(legacy, it).delete() }
				val resume = store.canResume
				val hasSaved = store.hasSaved
				mutableState.value = State(
					phase = if (resume) Phase.STARTING else Phase.IDLE,
					hasSavedLog = hasSaved || store.hasStoppedCheckpoint,
					savedPartial = if (hasSaved) runCatching { store.savedIsPartial() }.getOrDefault(true)
						else store.hasStoppedCheckpoint,
				)
				if (resume) start(resume = true)
			}
		}
	}

	fun setEnabled(enabled: Boolean) {
		fun accepts(phase: Phase): Boolean =
			if (enabled) phase == Phase.IDLE else phase == Phase.RECORDING || phase == Phase.STOP_FAILED
		val next = if (enabled) Phase.STARTING else Phase.STOPPING
		val previous = mutableState.getAndUpdate { state ->
			if (accepts(state.phase)) state.copy(phase = next, failed = if (enabled) false else state.failed) else state
		}
		if (!accepts(previous.phase)) return
		scope.launch {
			files.withLock {
				if (enabled) {
					if (persistUnsaved()) start(resume = false)
					else mutableState.update { it.copy(phase = Phase.IDLE, failed = true) }
				} else {
					stop()
				}
			}
		}
	}

	suspend fun export(uri: Uri): Boolean = withContext(Dispatchers.IO) {
		files.withLock {
			persistUnsaved()
			store.export(unsavedReport) { context.contentResolver.openOutputStream(uri, "wt") }
		}
	}

	suspend fun deleteSaved(): Boolean = withContext(Dispatchers.IO) {
		files.withLock {
			if (mutableState.value.phase != Phase.IDLE) return@withLock false
			runCatching { store.deleteSaved() }.onSuccess {
				unsavedReport = null
				mutableState.update { it.copy(hasSavedLog = false, savedPartial = false, failed = false) }
			}.isSuccess
		}
	}

	private fun start(resume: Boolean) {
		var partial = resume
		if (runCatching { store.prepare(resume) }.isFailure) partial = true
		if (runCatching { PerformanceDiagnostics.start(context) }.isFailure) {
			runCatching { PerformanceDiagnostics.stop() }
			val resumeRevoked = revokeResume()
			mutableState.update {
				it.copy(phase = if (resumeRevoked) Phase.IDLE else Phase.STOP_FAILED, failed = true)
			}
			return
		}
		if (!saveCheckpoint()) partial = true
		mutableState.update { it.copy(phase = Phase.RECORDING, capturePartial = partial, failed = false) }
		checkpoint = scope.launch {
			while (isActive) {
				delay(5_000)
				val saved = saveCheckpoint()
				if (!saved || PerformanceDiagnostics.captureFailed()) {
					mutableState.update { it.copy(capturePartial = true) }
				}
			}
		}
	}

	private fun saveCheckpoint(): Boolean {
		val report = PerformanceDiagnostics.exportSnapshot() ?: return false
		return runCatching { store.checkpoint(header() + report) }.isSuccess
	}

	private suspend fun stop() {
		// Commit the user's choice before waiting for the checkpoint or writing the report.
		val resumeRevoked = revokeResume()
		checkpoint?.cancelAndJoin()
		checkpoint = null
		if (PerformanceDiagnostics.captureFailed()) mutableState.update { it.copy(capturePartial = true) }
		val report = PerformanceDiagnostics.stop()
		if (report.isNotEmpty()) unsavedReport = header() + report
		val saved = persistUnsaved()
		mutableState.update {
			it.copy(
				phase = if (resumeRevoked) Phase.IDLE else Phase.STOP_FAILED,
				hasSavedLog = store.hasSaved || unsavedReport != null || store.hasStoppedCheckpoint,
				savedPartial = !saved || it.savedPartial,
				capturePartial = false,
				failed = !saved || !resumeRevoked,
			)
		}
	}

	private fun revokeResume(): Boolean = runCatching { store.revokeResumePermission() }.isSuccess

	private fun persistUnsaved(): Boolean {
		val report = unsavedReport ?: return true
		return try {
			val partial = store.finish(report, mutableState.value.capturePartial || mutableState.value.failed)
			unsavedReport = null
			mutableState.update { it.copy(hasSavedLog = true, savedPartial = partial, failed = false) }
			true
		} catch (_: IOException) {
			false
		} catch (_: SecurityException) {
			false
		}
	}

	private fun header(): String = "Miyorare diagnostic recording\n" +
		"version=${BuildConfig.VERSION_NAME} build=${BuildConfig.VERSION_CODE} type=${BuildConfig.BUILD_TYPE} " +
		"android_api=${Build.VERSION.SDK_INT}\n"
}
