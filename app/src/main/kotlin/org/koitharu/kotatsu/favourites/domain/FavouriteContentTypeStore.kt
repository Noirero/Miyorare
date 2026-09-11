package org.koitharu.kotatsu.favourites.domain

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import javax.inject.Inject
import javax.inject.Singleton

enum class FavouriteContentType {
	MANGA,
	NOVEL,
}

/**
 * Keeps Manga/Novel shelf state independent for Normal and Private without changing the Room schema.
 * Existing preference keys remain the Normal-space source of truth for backwards compatibility;
 * Private gets its own selected type and last-category keys so opening the vault can never rewrite
 * the public library's navigation state.
 */
@Singleton
class FavouriteContentTypeStore @Inject constructor(
	@ApplicationContext context: Context,
) {
	private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
	private val selectedTypes = mapOf(
		FavouriteSpace.NORMAL to MutableStateFlow(loadSelectedType(FavouriteSpace.NORMAL)),
		FavouriteSpace.PRIVATE to MutableStateFlow(loadSelectedType(FavouriteSpace.PRIVATE)),
	)

	/** Backwards-compatible Normal shelf state for callers outside Favourites. */
	val selectedType: StateFlow<FavouriteContentType> = selectedType(FavouriteSpace.NORMAL)

	private val _novelCategoryIds = MutableStateFlow(loadNovelCategoryIds())
	val novelCategoryIds: StateFlow<Set<Long>> = _novelCategoryIds.asStateFlow()

	fun selectedType(space: FavouriteSpace): StateFlow<FavouriteContentType> =
		checkNotNull(selectedTypes[space]).asStateFlow()

	fun setSelectedType(type: FavouriteContentType, space: FavouriteSpace = FavouriteSpace.NORMAL) {
		val state = checkNotNull(selectedTypes[space])
		if (state.value == type) return
		state.value = type
		prefs.edit { putString(selectedTypeKey(space), type.name) }
	}

	fun getLastCategoryId(
		type: FavouriteContentType,
		space: FavouriteSpace = FavouriteSpace.NORMAL,
	): Long? {
		val key = lastCategoryKey(type, space)
		return if (prefs.contains(key)) prefs.getLong(key, 0L) else null
	}

	fun setLastCategoryId(
		type: FavouriteContentType,
		categoryId: Long,
		space: FavouriteSpace = FavouriteSpace.NORMAL,
	) {
		val key = lastCategoryKey(type, space)
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

	private fun loadSelectedType(space: FavouriteSpace): FavouriteContentType = runCatching {
		FavouriteContentType.valueOf(prefs.getString(selectedTypeKey(space), null).orEmpty())
	}.getOrDefault(FavouriteContentType.MANGA)

	private fun loadNovelCategoryIds(): Set<Long> = prefs
		.getStringSet(KEY_NOVEL_CATEGORY_IDS, emptySet())
		.orEmpty()
		.mapNotNullTo(LinkedHashSet()) { it.toLongOrNull() }

	private fun commitNovelCategoryIds(ids: Set<Long>) {
		val snapshot = LinkedHashSet(ids)
		_novelCategoryIds.value = snapshot
		prefs.edit { putStringSet(KEY_NOVEL_CATEGORY_IDS, snapshot.mapTo(LinkedHashSet()) { it.toString() }) }
	}

	private fun selectedTypeKey(space: FavouriteSpace): String = when (space) {
		FavouriteSpace.NORMAL -> KEY_SELECTED_TYPE
		FavouriteSpace.PRIVATE -> KEY_PRIVATE_SELECTED_TYPE
	}

	private fun lastCategoryKey(type: FavouriteContentType, space: FavouriteSpace): String = when (space) {
		FavouriteSpace.NORMAL -> "last_category_${type.name.lowercase()}"
		FavouriteSpace.PRIVATE -> "last_category_private_${type.name.lowercase()}"
	}

	private companion object {
		const val PREFS_NAME = "favourite_content_types"
		const val KEY_SELECTED_TYPE = "selected_type"
		const val KEY_PRIVATE_SELECTED_TYPE = "selected_type_private"
		const val KEY_NOVEL_CATEGORY_IDS = "novel_category_ids"
	}
}
