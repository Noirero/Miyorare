package org.koitharu.kotatsu.backup

import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.progress.Progress
import java.util.concurrent.atomic.AtomicLong

/** Shared progress/result state for every manual backup/restore entry point. */
object BackupOperationTracker {

	enum class Kind(
		@StringRes val titleRes: Int,
		val isRestore: Boolean,
	) {
		LOCAL_BACKUP(R.string.create_backup, false),
		LOCAL_RESTORE(R.string.restore_backup, true),
		MIHON_RESTORE(R.string.restore_from_tachiyomi, true),
		MIHON_EXPORT(R.string.export_to_mihon, false),
	}

	sealed interface State {
		data object Idle : State

		data class Running(
			val id: Long,
			val kind: Kind,
			@StringRes val stageRes: Int,
			val progress: Progress,
		) : State

		data class Finished(
			val id: Long,
			val kind: Kind,
			val outcome: Outcome,
			@StringRes val stageRes: Int,
			val details: String? = null,
			val errorType: String? = null,
			val errorMessage: String? = null,
			val errorLocation: String? = null,
		) : State
	}

	enum class Outcome {
		SUCCESS,
		CANCELLED,
		FAILED,
	}

	private val nextId = AtomicLong(1L)
	private val mutableState = MutableStateFlow<State>(State.Idle)
	val state: StateFlow<State> = mutableState.asStateFlow()

	fun start(kind: Kind, @StringRes stageRes: Int): Long {
		val id = nextId.getAndIncrement()
		mutableState.value = State.Running(id, kind, stageRes, Progress.INDETERMINATE)
		return id
	}

	fun update(
		kind: Kind,
		progress: Progress,
		@StringRes stageRes: Int = currentStage(kind),
	) {
		val current = mutableState.value as? State.Running ?: return
		if (current.kind != kind) return
		mutableState.value = current.copy(stageRes = stageRes, progress = progress)
	}

	fun updateStage(
		kind: Kind,
		@StringRes stageRes: Int,
		progress: Progress = Progress.INDETERMINATE,
	) {
		val current = mutableState.value as? State.Running
		val effectiveProgress = if (kind == Kind.MIHON_RESTORE && progress.total == LEGACY_MIHON_STAGE_TOTAL) {
			// The Mihon screen used to report only two coarse stages (1/2 and 2/2). Once exact chapter
			// progress is available, keep it across the favourites-verification stage instead of
			// replacing e.g. 14000/14000 with 2/2. Before chapter counting starts, stay indeterminate.
			current?.takeIf { it.kind == kind }?.progress
				?.takeUnless { it.isIndeterminate }
				?: Progress.INDETERMINATE
		} else {
			progress
		}
		update(kind, effectiveProgress, stageRes)
	}

	fun success(kind: Kind, details: String? = null) {
		val current = mutableState.value as? State.Running ?: return
		if (current.kind != kind) return
		mutableState.value = State.Finished(
			id = current.id,
			kind = kind,
			outcome = Outcome.SUCCESS,
			stageRes = current.stageRes,
			details = details,
		)
	}

	fun cancelled(kind: Kind, details: String? = null) {
		val current = mutableState.value as? State.Running ?: return
		if (current.kind != kind) return
		mutableState.value = State.Finished(
			id = current.id,
			kind = kind,
			outcome = Outcome.CANCELLED,
			stageRes = current.stageRes,
			details = details,
		)
	}

	fun failed(kind: Kind, error: Throwable, details: String? = null) {
		val current = mutableState.value as? State.Running ?: return
		if (current.kind != kind) return
		val root = generateSequence(error) { it.cause }.last()
		mutableState.value = State.Finished(
			id = current.id,
			kind = kind,
			outcome = Outcome.FAILED,
			stageRes = current.stageRes,
			details = details,
			errorType = root.javaClass.simpleName.ifBlank { root.javaClass.name },
			errorMessage = root.message?.takeIf { it.isNotBlank() },
			errorLocation = findErrorLocation(error),
		)
	}

	fun failCurrent(error: Throwable, details: String? = null) {
		val current = mutableState.value as? State.Running ?: return
		failed(current.kind, error, details)
	}

	fun acknowledge(id: Long) {
		val current = mutableState.value as? State.Finished ?: return
		if (current.id == id) mutableState.value = State.Idle
	}

	private fun currentStage(kind: Kind): Int {
		val running = mutableState.value as? State.Running
		return running?.takeIf { it.kind == kind }?.stageRes ?: R.string.backup_operation_processing
	}

	private fun findErrorLocation(error: Throwable): String? {
		val chain = generateSequence(error) { it.cause }.toList().asReversed()
		val frame = chain.asSequence()
			.flatMap { it.stackTrace.asSequence() }
			.firstOrNull { it.className.startsWith("org.koitharu.kotatsu.") }
			?: chain.asSequence().flatMap { it.stackTrace.asSequence() }.firstOrNull()
			?: return null
		val file = frame.fileName ?: frame.className.substringAfterLast('.')
		return if (frame.lineNumber > 0) {
			"$file:${frame.lineNumber} — ${frame.className}.${frame.methodName}"
		} else {
			"$file — ${frame.className}.${frame.methodName}"
		}
	}

	private const val LEGACY_MIHON_STAGE_TOTAL = 2
}
