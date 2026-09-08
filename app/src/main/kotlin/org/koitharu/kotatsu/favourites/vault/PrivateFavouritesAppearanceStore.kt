package org.koitharu.kotatsu.favourites.vault

import android.content.Context
import androidx.preference.PreferenceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import org.koitharu.kotatsu.core.prefs.MiyorareAppearance
import org.koitharu.kotatsu.core.prefs.PrivateFavouritesThemePreset
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PrivateFavouritesAppearanceStore @Inject constructor(
	@ApplicationContext context: Context,
) {
	private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

	var themePreset: PrivateFavouritesThemePreset
		get() = prefs.getString(MiyorareAppearance.KEY_PRIVATE_FAVOURITES_THEME, null)
			?.let { name -> PrivateFavouritesThemePreset.entries.firstOrNull { it.name == name } }
			?: PrivateFavouritesThemePreset.FOLLOW_NORMAL
		set(value) {
			prefs.edit().putString(MiyorareAppearance.KEY_PRIVATE_FAVOURITES_THEME, value.name).apply()
		}
}
