package org.koitharu.kotatsu.core.cache

import kotlinx.coroutines.Deferred

class SafeDeferred<T>(
	private val delegate: Deferred<Result<T>>,
) {

	suspend fun await(): T {
		return delegate.await().getOrThrow()
	}

	suspend fun awaitOrNull(): T? {
		return delegate.await().getOrNull()
	}

	fun invokeOnCompletion(block: () -> Unit) {
		delegate.invokeOnCompletion { block() }
	}

	fun cancel() {
		delegate.cancel()
	}
}
