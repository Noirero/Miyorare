package org.koitharu.kotatsu.favourites.domain

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ListMode
import javax.inject.Inject
import javax.inject.Singleton

enum class FavouriteCategoryNavigationMode(
	val allowsTap: Boolean,
	val allowsSwipe: Boolean,
) {
	TAP(allowsTap = true, allowsSwipe = false),
	SWIPE(allowsTap = false, allowsSwipe = true),
	TAP_AND_SWIPE(allowsTap = true, allowsSwipe = true),
}

/**
 * Display-only preferences for the Favourites screen.
 *
 * Manga and Novel deliberately keep separate snapshots so changing a grid, card overlay or
 * category-tab option on one shelf never changes the other shelf. Existing global list settings are
 * only used as migration defaults; after a shelf is changed its values are stored independently.
 */
@Singleton
class FavouriteDisplayPreferences @Inject constructor(
	@ApplicationContext context: Context,
	private val appSettings: AppSettings,
) {

	data class Options(
		val listMode: ListMode,
		val gridColumns: Int,
		val gridSize: Int,
		val titleOverCover: Boolean,
		val gridSpacingIncreased: Boolean,
		val showDownloaded: Boolean,
		val showUnread: Boolean,
		val showLocalSource: Boolean,
		val showLanguage: Boolean,
		val showContinueReading: Boolean,
		val showCategoryTabs: Boolean,
		val showCategoryCounts: Boolean,
	)

	private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
	private val mutableState = MutableStateFlow(loadAll())
	val state: StateFlow<Map<FavouriteContentType, Options>> = mutableState.asStateFlow()
	private val mutableCategoryNavigationMode = MutableStateFlow(loadCategoryNavigationMode())
	val categoryNavigationMode: StateFlow<FavouriteCategoryNavigationMode> =
		mutableCategoryNavigationMode.asStateFlow()

	fun observe(type: FavouriteContentType) = state
		.map { it.getValue(type) }
		.distinctUntilChanged()

	fun current(type: FavouriteContentType): Options = state.value.getValue(type)

	fun setListMode(type: FavouriteContentType, value: ListMode) = update(type) { copy(listMode = value) }

	fun setGridColumns(type: FavouriteContentType, value: Int) = update(type) {
		copy(gridColumns = value.coerceIn(MIN_GRID_COLUMNS, MAX_GRID_COLUMNS))
	}

	fun setGridSize(type: FavouriteContentType, value: Int) = update(type) {
		copy(gridSize = value.coerceIn(MIN_GRID_SIZE, MAX_GRID_SIZE))
	}

	fun setTitleOverCover(type: FavouriteContentType, value: Boolean) = update(type) {
		copy(titleOverCover = value)
	}

	fun setGridSpacingIncreased(type: FavouriteContentType, value: Boolean) = update(type) {
		copy(gridSpacingIncreased = value)
	}

	fun setShowDownloaded(type: FavouriteContentType, value: Boolean) = update(type) {
		copy(showDownloaded = value)
	}

	fun setShowUnread(type: FavouriteContentType, value: Boolean) = update(type) {
		copy(showUnread = value)
	}

	fun setShowLocalSource(type: FavouriteContentType, value: Boolean) = update(type) {
		copy(showLocalSource = value)
	}

	fun setShowLanguage(type: FavouriteContentType, value: Boolean) = update(type) {
		copy(showLanguage = value)
	}

	fun setShowContinueReading(type: FavouriteContentType, value: Boolean) = update(type) {
		copy(showContinueReading = value)
	}

	fun setShowCategoryTabs(type: FavouriteContentType, value: Boolean) = update(type) {
		copy(showCategoryTabs = value)
	}

	fun setShowCategoryCounts(type: FavouriteContentType, value: Boolean) = update(type) {
		copy(showCategoryCounts = value)
	}

	fun setCategoryNavigationMode(value: FavouriteCategoryNavigationMode) {
		if (mutableCategoryNavigationMode.value == value) return
		prefs.edit { putString(KEY_CATEGORY_NAVIGATION_MODE, value.name) }
		mutableCategoryNavigationMode.value = value
	}

	private inline fun update(type: FavouriteContentType, transform: Options.() -> Options) {
		val next = current(type).transform()
		persist(type, next)
		mutableState.value = mutableState.value.toMutableMap().apply { put(type, next) }
	}

	private fun loadAll(): Map<FavouriteContentType, Options> = FavouriteContentType.entries.associateWith(::load)

	private fun loadCategoryNavigationMode(): FavouriteCategoryNavigationMode = runCatching {
		FavouriteCategoryNavigationMode.valueOf(
			prefs.getString(KEY_CATEGORY_NAVIGATION_MODE, null).orEmpty(),
		)
	}.getOrDefault(FavouriteCategoryNavigationMode.TAP_AND_SWIPE)

	private fun load(type: FavouriteContentType): Options {
		val prefix = prefix(type)
		val fallbackMode = appSettings.favoritesListMode
		val mode = runCatching {
			ListMode.valueOf(prefs.getString(prefix + KEY_LIST_MODE, null).orEmpty())
		}.getOrDefault(fallbackMode)
		return Options(
			listMode = mode,
			gridColumns = prefs.getInt(prefix + KEY_GRID_COLUMNS, DEFAULT_GRID_COLUMNS)
				.coerceIn(MIN_GRID_COLUMNS, MAX_GRID_COLUMNS),
			gridSize = prefs.getInt(prefix + KEY_GRID_SIZE, appSettings.gridSize)
				.coerceIn(MIN_GRID_SIZE, MAX_GRID_SIZE),
			titleOverCover = prefs.getBoolean(prefix + KEY_TITLE_OVER_COVER, appSettings.isTitleOverCover),
			gridSpacingIncreased = prefs.getBoolean(
				prefix + KEY_GRID_SPACING_INCREASED,
				appSettings.isGridSpacingIncreased,
			),
			showDownloaded = prefs.getBoolean(prefix + KEY_SHOW_DOWNLOADED, true),
			showUnread = prefs.getBoolean(prefix + KEY_SHOW_UNREAD, true),
			showLocalSource = prefs.getBoolean(prefix + KEY_SHOW_LOCAL_SOURCE, true),
			showLanguage = prefs.getBoolean(prefix + KEY_SHOW_LANGUAGE, true),
			showContinueReading = prefs.getBoolean(prefix + KEY_SHOW_CONTINUE_READING, false),
			showCategoryTabs = prefs.getBoolean(prefix + KEY_SHOW_CATEGORY_TABS, true),
			showCategoryCounts = prefs.getBoolean(prefix + KEY_SHOW_CATEGORY_COUNTS, true),
		)
	}

	private fun persist(type: FavouriteContentType, options: Options) {
		val prefix = prefix(type)
		prefs.edit {
			putString(prefix + KEY_LIST_MODE, options.listMode.name)
			putInt(prefix + KEY_GRID_COLUMNS, options.gridColumns)
			putInt(prefix + KEY_GRID_SIZE, options.gridSize)
			putBoolean(prefix + KEY_TITLE_OVER_COVER, options.titleOverCover)
			putBoolean(prefix + KEY_GRID_SPACING_INCREASED, options.gridSpacingIncreased)
			putBoolean(prefix + KEY_SHOW_DOWNLOADED, options.showDownloaded)
			putBoolean(prefix + KEY_SHOW_UNREAD, options.showUnread)
			putBoolean(prefix + KEY_SHOW_LOCAL_SOURCE, options.showLocalSource)
			putBoolean(prefix + KEY_SHOW_LANGUAGE, options.showLanguage)
			putBoolean(prefix + KEY_SHOW_CONTINUE_READING, options.showContinueReading)
			putBoolean(prefix + KEY_SHOW_CATEGORY_TABS, options.showCategoryTabs)
			putBoolean(prefix + KEY_SHOW_CATEGORY_COUNTS, options.showCategoryCounts)
		}
	}

	private fun prefix(type: FavouriteContentType): String =
		"favourites_display_${type.name.lowercase()}_"

	companion object {
		const val MIN_GRID_COLUMNS = 2
		const val MAX_GRID_COLUMNS = 6
		const val DEFAULT_GRID_COLUMNS = 3
		const val MIN_GRID_SIZE = 50
		const val MAX_GRID_SIZE = 150

		private const val KEY_LIST_MODE = "list_mode"
		private const val KEY_GRID_COLUMNS = "grid_columns"
		private const val KEY_GRID_SIZE = "grid_size"
		private const val KEY_TITLE_OVER_COVER = "title_over_cover"
		private const val KEY_GRID_SPACING_INCREASED = "grid_spacing_increased"
		private const val KEY_SHOW_DOWNLOADED = "show_downloaded"
		private const val KEY_SHOW_UNREAD = "show_unread"
		private const val KEY_SHOW_LOCAL_SOURCE = "show_local_source"
		private const val KEY_SHOW_LANGUAGE = "show_language"
		private const val KEY_SHOW_CONTINUE_READING = "show_continue_reading"
		private const val KEY_SHOW_CATEGORY_TABS = "show_category_tabs"
		private const val KEY_SHOW_CATEGORY_COUNTS = "show_category_counts"
		private const val KEY_CATEGORY_NAVIGATION_MODE = "favourites_category_navigation_mode"
	}
}
