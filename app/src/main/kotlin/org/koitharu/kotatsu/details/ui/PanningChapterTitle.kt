package org.koitharu.kotatsu.details.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainWidth
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import org.koitharu.kotatsu.core.util.ext.isAnimationsEnabled
import kotlin.math.roundToInt

@Composable
internal fun PanningChapterTitle(
	text: String,
	style: TextStyle,
	fontWeight: FontWeight,
	color: Color,
	canAnimate: () -> Boolean,
	modifier: Modifier = Modifier,
) {
	val context = LocalContext.current
	if (!remember(context) { context.isAnimationsEnabled }) {
		Text(
			text = text, style = style, fontWeight = fontWeight, color = color,
			maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier,
		)
		return
	}

	var overflowPx by remember(text) { mutableIntStateOf(0) }
	val progress = remember(text, overflowPx) { Animatable(0f) }
	val animationAllowed by rememberUpdatedState(canAnimate)
	val density = LocalDensity.current.density
	if (overflowPx > 0) {
		LaunchedEffect(progress, density) {
			val motionScale = currentCoroutineContext()[MotionDurationScale]
			val travelMs = chapterTitleTravelMillis(overflowPx, density)
			snapshotFlow { animationAllowed() && (motionScale?.scaleFactor ?: 1f) > 0f }
				.collectLatest { allowed ->
					progress.snapTo(0f)
					if (!allowed) return@collectLatest
					while (true) {
						delay(TITLE_END_PAUSE_MS)
						progress.animateTo(1f, tween(travelMs, easing = LinearEasing))
						delay(TITLE_END_PAUSE_MS)
						progress.animateTo(0f, tween(travelMs, easing = LinearEasing))
					}
				}
		}
	}

	Text(
		text = text,
		style = style,
		fontWeight = fontWeight,
		color = color,
		maxLines = 1,
		softWrap = false,
		overflow = TextOverflow.Clip,
		modifier = modifier.clipToBounds().layout { measurable, constraints ->
			val title = measurable.measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
			val viewportWidth = constraints.constrainWidth(title.width)
			val distance = if (viewportWidth > 0) (title.width - viewportWidth).coerceAtLeast(0) else 0
			overflowPx = distance
			val direction = if (layoutDirection == LayoutDirection.Ltr) -1 else 1
			layout(viewportWidth, title.height) {
				// Only the text layer pans; the slot and sibling icons never move.
				title.placeRelativeWithLayer(0, 0) {
					translationX = direction * distance * progress.value
				}
			}
		},
	)
}

internal fun chapterTitleTravelMillis(overflowPx: Int, density: Float): Int =
	(overflowPx * 1_000f / (density * TITLE_SPEED_DP_PER_SECOND)).roundToInt().coerceAtLeast(300)

private const val TITLE_SPEED_DP_PER_SECOND = 36f
private const val TITLE_END_PAUSE_MS = 900L
