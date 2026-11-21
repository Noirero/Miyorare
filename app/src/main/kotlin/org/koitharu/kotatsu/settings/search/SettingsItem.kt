package org.koitharu.kotatsu.settings.search

import androidx.fragment.app.Fragment
import org.koitharu.kotatsu.list.ui.model.ListModel

data class SettingsItem(
	val key: String,
	val title: CharSequence,
	val breadcrumbs: List<String>,
	val searchText: String,
	val fragmentClass: Class<out Fragment>,
	val sectionIconRes: Int = 0,
	val sectionPaletteKey: String = "",
) : ListModel {

	override fun areItemsTheSame(other: ListModel): Boolean {
		return other is SettingsItem && other.key == key && other.fragmentClass == fragmentClass
	}
}
