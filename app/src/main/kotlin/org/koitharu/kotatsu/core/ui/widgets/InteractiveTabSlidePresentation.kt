package org.koitharu.kotatsu.core.ui.widgets

import android.view.View

/**
 * Gesture-owned presentation only. Coordinates come directly from the pointer, not an animator.
 * At most two roots are visible; original root properties are restored on cancellation/commit.
 * The caller owns the route, producer and Fragment lifecycle, and supplies retained roots.
 */
internal class InteractiveTabSlidePresentation {
	private data class Root(val view: View, val x: Float, val visibility: Int)

	private val roots = linkedMapOf<Int, Root>()
	private var frame: TabSlideFrame? = null
	private var committedId: Int? = null
	private var width = 0f

	val isActive: Boolean get() = committedId != null

	fun contains(id: Int) = frame?.contains(id) == true

	fun seek(next: TabSlideFrame, committed: Int, current: View, resolve: (Int) -> View?): Boolean {
		if (committedId != null && (committedId != committed || roots[committed]?.view !== current)) finish()
		if (!isActive) {
			committedId = committed
			roots[committed] = Root(current, current.translationX, current.visibility)
		}
		val parent = current.parent as? View ?: return false
		width = parent.width.toFloat()
		if (width <= 0f) return false
		for (id in listOf(next.leftId, next.rightId).distinct()) {
			val view = roots[id]?.view ?: resolve(id) ?: return false
			if (view.parent !== parent) return false
			if (id !in roots) roots[id] = Root(view, view.translationX, view.visibility)
		}
		for ((id, root) in roots) {
			val offset = when {
				id == next.leftId -> -width * next.fraction
				id == next.rightId -> width * (1f - next.fraction)
				else -> 0f
			}
			root.view.translationX = root.x + offset
			root.view.visibility = when {
				next.contains(id) -> View.VISIBLE
				id == committed -> View.INVISIBLE
				else -> root.visibility
			}
		}
		frame = next
		return true
	}

	/** A release toward either page continues from the exact displayed offsets. */
	fun captureHandoff(target: Int): DirectionalSlidePresentation.Handoff? {
		val shown = frame ?: return null
		if (!shown.contains(target) || shown.leftId == shown.rightId) return null
		val incoming = roots[target] ?: return null
		val otherId = if (target == shown.leftId) shown.rightId else shown.leftId
		val outgoing = roots[otherId] ?: return null
		val distance = if (target == shown.rightId) width else -width
		return DirectionalSlidePresentation.Handoff(
			outgoing.view, incoming.view, outgoing.view.translationX - outgoing.x,
			incoming.view.translationX - incoming.x, distance,
		)
	}

	fun finish() {
		frame = null
		committedId = null
		for (root in roots.values) {
			root.view.translationX = root.x
			root.view.visibility = root.visibility
		}
		roots.clear()
		width = 0f
	}
}
