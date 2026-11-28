package org.koitharu.kotatsu.favourites.domain

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager

/** One display-only preference shared by Normal Manga and Novel. */
internal object FavouriteHeadingPreferences {

	const val KEY_COLLAPSED = "favourites_header_collapsed"

	fun isCollapsed(context: Context): Boolean =
		PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY_COLLAPSED, false)

	fun setCollapsed(context: Context, collapsed: Boolean) {
		PreferenceManager.getDefaultSharedPreferences(context).edit {
			putBoolean(KEY_COLLAPSED, collapsed)
		}
	}
}
