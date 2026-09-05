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
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.list.domain.MangaListQuickFilter
import org.koitharu.kotatsu.list.ui.model.ExtensionFilter
import org.koitharu.kotatsu.mihon.MihonExtensionManager

@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class FavouriteShelfFilterState(
	private val delegate: StateFlow<FavouriteQuickFilterStore.Snapshot>,
	private val contentType: StateFlow<FavouriteContentType>,
	private val hideDownloaded: Boolean,
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

	private fun filter(filters: Set<ListFilterOption>): Set<ListFilterOption> =
		if (hideDownloaded) filters - ListFilterOption.Downloaded else filters
}

class FavoritesListQuickFilter @AssistedInject constructor(
	@Assisted private val categoryId: Long,
	private val settings: AppSettings,
	private val repository: FavouritesRepository,
	private val filterStore: FavouriteQuickFilterStore,
	private val contentTypeStore: FavouriteContentTypeStore,
	private val mihonExtensionManager: MihonExtensionManager,
) : MangaListQuickFilter(settings) {

	private val categoryAppliedOptions: StateFlow<Set<ListFilterOption>> = FavouriteShelfFilterState(
		delegate = filterStore.state,
		contentType = contentTypeStore.selectedType,
		hideDownloaded = categoryId == DOWNLOADED_FAVOURITES_CATEGORY_ID,
	)

	init {
		isStateFilterEnabled = false
	}

	override val appliedOptions
		get() = categoryAppliedOptions

	override fun setFilterOption(option: ListFilterOption, isApplied: Boolean) {
		filterStore.set(contentTypeStore.selectedType.value, option, isApplied)
	}

	override fun toggleFilterOption(option: ListFilterOption) {
		filterStore.toggle(contentTypeStore.selectedType.value, option)
	}

	override fun clearFilter() {
		filterStore.clear(contentTypeStore.selectedType.value)
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

		if (settings.isTrackerEnabled) {
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

		if (categoryId != DOWNLOADED_FAVOURITES_CATEGORY_ID) {
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

		val selectedSources = selectedOptions.filterIsInstance<ListFilterOption.Source>().toSet()
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
		val isDownloadedShelf = categoryId == DOWNLOADED_FAVOURITES_CATEGORY_ID
		val categorySources = if (isDownloadedShelf) {
			repository.getDownloadedCountsBySource()
				.sortedByDescending { it.itemCount }
				.map { MangaSource(it.source) }
		} else {
			repository.findSources(categoryId)
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
		fun create(categoryId: Long): FavoritesListQuickFilter
	}
}
