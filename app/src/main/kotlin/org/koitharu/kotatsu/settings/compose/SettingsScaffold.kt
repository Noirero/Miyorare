package org.koitharu.kotatsu.settings.compose

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.core.ui.LocalMiyorareVisualPalette
import org.koitharu.kotatsu.settings.SettingsActivity

val LocalSettingsHighlightScroll = compositionLocalOf<(Float) -> Unit> { {} }
val LocalSettingsScrollToTop = compositionLocalOf<(Float) -> Unit> { {} }

/**
 * Top-level container for redesigned settings screens. Modern gets one static three-stop background
 * wash, while Classic keeps its original host background and all search/scroll behaviour unchanged.
 */
@Composable
fun SettingsScaffold(
	modifier: Modifier = Modifier,
	content: SettingsListScope.() -> Unit,
) {
	val scope = SettingsListScope()
	scope.content()
	val visualPalette = LocalMiyorareVisualPalette.current

	val scrollState = rememberScrollState()
	val activity = LocalContext.current.findSettingsActivity()
	LaunchedEffect(activity, scrollState) {
		activity?.appBar?.setExpanded(scrollState.value == 0, false)
	}
	val coroutineScope = rememberCoroutineScope()
	val viewportTop = remember { mutableFloatStateOf(0f) }
	val viewportHeight = remember { mutableIntStateOf(0) }
	val scrollTo = remember(scrollState, coroutineScope) {
		{ windowY: Float ->
			val bias = viewportHeight.intValue * 0.28f
			val target = (scrollState.value + (windowY - viewportTop.floatValue) - bias)
				.toInt()
				.coerceIn(0, scrollState.maxValue)
			coroutineScope.launch { scrollState.animateScrollTo(target) }
			Unit
		}
	}
	val scrollToTop = remember(scrollState, coroutineScope) {
		{ windowY: Float ->
			val target = (scrollState.value + windowY - viewportTop.floatValue)
				.toInt().coerceIn(0, scrollState.maxValue)
			coroutineScope.launch { scrollState.animateScrollTo(target) }
			Unit
		}
	}

	Box(
		modifier = modifier
			.fillMaxSize()
			.let {
				if (visualPalette.isModern) {
					it.background(
						brush = Brush.verticalGradient(
							listOf(
								visualPalette.backgroundGradientStart,
								visualPalette.backgroundGradientMiddle,
								visualPalette.backgroundGradientEnd,
							),
						),
					)
				} else {
					it
				}
			}
			.nestedScroll(rememberNestedScrollInteropConnection())
			.onGloballyPositioned {
				viewportTop.floatValue = it.positionInWindow().y
				viewportHeight.intValue = it.size.height
			},
	) {
		CompositionLocalProvider(
			LocalSettingsHighlightScroll provides scrollTo,
			LocalSettingsScrollToTop provides scrollToTop,
		) {
			Column(
				modifier = Modifier
					.fillMaxSize()
					.verticalScroll(scrollState)
					.padding(top = 10.dp, bottom = 28.dp, start = 16.dp, end = 16.dp),
			) {
				scope.items.forEach { item ->
					Box(Modifier.fillMaxWidth()) { item() }
				}
			}
		}
	}
}

private tailrec fun Context.findSettingsActivity(): SettingsActivity? = when (this) {
	is SettingsActivity -> this
	is ContextWrapper -> baseContext.findSettingsActivity()
	else -> null
}

class SettingsListScope {
	internal val items = mutableListOf<@Composable () -> Unit>()
	fun item(content: @Composable () -> Unit) {
		items += content
	}
}
