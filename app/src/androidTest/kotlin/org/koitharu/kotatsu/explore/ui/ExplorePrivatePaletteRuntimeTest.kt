package org.koitharu.kotatsu.explore.ui

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.button.MaterialButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.MiyorareAppearance
import org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import org.koitharu.kotatsu.core.prefs.PrivateFavouritesThemePreset
import org.koitharu.kotatsu.core.ui.BaseListAdapter
import org.koitharu.kotatsu.core.ui.PrivateFavouritesVisualResolver
import org.koitharu.kotatsu.core.ui.explicitPrivateViewPaletteFromPreferences
import org.koitharu.kotatsu.core.ui.miyorareViewPaletteFromPreferences
import org.koitharu.kotatsu.core.ui.neonGlass
import org.koitharu.kotatsu.explore.ui.adapter.ExploreSourceFiltersItem
import org.koitharu.kotatsu.explore.ui.adapter.ExploreSourceOriginFilter
import org.koitharu.kotatsu.explore.ui.adapter.exploreButtonsAD
import org.koitharu.kotatsu.explore.ui.adapter.exploreListHeaderAD
import org.koitharu.kotatsu.explore.ui.adapter.exploreSourceFiltersAD
import org.koitharu.kotatsu.explore.ui.model.ExploreButtons
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.list.ui.adapter.ListItemType
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.list.ui.model.ListModel

/** Synthetic host and real header/delegate Views; no Private data, session mutation or security bypass. */
class ExplorePrivatePaletteRuntimeTest {

	@Test
	fun headerAndRowsUseAllSixPrivatePalettesAndRestoreNormalAndClassic() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val prefs = PreferenceManager.getDefaultSharedPreferences(context)
		val keys = listOf(MiyorareAppearance.KEY_DESIGN_STYLE, MiyorareAppearance.KEY_THEME_PRESET, MiyorareAppearance.KEY_PRIVATE_FAVOURITES_THEME)
		val before = keys.associateWith { prefs.getString(it, null) }
		try {
			prefs.edit().putString(MiyorareAppearance.KEY_DESIGN_STYLE, MiyorareDesignStyle.MODERN.name)
				.putString(MiyorareAppearance.KEY_THEME_PRESET, MiyorareThemePreset.EMERALD.name).commit()
			ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
				scenario.onActivity { activity ->
					val themed = ContextThemeWrapper(activity, R.style.Theme_Kotatsu)
					fun colors(): List<Int?> {
						// Reinflate and recreate holders as PrivateWorkspace does when returning from Settings.
						val header = LayoutInflater.from(themed).inflate(R.layout.layout_explore_header, null)
						val headerColors = listOf(R.id.button_source_view_modern, R.id.button_source_view_list,
							R.id.button_content_filter_all, R.id.button_content_filter_sfw, R.id.button_content_filter_nsfw).flatMap { id ->
							val button = header.findViewById<MaterialButton>(id)
							val checked = intArrayOf(android.R.attr.state_checked, android.R.attr.state_enabled)
							listOf(button.backgroundTintList?.getColorForState(checked, 0),
								button.strokeColor?.getColorForState(checked, 0))
						}
						val adapter = BaseListAdapter<ListModel>()
							.addDelegate(ListItemType.EXPLORE_BUTTONS, exploreButtonsAD(View.OnClickListener {}))
							.addDelegate(ListItemType.HEADER, exploreListHeaderAD(null))
							.addDelegate(ListItemType.EXPLORE_SOURCE_FILTERS, exploreSourceFiltersAD({}, {}))
						adapter.items = listOf(ExploreButtons, ListHeader(R.string.suggestions, R.string.more, R.id.nav_suggestions),
							ExploreSourceFiltersItem(ExploreSourceOriginFilter.ALL, null))
						val parent = FrameLayout(themed)
						fun row(position: Int): View {
							val holder = adapter.createViewHolder(parent, adapter.getItemViewType(position))
							adapter.bindViewHolder(holder, position)
							return holder.itemView
						}
						val shortcuts = row(0)
						val suggestions = row(1)
						val filters = row(2)
						return headerColors + listOf(
							shortcuts.findViewById<MaterialButton>(R.id.button_downloads).backgroundTintList?.defaultColor,
							suggestions.findViewById<MaterialButton>(R.id.button_more).currentTextColor,
							suggestions.findViewById<MaterialButton>(R.id.button_visibility).iconTint?.defaultColor,
							filters.findViewById<MaterialButton>(R.id.button_origin).strokeColor?.defaultColor,
						)
					}
					activity.intent.putExtra(EXTRA_FAVOURITE_SPACE, FavouriteSpace.NORMAL.dbValue)
					val normal = colors()
					assertNull(themed.explicitPrivateViewPaletteFromPreferences())
					val allPrivateColors = mutableSetOf<List<Int?>>()
					for (preset in PrivateFavouritesThemePreset.entries.filter { it != PrivateFavouritesThemePreset.FOLLOW_NORMAL }) {
						prefs.edit().putString(MiyorareAppearance.KEY_PRIVATE_FAVOURITES_THEME, preset.name).commit()
						activity.intent.putExtra(EXTRA_FAVOURITE_SPACE, FavouriteSpace.PRIVATE.dbValue)
						val actual = requireNotNull(themed.explicitPrivateViewPaletteFromPreferences())
						val expected = requireNotNull(PrivateFavouritesVisualResolver.resolve(preset))
						assertEquals(preset.name, expected.primary, actual.primary)
						assertEquals(themed.miyorareViewPaletteFromPreferences(privateFavourites = true), actual)
						val glass = actual.neonGlass()
						val rendered = colors()
						assertEquals(List(5) { listOf(glass.selectedSurface, glass.selectedBorder) }.flatten() +
							listOf(actual.surfaceContainerHigh, actual.primary, actual.primary, actual.outlineVariant), rendered)
						allPrivateColors.add(rendered)
						activity.intent.putExtra(EXTRA_FAVOURITE_SPACE, FavouriteSpace.NORMAL.dbValue)
						assertNull(themed.explicitPrivateViewPaletteFromPreferences())
						assertEquals(normal, colors())
					}
					assertEquals(6, allPrivateColors.size)
					prefs.edit().putString(MiyorareAppearance.KEY_PRIVATE_FAVOURITES_THEME, PrivateFavouritesThemePreset.FOLLOW_NORMAL.name).commit()
					activity.intent.putExtra(EXTRA_FAVOURITE_SPACE, FavouriteSpace.PRIVATE.dbValue)
					assertNull(themed.explicitPrivateViewPaletteFromPreferences())
					assertEquals(normal, colors())
					prefs.edit().putString(MiyorareAppearance.KEY_DESIGN_STYLE, MiyorareDesignStyle.CLASSIC.name)
						.putString(MiyorareAppearance.KEY_PRIVATE_FAVOURITES_THEME, PrivateFavouritesThemePreset.AMBER.name).commit()
					assertNull(themed.explicitPrivateViewPaletteFromPreferences())
					assertNull(themed.miyorareViewPaletteFromPreferences(privateFavourites = true))
					val classicPrivate = colors()
					activity.intent.putExtra(EXTRA_FAVOURITE_SPACE, FavouriteSpace.NORMAL.dbValue)
					assertEquals(classicPrivate, colors())
				}
			}
		} finally {
			prefs.edit().apply { before.forEach { (key, value) -> if (value == null) remove(key) else putString(key, value) } }.commit()
		}
	}
}
