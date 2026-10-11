package org.koitharu.kotatsu.favourites.ui.list

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

/** Keeps the last published list, but owns its producer and enrichment only while the UI is active. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class FavouritesListWorkScope(private val onStopped: () -> Unit = {}) {

	private val active = MutableStateFlow(false)
	private var producerScope: CoroutineScope? = null

	fun setActive(value: Boolean) {
		active.value = value
	}

	fun <T> whileActive(source: Flow<T>): Flow<T> = active.flatMapLatest { enabled ->
		if (!enabled) {
			emptyFlow()
		} else {
			flow {
				try {
					supervisorScope {
						check(producerScope == null) { "List producer is already active" }
						producerScope = this
						try {
							emitAll(source)
						} finally {
							producerScope = null
							coroutineContext.cancelChildren()
						}
					}
				} finally {
					// supervisorScope has joined even replaced/cancelled enrichment jobs before resuming.
					onStopped()
				}
			}
		}
	}.buffer(0)

	/** Called by the producer: these jobs must not escape into viewModelScope. */
	fun launch(dispatcher: CoroutineDispatcher, block: suspend CoroutineScope.() -> Unit): Job =
		checkNotNull(producerScope) { "Enrichment requires an active list producer" }.launch(dispatcher, block = block)
}
