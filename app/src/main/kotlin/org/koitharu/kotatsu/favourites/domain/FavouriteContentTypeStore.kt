package org.koitharu.kotatsu.favourites.domain

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class FavouriteContentType {
	MANGA,
	NOVEL,
}

/**
 * Keeps the Manga and Novel favourite shelves separate without changing the existing Room schema.
 * Existing categories are treated as Manga categories for backwards compatibility. Categories
 * created while the Novel shelf is selected are recorded here as Novel-only categories.
 */
@Singleton
class FavouriteContentTypeStore @Inject constructor(
	@ApplicationContext context: Context,
) {
	private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

	private val _selectedType = MutableStateFlow(
		runCatching {
			FavouriteContentType.valueOf(prefs.getString(KEY_SELECTED_TYPE, null).orEmpty())
		}.getOrDefault(FavouriteContentType.MANGA),
	)
	val selectedType: StateFlow<FavouriteContentType> = _selectedType.asStateFlow()

	private val _novelCategoryIds = MutableStateFlow(loadNovelCategoryIds())
	val novelCategoryIds: StateFlow<Set<Long>> = _novelCategoryIds.asStateFlow()

	fun setSelectedType(type: FavouriteContentType) {
		if (_selectedType.value == type) return
		_selectedType.value = type
		prefs.edit { putString(KEY_SELECTED_TYPE, type.name) }
	}

	fun getLastCategoryId(type: FavouriteContentType): Long? {
		val key = lastCategoryKey(type)
		return if (prefs.contains(key)) prefs.getLong(key, 0L) else null
	}

	fun setLastCategoryId(type: FavouriteContentType, categoryId: Long) {
		val key = lastCategoryKey(type)
		if (prefs.contains(key) && prefs.getLong(key, 0L) == categoryId) return
		prefs.edit { putLong(key, categoryId) }
	}

	fun isCategoryForType(categoryId: Long, type: FavouriteContentType): Boolean {
		val isNovel = categoryId in _novelCategoryIds.value
		return if (type == FavouriteContentType.NOVEL) isNovel else !isNovel
	}

	fun setCategoryType(categoryId: Long, type: FavouriteContentType) {
		val updated = _novelCategoryIds.value.toMutableSet()
		if (type == FavouriteContentType.NOVEL) {
			updated += categoryId
		} else {
			updated -= categoryId
		}
		commitNovelCategoryIds(updated)
	}

	fun removeCategories(categoryIds: Collection<Long>) {
		if (categoryIds.isEmpty()) return
		val updated = _novelCategoryIds.value - categoryIds.toSet()
		if (updated.size != _novelCategoryIds.value.size) {
			commitNovelCategoryIds(updated)
		}
	}

	private fun loadNovelCategoryIds(): Set<Long> = prefs
		.getStringSet(KEY_NOVEL_CATEGORY_IDS, emptySet())
		.orEmpty()
		.mapNotNullTo(LinkedHashSet()) { it.toLongOrNull() }

	private fun commitNovelCategoryIds(ids: Set<Long>) {
		val snapshot = LinkedHashSet(ids)
		_novelCategoryIds.value = snapshot
		prefs.edit { putStringSet(KEY_NOVEL_CATEGORY_IDS, snapshot.mapTo(LinkedHashSet()) { it.toString() }) }
	}

	private fun lastCategoryKey(type: FavouriteContentType) = "last_category_${type.name.lowercase()}"

	private companion object {
		const val PREFS_NAME = "favourite_content_types"
		const val KEY_SELECTED_TYPE = "selected_type"
		const val KEY_NOVEL_CATEGORY_IDS = "novel_category_ids"
	}
}
