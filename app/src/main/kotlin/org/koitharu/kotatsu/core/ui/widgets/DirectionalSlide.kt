package org.koitharu.kotatsu.core.ui.widgets

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.DecelerateInterpolator
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.isAnimationsEnabled

/** Candidate motion constants; physical reference calibration is still required. */
internal object DirectionalSlide {
	fun direction(from: Int, to: Int, rtl: Boolean = false): Int {
		if (from < 0 || to < 0 || from == to) return 0
		val sign = if (to > from) 1 else -1
		return if (rtl) -sign else sign
	}

	fun animations(direction: Int): Pair<Int, Int> =
		if (direction > 0) R.anim.navigation_slide_enter_right to R.anim.navigation_slide_exit_left
		else R.anim.navigation_slide_enter_left to R.anim.navigation_slide_exit_right
}

/**
 * Two-view presentation only. It never owns a fragment, route, selection or lifecycle.
 * Shared by tab-drag previews and the isolated Settings demo. A new intent finishes the
 * old presentation before starting; cleanup removes listeners before cancellation.
 */
internal class DirectionalSlidePresentation {
	private data class PairState(
		val outgoing: View,
		val incoming: View,
		val fromX: Float,
		val toX: Float,
		val outgoingAfter: Int,
	)

	private var state: PairState? = null
	private var animator: ValueAnimator? = null

	fun start(outgoing: View, incoming: View, direction: Int, outgoingAfter: Int) {
		finish()
		if (outgoing === incoming) { incoming.visibility = View.VISIBLE; return }
		incoming.visibility = View.VISIBLE
		val width = (outgoing.parent as? View)?.width?.takeIf { it > 0 } ?: outgoing.width
		if (direction == 0 || width <= 0 || !outgoing.context.isAnimationsEnabled) {
			outgoing.visibility = outgoingAfter
			return
		}
		val pair = PairState(outgoing, incoming, outgoing.translationX, incoming.translationX, outgoingAfter)
		state = pair
		outgoing.visibility = View.VISIBLE
		val distance = direction * width.toFloat()
		incoming.translationX = pair.toX + distance
		animator = ValueAnimator.ofFloat(0f, 1f).apply {
			duration = outgoing.resources.getInteger(R.integer.navigation_slide_duration).toLong()
			interpolator = DecelerateInterpolator()
			addUpdateListener {
				if (state === pair) {
					val progress = it.animatedValue as Float
					outgoing.translationX = pair.fromX - distance * progress
					incoming.translationX = pair.toX + distance * (1f - progress)
				}
			}
			addListener(object : AnimatorListenerAdapter() {
				override fun onAnimationEnd(animation: Animator) {
					if (state === pair) finish()
				}
			})
			start()
		}
	}

	fun finish() {
		val pair = state
		state = null
		val old = animator
		animator = null
		old?.removeAllListeners()
		old?.removeAllUpdateListeners()
		old?.cancel()
		pair?.let {
			it.outgoing.translationX = it.fromX
			it.incoming.translationX = it.toX
			it.outgoing.visibility = it.outgoingAfter
		}
	}
}
