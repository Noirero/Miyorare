package org.koitharu.kotatsu.core.ui.widgets

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.OvershootInterpolator
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs

/** One gesture owner for both bottom bars and the native rail. Preview is discrete; drawing isn't. */
internal class NavigationTabDrag(
	private val host: View,
	private val vertical: Boolean,
	private val visibleSlots: () -> List<Pair<Int, Rect>>,
	private val selectedId: () -> Int,
	private val accent: () -> Int,
	private val onIndicatorChanged: (Boolean) -> Unit,
	private val canStart: (Float, Rect) -> Boolean = { _, _ -> true },
) {
	var preview: ((Int?) -> Boolean)? = null
	var commit: ((Int) -> Unit)? = null
	private val touchSlop = ViewConfiguration.get(host.context).scaledTouchSlop
	private val origin = IntArray(2)
	private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val shape = RectF()
	private val density = host.resources.displayMetrics.density
	private var stream: Stream? = null
	private var previewId: Int? = null
	internal var indicatorBounds: Rect? = null
		private set
	private var settle: ValueAnimator? = null
	private var settleId: Int? = null
	private var progress = 0f

	private class Stream(val x: Float, val y: Float, val slots: List<Pair<Int, Rect>>) {
		var captured = false
		var rejected = false
	}

	fun cancel() {
		val drag = stream ?: return
		drag.rejected = true
		if (drag.captured) {
			showPreview(null)
			settleToSelection()
		}
	}

	fun reset() {
		if (stream?.captured == true) host.parent?.requestDisallowInterceptTouchEvent(false)
		stream = null
		showPreview(null)
		clearIndicator()
	}

	fun dispatch(event: MotionEvent, dispatchDefault: (MotionEvent) -> Boolean): Boolean {
		val action = event.actionMasked
		if (action == MotionEvent.ACTION_DOWN) {
			reset()
			if (preview != null) {
				host.getLocationInWindow(origin)
				val x = event.x + origin[0]
				val y = event.y + origin[1]
				val slots = visibleSlots()
				if (slots.any { (_, rect) -> contains(rect, x, y) && canStart(x, rect) }) {
					stream = Stream(x, y, slots)
				}
			}
			return dispatchDefault(event).also { if (!it) stream = null }
		}
		val drag = stream ?: return dispatchDefault(event)
		if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_CANCEL) cancel()
		if (action == MotionEvent.ACTION_MOVE && !drag.rejected) {
			host.getLocationInWindow(origin)
			val x = event.x + origin[0]
			val y = event.y + origin[1]
			if (!drag.captured) {
				val dx = abs(x - drag.x)
				val dy = abs(y - drag.y)
				val along = if (vertical) dy else dx
				val across = if (vertical) dx else dy
				if (event.eventTime - event.downTime >= ViewConfiguration.getLongPressTimeout() ||
					(across > touchSlop && across >= along)
				) {
					drag.rejected = true
				} else if (along > touchSlop && along > across) {
					drag.captured = true
					val cancel = MotionEvent.obtain(event)
					try {
						cancel.action = MotionEvent.ACTION_CANCEL
						dispatchDefault(cancel)
					} finally {
						cancel.recycle()
					}
					host.parent?.requestDisallowInterceptTouchEvent(true)
				}
			}
			if (drag.captured) {
				val slot = hit(drag.slots, x, y)
				showPreview(slot?.first)
				if (slot != null && previewId == slot.first && !drag.rejected) {
					val rect = slot.second
					val axis = if (vertical) y else x
					val first = drag.slots.minOf { center(it.second) }
					val last = drag.slots.maxOf { center(it.second) }
					val offset = axis.coerceIn(first, last) - center(rect)
					// The visual position updates even when the hovered item has not changed.
					setIndicator(if (vertical) Rect(rect.left, rect.top + offset, rect.right, rect.bottom + offset)
					else Rect(rect.left + offset, rect.top, rect.right + offset, rect.bottom))
				} else if (!drag.rejected) {
					// Leaving the tab strip restores its committed highlight, too.
					clearIndicator()
				}
			}
		}
		if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
			stream = null
			if (drag.captured) {
				host.parent?.requestDisallowInterceptTouchEvent(false)
				try {
					if (action == MotionEvent.ACTION_UP && !drag.rejected) {
						host.getLocationInWindow(origin)
						hit(drag.slots, event.x + origin[0], event.y + origin[1])?.let { commit?.invoke(it.first) }
					}
				} finally {
					showPreview(null)
					settleToSelection()
				}
			}
		}
		return drag.captured || dispatchDefault(event)
	}

	private fun showPreview(id: Int?) {
		if (id == previewId) return
		previewId = id.takeIf { preview?.invoke(id) == true }
	}

	private fun setIndicator(rect: Rect) {
		val wasIdle = indicatorBounds == null
		indicatorBounds = rect
		if (wasIdle) onIndicatorChanged(true)
		host.invalidate()
	}

	private fun settleToSelection() {
		if (indicatorBounds == null || settle != null) return
		settleId = selectedId()
		progress = 0f
		settle = ValueAnimator.ofFloat(0f, 1f).apply {
			duration = 210L
			interpolator = OvershootInterpolator(0.8f)
			addUpdateListener {
				progress = it.animatedValue as Float
				host.invalidate()
			}
			addListener(object : AnimatorListenerAdapter() {
				override fun onAnimationEnd(animation: Animator) = clearIndicator()
			})
		}
		settle?.start()
	}

	private fun clearIndicator() {
		val animation = settle
		settle = null
		animation?.removeAllListeners()
		animation?.removeAllUpdateListeners()
		animation?.cancel()
		settleId = null
		if (indicatorBounds == null) return
		indicatorBounds = null
		onIndicatorChanged(false)
		host.invalidate()
	}

	fun draw(canvas: Canvas) {
		val from = indicatorBounds ?: return
		val to = settleId?.let { id -> visibleSlots().firstOrNull { it.first == id }?.second } ?: from
		fun blend(a: Float, b: Float) = a + (b - a) * progress
		host.getLocationInWindow(origin)
		val x = blend(from.center.x, to.center.x) - origin[0]
		val y = blend(from.center.y, to.center.y) - origin[1]
		val halfWidth = (blend(from.width, to.width) * 0.5f - 3f * density).coerceIn(4f * density, 29f * density)
		val halfHeight = (blend(from.height, to.height) * 0.5f - 4f * density).coerceIn(4f * density, 24f * density)
		shape.set(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight)
		val radius = minOf(halfHeight, halfWidth, (if (vertical) 16f else 23f) * density)
		paint.color = accent()
		paint.style = Paint.Style.FILL
		paint.alpha = 48
		canvas.drawRoundRect(shape, radius, radius, paint)
		paint.style = Paint.Style.STROKE
		for (layer in 0..2) {
			paint.strokeWidth = (if (layer == 0) 9f else if (layer == 1) 4f else 1.4f) * density
			paint.alpha = if (layer == 0) 24 else if (layer == 1) 52 else 168
			canvas.drawRoundRect(shape, radius, radius, paint)
		}
	}

	private fun center(rect: Rect) = if (vertical) rect.center.y else rect.center.x

	private fun hit(slots: List<Pair<Int, Rect>>, x: Float, y: Float): Pair<Int, Rect>? {
		val coordinate = if (vertical) y else x
		var first = Float.POSITIVE_INFINITY
		var last = Float.NEGATIVE_INFINITY
		var nearest: Pair<Int, Rect>? = null
		var distance = Float.POSITIVE_INFINITY
		for (slot in slots) {
			val rect = slot.second
			if (contains(rect, x, y)) return slot
			if (vertical && (x < rect.left || x >= rect.right) ||
				!vertical && (y < rect.top || y >= rect.bottom)) continue
			val start = if (vertical) rect.top else rect.left
			val end = if (vertical) rect.bottom else rect.right
			first = minOf(first, start)
			last = maxOf(last, end)
			val gap = maxOf(start - coordinate, coordinate - end)
			if (gap < distance) {
				distance = gap
				nearest = slot
			}
		}
		return nearest.takeIf { coordinate >= first && coordinate < last }
	}

	private fun contains(rect: Rect, x: Float, y: Float) =
		x >= rect.left && x < rect.right && y >= rect.top && y < rect.bottom
}
