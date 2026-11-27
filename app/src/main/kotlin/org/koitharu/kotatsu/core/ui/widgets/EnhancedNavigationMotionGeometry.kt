package org.koitharu.kotatsu.core.ui.widgets

import org.koitharu.kotatsu.core.prefs.NavigationMotion
import kotlin.math.abs

/** Pure drawing geometry in dp. The hit rectangles and selection owner are deliberately untouched. */
internal object EnhancedNavigationMotionGeometry {
	data class Shape(val left: Float, val top: Float, val right: Float, val bottom: Float, val radius: Float)

	// Position-based resistance survives stationary holds beyond the end of the strip.
	fun edgePull(axis: Float, first: Float, last: Float): Float {
		val excess = axis - axis.coerceIn(first, last)
		return excess / (abs(excess) + 32f)
	}

	fun calculate(
		motion: NavigationMotion,
		centerX: Float,
		centerY: Float,
		slotWidth: Float,
		slotHeight: Float,
		hostWidth: Float,
		hostHeight: Float,
		stripLeft: Float,
		stripRight: Float,
		stripHeight: Float,
		active: Float,
		stretch: Float,
		vertical: Boolean,
		edgePull: Float = 0f,
	): Shape? {
		if (hostWidth <= 4f || hostHeight <= 4f || motion == NavigationMotion.CLASSIC_GLIDE) return null
		val amount = active.coerceIn(0f, 1f)
		val velocity = stretch.coerceIn(-1f, 1f)
		val elastic = motion == NavigationMotion.ELASTIC_GLIDE
		val baseW = (slotWidth - 6f).coerceIn(8f, 58f)
		val baseH = (slotHeight - 8f).coerceIn(8f, 48f)
		// Allow bounded draw-only overflow, preserving the gap to Continue Reading and host stroke clearance.
		val overflow = if (vertical) -2f else -2f + 6f * amount
		val leftLimit = (stripLeft - overflow).coerceIn(2f, hostWidth - 2f)
		val rightLimit = (stripRight + overflow).coerceIn(leftLimit, hostWidth - 2f)
		if (rightLimit - leftLimit < 4f) return null
		// An obvious expanded silhouette, sized from the tab and the actual strip.
		// Rail retains a bounded native-width highlight; no draw extension into neighboring content.
		val activeW = if (vertical) minOf(slotWidth, if (elastic) 68f else 76f)
			else if (elastic) (slotWidth * 1.28f + abs(velocity) * 14f).coerceIn(64f, 112f) +
				abs(edgePull.coerceIn(-1f, 1f)) * 28f
			else (slotWidth * 1.42f).coerceIn(78f, 124f)
		val activeH = if (vertical) minOf(slotHeight, if (elastic) 64f else 72f)
			// The lens spans both strip edges; keep a slight lift within existing host margins.
			else stripHeight + if (elastic) 8f else 10f
		val width = (baseW + (activeW - baseW) * amount).coerceIn(4f, rightLimit - leftLimit)
		val height = (baseH + (activeH - baseH) * amount).coerceIn(4f, hostHeight - 4f)
		val halfW = width * 0.5f
		val halfH = height * 0.5f
		val x = (centerX - if (!vertical && elastic) velocity * 5f * amount else 0f)
			.coerceIn(leftLimit + halfW, (rightLimit - halfW).coerceAtLeast(leftLimit + halfW))
		val y = (centerY - if (!vertical && !elastic) 2f * amount else 0f)
			.coerceIn(2f + halfH, (hostHeight - 2f - halfH).coerceAtLeast(2f + halfH))
		val targetRadius = if (elastic) minOf(halfW, halfH) else minOf(halfW, halfH, 24f)
		val radius = (23f + (targetRadius - 23f) * amount).coerceIn(0f, minOf(halfW, halfH))
		return Shape(x - halfW, y - halfH, x + halfW, y + halfH, radius)
	}
}
