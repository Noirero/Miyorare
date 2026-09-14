package org.koitharu.kotatsu.settings.compose

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * Top-level container for redesigned settings screens.
 * Modern Settings is deliberately Clean: a flat semantic background, while Classic keeps its
 * original host background and all search/scroll behaviour unchanged.
 */
@Composable
fun SettingsScaffold(
	modifier: Modifier = Modifier,
	content: SettingsListScope.() -> Unit,
) {
	val scope = SettingsListScope()
	scope.content()
	val visualPalette = LocalMiyorareVisualPalette.current
	val modernBackground = MaterialTheme.colorScheme.background

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

	BoxWithConstraints(
		modifier = modifier
			.fillMaxSize()
			.let {
				if (visualPalette.isModern) {
					it.background(modernBackground)
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
		val horizontalPadding = when {
			maxWidth < 360.dp -> 10.dp
			maxWidth < 600.dp -> 16.dp
			else -> 24.dp
		}
		val contentWidth = maxWidth.coerceAtMost(840.dp)
		CompositionLocalProvider(
			LocalSettingsHighlightScroll provides scrollTo,
			LocalSettingsScrollToTop provides scrollToTop,
		) {
			Column(
				modifier = Modifier
					.align(Alignment.TopCenter)
					.width(contentWidth)
					.fillMaxHeight()
					.verticalScroll(scrollState)
					.padding(top = 10.dp, bottom = 28.dp, start = horizontalPadding, end = horizontalPadding),
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
