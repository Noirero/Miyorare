package org.koitharu.kotatsu.core.ui.widgets

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import androidx.core.graphics.ColorUtils
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import com.google.android.material.R as materialR
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.OvershootInterpolator
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import android.view.animation.DecelerateInterpolator
import org.koitharu.kotatsu.core.prefs.NavigationMotion
import org.koitharu.kotatsu.core.util.ext.isAnimationsEnabled

/** One gesture owner for both bottom bars and the native rail. Preview is discrete; drawing isn't. */
internal class NavigationTabDrag(
	private val host: View,
	private val vertical: Boolean,
	private val visibleSlots: () -> List<Pair<Int, Rect>>,
	private val selectedId: () -> Int,
	private val accent: () -> Int,
	private val onIndicatorChanged: (Boolean) -> Unit,
	private val canStart: (Float, Rect) -> Boolean = { _, _ -> true },
	private val onVisualFrameChanged: () -> Unit = {},
	private val surfaceColor: () -> Int = { host.context.getThemeColor(materialR.attr.colorSurface) },
) {
	var preview: ((Int?) -> Boolean)? = null
	var commit: ((Int) -> Unit)? = null
	var slideFrame: ((TabSlideFrame) -> Unit)? = null
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
	private var motion = NavigationMotion.CLASSIC_GLIDE
	private var motionEnabled = true
	private var pressed = false
	private var stretch = 0f
	private var motionViewport: Rect? = null // window coordinates of the actual tab strip, not Continue Reading
	private val lensMatrix = Matrix()
	private var lensShader: LinearGradient? = null
	private var lensTop = 0
	private var lensBottom = 0

	fun setMotionViewport(bounds: Rect?) {
		if (motionViewport == bounds) return
		motionViewport = bounds
		invalidateIndicator()
	}

	private fun invalidateIndicator() {
		host.invalidate()
		if (motion != NavigationMotion.CLASSIC_GLIDE) onVisualFrameChanged()
	}

	fun setMotion(value: NavigationMotion) {
		if (motion == value) return
		reset()
		motion = value
	}

	fun animateSelection(fromId: Int, toId: Int) {
		if (motion == NavigationMotion.CLASSIC_GLIDE || fromId == toId || !host.context.isAnimationsEnabled) return
		val from = visibleSlots().firstOrNull { it.first == fromId }?.second ?: return
		if (visibleSlots().none { it.first == toId }) return
		clearIndicator()
		motionEnabled = true
		pressed = true
		setIndicator(from)
		settleToSelection(toId)
	}

	private class Stream(val x: Float, val y: Float, val slots: List<Pair<Int, Rect>>) {
		var captured = false
		var rejected = false
	}

	fun cancel() {
		val drag = stream ?: run {
			if (settle != null) clearIndicator()
			return
		}
		drag.rejected = true
		if (drag.captured) {
			showPreview(null)
			settleToSelection()
		} else if (pressed) {
			clearIndicator()
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
			motionEnabled = host.context.isAnimationsEnabled
			if (preview != null) {
				host.getLocationInWindow(origin)
				val x = event.x + origin[0]
				val y = event.y + origin[1]
				val slots = visibleSlots()
				if (slots.any { (_, rect) -> contains(rect, x, y) && canStart(x, rect) }) {
					stream = Stream(x, y, slots)
					// Press feedback does not capture the stream or suppress the existing long click.
					if (motionEnabled && motion != NavigationMotion.CLASSIC_GLIDE) {
						slots.firstOrNull { (id, rect) -> id == selectedId() && contains(rect, x, y) }?.let {
							pressed = true
							setIndicator(it.second)
						}
					}
				}
			}
			return dispatchDefault(event).also { if (!it) { stream = null; clearIndicator() } }
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
					if (pressed) clearIndicator()
				} else if (along > touchSlop && along > across) {
					drag.captured = true
					pressed = true
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
					pressed = true
					val rect = slot.second
					val axis = if (vertical) y else x
					emitSlideFrame(drag, axis, slot.first)
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
						val x = event.x + origin[0]
						val y = event.y + origin[1]
						hit(drag.slots, x, y)?.let { slot ->
							showPreview(slot.first)
							if (previewId == slot.first) emitSlideFrame(drag, if (vertical) y else x, slot.first)
							commit?.invoke(slot.first)
						}
					}
				} finally {
					showPreview(null)
					settleToSelection()
				}
			} else if (pressed) {
				settleToSelection()
			}
		}
		return drag.captured || dispatchDefault(event)
	}

	private fun emitSlideFrame(drag: Stream, axis: Float, hoveredId: Int) {
		val listener = slideFrame ?: return
		// Use the same frozen slot geometry as the indicator; no time-driven motion while held.
		val frame = if (motionEnabled) TabSlideFrame.at(drag.slots.map { it.first to center(it.second) }, axis)
			else TabSlideFrame.single(hoveredId)
		frame?.let(listener)
	}

	private fun showPreview(id: Int?) {
		if (id == previewId) return
		previewId = id.takeIf { preview?.invoke(id) == true }
	}

	private fun setIndicator(rect: Rect) {
		val previous = indicatorBounds
		val wasIdle = previous == null
		if (motion != NavigationMotion.CLASSIC_GLIDE) {
			stretch = previous?.let { ((center(rect) - center(it)) / (18f * density)).coerceIn(-1f, 1f) } ?: 0f
		}
		indicatorBounds = rect
		if (wasIdle) onIndicatorChanged(true)
		invalidateIndicator()
	}

	private fun settleToSelection(targetId: Int = selectedId()) {
		if (indicatorBounds == null || settle != null) return
		if (!motionEnabled) { clearIndicator(); return }
		settleId = targetId
		progress = 0f
		settle = ValueAnimator.ofFloat(0f, 1f).apply {
			duration = when (motion) {
				NavigationMotion.CLASSIC_GLIDE -> 210L
				NavigationMotion.ELASTIC_GLIDE -> 310L
				NavigationMotion.FLUID_LENS -> 285L
			}
			interpolator = when (motion) {
				NavigationMotion.CLASSIC_GLIDE -> OvershootInterpolator(0.8f)
				NavigationMotion.ELASTIC_GLIDE -> OvershootInterpolator(1.15f)
				NavigationMotion.FLUID_LENS -> DecelerateInterpolator()
			}
			addUpdateListener {
				progress = it.animatedValue as Float
				invalidateIndicator()
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
		pressed = false
		stretch = 0f
		progress = 0f
		if (indicatorBounds == null) return
		indicatorBounds = null
		onIndicatorChanged(false)
		invalidateIndicator()
	}

	/**
	 * Classic keeps the original overlay exactly. Enhanced bottom-bar material is drawn behind
	 * Compose icons; only the part above/below the strip is drawn by the host. The rail has no
	 * Compose underlay, so its enhanced material stays translucent and inside native bounds.
	 */
	fun draw(canvas: Canvas) {
		val from = indicatorBounds ?: return
		if (motionEnabled && motion != NavigationMotion.CLASSIC_GLIDE) {
			val viewport = motionViewport
			if (viewport != null && !vertical) {
				host.getLocationInWindow(origin)
				val top = viewport.top - origin[1]
				val bottom = viewport.bottom - origin[1]
				for (range in 0..1) {
					val saved = canvas.save()
					if (range == 0) canvas.clipRect(0f, 0f, host.width.toFloat(), top.coerceAtLeast(0f))
					else canvas.clipRect(0f, bottom.coerceAtMost(host.height.toFloat()), host.width.toFloat(), host.height.toFloat())
					drawEnhanced(canvas, underlay = true)
					canvas.restoreToCount(saved)
				}
			} else {
				drawEnhanced(canvas, underlay = false)
			}
			return
		}
		val to = settleId?.let { id -> visibleSlots().firstOrNull { it.first == id }?.second } ?: from
		fun blend(a: Float, b: Float) = a + (b - a) * progress
		host.getLocationInWindow(origin)
		val x = blend(from.center.x, to.center.x) - origin[0]
		val y = blend(from.center.y, to.center.y) - origin[1]
		val halfWidth = (blend(from.width, to.width) * 0.5f - 3f * density).coerceIn(4f * density, 29f * density)
		val halfHeight = (blend(from.height, to.height) * 0.5f - 4f * density).coerceIn(4f * density, 24f * density)
		shape.set(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight)
		val radius = minOf(halfHeight, halfWidth, (if (vertical) 16f else 23f) * density)
		paint.shader = null
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

	/** Draw-only bridge for both Compose renderers, without changing their layout or hit areas. */
	fun drawInMotionViewport(canvas: Canvas) {
		val viewport = motionViewport ?: return
		if (vertical || !motionEnabled || motion == NavigationMotion.CLASSIC_GLIDE || indicatorBounds == null) return
		host.getLocationInWindow(origin)
		val saved = canvas.save()
		canvas.translate(origin[0] - viewport.left, origin[1] - viewport.top)
		drawEnhanced(canvas, underlay = true)
		canvas.restoreToCount(saved)
	}

	private fun drawEnhanced(canvas: Canvas, underlay: Boolean) {
		val from = indicatorBounds ?: return
		val to = settleId?.let { id -> visibleSlots().firstOrNull { it.first == id }?.second } ?: from
		fun blend(a: Float, b: Float) = a + (b - a) * progress
		host.getLocationInWindow(origin)
		val viewport = motionViewport
		val active = if (pressed) (1f - progress).coerceIn(0f, 1f) else 0f
		val geometry = EnhancedNavigationMotionGeometry.calculate(
			motion = motion,
			centerX = (blend(from.center.x, to.center.x) - origin[0]) / density,
			centerY = (blend(from.center.y, to.center.y) - origin[1]) / density,
			slotWidth = blend(from.width, to.width) / density,
			slotHeight = blend(from.height, to.height) / density,
			hostWidth = host.width / density,
			hostHeight = host.height / density,
			stripLeft = viewport?.let { (it.left - origin[0]) / density } ?: 0f,
			stripRight = viewport?.let { (it.right - origin[0]) / density } ?: host.width / density,
			stripHeight = viewport?.height?.div(density) ?: blend(from.height, to.height) / density,
			active = active,
			stretch = stretch,
			vertical = vertical,
		) ?: return
		shape.set(geometry.left * density, geometry.top * density, geometry.right * density, geometry.bottom * density)
		val radius = geometry.radius * density
		val tint = accent()
		val surface = surfaceColor()
		val light = ColorUtils.calculateLuminance(surface) >= 0.5
		val contrastBase = if (light) Color.WHITE else Color.BLACK
		val elastic = motion == NavigationMotion.ELASTIC_GLIDE
		paint.style = Paint.Style.FILL
		if (elastic) {
			// Saturated, near-solid shape beneath icons. Native rail overlay remains see-through.
			paint.shader = null
			// The approved pure-black identity uses white glyphs and a white motion accent.
			// Mixing only 38% black here produces a light gray fill beneath white content.
			val baseMix = when {
				surface == Color.BLACK && tint == Color.WHITE -> 0.78f
				light -> 0.55f
				else -> 0.38f
			}
			paint.color = ColorUtils.blendARGB(tint, contrastBase, baseMix)
			paint.alpha = (48 + (if (underlay) 198 else 92) * active).toInt()
		} else {
			// Single translucent lens, with a soft directional sheen, no backdrop blur or extra glow rings.
			val glass = ColorUtils.blendARGB(surface, tint, 0.18f)
			val top = ColorUtils.setAlphaComponent(ColorUtils.blendARGB(glass, if (light) Color.BLACK else Color.WHITE, 0.26f), 125)
			val bottom = ColorUtils.setAlphaComponent(glass, 52)
			if (lensShader == null || lensTop != top || lensBottom != bottom) {
				lensTop = top
				lensBottom = bottom
				lensShader = LinearGradient(0f, 0f, 0f, 1f, top, bottom, Shader.TileMode.CLAMP)
			}
			lensMatrix.setScale(1f, shape.height().coerceAtLeast(1f))
			lensMatrix.postTranslate(0f, shape.top)
			lensShader?.setLocalMatrix(lensMatrix)
			paint.shader = lensShader
			paint.alpha = (110 + 145 * active).toInt()
		}
		canvas.drawRoundRect(shape, radius, radius, paint)
		paint.shader = null
		paint.style = Paint.Style.STROKE
		paint.color = if (elastic) tint else ColorUtils.blendARGB(tint, if (light) Color.BLACK else Color.WHITE, 0.55f)
		paint.alpha = (120 + (if (elastic) 75 else 105) * active).toInt()
		paint.strokeWidth = (if (elastic) 1.3f else 1.7f) * density
		canvas.drawRoundRect(shape, radius, radius, paint)
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
