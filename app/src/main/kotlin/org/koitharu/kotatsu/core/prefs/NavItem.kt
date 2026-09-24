package org.koitharu.kotatsu.core.prefs

import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.annotation.Keep
import androidx.annotation.StringRes
import org.koitharu.kotatsu.R

@Keep
enum class NavItem(
	@IdRes val id: Int,
	@StringRes val title: Int,
	@DrawableRes val icon: Int,
	@StringRes val navTitle: Int,
) {

	HISTORY(R.id.nav_history, R.string.history, R.drawable.ic_history_selector, R.string.history),
	FAVORITES(R.id.nav_favorites, R.string.favourites, R.drawable.ic_favourites_selector, R.string.favourites),
	LOCAL(R.id.nav_local, R.string.on_device, R.drawable.ic_storage_selector, R.string.on_device),
	EXPLORE(R.id.nav_explore, R.string.explore, R.drawable.ic_explore_selector, R.string.explore),
	SUGGESTIONS(R.id.nav_suggestions, R.string.suggestions, R.drawable.ic_suggestion_selector, R.string.suggestions),
	FEED(R.id.nav_feed, R.string.feed, R.drawable.ic_feed_selector, R.string.feed),
	UPDATED(R.id.nav_updated, R.string.updated, R.drawable.ic_updated_selector, R.string.updated_nav),
	BOOKMARKS(R.id.nav_bookmarks, R.string.bookmarks, R.drawable.ic_bookmark_selector, R.string.bookmarks),
	READER_JOURNEY(R.id.nav_reader_journey, R.string.reader_journey, R.drawable.ic_auto_stories, R.string.reader_journey_nav),
	;

	fun isAvailable(settings: AppSettings): Boolean = when (this) {
		SUGGESTIONS -> settings.isSuggestionsEnabled
		UPDATED, FEED -> settings.isTrackerEnabled
		else -> true
	}
}
