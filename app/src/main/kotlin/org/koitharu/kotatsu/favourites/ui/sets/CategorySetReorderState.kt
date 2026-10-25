package org.koitharu.kotatsu.favourites.ui.sets

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/** Gesture-local order only. One final ID snapshot is persisted when the handle is released. */
@Stable
internal class CategorySetReorderState(val list: LazyListState) {
	var order by mutableStateOf<List<CategorySetRow>>(emptyList())
	var draggingId by mutableStateOf<Long?>(null)
		private set
	private var top by mutableFloatStateOf(0f)
	private var height = 0

	fun start(id: Long) {
		val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id } ?: return
		draggingId = id
		top = item.offset.toFloat()
		height = item.size
	}

	fun drag(delta: Float) {
		top += delta
		moveAtCenter()
	}

	fun translation(id: Long): Float = if (draggingId == id) {
		top - (list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }?.offset ?: top.toInt())
	} else 0f

	private fun moveAtCenter() {
		val id = draggingId ?: return
		val info = list.layoutInfo
		val center = (top + height / 2f).coerceIn(
			info.viewportStartOffset.toFloat(), (info.viewportEndOffset - 1).coerceAtLeast(info.viewportStartOffset).toFloat(),
		)
		val target = list.layoutInfo.visibleItemsInfo.firstOrNull {
			it.key != id && center >= it.offset && center < it.offset + it.size
		} ?: return
		val from = order.indexOfFirst { it.set.setId == id }
		val to = target.index
		// Layout info can lag one frame behind the local move. Do not move back against stale bounds.
		if (order.getOrNull(to)?.set?.setId != target.key) return
		if (from >= 0 && to in order.indices && from != to) {
			order = order.toMutableList().apply { add(to, removeAt(from)) }
		}
	}

	suspend fun autoScroll(edge: Float) {
		while (coroutineContext.isActive && draggingId != null) {
			withFrameNanos { }
			val info = list.layoutInfo
			val delta = when {
				top < info.viewportStartOffset + edge -> -((info.viewportStartOffset + edge - top) / 5).coerceAtMost(edge / 3)
				top + height > info.viewportEndOffset - edge -> ((top + height - info.viewportEndOffset + edge) / 5).coerceAtMost(edge / 3)
				else -> 0f
			}
			if (delta != 0f) {
				list.scrollBy(delta)
				moveAtCenter()
			}
		}
	}

	fun stop(): List<Long> {
		draggingId = null
		return order.map { it.set.setId }
	}
}
