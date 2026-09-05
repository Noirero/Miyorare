package org.koitharu.kotatsu.list.ui.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.ListMode
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.ui.sheet.SheetChip
import org.koitharu.kotatsu.core.ui.sheet.SheetChips
import org.koitharu.kotatsu.core.ui.sheet.SheetContentPadding
import org.koitharu.kotatsu.core.ui.sheet.SheetSection
import org.koitharu.kotatsu.core.ui.sheet.SheetSegment
import org.koitharu.kotatsu.core.ui.sheet.SheetSegmentedSelector
import org.koitharu.kotatsu.core.ui.sheet.SheetSwitchRow
import org.koitharu.kotatsu.core.util.ext.consume
import org.koitharu.kotatsu.databinding.SheetListModeBinding
import org.koitharu.kotatsu.favourites.domain.FavouriteCategoryNavigationMode
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import kotlin.math.roundToInt

@AndroidEntryPoint
class ListConfigBottomSheet : BaseAdaptiveSheet<SheetListModeBinding>() {

	private val viewModel by viewModels<ListConfigViewModel>()

	override fun onCreateViewBinding(
		inflater: LayoutInflater,
		container: ViewGroup?,
	) = SheetListModeBinding.inflate(inflater, container, false)

	override fun onViewBindingCreated(binding: SheetListModeBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		binding.composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		binding.composeView.setContent {
			DropSauceTheme {
				Content()
			}
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val typeMask = WindowInsetsCompat.Type.systemBars()
		viewBinding?.scrollView?.updatePadding(
			bottom = insets.getInsets(typeMask).bottom,
		)
		return insets.consume(v, typeMask, bottom = true)
	}

	@Composable
	private fun Content() {
		// Settings write through immediately. Favourites uses shelf-specific preferences, so changing
		// Manga never changes Novel (and vice versa).
		var mode by remember { mutableStateOf(viewModel.listMode) }
		var isTitleOverCover by remember { mutableStateOf(viewModel.isTitleOverCover) }
		var isGridSpacingIncreased by remember { mutableStateOf(viewModel.isGridSpacingIncreased) }
		var gridSize by remember { mutableFloatStateOf(viewModel.gridSize.toFloat()) }
		var gridColumns by remember { mutableFloatStateOf(viewModel.gridColumns.toFloat()) }
		var showDownloaded by remember { mutableStateOf(viewModel.showDownloaded) }
		var showUnread by remember { mutableStateOf(viewModel.showUnread) }
		var showLocalSource by remember { mutableStateOf(viewModel.showLocalSource) }
		var showLanguage by remember { mutableStateOf(viewModel.showLanguage) }
		var showContinueReading by remember { mutableStateOf(viewModel.showContinueReading) }
		var showCategoryTabs by remember { mutableStateOf(viewModel.showCategoryTabs) }
		var showCategoryCounts by remember { mutableStateOf(viewModel.showCategoryCounts) }
		var categoryNavigationMode by remember { mutableStateOf(viewModel.categoryNavigationMode) }
		var isGroupingEnabled by remember { mutableStateOf(viewModel.isGroupingEnabled) }
		val isGroupingAvailable = viewModel.isGroupingAvailable
		val isGridMode = mode == ListMode.GRID || mode == ListMode.COVER_ONLY

		Column(modifier = Modifier.padding(bottom = 16.dp)) {
			SheetSection(title = stringResource(R.string.list_mode)) {
				SheetSegmentedSelector(
					options = LIST_MODES.map { (_, labelRes, iconRes) ->
						SheetSegment(label = stringResource(labelRes), icon = painterResource(iconRes))
					},
					selectedIndex = LIST_MODES.indexOfFirst { it.first == mode }.coerceAtLeast(0),
					onSelect = { index ->
						val value = LIST_MODES[index].first
						mode = value
						viewModel.listMode = value
					},
					modifier = Modifier.padding(horizontal = SheetContentPadding),
				)
			}

			AnimatedVisibility(
				visible = isGridMode,
				enter = expandVertically() + fadeIn(),
				exit = shrinkVertically() + fadeOut(),
			) {
				Column {
					if (viewModel.isFavouriteSection) {
						SheetSection(
							title = stringResource(R.string.favourites_items_per_row),
							value = gridColumns.roundToInt().toString(),
						) {
							Slider(
								value = gridColumns,
								valueRange = GRID_COLUMNS_MIN..GRID_COLUMNS_MAX,
								steps = GRID_COLUMNS_STEPS,
								onValueChange = {
									gridColumns = it.roundToInt().toFloat()
									viewModel.gridColumns = gridColumns.roundToInt()
								},
								modifier = Modifier.padding(horizontal = SheetContentPadding),
							)
						}
					}

					SheetSwitchRow(
						icon = painterResource(R.drawable.ic_title),
						title = stringResource(R.string.title_over_cover),
						checked = isTitleOverCover,
						onCheckedChange = {
							isTitleOverCover = it
							viewModel.isTitleOverCover = it
						},
						modifier = Modifier.padding(top = 8.dp),
					)
					SheetSwitchRow(
						icon = painterResource(R.drawable.ic_grid),
						title = stringResource(R.string.increase_cover_spacing),
						checked = isGridSpacingIncreased,
						onCheckedChange = {
							isGridSpacingIncreased = it
							viewModel.isGridSpacingIncreased = it
						},
					)
					SheetSection(
						title = stringResource(R.string.grid_size),
						value = "${gridSize.roundToInt()}%",
					) {
						Slider(
							value = gridSize,
							valueRange = GRID_SIZE_MIN..GRID_SIZE_MAX,
							onValueChange = {
								gridSize = it
								viewModel.gridSize = it.roundToInt()
							},
							modifier = Modifier.padding(horizontal = SheetContentPadding),
						)
					}
				}
			}

			if (viewModel.isFavouriteSection) {
				Spacer(Modifier.height(8.dp))
				SheetSection(title = stringResource(R.string.favourites_card_information)) {}
				SheetSwitchRow(
					icon = painterResource(R.drawable.ic_storage),
					title = stringResource(R.string.favourites_show_downloaded),
					checked = showDownloaded,
					onCheckedChange = {
						showDownloaded = it
						viewModel.showDownloaded = it
					},
				)
				SheetSwitchRow(
					icon = painterResource(R.drawable.ic_history),
					title = stringResource(R.string.favourites_show_unread),
					checked = showUnread,
					onCheckedChange = {
						showUnread = it
						viewModel.showUnread = it
					},
				)
				SheetSwitchRow(
					icon = painterResource(R.drawable.ic_storage),
					title = stringResource(R.string.favourites_show_local_source),
					checked = showLocalSource,
					onCheckedChange = {
						showLocalSource = it
						viewModel.showLocalSource = it
					},
				)
				SheetSwitchRow(
					icon = painterResource(R.drawable.ic_manga_source),
					title = stringResource(R.string.favourites_show_language),
					checked = showLanguage,
					onCheckedChange = {
						showLanguage = it
						viewModel.showLanguage = it
					},
				)
				SheetSwitchRow(
					icon = painterResource(R.drawable.ic_history),
					title = stringResource(R.string.favourites_show_continue_reading),
					checked = showContinueReading,
					onCheckedChange = {
						showContinueReading = it
						viewModel.showContinueReading = it
					},
				)

				Spacer(Modifier.height(8.dp))
				val categoryNavigationModes = FavouriteCategoryNavigationMode.entries
				val categoryNavigationLabels = categoryNavigationModes.map { navigationMode ->
					when (navigationMode) {
						FavouriteCategoryNavigationMode.TAP -> stringResource(R.string.favourites_category_navigation_tap)
						FavouriteCategoryNavigationMode.SWIPE -> stringResource(R.string.favourites_category_navigation_swipe)
						FavouriteCategoryNavigationMode.TAP_AND_SWIPE -> stringResource(R.string.favourites_category_navigation_tap_and_swipe)
					}
				}
				SheetSection(title = stringResource(R.string.favourites_category_navigation)) {
					SheetChips(
						chips = categoryNavigationModes.mapIndexed { index, navigationMode ->
							SheetChip(
								title = categoryNavigationLabels[index],
								isChecked = navigationMode == categoryNavigationMode,
							)
						},
						onClick = { index ->
							val value = categoryNavigationModes[index]
							categoryNavigationMode = value
							viewModel.categoryNavigationMode = value
						},
						modifier = Modifier.padding(horizontal = SheetContentPadding),
					)
				}

				SheetSection(title = stringResource(R.string.favourites_category_tabs)) {}
				SheetSwitchRow(
					icon = painterResource(R.drawable.ic_list),
					title = stringResource(R.string.favourites_show_category_tabs),
					checked = showCategoryTabs,
					onCheckedChange = {
						showCategoryTabs = it
						viewModel.showCategoryTabs = it
					},
				)
				SheetSwitchRow(
					icon = painterResource(R.drawable.ic_info),
					title = stringResource(R.string.favourites_show_category_counts),
					checked = showCategoryCounts,
					onCheckedChange = {
						showCategoryCounts = it
						viewModel.showCategoryCounts = it
					},
				)
			}

			if (viewModel.isGroupingSupported) {
				SheetSwitchRow(
					icon = painterResource(R.drawable.ic_list_group),
					title = stringResource(R.string.group),
					checked = isGroupingEnabled,
					enabled = isGroupingAvailable,
					onCheckedChange = {
						isGroupingEnabled = it
						viewModel.isGroupingEnabled = it
					},
					modifier = Modifier.padding(top = 8.dp),
				)
			}
		}
	}

	private companion object {
		const val GRID_SIZE_MIN = 50f
		const val GRID_SIZE_MAX = 150f
		const val GRID_COLUMNS_MIN = 2f
		const val GRID_COLUMNS_MAX = 6f
		const val GRID_COLUMNS_STEPS = 3

		val LIST_MODES = listOf(
			Triple(ListMode.LIST, R.string.compact, R.drawable.ic_list),
			Triple(ListMode.DETAILED_LIST, R.string.details, R.drawable.ic_list_detailed),
			Triple(ListMode.GRID, R.string.grid, R.drawable.ic_grid),
			Triple(ListMode.COVER_ONLY, R.string.cover_only, R.drawable.ic_images),
		)
	}
}