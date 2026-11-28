package org.koitharu.kotatsu.core.ui.widgets

import android.graphics.Rect
import android.os.Build
import android.view.View
import org.koitharu.kotatsu.core.prefs.ScreenTransition
import kotlin.math.roundToInt

/** Original properties belong to the page, not to the transient presentation. */
internal class ScreenTransitionRoot(val view: View) {
	val x = view.translationX
	val visibility = view.visibility
	private val y = view.translationY
	private val z = view.translationZ
	private val alpha = view.alpha
	private val scaleX = view.scaleX
	private val scaleY = view.scaleY
	private val rotation = view.rotationY
	private val camera = view.cameraDistance
	private val pivotX = view.pivotX
	private val pivotY = view.pivotY
	private val explicitPivot = Build.VERSION.SDK_INT < 28 || view.isPivotSet
	private val clip = view.clipBounds?.let(::Rect)
	private val accessibility = view.importantForAccessibility
	private var pivotChanged = false
	private var xChanged = false
	private var yChanged = false
	private var alphaChanged = false
	private var scaleChanged = false
	private var rotationChanged = false
	private var zChanged = false
	private var clipChanged = false
	private var accessibilityChanged = false

	fun translate(dx: Float? = null, dy: Float? = null) {
		if (dx != null) {
			xChanged = true
			view.translationX = x + dx
		}
		if (dy != null) {
			yChanged = true
			view.translationY = y + dy
		}
	}

	fun opacity(value: Float) {
		alphaChanged = true
		view.alpha = alpha * value
	}
	fun scale(value: Float) {
		scaleChanged = true
		view.scaleX = scaleX * value
		view.scaleY = scaleY * value
	}

	fun rotate(angle: Float, distance: Float, pivot: Float? = null, centerY: Float = 0f) {
		rotationChanged = true
		view.rotationY = rotation + angle
		view.cameraDistance = distance
		if (pivot != null) {
			pivotChanged = true
			view.pivotX = pivot
			view.pivotY = centerY
		}
	}

	fun above(other: ScreenTransitionRoot) {
		zChanged = true
		view.translationZ = maxOf(z, other.view.z - view.elevation + 1f)
	}

	fun reveal(fraction: Float, width: Float, height: Float) {
		val w = view.width.takeIf { it > 0 } ?: width.roundToInt()
		val h = view.height.takeIf { it > 0 } ?: height.roundToInt()
		val left = (w * (1f - fraction) / 2f).roundToInt()
		val bounds = Rect(left, 0, w - left, h)
		if (clip != null && !bounds.intersect(clip)) bounds.setEmpty()
		clipChanged = true
		view.clipBounds = bounds
	}

	fun accessibilityHidden(hidden: Boolean) {
		accessibilityChanged = true
		view.importantForAccessibility = if (hidden) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else accessibility
	}

	fun restore(visible: Int = visibility) {
		if (xChanged) view.translationX = x
		if (yChanged) view.translationY = y
		if (zChanged) view.translationZ = z
		if (alphaChanged) view.alpha = alpha
		if (scaleChanged) {
			view.scaleX = scaleX
			view.scaleY = scaleY
		}
		if (rotationChanged) {
			view.rotationY = rotation
			view.cameraDistance = camera
		}
		xChanged = false
		yChanged = false
		zChanged = false
		alphaChanged = false
		scaleChanged = false
		rotationChanged = false
		if (pivotChanged) {
			if (Build.VERSION.SDK_INT >= 28 && !explicitPivot) view.resetPivot()
			else {
				view.pivotX = pivotX
				view.pivotY = pivotY
			}
			pivotChanged = false
		}
		if (clipChanged) view.clipBounds = clip
		if (accessibilityChanged) view.importantForAccessibility = accessibility
		clipChanged = false
		accessibilityChanged = false
		view.visibility = visible
	}
}

/** A physical left/right pair, independent of route and gesture direction.
 * Reversing the fraction reverses the exact same picture, including asymmetric effects.
 */
internal object ScreenTransitionTransformer {
	fun render(effect: ScreenTransition, left: ScreenTransitionRoot, right: ScreenTransitionRoot,
		fraction: Float, width: Float, height: Float) {
		val p = fraction.coerceIn(0f, 1f)
		val q = 1f - p
		left.view.visibility = if (p == 1f) View.INVISIBLE else View.VISIBLE
		right.view.visibility = if (p == 0f) View.INVISIBLE else View.VISIBLE
		when (effect) {
			ScreenTransition.DIRECTIONAL_SLIDE -> {
				left.translate(-width * p)
				right.translate(width * q)
			}
			ScreenTransition.FLIP_3D -> {
				val camera = maxOf(width, height) * 4f
				// On API 28+ the implicit pivot can be restored exactly. Older
				// releases retain the page's native (normally centered) pivot.
				val center = if (Build.VERSION.SDK_INT >= 28) width / 2f else null
				left.rotate(-180f * p, camera, center, height / 2f)
				right.rotate(180f * q, camera, center, height / 2f)
				// Never expose mirrored back faces, including on a reversed gesture.
				left.view.visibility = if (p < 0.5f) View.VISIBLE else View.INVISIBLE
				right.view.visibility = if (p >= 0.5f) View.VISIBLE else View.INVISIBLE
			}
			ScreenTransition.CUBE_3D -> {
				val camera = maxOf(width, height) * 4f
				left.translate(-width * p)
				right.translate(width * q)
				left.rotate(-90f * p, camera, width, height / 2f)
				right.rotate(90f * q, camera, 0f, height / 2f)
			}
			ScreenTransition.CROSSFADE -> {
				left.opacity(q)
				right.opacity(p)
				right.above(left)
			}
			ScreenTransition.ZOOM -> {
				left.scale(1f - 0.24f * p)
				right.scale(0.76f + 0.24f * p)
				left.opacity(q)
				right.opacity(p)
				right.above(left)
			}
			ScreenTransition.CARD_STACK -> {
				left.scale(1f - 0.12f * p)
				left.opacity(q)
				right.translate(width * q)
				right.above(left)
			}
			ScreenTransition.VERTICAL_SLIDE -> {
				left.translate(dy = -height * p)
				right.translate(dy = height * q)
			}
			ScreenTransition.CURTAIN_REVEAL -> {
				// Live center-out clipping, not a snapshot. Fade the old transparent
				// content underneath to avoid leaving it visible through the new page.
				left.opacity(q)
				right.reveal(p, width, height)
				right.above(left)
			}
		}
	}
}
