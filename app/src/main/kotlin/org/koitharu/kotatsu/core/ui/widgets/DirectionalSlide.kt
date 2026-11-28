package org.koitharu.kotatsu.core.ui.widgets

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.DecelerateInterpolator
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.ScreenTransition
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
		val distance: Float,
		val effect: ScreenTransition = ScreenTransition.DIRECTIONAL_SLIDE,
		val outgoingRoot: ScreenTransitionRoot? = null,
		val incomingRoot: ScreenTransitionRoot? = null,
		var fromOffset: Float = 0f,
		var toOffset: Float = 0f,
	)

	internal data class Handoff(
		val outgoing: View,
		val incoming: View,
		val outgoingOffset: Float,
		val incomingOffset: Float,
		val distance: Float,
		val effect: ScreenTransition = ScreenTransition.DIRECTIONAL_SLIDE,
	)

	private var state: PairState? = null
	private var animator: ValueAnimator? = null

	val hasCustomTransition: Boolean get() = state?.effect?.let { it != ScreenTransition.DIRECTIONAL_SLIDE } == true

	fun start(outgoing: View, incoming: View, direction: Int, outgoingAfter: Int,
		effect: ScreenTransition = ScreenTransition.DIRECTIONAL_SLIDE) {
		finish()
		if (outgoing === incoming) { incoming.visibility = View.VISIBLE; return }
		incoming.visibility = View.VISIBLE
		val width = (outgoing.parent as? View)?.width?.takeIf { it > 0 } ?: outgoing.width
		if (direction == 0 || width <= 0 || !outgoing.context.isAnimationsEnabled) {
			outgoing.visibility = outgoingAfter
			return
		}
		val distance = direction * width.toFloat()
		val pair = pairState(outgoing, incoming, outgoingAfter, distance, effect)
		animate(pair, 0f, distance, outgoing.resources.getInteger(R.integer.navigation_slide_duration).toLong())
	}

	/** Snapshot visual offsets before the delegate restores views and commits a valid target. */
	fun captureHandoff(incoming: View): Handoff? = state?.takeIf { it.incoming === incoming }?.let {
		Handoff(it.outgoing, it.incoming, it.fromOffset, it.toOffset, it.distance, it.effect)
	}

	/** Continue only the remaining visual distance after the real fragment commit. */
	fun resume(handoff: Handoff) {
		finish()
		val outgoing = handoff.outgoing
		val incoming = handoff.incoming
		if (!outgoing.isAttachedToWindow || !incoming.isAttachedToWindow ||
			outgoing.parent !== incoming.parent || outgoing.visibility == View.VISIBLE ||
			!incoming.context.isAnimationsEnabled || handoff.distance == 0f
		) return
		val fraction = kotlin.math.abs(handoff.incomingOffset / handoff.distance).coerceIn(0f, 1f)
		if (fraction <= 0.01f) return
		val pair = pairState(outgoing, incoming, outgoing.visibility, handoff.distance, handoff.effect)
		val duration = (outgoing.resources.getInteger(R.integer.navigation_slide_duration) * fraction).toLong().coerceAtLeast(1L)
		animate(pair, handoff.outgoingOffset, handoff.incomingOffset, duration)
	}

	private fun pairState(outgoing: View, incoming: View, after: Int, distance: Float, effect: ScreenTransition): PairState {
		val custom = effect != ScreenTransition.DIRECTIONAL_SLIDE
		return PairState(outgoing, incoming, outgoing.translationX, incoming.translationX, after, distance,
			effect, if (custom) ScreenTransitionRoot(outgoing) else null, if (custom) ScreenTransitionRoot(incoming) else null)
	}

	private fun render(pair: PairState, fromOffset: Float, toOffset: Float) {
		pair.fromOffset = fromOffset
		pair.toOffset = toOffset
		val outgoing = pair.outgoingRoot
		val incoming = pair.incomingRoot
		if (outgoing == null || incoming == null) {
			pair.outgoing.translationX = pair.fromX + fromOffset
			pair.incoming.translationX = pair.toX + toOffset
			return
		}
		val remaining = (toOffset / pair.distance).coerceIn(0f, 1f)
		val parent = pair.incoming.parent as? View
		val width = kotlin.math.abs(pair.distance)
		val height = (parent?.height ?: pair.incoming.height).toFloat().coerceAtLeast(1f)
		if (pair.distance > 0f) ScreenTransitionTransformer.render(pair.effect, outgoing, incoming, 1f - remaining, width, height)
		else ScreenTransitionTransformer.render(pair.effect, incoming, outgoing, remaining, width, height)
		outgoing.accessibilityHidden(true)
		// A visually absent incoming root should not advertise accessibility nodes.
		incoming.accessibilityHidden(incoming.view.visibility != View.VISIBLE)
	}

	private fun animate(pair: PairState, fromOffset: Float, toOffset: Float, durationMs: Long) {
		state = pair
		pair.outgoing.visibility = View.VISIBLE
		pair.incoming.visibility = View.VISIBLE
		render(pair, fromOffset, toOffset)
		animator = ValueAnimator.ofFloat(0f, 1f).apply {
			duration = durationMs
			interpolator = DecelerateInterpolator()
			addUpdateListener {
				if (state === pair) {
					val progress = it.animatedValue as Float
					render(pair, fromOffset + (-pair.distance - fromOffset) * progress, toOffset * (1f - progress))
				}
			}
			addListener(object : AnimatorListenerAdapter() {
				override fun onAnimationEnd(animation: Animator) {
					if (state === pair) finish()
				}
			})
		}
		animator?.start()
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
			if (it.outgoingRoot != null && it.incomingRoot != null) {
				it.outgoingRoot.restore(it.outgoingAfter)
				it.incomingRoot.restore(View.VISIBLE)
			} else {
				it.outgoing.translationX = it.fromX
				it.incoming.translationX = it.toX
				it.outgoing.visibility = it.outgoingAfter
			}
		}
	}
}
