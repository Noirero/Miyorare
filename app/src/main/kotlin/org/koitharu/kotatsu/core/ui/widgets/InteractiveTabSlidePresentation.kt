package org.koitharu.kotatsu.core.ui.widgets

import android.view.View
import org.koitharu.kotatsu.core.prefs.ScreenTransition

/**
 * Gesture-owned presentation only. Coordinates come directly from the pointer, not an animator.
 * At most two roots are visible; original root properties are restored on cancellation/commit.
 * The caller owns the route, producer and Fragment lifecycle, and supplies retained roots.
 */
internal class InteractiveTabSlidePresentation {
	private data class Root(val view: View, val x: Float, val visibility: Int, val transform: ScreenTransitionRoot?)

	private val roots = linkedMapOf<Int, Root>()
	private var frame: TabSlideFrame? = null
	private var committedId: Int? = null
	private var width = 0f
	private var effect = ScreenTransition.DIRECTIONAL_SLIDE

	val isActive: Boolean get() = committedId != null

	fun contains(id: Int) = frame?.contains(id) == true

	fun seek(next: TabSlideFrame, committed: Int, current: View,
		nextEffect: ScreenTransition = ScreenTransition.DIRECTIONAL_SLIDE, resolve: (Int) -> View?): Boolean {
		if (effect != nextEffect) finish()
		effect = nextEffect
		if (committedId != null && (committedId != committed || roots[committed]?.view !== current)) finish()
		if (!isActive) {
			committedId = committed
			roots[committed] = captureRoot(current)
		}
		val parent = current.parent as? View ?: return false
		width = parent.width.toFloat()
		if (width <= 0f) return false
		for (id in listOf(next.leftId, next.rightId).distinct()) {
			val view = roots[id]?.view ?: resolve(id) ?: return false
			if (view.parent !== parent) return false
			if (id !in roots) roots[id] = captureRoot(view)
		}
		if (effect != ScreenTransition.DIRECTIONAL_SLIDE) {
			// Restore pages leaving the active pair; do not rebuild or reparent them.
			for ((id, root) in roots) {
				if (!next.contains(id) || next.leftId == next.rightId) {
					root.transform?.restore(if (next.contains(id)) View.VISIBLE else if (id == committed) View.INVISIBLE else root.visibility)
				}
				root.transform?.accessibilityHidden(id != committed)
			}
			if (next.leftId != next.rightId) {
				val left = checkNotNull(roots[next.leftId]?.transform)
				val right = checkNotNull(roots[next.rightId]?.transform)
				ScreenTransitionTransformer.render(effect, left, right, next.fraction, width, parent.height.toFloat().coerceAtLeast(1f))
				left.accessibilityHidden(next.leftId != committed || left.view.visibility != View.VISIBLE)
				right.accessibilityHidden(next.rightId != committed || right.view.visibility != View.VISIBLE)
			}
			frame = next
			return true
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

	private fun captureRoot(view: View) = Root(view, view.translationX, view.visibility,
		if (effect == ScreenTransition.DIRECTIONAL_SLIDE) null else ScreenTransitionRoot(view))

	/** A release toward either page continues from the exact displayed progress. */
	fun captureHandoff(target: Int): DirectionalSlidePresentation.Handoff? {
		val shown = frame ?: return null
		if (!shown.contains(target) || shown.leftId == shown.rightId) return null
		val incoming = roots[target] ?: return null
		val otherId = if (target == shown.leftId) shown.rightId else shown.leftId
		val outgoing = roots[otherId] ?: return null
		val distance = if (target == shown.rightId) width else -width
		val incomingOffset = if (target == shown.rightId) width * (1f - shown.fraction) else -width * shown.fraction
		val outgoingOffset = incomingOffset - distance
		return DirectionalSlidePresentation.Handoff(outgoing.view, incoming.view, outgoingOffset, incomingOffset, distance, effect)
	}

	fun finish() {
		frame = null
		committedId = null
		for (root in roots.values) {
			if (root.transform != null) root.transform.restore() else {
				root.view.translationX = root.x
				root.view.visibility = root.visibility
			}
		}
		roots.clear()
		width = 0f
	}
}
