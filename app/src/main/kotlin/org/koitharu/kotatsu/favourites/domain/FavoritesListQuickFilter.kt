package org.koitharu.kotatsu.favourites.domain

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.model.isNovelSource
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.list.domain.MangaListQuickFilter
import org.koitharu.kotatsu.list.ui.model.ExtensionFilter
import org.koitharu.kotatsu.mihon.MihonExtensionManager

@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class FavouriteShelfFilterState(
	private val delegate: StateFlow<FavouriteQuickFilterStore.Snapshot>,
	private val contentType: StateFlow<FavouriteContentType>,
	private val hideDownloaded: Boolean,
	private val hideSources: Boolean,
) : StateFlow<Set<ListFilterOption>> {

	override val value: Set<ListFilterOption>
		get() = filter(delegate.value.filtersFor(contentType.value))

	override val replayCache: List<Set<ListFilterOption>>
		get() = listOf(value)

	override suspend fun collect(collector: FlowCollector<Set<ListFilterOption>>): Nothing {
		combine(delegate, contentType) { snapshot, type ->
			filter(snapshot.filtersFor(type))
		}.collect(collector)
		error("Favourite filter state collection completed")
	}

	private fun filter(filters: Set<ListFilterOption>): Set<ListFilterOption> = filters.filterTo(LinkedHashSet()) { option ->
		(!hideDownloaded || option != ListFilterOption.Downloaded) &&
			(!hideSources || option !is ListFilterOption.Source)
	}
}

class FavoritesListQuickFilter @AssistedInject constructor(
	@Assisted private val categoryId: Long,
	@Assisted private val favouriteSpace: FavouriteSpace,
	private val settings: AppSettings,
	private val repository: FavouritesRepository,
	private val filterStore: FavouriteQuickFilterStore,
	private val contentTypeStore: FavouriteContentTypeStore,
	private val mihonExtensionManager: MihonExtensionManager,
) : MangaListQuickFilter(settings) {

	private val isDownloadedShelf = categoryId == DOWNLOADED_FAVOURITES_CATEGORY_ID
	private val isLocalShelf = categoryId == LOCAL_FAVOURITES_CATEGORY_ID

	private val categoryAppliedOptions: StateFlow<Set<ListFilterOption>> = FavouriteShelfFilterState(
		delegate = filterStore.state(favouriteSpace),
		contentType = contentTypeStore.selectedType,
		hideDownloaded = isDownloadedShelf || isLocalShelf,
		hideSources = isLocalShelf,
	)

	init {
		isStateFilterEnabled = false
	}

	override val appliedOptions
		get() = categoryAppliedOptions

	override fun setFilterOption(option: ListFilterOption, isApplied: Boolean) {
		if (isLocalShelf && (option == ListFilterOption.Downloaded || option is ListFilterOption.Source)) return
		filterStore.set(contentTypeStore.selectedType.value, option, isApplied, favouriteSpace)
	}

	override fun toggleFilterOption(option: ListFilterOption) {
		if (isLocalShelf && (option == ListFilterOption.Downloaded || option is ListFilterOption.Source)) return
		filterStore.toggle(contentTypeStore.selectedType.value, option, favouriteSpace)
	}

	override fun clearFilter() {
		filterStore.clear(contentTypeStore.selectedType.value, favouriteSpace)
	}

	override suspend fun getAvailableFilterOptions(): List<ListFilterOption> = emptyList()

	override suspend fun getAdditionalChips(
		selectedOptions: Set<ListFilterOption>,
	): List<ChipsView.ChipModel> = buildList {
		val progress = selectedOptions.filterIsInstance<ListFilterOption.ReadingProgress>().firstOrNull()
		val continueReading = ListFilterOption.ReadingProgress.IN_PROGRESS
		add(
			ChipsView.ChipModel(
				titleResId = R.string.favorites_continue_reading,
				isChecked = progress == continueReading,
				isCheckedIconVisible = false,
				data = continueReading,
			),
		)

		// Private categories deliberately do not participate in tracker/background update flows.
		if (settings.isTrackerEnabled && favouriteSpace == FavouriteSpace.NORMAL) {
			add(
				ChipsView.ChipModel(
					titleResId = R.string.favorites_new_chapters,
					icon = R.drawable.ic_updated,
					isChecked = ListFilterOption.Macro.NEW_CHAPTERS in selectedOptions,
					isCheckedIconVisible = false,
					data = ListFilterOption.Macro.NEW_CHAPTERS,
				),
			)
		}

		// Downloaded and Local are already device-backed virtual shelves; the extra chip would be
		// redundant and, for Local, could accidentally carry a filter from another Private category.
		if (!isDownloadedShelf && !isLocalShelf) {
			add(
				ChipsView.ChipModel(
					titleResId = R.string.favorites_on_device,
					icon = R.drawable.ic_storage,
					isChecked = ListFilterOption.Downloaded in selectedOptions,
					isCheckedIconVisible = false,
					data = ListFilterOption.Downloaded,
			),
			)
		}

		val selectedSources = if (isLocalShelf) emptySet() else selectedOptions.filterIsInstance<ListFilterOption.Source>().toSet()
		val options = (getSourceOptions() + selectedSources).distinctBy { it.mangaSource.name }
		val publicationState = selectedOptions.filterIsInstance<ListFilterOption.State>().firstOrNull()
		val advancedCount =
			(if (selectedSources.isNotEmpty()) 1 else 0) +
				(if (publicationState != null) 1 else 0) +
				(if (progress != null && progress != continueReading) 1 else 0)
		add(
			ChipsView.ChipModel(
				titleResId = R.string.favorites_filter,
				icon = R.drawable.ic_filter_funnel,
				counter = advancedCount,
				isChecked = advancedCount > 0,
				isCheckedIconVisible = false,
				isDropdown = true,
				data = ExtensionFilter(
					options = options,
					selectedOptions = selectedSources,
					readingProgress = progress,
					publicationState = publicationState,
					isAdvanced = true,
				),
			),
		)
	}

	private suspend fun getSourceOptions(): List<ListFilterOption.Source> {
		if (isLocalShelf) return emptyList()
		val categorySources = if (isDownloadedShelf) {
			repository.getDownloadedCountsBySource(favouriteSpace)
				.sortedByDescending { it.itemCount }
				.map { MangaSource(it.source) }
		} else {
			repository.findSources(categoryId, favouriteSpace)
		}
		if (categorySources.isEmpty()) return emptyList()

		mihonExtensionManager.ensureReady()
		val installedSources = mihonExtensionManager.getMihonMangaSources().associateBy { it.name }
		val wantNovel = contentTypeStore.selectedType.value == FavouriteContentType.NOVEL
		return categorySources
			.map { source -> installedSources[source.name] ?: source }
			.filter { source -> !isDownloadedShelf || source.isLocal || source.isNovelSource == wantNovel }
			.distinctBy { it.name }
			.map { ListFilterOption.Source(it) }
	}

	@AssistedFactory
	interface Factory {
		fun create(categoryId: Long, favouriteSpace: FavouriteSpace): FavoritesListQuickFilter
	}
}
