package org.koitharu.kotatsu.core.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onEach

/** A stable onEach on the existing UI subscription; no extra collector or lifecycle change. */
@Composable
fun <T> StateFlow<T>.collectDiagnosticState(session: Long, stage: String, count: (T) -> Int): State<T> {
	if (!DetailsDiagnostic.enabled) return collectAsState()
	val observed = remember(this, session, stage) {
		onEach { value ->
			val size = count(value)
			if (size > 0 && DetailsDiagnostic.pending(session, stage)) DetailsDiagnostic.mark(session, stage,
				mapOf("chapterCount" to size, "origin" to "collector_emission"), once = true)
		}
	}
	val state = observed.collectAsState(initial = value)
	// StateFlow may already hold chapters before the UI collector coroutine starts. Record the
	// actual Compose state read as well so this fast path cannot appear to consume after layout.
	if (DetailsDiagnostic.pending(session, stage)) {
		val size = count(state.value)
		if (size > 0) DetailsDiagnostic.mark(session, stage,
			mapOf("chapterCount" to size, "origin" to "composition_state_read"), once = true)
	}
	return state
}

private class ChapterPlacement { var intersectsWindow = false }

/** Adds observers/draw recording only. Does not measure, resize, wrap or invalidate the child. */
@Composable
fun Modifier.diagnosticChapter(session: Long, chapterId: Long): Modifier {
	if (!DetailsDiagnostic.enabled || session == 0L) return this
	val view = LocalView.current
	val placement = remember(session, chapterId) { ChapterPlacement() }
	return this
		.onPlaced {
			if (DetailsDiagnostic.pending(session, "first_chapter_placement")) {
				DetailsDiagnostic.mark(session, "first_chapter_placement", mapOf("chapterId" to chapterId,
					"width" to it.size.width, "height" to it.size.height), once = true)
			}
		}
		.onGloballyPositioned {
			val bounds = it.boundsInWindow()
			placement.intersectsWindow = view.isShown && bounds.width > 0 && bounds.height > 0 &&
				bounds.right > 0 && bounds.bottom > 0 && bounds.left < view.rootView.width && bounds.top < view.rootView.height
			if (placement.intersectsWindow && DetailsDiagnostic.pending(session, "first_chapter_in_window")) DetailsDiagnostic.mark(session, "first_chapter_in_window",
				mapOf("chapterId" to chapterId, "top" to bounds.top, "bottom" to bounds.bottom), once = true)
		}
		.drawWithContent {
			drawContent()
			if (placement.intersectsWindow) DetailsDiagnostic.firstDraw(session, chapterId)
		}
}
