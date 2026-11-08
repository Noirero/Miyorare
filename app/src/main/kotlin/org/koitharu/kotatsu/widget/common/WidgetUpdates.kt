package org.koitharu.kotatsu.widget.common

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.MiyorareAppearance
import org.koitharu.kotatsu.core.prefs.VisualEffectPreferences
import org.koitharu.kotatsu.widget.continuereading.ContinueReadingWidget
import org.koitharu.kotatsu.widget.favorites.FavoritesWidget
import org.koitharu.kotatsu.widget.history.HistoryWidget
import org.koitharu.kotatsu.widget.stats.StatsWidget

private val ALL_PROVIDERS = arrayOf(
	ContinueReadingWidget::class.java,
	FavoritesWidget::class.java,
	HistoryWidget::class.java,
	StatsWidget::class.java,
)

/** Asks every pinned instance of [providerClass] to redraw itself. */
fun nudgeWidgets(context: Context, providerClass: Class<*>) {
	val ids = AppWidgetManager.getInstance(context)
		.getAppWidgetIds(ComponentName(context, providerClass))
	if (ids.isEmpty()) return
	context.sendBroadcast(
		Intent(context, providerClass)
			.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
			.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
	)
}

/** Used when something global changed — the color scheme, for instance. */
fun nudgeAllWidgets(context: Context) {
	for (provider in ALL_PROVIDERS) {
		nudgeWidgets(context, provider)
	}
}

/**
 * Repaints every widget when the theme changes. Lives on the Application rather than the settings
 * screen because the onboarding screen writes these keys too, and a widget left on the old palette
 * until its next content update looks broken.
 *
 * Must be held by a strong reference — SharedPreferences keeps listeners weakly.
 */
class WidgetThemeWatcher(private val context: Context) : SharedPreferences.OnSharedPreferenceChangeListener {

	override fun onSharedPreferenceChanged(preferences: SharedPreferences?, key: String?) {
		if (key in PALETTE_KEYS) {
			WidgetTheme.invalidate()
			nudgeAllWidgets(context)
		}
	}

	private companion object {
		val PALETTE_KEYS = setOf(
			AppSettings.KEY_THEME,
			AppSettings.KEY_COLOR_THEME,
			AppSettings.KEY_THEME_AMOLED,
			MiyorareAppearance.KEY_DESIGN_STYLE,
			MiyorareAppearance.KEY_THEME_PRESET,
			MiyorareAppearance.KEY_MODERN_PALETTE_SOURCE,
			MiyorareAppearance.KEY_CUSTOM_ACCENT,
			MiyorareAppearance.KEY_CUSTOM_BACKGROUND_COLOR_SYNC,
			MiyorareAppearance.KEY_CUSTOM_BACKGROUND_INTENSITY,
			MiyorareAppearance.KEY_CUSTOM_BACKGROUND_PRIMARY,
			MiyorareAppearance.KEY_CUSTOM_BACKGROUND_SECONDARY,
			MiyorareAppearance.KEY_CUSTOM_BACKGROUND_TERTIARY,
			MiyorareAppearance.KEY_CUSTOM_BACKGROUND_REVISION,
			VisualEffectPreferences.KEY_LEVEL,
		)
	}
}
