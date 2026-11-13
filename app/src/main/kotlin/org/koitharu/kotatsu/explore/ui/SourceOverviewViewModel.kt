package org.koitharu.kotatsu.explore.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.SortOrder
import javax.inject.Inject

@HiltViewModel
class SourceOverviewViewModel @Inject constructor(
    state: SavedStateHandle,
    private val factory: MangaRepository.Factory,
    private val sources: MangaSourcesRepository,
    private val settings: AppSettings,
) : ViewModel() {
    private val originalSource = MangaSource(state.get<String>(AppRouter.KEY_SOURCE))
    private val mutableState = MutableStateFlow(State(originalSource))
    val state = mutableState.asStateFlow()
    private var loadJob: Job? = null

    init { reload() }

    fun reload() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            mutableState.value = State(mutableState.value.source)
            try {
                val (source, repository, orders) = withContext(Dispatchers.IO) {
                    if (originalSource.name.startsWith("MIHON_")) sources.ensureExternalSourcesReady()
                    val source = sources.resolveActiveSource(originalSource).source
                    val repository = factory.create(source)
                    Triple(source, repository, sourceShelfOrders(repository.resolveCatalogSortOrders()))
                }
                sources.trackUsage(source)
                mutableState.value = State(source, orders.map { Shelf(it) }, loading = false, openCatalog = orders.isEmpty())
                for (order in orders) {
                    val shelf = try {
                        val entries = withContext(Dispatchers.IO) {
                            repository.getList(0, order, null).asSequence()
                                .filterNot { settings.isNsfwContentDisabled && it.isNsfw() }
                                .distinctBy { it.id }.take(12).toList()
                        }
                        Shelf(order, entries)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        Shelf(order, failed = true)
                    }
                    mutableState.update { current ->
                        current.copy(shelves = current.shelves.map { if (it.order == order) shelf else it })
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableState.update { it.copy(loading = false, failed = true) }
            }
        }
    }

    data class State(
        val source: org.koitharu.kotatsu.parsers.model.MangaSource,
        val shelves: List<Shelf> = emptyList(),
        val loading: Boolean = true,
        val openCatalog: Boolean = false,
        val failed: Boolean = false,
    )

    data class Shelf(val order: SortOrder, val entries: List<Manga>? = null, val failed: Boolean = false)
}

internal fun sourceShelfOrders(supported: Set<SortOrder>): List<SortOrder> =
    listOf(SortOrder.UPDATED, SortOrder.POPULARITY).filter { it in supported }
