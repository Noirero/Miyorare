package org.koitharu.kotatsu.discovery.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koitharu.kotatsu.discovery.data.TrackerCatalogRepository

private const val DEFAULT_HOME_REFRESH_TIMEOUT_MS = 12_000L

data class DiscoveryHubState(
	val request: CatalogHomeRequest,
	val home: TrackerCatalogHome? = null,
	val loading: Boolean = true,
	val refreshing: Boolean = false,
	val stale: Boolean = false,
	val failure: CatalogFailure? = null,
)

/** The production loader used by the ViewModel; request identity also protects against late tasks. */
class DiscoveryHubController(
	private val repository: TrackerCatalogRepository,
	private val scope: CoroutineScope,
	initial: CatalogHomeRequest,
	private val now: () -> Long = System::currentTimeMillis,
	private val refreshTimeoutMs: Long = DEFAULT_HOME_REFRESH_TIMEOUT_MS,
) {
	private val mutableState = MutableStateFlow(DiscoveryHubState(initial))
	val state: StateFlow<DiscoveryHubState> = mutableState
	private var generation = 0L
	private var job: Job? = null

	fun load(key: CatalogHomeRequest = state.value.request, force: Boolean = false) {
		val ticket = ++generation
		job?.cancel()
		val retained = state.value.home?.takeIf { state.value.request == key }
		mutableState.value = DiscoveryHubState(key, retained, loading = retained == null, refreshing = retained != null)
		job = scope.launch {
			fun current() = generation == ticket && mutableState.value.request == key
			try {
				val cached = retained ?: repository.cached(key)
				if (!current()) return@launch
				if (cached != null) {
					val fresh = CatalogCachePolicy.isFresh(cached.savedAt, now()) && cached.sections.none { it.failure != null }
					mutableState.value = DiscoveryHubState(key, cached, loading = false, refreshing = force || !fresh, stale = !fresh)
					if (fresh && !force) return@launch
				}
				val home = withTimeoutOrNull(refreshTimeoutMs) { repository.refresh(key) }
				if (home == null) {
					if (current()) {
						mutableState.value = mutableState.value.copy(
							loading = false,
							refreshing = false,
							stale = mutableState.value.home != null,
							failure = CatalogFailure(CatalogFailureKind.PROVIDER),
						)
					}
					return@launch
				}
				if (current()) mutableState.value = DiscoveryHubState(key, home, loading = false, stale = home.sections.any { it.failure != null })
			} catch (e: CancellationException) { throw e }
			catch (e: Exception) { if (current()) mutableState.value = mutableState.value.copy(loading = false, refreshing = false, stale = mutableState.value.home != null, failure = e.catalogFailure()) }
		}
	}
}
