package org.koitharu.kotatsu.discovery.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.alternatives.domain.AlternativeSearchEvent
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.discovery.data.TrackerCatalogRepository
import org.koitharu.kotatsu.discovery.domain.*
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.levenshteinDistance
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import javax.inject.Inject

enum class CatalogScreenMode { SEARCH, SECTION, DETAILS }
object CatalogArguments {
	const val MODE = "catalog_mode"
	const val SERVICE = "catalog_service"
	const val KIND = "catalog_media_kind"
	const val ID = "catalog_remote_id"
	const val SECTION = "catalog_section"
	const val FILTER = "catalog_filter"
	const val QUERY = "catalog_query"
}
data class CatalogResolutionState(val visible: Boolean = false, val searching: Boolean = false, val candidates: List<Manga> = emptyList(), val failedSources: Boolean = false, val allSources: Boolean = false)
data class CatalogScreenState(
	val service: ScrobblerService,
	val mode: CatalogScreenMode,
	val filter: CatalogFilter,
	val query: String = "",
	val items: List<TrackerCatalogItem> = emptyList(),
	val details: TrackerCatalogDetails? = null,
	val loading: Boolean = false,
	val loadingMore: Boolean = false,
	val nextPage: Int? = null,
	val failure: CatalogFailure? = null,
	val known: List<Manga> = emptyList(),
	val resolution: CatalogResolutionState = CatalogResolutionState(),
	val searched: Boolean = false,
)

@HiltViewModel
class TrackerCatalogViewModel @Inject constructor(
	private val saved: SavedStateHandle,
	private val repository: TrackerCatalogRepository,
	private val resolver: CatalogSourceResolution,
	private val settings: AppSettings,
) : ViewModel() {
	private val selectedService = runCatching { ScrobblerService.valueOf(checkNotNull(saved.get<String>(CatalogArguments.SERVICE))) }.getOrNull()?.takeIf { it in repository.services }
	private val service = selectedService ?: ScrobblerService.ANILIST
	private val mode = runCatching { CatalogScreenMode.valueOf(checkNotNull(saved.get<String>(CatalogArguments.MODE))) }.getOrDefault(CatalogScreenMode.SEARCH)
	private val initialFilter = runCatching { CatalogFilter.valueOf(checkNotNull(saved.get<String>(CatalogArguments.FILTER))) }.getOrDefault(CatalogFilter.ALL)
	private val mutableState = MutableStateFlow(CatalogScreenState(service, mode, initialFilter, saved.get<String>(CatalogArguments.QUERY).orEmpty()))
	val state: StateFlow<CatalogScreenState> = mutableState
	@Volatile private var generation = 0L
	private var loadJob: Job? = null
	private var sourceJob: Job? = null
	@Volatile private var sourceGeneration = 0L
	private var adult = !settings.isNsfwContentDisabled
	private var explicitlyChosen: Manga? = null
	private data class SourceEvent(val generation: Long, val key: TrackerCatalogKey, val manga: Manga)
	private val sourceEvents = Channel<SourceEvent>(Channel.BUFFERED)
	// Recheck on delivery too: an Activity may have been paused while the choice was queued.
	val openSource = sourceEvents.receiveAsFlow().filter { event ->
		try { event.generation == generation && resolver.canOpen(event.manga, event.key.kind) && event.generation == generation }
		catch (e: CancellationException) { throw e }
		catch (_: Exception) { false }
	}.map { it.manga }
	val key: TrackerCatalogKey? = runCatching {
		TrackerCatalogKey(service, CatalogMediaKind.valueOf(checkNotNull(saved.get<String>(CatalogArguments.KIND))), checkNotNull(saved.get<String>(CatalogArguments.ID)))
	}.getOrNull()
	val section: CatalogSectionKey? = runCatching {
		CatalogSectionKey(service, CatalogMediaKind.valueOf(checkNotNull(saved.get<String>(CatalogArguments.KIND))), CatalogSectionType.valueOf(checkNotNull(saved.get<String>(CatalogArguments.SECTION))))
	}.getOrNull()

	init {
		if (mode != CatalogScreenMode.SEARCH || state.value.query.isNotBlank()) reload()
		viewModelScope.launch { settings.observeChanges().collect {
			if (adult != !settings.isNsfwContentDisabled) {
				adult = !settings.isNsfwContentDisabled
				sourceJob?.cancel()
				mutableState.update { it.copy(items = emptyList(), details = null, known = emptyList(), resolution = CatalogResolutionState()) }
				reload()
			}
		} }
	}
	fun updateQuery(value: String) {
		if (state.value.query == value) return
		saved[CatalogArguments.QUERY] = value
		generation++
		loadJob?.cancel()
		mutableState.update { it.copy(query = value, searched = false, items = emptyList(), nextPage = null, loading = false, loadingMore = false, failure = null) }
	}
	fun selectFilter(filter: CatalogFilter) { saved[CatalogArguments.FILTER] = filter.name; mutableState.update { it.copy(filter = filter) }; reload() }
	fun reload() = load(1, append = false)
	fun nextPage() { if (!state.value.loading && !state.value.loadingMore) state.value.nextPage?.let { load(it, append = true) } }
	fun retry() { if (state.value.items.isNotEmpty() && state.value.nextPage != null) nextPage() else reload() }

	private fun load(page: Int, append: Boolean) {
		if (selectedService == null) { mutableState.update { it.copy(failure = CatalogFailure(CatalogFailureKind.UNSUPPORTED), loading = false) }; return }
		val token = ++generation
		loadJob?.cancel()
		val request = state.value
		if (mode == CatalogScreenMode.SEARCH && request.query.isBlank()) { mutableState.update { it.copy(items = emptyList(), loading = false, failure = null) }; return }
		mutableState.update { it.copy(loading = !append, loadingMore = append, failure = null, searched = mode == CatalogScreenMode.SEARCH, items = if (append || mode == CatalogScreenMode.DETAILS) it.items else emptyList()) }
		loadJob = viewModelScope.launch {
			try {
				if (mode == CatalogScreenMode.DETAILS) {
					val details = repository.details(checkNotNull(key), adult)
					val known = resolver.known(details.item.key)
					if (token == generation) mutableState.update { it.copy(details = details, known = known, loading = false) }
				} else {
					val result = if (mode == CatalogScreenMode.SECTION) repository.browse(checkNotNull(section), adult, page)
						else repository.search(service, request.query.trim(), request.filter, adult, page)
					if (token == generation) {
						mutableState.update { it.copy(items = ((if (append) it.items else emptyList()) + result.items).distinctBy { item -> item.key }, nextPage = result.nextPage?.takeIf { it > page }, loading = false, loadingMore = false) }
					}
				}
			} catch (e: CancellationException) { throw e }
			catch (e: Exception) { if (token == generation) mutableState.update { it.copy(loading = false, loadingMore = false, failure = e.catalogFailure()) } }
		}
	}

	fun startResolution(allSources: Boolean = false) {
		val details = state.value.details ?: return
		val token = generation
		val sourceToken = ++sourceGeneration
		sourceJob?.cancel()
		if (!allSources && state.value.known.isNotEmpty()) {
			mutableState.update { it.copy(resolution = CatalogResolutionState(visible = true, candidates = it.known)) }
			return
		}
		mutableState.update { it.copy(resolution = CatalogResolutionState(visible = true, searching = true, allSources = allSources)) }
		sourceJob = viewModelScope.launch(Dispatchers.Default) {
			val titles = (listOf(details.item.title) + details.alternativeTitles).take(6).map { it.take(128).lowercase(java.util.Locale.ROOT) }
			val scores = mutableMapOf<Pair<String, Long>, Int>()
			fun current() = token == generation && sourceToken == sourceGeneration
			try {
				resolver.search(details, allSources).takeWhile { state.value.resolution.candidates.size < CatalogCachePolicy.MAX_CANDIDATES }.collect { event ->
					if (!current()) return@collect
					when (event) {
						is AlternativeSearchEvent.Result -> {
							val identity = event.manga.source.name to event.manga.id
							// Rank bounded title prefixes once per candidate; this never establishes identity.
							scores.getOrPut(identity) { titles.minOf { event.manga.title.take(128).lowercase(java.util.Locale.ROOT).levenshteinDistance(it) } }
							mutableState.update { old ->
								if (!current()) old else {
									val candidates = (old.resolution.candidates + event.manga).distinctBy { it.source.name to it.id }.sortedBy { scores[it.source.name to it.id] ?: Int.MAX_VALUE }
									old.copy(resolution = old.resolution.copy(candidates = candidates))
								}
							}
						}
						is AlternativeSearchEvent.SourceFinished -> if (event.error != null) mutableState.update { if (current()) it.copy(resolution = it.resolution.copy(failedSources = true)) else it }
					}
				}
			} catch (e: CancellationException) { throw e }
			catch (_: Exception) { mutableState.update { if (current()) it.copy(resolution = it.resolution.copy(failedSources = true)) else it } }
			finally { mutableState.update { if (current()) it.copy(resolution = it.resolution.copy(searching = false)) else it } }
		}
	}
	fun closeResolution() { sourceGeneration++; sourceJob?.cancel(); mutableState.update { it.copy(resolution = CatalogResolutionState()) } }
	fun read() {
		val details = state.value.details ?: return
		val token = generation
		viewModelScope.launch {
			try {
				val chosen = explicitlyChosen?.takeIf { resolver.canOpen(it, details.item.key.kind) }
				val known = chosen?.let(::listOf) ?: resolver.known(details.item.key)
				if (token != generation) return@launch
				mutableState.update { it.copy(known = known) }
				if (known.size == 1) sourceEvents.send(SourceEvent(token, details.item.key, known.single())) else startResolution()
			} catch (e: CancellationException) { throw e }
			catch (_: Exception) { if (token == generation) startResolution() }
		}
	}
	fun chooseSource(manga: Manga) {
		val details = state.value.details ?: return
		val token = generation
		viewModelScope.launch {
			try {
				if (resolver.canOpen(manga, details.item.key.kind) && token == generation) {
					explicitlyChosen = manga
					mutableState.update { it.copy(known = listOf(manga)) }
					closeResolution()
					sourceEvents.send(SourceEvent(token, details.item.key, manga))
				}
			} catch (e: CancellationException) { throw e }
			catch (_: Exception) { if (token == generation) mutableState.update { it.copy(resolution = it.resolution.copy(failedSources = true)) } }
		}
	}
}
