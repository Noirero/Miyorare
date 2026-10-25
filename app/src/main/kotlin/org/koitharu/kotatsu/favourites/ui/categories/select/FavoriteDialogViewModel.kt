package org.koitharu.kotatsu.favourites.ui.categories.select

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.google.android.material.checkbox.MaterialCheckBox
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.FavouriteCategory
import org.koitharu.kotatsu.core.model.isNovelContent
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.require
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.domain.FavouriteContentType
import org.koitharu.kotatsu.favourites.domain.FavouriteContentTypeStore
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.favourites.ui.categories.select.model.MangaCategoryItem
import org.koitharu.kotatsu.list.ui.model.EmptyState
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingState
import javax.inject.Inject

@HiltViewModel
class FavoriteDialogViewModel @Inject constructor(
	private val savedStateHandle: SavedStateHandle,
	private val favouritesRepository: FavouritesRepository,
	settings: AppSettings,
	private val contentTypeStore: FavouriteContentTypeStore,
	private val setSelection: org.koitharu.kotatsu.favourites.domain.FavouriteSetSelectionStore,
	@ApplicationContext appContext: Context,
) : BaseViewModel() {

	val manga = savedStateHandle.require<List<ParcelableManga>>(AppRouter.KEY_MANGA_LIST).map {
		it.manga
	}
	val favouriteSpace: FavouriteSpace = FavouriteSpace.fromArgument(
		savedStateHandle[EXTRA_FAVOURITE_SPACE] ?: FavouriteSpace.NORMAL.dbValue,
	)
	val contentType = if (manga.firstOrNull()?.isNovelContent == true) {
		FavouriteContentType.NOVEL
	} else {
		FavouriteContentType.MANGA
	}
	val searchQuery = MutableStateFlow("")
	val sets = favouritesRepository.observeSets(favouriteSpace)
		.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
	val activeSet = setSelection.observe(favouriteSpace, flowOf(contentType))
		.onEach { searchQuery.value = "" }
		.stateIn(viewModelScope, SharingStarted.Eagerly, null)
	private val activeCategoryIds = activeSet.filterNotNull().flatMapLatest {
		favouritesRepository.observeCategoriesInSet(it.setId, favouriteSpace).map { categories -> it.setId to categories }
	}.stateIn(viewModelScope, SharingStarted.Eagerly, null)


	fun selectSet(set: org.koitharu.kotatsu.favourites.data.FavouriteCategorySetEntity) {
		if (isSaving.value) return
		searchQuery.value = ""
		setSelection.select(set, favouriteSpace, contentType)
	}
	fun getPage(setId: Long): Int = savedStateHandle["set_page:$setId"] ?: 0
	fun rememberPage(setId: Long, page: Int) { if (searchQuery.value.isBlank()) savedStateHandle["set_page:$setId"] = page }

	private val isSingleNormalFavourite = favouriteSpace == FavouriteSpace.NORMAL && manga.size == 1
	private val categoryMemory = appContext.getSharedPreferences(CATEGORY_MEMORY_PREFS, Context.MODE_PRIVATE)
	private val restoredCategoryIds = MutableStateFlow<Set<Long>>(emptySet())

	private val pendingChanges = MutableStateFlow<Map<Long, Boolean>>(
		(savedStateHandle.get<LongArray>("pending_category_changes") ?: longArrayOf()).associate { kotlin.math.abs(it) to (it > 0) },
	)
	val isSaving = MutableStateFlow(false)
	val onSaved = MutableEventFlow<Boolean>()
	private val savedContent = combine(
		favouritesRepository.observeCategories(favouriteSpace),
		settings.observeAsFlow(AppSettings.KEY_TRACKER_ENABLED) { isTrackerEnabled },
		contentTypeStore.novelCategoryIds,
	) { categories, tracker, _ ->
		mapList(
			categories.filter { contentTypeStore.isCategoryForType(it.id, contentType) },
			tracker && favouriteSpace == FavouriteSpace.NORMAL,
		)
	}.withErrorHandling()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

	val content = combine(savedContent, pendingChanges, activeCategoryIds, searchQuery, activeSet) { saved, pending, scoped, query, set ->
		if (scoped == null || scoped.first != set?.setId) return@combine listOf(LoadingState)
		val ids = scoped.second.mapTo(HashSet()) { it.id }
		saved.filter { model ->
			model !is MangaCategoryItem || (model.category.id in ids && model.category.title.contains(query.trim(), ignoreCase = true))
		}.map { model ->
			val item = model as? MangaCategoryItem ?: return@map model
			val checked = pending[item.category.id] ?: return@map item
			item.copy(
				checkedState = if (checked) {
					MaterialCheckBox.STATE_CHECKED
				} else {
					MaterialCheckBox.STATE_UNCHECKED
				},
			)
		}
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

	fun setChecked(categoryId: Long, isChecked: Boolean) {
		if (isSaving.value) return
		pendingChanges.update { it + (categoryId to isChecked) }
		savedStateHandle["pending_category_changes"] = pendingChanges.value.map { (id, checked) -> if (checked) id else -id }.toLongArray()
	}

	fun save(openCategoryManagement: Boolean = false) {
		if (!isSaving.compareAndSet(expect = false, update = true)) return
		val pending = pendingChanges.value
		val restored = restoredCategoryIds.value
		val changes = LinkedHashMap<Long, Boolean>(restored.size + pending.size).apply {
			for (categoryId in restored) put(categoryId, true)
			putAll(pending)
		}
		val rememberedAfterSave = if (isSingleNormalFavourite) {
			restored.toMutableSet().apply {
				for ((categoryId, isChecked) in pending) {
					if (isChecked) add(categoryId) else remove(categoryId)
				}
			}.toSet()
		} else {
			emptySet()
		}
		launchJob(Dispatchers.Default) {
			try {
				favouritesRepository.updateCategoryMemberships(
					mangas = manga,
					changes = changes,
					space = favouriteSpace,
				)
				if (isSingleNormalFavourite) {
					rememberCategories(manga.single().id, rememberedAfterSave)
				}
				withContext(Dispatchers.Main.immediate) {
					pendingChanges.value = emptyMap()
					savedStateHandle["pending_category_changes"] = longArrayOf()
				}
				restoredCategoryIds.value = emptySet()
				if (openCategoryManagement) prepareCategoryManagement()
				onSaved.call(openCategoryManagement)
			} finally {
				isSaving.value = false
			}
		}
	}

	fun prepareCategoryManagement() {
		contentTypeStore.setSelectedType(contentType, favouriteSpace)
	}

	private suspend fun mapList(
		categories: List<FavouriteCategory>,
		tracker: Boolean,
	): List<ListModel> {
		if (categories.isEmpty()) {
			restoredCategoryIds.value = emptySet()
			return listOf(
				EmptyState(
					icon = 0,
					textPrimary = R.string.empty_favourite_categories,
					textSecondary = 0,
					actionStringRes = 0,
				),
			)
		}

		val selectedIds = manga.mapTo(HashSet(manga.size)) { it.id }
		val selectedCount = selectedIds.size
		val validCategoryIds = categories.mapTo(HashSet(categories.size)) { it.id }
		var rememberedForRestore: Set<Long> = emptySet()
		val countsByCategory: Map<Long, Int> = if (isSingleNormalFavourite) {
			val mangaId = selectedIds.single()
			val activeCategoryIds = favouritesRepository.getCategoriesIds(mangaId, favouriteSpace)
			val effectiveCategoryIds = if (activeCategoryIds.isEmpty()) {
				readRememberedCategories(mangaId).filterTo(LinkedHashSet()) { it in validCategoryIds }.also {
					rememberedForRestore = it
				}
			} else {
				activeCategoryIds
			}
			effectiveCategoryIds.associateWith { 1 }
		} else {
			favouritesRepository.getCategoryCountsForMangaIds(selectedIds, favouriteSpace)
		}
		restoredCategoryIds.value = rememberedForRestore

		return categories.map { cat ->
			MangaCategoryItem(
				category = cat,
				checkedState = when (countsByCategory[cat.id] ?: 0) {
					0 -> MaterialCheckBox.STATE_UNCHECKED
					selectedCount -> MaterialCheckBox.STATE_CHECKED
					else -> MaterialCheckBox.STATE_INDETERMINATE
				},
				isTrackerEnabled = tracker,
			)
		}
	}

	private fun rememberCategories(mangaId: Long, categoryIds: Set<Long>) {
		val editor = categoryMemory.edit()
		if (categoryIds.isEmpty()) {
			editor.remove(mangaId.toString())
		} else {
			editor.putStringSet(
				mangaId.toString(),
				categoryIds.mapTo(HashSet(categoryIds.size)) { it.toString() },
			)
		}
		editor.apply()
	}

	private fun readRememberedCategories(mangaId: Long): Set<Long> = categoryMemory
		.getStringSet(mangaId.toString(), emptySet())
		.orEmpty()
		.mapNotNullTo(LinkedHashSet()) { it.toLongOrNull() }

	private companion object {
		const val CATEGORY_MEMORY_PREFS = "normal_favourite_category_memory"
	}
}
