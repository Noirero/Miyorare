package org.koitharu.kotatsu.list.ui

import android.content.res.Resources
import android.view.View
import androidx.core.view.ancestors
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import org.koitharu.kotatsu.R
import kotlin.math.abs
import kotlin.math.roundToInt

class GridSpanResolver(
	resources: Resources,
) : View.OnLayoutChangeListener {

	var spanCount = 3
		private set

	private val gridWidth = resources.getDimension(R.dimen.preferred_grid_width)
	private val spacing = resources.getDimension(R.dimen.grid_spacing)
	private var cellWidth = -1f
	private var fixedSpanCount: Int? = null

	override fun onLayoutChange(
		v: View?,
		left: Int,
		top: Int,
		right: Int,
		bottom: Int,
		oldLeft: Int,
		oldTop: Int,
		oldRight: Int,
		oldBottom: Int,
	) {
		val rv = v as? RecyclerView ?: return
		fixedSpanCount?.let { fixed ->
			spanCount = fixed
			(rv.layoutManager as? GridLayoutManager)?.spanCount = fixed
			return
		}
		if (cellWidth <= 0f) {
			return
		}
		val width = abs(right - left)
		// ViewPager2 can lay out a page at an intermediate width that is still larger
		// than one cell. Keep the previous span count until the full pager width arrives;
		// FitHeightGridLayoutManager already skips drawing the same transient pass.
		if (!isUsableWidth(rv, width)) return
		resolveGridSpanCount(width)
		(rv.layoutManager as? GridLayoutManager)?.spanCount = spanCount
	}

	fun setGridSize(scaleFactor: Float, rv: RecyclerView) {
		cellWidth = (gridWidth * scaleFactor) + spacing
		val lm = rv.layoutManager as? GridLayoutManager ?: return
		fixedSpanCount?.let { fixed ->
			spanCount = fixed
			lm.spanCount = fixed
			return
		}
		val innerWidth = lm.width - lm.paddingEnd - lm.paddingStart
		if (isUsableWidth(rv, lm.width) && innerWidth >= cellWidth) {
			resolveGridSpanCount(innerWidth)
			lm.spanCount = spanCount
		}
	}

	/**
	 * A non-null value pins the grid to an exact number of items per row. Null restores the original
	 * automatic width-based behaviour used by every non-Favourites list.
	 */
	fun setFixedSpanCount(count: Int?, rv: RecyclerView) {
		fixedSpanCount = count?.coerceAtLeast(2)
		val lm = rv.layoutManager as? GridLayoutManager
		if (fixedSpanCount != null) {
			spanCount = checkNotNull(fixedSpanCount)
			lm?.spanCount = spanCount
		} else if (lm != null) {
			val innerWidth = lm.width - lm.paddingEnd - lm.paddingStart
			if (cellWidth > 0f && isUsableWidth(rv, lm.width) && innerWidth >= cellWidth) {
				resolveGridSpanCount(innerWidth)
				lm.spanCount = spanCount
			}
		}
	}

	private fun isUsableWidth(rv: RecyclerView, width: Int): Boolean {
		val pagerWidth = rv.ancestors.filterIsInstance<ViewPager2>().firstOrNull()?.measuredWidth ?: 0
		return isUsableGridWidth(width, pagerWidth, cellWidth)
	}

	private fun resolveGridSpanCount(width: Int) {
		val estimatedCount = (width / cellWidth).roundToInt()
		spanCount = estimatedCount.coerceAtLeast(2)
	}
}

/** The grid must not adopt an intermediate ViewPager2 width, even if it fits multiple cells. */
internal fun isUsableGridWidth(width: Int, pagerWidth: Int, cellWidth: Float): Boolean =
	width >= cellWidth && (pagerWidth <= 0 || width >= pagerWidth)
