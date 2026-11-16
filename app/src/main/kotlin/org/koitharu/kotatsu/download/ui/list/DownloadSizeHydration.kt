package org.koitharu.kotatsu.download.ui.list

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/** Owns the latest eligible request snapshot independently of WorkManager emissions. */
internal class DownloadSizeHydration<T : Any>(
	scope: CoroutineScope,
	private val resolve: suspend (UUID, T) -> Long,
) {

	private class Request<T>(val input: T)

	private val lock = Any()
	private val signal = Channel<Unit>(Channel.CONFLATED)
	private val requests = LinkedHashMap<UUID, Request<T>>()
	private val pending = LinkedHashMap<UUID, Request<T>>()
	private val mutableSizes = MutableStateFlow<Map<UUID, Long>>(emptyMap())
	val sizes = mutableSizes.asStateFlow()

	init {
		// One worker bounds disk work even when an in-flight filesystem call cannot be cancelled.
		scope.launch {
			for (ignored in signal) {
				while (true) {
					val next = synchronized(lock) {
						val iterator = pending.entries.iterator()
						if (iterator.hasNext()) iterator.next().let { (id, request) ->
							iterator.remove()
							id to request
						} else null
					} ?: break
					val (id, request) = next
					val size = resolve(id, request.input)
					synchronized(lock) {
						// Identity is the generation token; replacement/removal and publication share this lock.
						if (requests[id] === request) mutableSizes.value = mutableSizes.value + (id to size)
					}
				}
			}
		}
	}

	fun replaceRequests(snapshot: Map<UUID, T>) {
		synchronized(lock) {
			val removed = requests.keys.filter { it !in snapshot }
			for (id in removed) {
				requests.remove(id)
				pending.remove(id)
			}
			val invalidated = removed.toMutableSet()
			for ((id, input) in snapshot) {
				if (requests[id]?.input == input) continue
				val request = Request(input)
				requests[id] = request
				pending[id] = request
				invalidated.add(id)
			}
			if (invalidated.isNotEmpty()) mutableSizes.value = mutableSizes.value - invalidated
		}
		signal.trySend(Unit)
	}

	fun invalidate() {
		synchronized(lock) {
			mutableSizes.value = emptyMap()
			requests.replaceAll { _, request -> Request(request.input) }
			pending.clear()
			pending.putAll(requests)
		}
		signal.trySend(Unit)
	}
}
