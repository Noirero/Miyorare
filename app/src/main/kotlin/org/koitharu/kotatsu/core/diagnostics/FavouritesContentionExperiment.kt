package org.koitharu.kotatsu.core.diagnostics

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow

/** Only enabled in the isolated diagnostic Preview. Does not change normal build ownership. */
object FavouritesContentionExperiment {
	private val gate = FavouritesWorkloadGate { stage, fields ->
		DetailsDiagnostic.mark(DetailsDiagnostic.currentSession(), stage, fields)
	}

	fun register(owner: Any, space: String, categoryId: Long) {
		if (DetailsDiagnostic.enabled) gate.register(System.identityHashCode(owner), space, categoryId)
	}
	fun unregister(owner: Any) {
		if (DetailsDiagnostic.enabled) gate.unregister(System.identityHashCode(owner))
	}
	fun visibility(owner: Any, visible: Boolean, reason: String) {
		if (DetailsDiagnostic.enabled) gate.visibility(System.identityHashCode(owner), visible, reason)
	}
	fun removeContainer(owner: Any) {
		if (DetailsDiagnostic.enabled) gate.removeContainer(System.identityHashCode(owner))
	}
	fun <T> pauseListWhenBackground(owner: Any, source: Flow<T>): Flow<T> =
		if (DetailsDiagnostic.enabled) gate.gate(System.identityHashCode(owner), source) else source
	fun emission(owner: Any) {
		if (DetailsDiagnostic.enabled) gate.emission(System.identityHashCode(owner))
	}
	fun trackEnrichment(owner: Any, job: Job) {
		if (DetailsDiagnostic.enabled) gate.trackAuxiliary(System.identityHashCode(owner), job)
	}
	fun snapshot(): Map<String, Any?> = gate.snapshot()
}
