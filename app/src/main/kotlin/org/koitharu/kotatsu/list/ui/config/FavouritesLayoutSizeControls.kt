package org.koitharu.kotatsu.list.ui.config

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.sheet.SheetChip
import org.koitharu.kotatsu.core.ui.sheet.SheetChips
import org.koitharu.kotatsu.core.ui.sheet.SheetContentPadding
import org.koitharu.kotatsu.core.ui.sheet.SheetSection
import kotlin.math.roundToInt

private enum class FavouritesLayoutPreset {
	MODERN,
	CLASSIC,
	CUSTOM,
}

/**
 * Friendly controls over the existing favourites gridColumns + gridSize authorities.
 * Presets never switch the renderer: Classic only adopts the larger two-column geometry while
 * the Modern card styling remains untouched.
 */
@Composable
internal fun FavouritesLayoutSizeControls(
	gridColumns: Float,
	gridSize: Float,
	onGridColumnsChange: (Float) -> Unit,
	onGridSizeChange: (Float) -> Unit,
) {
	val derivedPreset = when {
		gridColumns.roundToInt() == MODERN_COLUMNS && gridSize.roundToInt() == PRESET_GRID_SIZE ->
			FavouritesLayoutPreset.MODERN
		gridColumns.roundToInt() == CLASSIC_COLUMNS && gridSize.roundToInt() == PRESET_GRID_SIZE ->
			FavouritesLayoutPreset.CLASSIC
		else -> FavouritesLayoutPreset.CUSTOM
	}
	var selectedPreset by remember { mutableStateOf(derivedPreset) }
	val presets = FavouritesLayoutPreset.entries
	val labels = listOf(
		stringResource(R.string.favourites_layout_modern),
		stringResource(R.string.favourites_layout_classic),
		stringResource(R.string.favourites_layout_custom),
	)

	SheetSection(title = stringResource(R.string.favourites_layout_size)) {
		SheetChips(
			chips = presets.mapIndexed { index, preset ->
				SheetChip(title = labels[index], isChecked = preset == selectedPreset)
			},
			onClick = { index ->
				selectedPreset = presets[index]
				when (selectedPreset) {
					FavouritesLayoutPreset.MODERN -> {
						onGridColumnsChange(MODERN_COLUMNS.toFloat())
						onGridSizeChange(PRESET_GRID_SIZE.toFloat())
					}
					FavouritesLayoutPreset.CLASSIC -> {
						onGridColumnsChange(CLASSIC_COLUMNS.toFloat())
						onGridSizeChange(PRESET_GRID_SIZE.toFloat())
					}
					FavouritesLayoutPreset.CUSTOM -> Unit
				}
			},
			modifier = Modifier.padding(horizontal = SheetContentPadding),
		)
	}

	SheetSection(title = stringResource(R.string.favourites_layout_preview)) {
		FavouritesLayoutPreview(
			columns = gridColumns.roundToInt(),
			gridSize = gridSize.roundToInt(),
		)
	}

	AnimatedVisibility(visible = selectedPreset == FavouritesLayoutPreset.CUSTOM) {
		Column {
			SheetSection(
				title = stringResource(R.string.favourites_items_per_row),
				value = gridColumns.roundToInt().toString(),
			) {
				Slider(
					value = gridColumns,
					valueRange = GRID_COLUMNS_MIN..GRID_COLUMNS_MAX,
					steps = GRID_COLUMNS_STEPS,
					onValueChange = {
						selectedPreset = FavouritesLayoutPreset.CUSTOM
						onGridColumnsChange(it)
					},
					modifier = Modifier.padding(horizontal = SheetContentPadding),
				)
			}
			SheetSection(
				title = stringResource(R.string.favourites_cover_size),
				value = "${gridSize.roundToInt()}%",
			) {
				Slider(
					value = gridSize,
					valueRange = GRID_SIZE_MIN..GRID_SIZE_MAX,
					onValueChange = {
						selectedPreset = FavouritesLayoutPreset.CUSTOM
						onGridSizeChange(it)
					},
					modifier = Modifier.padding(horizontal = SheetContentPadding),
				)
			}
		}
	}
}

@Composable
private fun FavouritesLayoutPreview(columns: Int, gridSize: Int) {
	val safeColumns = columns.coerceIn(GRID_COLUMNS_MIN.toInt(), GRID_COLUMNS_MAX.toInt())
	val scale = gridSize.coerceIn(GRID_SIZE_MIN.toInt(), GRID_SIZE_MAX.toInt()) / 100f
	Surface(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = SheetContentPadding),
		shape = RoundedCornerShape(18.dp),
		color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(12.dp),
			horizontalArrangement = Arrangement.spacedBy(6.dp),
		) {
			repeat(safeColumns) {
				Column(
					modifier = Modifier.weight(1f),
					verticalArrangement = Arrangement.spacedBy(4.dp),
				) {
					Surface(
						modifier = Modifier
							.fillMaxWidth()
							.aspectRatio((2f / 3f) / scale.coerceIn(0.5f, 1.5f)),
						shape = RoundedCornerShape(7.dp),
						color = MaterialTheme.colorScheme.primaryContainer,
					) {}
					Box(
						modifier = Modifier
							.fillMaxWidth(0.82f)
							.height(5.dp)
							.background(
								MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
								RoundedCornerShape(3.dp),
							),
					)
				}
			}
		}
	}
}

private const val MODERN_COLUMNS = 3
private const val CLASSIC_COLUMNS = 2
private const val PRESET_GRID_SIZE = 100
private const val GRID_SIZE_MIN = 50f
private const val GRID_SIZE_MAX = 150f
private const val GRID_COLUMNS_MIN = 2f
private const val GRID_COLUMNS_MAX = 6f
private const val GRID_COLUMNS_STEPS = 3
