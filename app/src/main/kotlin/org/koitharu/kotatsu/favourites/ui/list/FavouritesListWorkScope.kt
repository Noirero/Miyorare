package org.koitharu.kotatsu.favourites.ui.list

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

/** Screen resume controls standalone lists; pager children keep their STARTED eligibility. */
internal class FavouritesListWorkOwner {

	private var started = false
	private var resumed = false

	fun onStart() {
		started = true
	}

	fun onResume() {
		resumed = true
	}

	fun onPause() {
		resumed = false
	}

	fun onStop() {
		started = false
		resumed = false
	}
	fun onDestroyView() = onStop()

	fun isActive(hasView: Boolean, hidden: Boolean, containerActive: Boolean? = null): Boolean =
		hasView && started && !hidden && (containerActive ?: resumed)
}

/** Keeps the last published list, but owns its producer and enrichment only while the UI is active. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class FavouritesListWorkScope(private val onStopped: () -> Unit = {}) {

	private val lock = Any()
	// A new token on resume prevents a conflated pause/resume from reusing the cancelled generation.
	private val activation = MutableStateFlow<Any?>(null)
	private var producerScope: CoroutineScope? = null

	fun setActive(value: Boolean) {
		val toCancel = synchronized(lock) {
			if (value) {
				if (activation.value == null) activation.value = Any()
				null
			} else {
				activation.value = null
				producerScope?.coroutineContext?.get(Job)
			}
		}
		// Request cancellation before returning to the lifecycle callback; never join on the caller.
		toCancel?.cancel()
	}

	fun <T> whileActive(source: Flow<T>): Flow<T> = activation.flatMapLatest { token ->
		if (token == null) {
			emptyFlow()
		} else {
			flow {
				var registeredScope: CoroutineScope? = null
				try {
					supervisorScope {
						val owner = this
						val registered = synchronized(lock) {
							if (activation.value !== token) {
								false
							} else {
								check(producerScope == null) { "List producer is already active" }
								producerScope = owner
								registeredScope = owner
								true
							}
						}
						if (!registered) return@supervisorScope
						try {
							coroutineContext.ensureActive()
							emitAll(source)
						} finally {
							coroutineContext.cancelChildren()
						}
					}
				} finally {
					if (registeredScope != null) {
						// Keep the owner registered until every child has drained, including replaced jobs.
						synchronized(lock) { producerScope = null }
						onStopped()
					}
				}
			}
		}
	}.buffer(0)

	/** Called by the producer: these jobs must not escape into viewModelScope. */
	fun launch(dispatcher: CoroutineDispatcher, block: suspend CoroutineScope.() -> Unit): Job {
		val owner = synchronized(lock) {
			checkNotNull(producerScope) { "Enrichment requires an active list producer" }
		}
		return owner.launch(dispatcher, block = block)
	}
}
