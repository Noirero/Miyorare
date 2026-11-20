package org.koitharu.kotatsu.settings.search

import org.koitharu.kotatsu.list.ui.model.ListModel

/**
 * One settings section rendered as a single card in the settings search browse list:
 * a category header (icon + title) followed by the section's settings rows.
 */
data class SettingsSectionCard(
	val title: String,
	val iconRes: Int,
	val paletteKey: String,
	val items: List<SettingsItem>,
) : ListModel {

	override fun areItemsTheSame(other: ListModel): Boolean {
		return other is SettingsSectionCard && other.title == title
	}
}
