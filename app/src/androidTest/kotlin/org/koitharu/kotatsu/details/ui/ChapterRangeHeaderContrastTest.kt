package org.koitharu.kotatsu.details.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.core.prefs.MiyorareAppearance
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel
import org.koitharu.kotatsu.core.ui.LocalMiyorareVisualPalette
import org.koitharu.kotatsu.core.ui.miyorareThemeColors
import org.koitharu.kotatsu.details.ui.model.ChapterListItem
import org.koitharu.kotatsu.details.ui.model.ChapterRange
import kotlin.math.abs

/** Verify rendered foregrounds, including the unlabeled chevron, on translucent Modern headers. */
class ChapterRangeHeaderContrastTest {
	@get:Rule val compose = createComposeRule()

	@Test fun darkHeaderLabelAndBothChevronOrientationsUseThemeForeground() = checkForeground(dark = true)
	@Test fun lightHeaderLabelAndBothChevronOrientationsUseThemeForeground() = checkForeground(dark = false)

	private fun checkForeground(dark: Boolean) {
		val colors = miyorareThemeColors(
			preset = MiyorareThemePreset.MIYORARE,
			customAccent = MiyorareAppearance.DEFAULT_CUSTOM_ACCENT,
			darkTheme = dark,
			amoled = false,
			effectLevel = VisualEffectLevel.BALANCED,
		)
		val range = ChapterRange("contrast", 1, 50f, (1..50).map {
			ChapterListItem(SampleData.chapter.copy(id = it.toLong(), number = it.toFloat()), ChapterListItem.FLAG_UNREAD)
		})
		val expanded = mutableStateOf(false)
		compose.setContent {
			MaterialTheme(colorScheme = colors.colorScheme) {
				// Deliberately unsuitable ambient content reproduces the original inheritance bug.
				CompositionLocalProvider(
					LocalContentColor provides if (dark) Color.Black else Color.White,
					LocalMiyorareVisualPalette provides colors.visualPalette,
				) {
					Box(Modifier.background(colors.colorScheme.surface)) {
						InlineChapterRangeHeader(range.copy(expanded = expanded.value), colors.colorScheme.primary) {}
					}
				}
			}
		}
		for (open in listOf(false, true)) {
			compose.runOnIdle { expanded.value = open }
			val label = compose.onNodeWithText(range.numberLabel!!, useUnmergedTree = true).captureToImage().toPixelMap()
			assertTrue("Range label must use the active theme foreground", label.hasForeground(colors.colorScheme.onSurface))
			val header = compose.onNode(hasClickAction()).captureToImage().toPixelMap()
			val left = with(compose.density) { 12.dp.roundToPx() }
			val iconSize = with(compose.density) { 18.dp.roundToPx() }
			val top = (header.height - iconSize) / 2
			assertTrue("Chevron must use the active theme foreground (expanded=$open)",
				header.hasForeground(colors.colorScheme.onSurface, left, top, left + iconSize, top + iconSize))
		}
	}

	private fun PixelMap.hasForeground(color: Color, left: Int = 0, top: Int = 0, right: Int = width, bottom: Int = height): Boolean =
		(top until bottom).any { y ->
			(left until right).any { x ->
				val pixel = this[x, y]
				abs(pixel.red - color.red) < 0.035f && abs(pixel.green - color.green) < 0.035f &&
					abs(pixel.blue - color.blue) < 0.035f
			}
		}
}
