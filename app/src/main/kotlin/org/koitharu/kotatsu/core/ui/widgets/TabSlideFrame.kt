package org.koitharu.kotatsu.core.ui.widgets

import kotlin.math.abs

/** Adjacent physical slots and the continuous page coordinate between their centers. */
internal data class TabSlideFrame(val leftId: Int, val rightId: Int, val fraction: Float) {
	fun contains(id: Int) = id == leftId || id == rightId
	fun hasOtherThan(id: Int) = leftId != id || rightId != id

	companion object {
		fun single(id: Int) = TabSlideFrame(id, id, 0f)

		fun at(centers: List<Pair<Int, Float>>, axis: Float): TabSlideFrame? {
			val slots = centers.sortedBy { it.second }
			val first = slots.firstOrNull() ?: return null
			val last = slots.last()
			if (axis <= first.second) return single(first.first)
			if (axis >= last.second) return single(last.first)
			// At a slot center only that page is needed. Avoid loading its neighbor at rest.
			slots.firstOrNull { abs(axis - it.second) <= 0.5f }?.let { return single(it.first) }
			for (index in 0 until slots.lastIndex) {
				val left = slots[index]
				val right = slots[index + 1]
				if (axis < right.second && right.second > left.second) {
					return TabSlideFrame(left.first, right.first,
						((axis - left.second) / (right.second - left.second)).coerceIn(0f, 1f))
				}
			}
			return single(last.first)
		}
	}
}
