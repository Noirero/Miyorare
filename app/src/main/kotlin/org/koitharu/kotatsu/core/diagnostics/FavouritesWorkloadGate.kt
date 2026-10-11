package org.koitharu.kotatsu.core.diagnostics

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.withContext

/** Experiment only. Cancels and joins one named list producer; never emits an empty/reset value. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class FavouritesWorkloadGate(
	private val changed: (String, Map<String, Any?>) -> Unit = { _, _ -> },
) {
	private val foreground = MutableStateFlow(false)
	private val containers = HashMap<Int, Boolean>()
	private val slots = LinkedHashMap<Int, Slot>()
	private var generation = 0L

	private class Slot(val space: String, val categoryId: Long) {
		var phase = "REGISTERED"
		var starts = 0L
		var completions = 0L
		var emissions = 0L
		val auxiliary = HashSet<Job>()
	}

	@Synchronized
	fun register(id: Int, space: String, categoryId: Long) {
		slots[id] = Slot(space, categoryId)
		generation++
	}

	@Synchronized
	fun unregister(id: Int) { slots.remove(id); generation++ }

	@Synchronized
	fun visibility(id: Int, visible: Boolean, reason: String) {
		containers[id] = visible
		val next = containers.values.any { it }
		if (next != foreground.value) {
			generation++
			foreground.value = next
			changed("favourites_pause_requested", mapOf("foreground" to next, "reason" to reason, "generation" to generation))
		}
	}

	@Synchronized
	fun removeContainer(id: Int) {
		containers.remove(id)
		foreground.value = containers.values.any { it }
		generation++
	}

	fun <T> gate(id: Int, source: Flow<T>): Flow<T> = foreground.flatMapLatest { visible ->
		flow {
			if (visible) {
				phase(id, "ACTIVE", start = true)
				try {
					emitAll(source)
				} finally {
					phase(id, "DRAINING", completed = true)
					// These jobs belong to the list mapper, but were launched in viewModelScope.
					// Join every tracked job, including an older cancelled job whose handle was replaced.
					withContext(NonCancellable) { drainAuxiliary(id) }
				}
			} else {
				// flatMapLatest has cancelled AND joined its previous child before reaching here.
				phase(id, "PAUSED")
			}
		}
	}.buffer(0).onCompletion { phase(id, "STOPPED") }

	@Synchronized
	private fun phase(id: Int, phase: String, start: Boolean = false, completed: Boolean = false) {
		val slot = slots[id] ?: return
		slot.phase = phase
		if (start) slot.starts++
		if (completed) slot.completions++
		generation++
		changed("favourites_workload_phase", mapOf("ownerId" to id, "phase" to phase,
			"starts" to slot.starts, "completions" to slot.completions, "auxiliaryJobs" to slot.auxiliary.size,
			"generation" to generation))
	}

	@Synchronized
	fun emission(id: Int) { slots[id]?.let { it.emissions++; generation++ } }

	fun trackAuxiliary(id: Int, job: Job) {
		synchronized(this) { slots[id]?.auxiliary?.add(job); generation++ }
		job.invokeOnCompletion {
			synchronized(this) { slots[id]?.auxiliary?.remove(job); generation++ }
		}
	}

	private suspend fun drainAuxiliary(id: Int) {
		val jobs = synchronized(this) { slots[id]?.auxiliary?.toList().orEmpty() }
		jobs.forEach { it.cancel() }
		jobs.forEach { it.cancelAndJoin() }
	}

	@Synchronized
	fun snapshot(): Map<String, Any?> = mapOf(
		"scope" to "favourites_list_refresh_and_its_card_enrichment_only",
		"foreground" to foreground.value,
		"generation" to generation,
		"registeredOwners" to slots.size,
		"pausedConfirmed" to (!foreground.value && slots.isNotEmpty() &&
			slots.values.all { it.phase == "PAUSED" && it.auxiliary.isEmpty() }),
		"owners" to slots.map { (id, slot) -> mapOf(
			"ownerId" to id, "space" to slot.space, "categoryId" to slot.categoryId,
			"phase" to slot.phase, "starts" to slot.starts, "completions" to slot.completions,
			"emissions" to slot.emissions, "auxiliaryJobs" to slot.auxiliary.size,
		) },
		"groupsAndCounts" to "baseline_not_paused",
	)
}
