package org.koitharu.kotatsu.widget.common

import android.content.Context
import androidx.room.InvalidationTracker
import dagger.hilt.android.qualifiers.ApplicationContext
import org.koitharu.kotatsu.widget.continuereading.ContinueReadingWidget
import org.koitharu.kotatsu.widget.favorites.FavoritesWidget
import org.koitharu.kotatsu.widget.history.HistoryWidget
import org.koitharu.kotatsu.widget.stats.StatsWidget
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Watches the tables that feed launcher widgets and nudges installed instances whenever their
 * visible projection can change. Membership tables are privacy-critical: moving a currently shown
 * manga from Normal to Private-only must remove its title/cover from the launcher immediately,
 * even though its history/stats rows intentionally remain stored internally.
 *
 * Coalescing is implicit: Room batches invalidations and AppWidgetManager dedupes broadcasts.
 */
@Singleton
class WidgetRefreshObserver @Inject constructor(
	@ApplicationContext private val context: Context,
) : InvalidationTracker.Observer(WATCHED_TABLES) {

	override fun onInvalidated(tables: Set<String>) {
		val historyChanged = HISTORY_TABLE in tables
		val statsChanged = STATS_TABLE in tables
		val membershipChanged = FAVOURITES_TABLE in tables || PRIVATE_FAVOURITES_TABLE in tables

		if (historyChanged || membershipChanged) {
			nudgeWidgets(context, ContinueReadingWidget::class.java)
			nudgeWidgets(context, HistoryWidget::class.java)
		}
		if (statsChanged || membershipChanged) {
			nudgeWidgets(context, StatsWidget::class.java)
		}
		if (membershipChanged) {
			nudgeWidgets(context, FavoritesWidget::class.java)
		}
	}

	companion object {
		private const val HISTORY_TABLE = "history"
		private const val STATS_TABLE = "stats"
		private const val FAVOURITES_TABLE = "favourites"
		private const val PRIVATE_FAVOURITES_TABLE = "private_favourites"
		private val WATCHED_TABLES = arrayOf(
			HISTORY_TABLE,
			STATS_TABLE,
			FAVOURITES_TABLE,
			PRIVATE_FAVOURITES_TABLE,
		)
	}
}
