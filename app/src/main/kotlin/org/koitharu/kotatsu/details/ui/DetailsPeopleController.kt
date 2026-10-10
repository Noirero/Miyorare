package org.koitharu.kotatsu.details.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.scrobbling.common.domain.model.AssociatedTrackerDetails
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerDetailsReadPolicy
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerPerson
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerResult

internal data class PeopleAssociation(val serviceId: Int, val rateId: Int, val targetId: Long)

/** Screen request identity. No parser id is ever interpreted as a provider target. */
internal data class DetailsPeopleContext(
	val mangaId: Long,
	val source: String,
	val url: String,
	val associations: Set<PeopleAssociation>,
	val sessions: Map<ScrobblerService, Long>,
	val services: Set<ScrobblerService>,
	val policy: TrackerDetailsReadPolicy,
)

data class DetailsPeopleUiState(
	val isRequested: Boolean = false,
	val isLoading: Boolean = false,
	val providers: List<AssociatedTrackerDetails> = emptyList(),
	val isUnavailable: Boolean = false,
)

/**
 * One screen-owned flight, sequential providers, no cache/persistence/background scope.
 * All controls run on the ViewModel's main dispatcher. Cancellation and identity checks are
 * deliberately both used: a provider/test double may finish after ignoring cancellation.
 */
internal class DetailsPeopleController(
	private val scope: CoroutineScope,
	private val read: suspend (DetailsPeopleContext, ScrobblerService) -> List<AssociatedTrackerDetails>,
	private val isCurrent: suspend (DetailsPeopleContext) -> Boolean,
) {
	private val _state = MutableStateFlow(DetailsPeopleUiState())
	val state = _state.asStateFlow()
	private var context: DetailsPeopleContext? = null
	private var active = false
	private var demanded = false
	private var generation = 0L
	private var job: Job? = null
	private val completed = mutableSetOf<ScrobblerService>()

	fun updateContext(value: DetailsPeopleContext?) {
		if (context == value) return
		val old = context
		invalidate()
		context = value
		if (old != null && (old.mangaId != value?.mangaId || old.source != value?.source || old.url != value?.url)) {
			demanded = false
		}
		completed.clear()
		_state.value = DetailsPeopleUiState(isRequested = demanded)
		startIfNeeded()
	}

	fun setActive(value: Boolean) {
		if (active == value) return
		active = value
		if (!value) {
			invalidate()
			_state.value = _state.value.copy(isLoading = false)
		} else {
			startIfNeeded()
		}
	}

	fun request() {
		demanded = true
		_state.value = _state.value.copy(isRequested = true)
		startIfNeeded()
	}

	/** Retry only this provider; other providers' valid data remains visible. */
	fun retry(service: ScrobblerService) {
		if (service !in context?.services.orEmpty() || _state.value.isLoading) return
		completed.remove(service)
		_state.value = _state.value.copy(providers = _state.value.providers.filter { it.service != service })
		startIfNeeded()
	}

	fun refresh() {
		invalidate()
		completed.clear()
		_state.value = DetailsPeopleUiState(isRequested = demanded)
		startIfNeeded()
	}

	private fun invalidate() {
		generation++
		job?.cancel()
		job = null
	}

	private fun startIfNeeded() {
		val key = context ?: return
		if (!active || !demanded || !key.policy.allowsNetwork || job != null) return
		val pending = key.services.filter { it !in completed }.sortedBy { it.id }
		if (pending.isEmpty()) return
		val ticket = ++generation
		_state.value = _state.value.copy(isLoading = true, isUnavailable = false)
		// Lazy start assigns the flight before any synchronous fake/empty read can complete.
		val flight = scope.launch(start = CoroutineStart.LAZY) {
			try {
				for (service in pending) {
					if (!accepts(key, ticket)) return@launch
					val result = read(key, service)
					if (!accepts(key, ticket)) return@launch
					completed += service
					_state.value = _state.value.copy(
						providers = (_state.value.providers.filter { it.service != service } + result).sortedBy { it.service.id },
					)
				}
			} catch (e: CancellationException) {
				throw e
			} catch (_: Exception) {
				// B1 isolates provider errors. This covers only the optional orchestration/DB boundary.
				if (accepts(key, ticket)) _state.value = _state.value.copy(isUnavailable = true)
			} finally {
				if (ticket == generation) {
					job = null
					_state.value = _state.value.copy(isLoading = false)
				}
			}
		}
		job = flight
		flight.start()
	}

	private suspend fun accepts(key: DetailsPeopleContext, ticket: Long): Boolean {
		currentCoroutineContext().ensureActive()
		if (ticket != generation || context != key || !active || !key.policy.allowsNetwork) return false
		val valid = try {
			isCurrent(key)
		} catch (e: CancellationException) {
			throw e
		} catch (_: Exception) {
			false // Unknown identity/privacy is not permission to publish or request metadata.
		}
		currentCoroutineContext().ensureActive()
		if (!valid && ticket == generation) {
			completed.clear()
			_state.value = DetailsPeopleUiState(isRequested = demanded)
		}
		return valid && ticket == generation && context == key && active
	}
}

internal fun TrackerResult<TrackerPerson>.peopleItems(): List<TrackerPerson> = when (this) {
	is TrackerResult.Success -> items
	is TrackerResult.Partial -> items
	else -> emptyList()
}
