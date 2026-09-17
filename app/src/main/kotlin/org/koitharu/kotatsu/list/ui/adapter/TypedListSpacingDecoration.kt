package org.koitharu.kotatsu.list.ui.adapter

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewParent
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.ItemDecoration
import androidx.viewpager2.widget.ViewPager2
import org.koitharu.kotatsu.R

class TypedListSpacingDecoration(
	context: Context,
	private val addHorizontalPadding: Boolean,
) : ItemDecoration() {

	private val spacingSmall = context.resources.getDimensionPixelOffset(R.dimen.list_spacing_small)
	private val spacingNormal =
		context.resources.getDimensionPixelOffset(R.dimen.list_spacing_normal)
	private val pagerBottomClearance = (PAGER_BOTTOM_CLEARANCE_DP * context.resources.displayMetrics.density).toInt()

	override fun getItemOffsets(
		outRect: Rect,
		view: View,
		parent: RecyclerView,
		state: RecyclerView.State,
	) {
		val itemType = parent.getChildViewHolder(view)?.itemViewType?.let {
			ListItemType.entries.getOrNull(it)
		}
		when (itemType) {
			ListItemType.FILTER_SORT,
			ListItemType.FILTER_TAG,
			ListItemType.FILTER_TAG_MULTI,
			ListItemType.FILTER_STATE,
			ListItemType.FILTER_LANGUAGE,
			ListItemType.QUICK_FILTER,
			ListItemType.MIHON_SORT_OPTION,
			ListItemType.MIHON_SORT_SECTION,
				-> outRect.set(0)

			ListItemType.HEADER,
			ListItemType.FEED,
			ListItemType.EXPLORE_SOURCE_LIST,
			ListItemType.MANGA_SCROBBLING,
			ListItemType.MANGA_LIST,
			ListItemType.LIBRARY_GROUP,
				-> outRect.set(0)

			ListItemType.DOWNLOAD,
			ListItemType.HINT_EMPTY,
			ListItemType.MANGA_LIST_DETAILED,
				-> outRect.set(spacingNormal)

			ListItemType.PAGE_THUMB -> outRect.set(spacingNormal)
			ListItemType.MANGA_GRID,
			ListItemType.LIBRARY_GROUP_GRID,
				-> outRect.set(0)
			// Carousel items carry their own margins in the item layout.
			ListItemType.MANGA_CAROUSEL -> outRect.set(0)

			ListItemType.EXPLORE_BUTTONS -> outRect.set(spacingNormal)

			ListItemType.FOOTER_LOADING,
			ListItemType.FOOTER_ERROR,
			ListItemType.FOOTER_BUTTON,
			ListItemType.STATE_LOADING,
			ListItemType.STATE_ERROR,
			ListItemType.STATE_EMPTY,
			ListItemType.EXPLORE_SOURCE_GRID,
			ListItemType.EXPLORE_SUGGESTION,
			ListItemType.MANGA_NESTED_GROUP,
			ListItemType.CATEGORY_LARGE,
			ListItemType.NAV_ITEM,
			ListItemType.CHAPTER_LIST,
			ListItemType.MISSING_CHAPTERS,
			ListItemType.INFO,
			null,
				-> outRect.set(0)

			ListItemType.CHAPTER_GRID -> outRect.set(spacingSmall)

			ListItemType.TIP -> outRect.set(0) // TODO
		}
		if (addHorizontalPadding && !itemType.isEdgeToEdge()) {
			outRect.set(
				outRect.left + spacingNormal,
				outRect.top,
				outRect.right + spacingNormal,
				outRect.bottom,
			)
		}

		// Pager-backed lists can share their bottom edge with navigation/sheet surfaces. For grids, add
		// clearance to every item in the final visual row rather than only the final adapter item. This is
		// important for variable-height page thumbnails: otherwise a taller sibling can define the row
		// height and absorb the final item's clearance, leaving the bottom of that thumbnail clipped.
		val position = parent.getChildAdapterPosition(view)
		if (
			position != RecyclerView.NO_POSITION &&
			parent.isInsideViewPager2() &&
			parent.isInLastVisualRow(position, state.itemCount)
		) {
			outRect.bottom += pagerBottomClearance
		}
	}

	private fun RecyclerView.isInsideViewPager2(): Boolean {
		var ancestor: ViewParent? = parent
		while (ancestor != null) {
			if (ancestor is ViewPager2) return true
			ancestor = ancestor.parent
		}
		return false
	}

	private fun RecyclerView.isInLastVisualRow(position: Int, stateItemCount: Int): Boolean {
		// During RecyclerView pre-layout, State can still expose positions from the previous list while
		// AsyncListDiffer has already committed a shorter current list. Never ask SpanSizeLookup to resolve
		// one of those stale positions: adapter delegates index directly into the current items snapshot.
		val itemCount = minOf(stateItemCount, adapter?.itemCount ?: stateItemCount)
		if (itemCount <= 0 || position !in 0 until itemCount) return false
		val gridLayoutManager = layoutManager as? GridLayoutManager ?: return position == itemCount - 1
		val spanCount = gridLayoutManager.spanCount
		if (spanCount <= 0) return position == itemCount - 1
		val spanSizeLookup = gridLayoutManager.spanSizeLookup
		return spanSizeLookup.getSpanGroupIndex(position, spanCount) ==
			spanSizeLookup.getSpanGroupIndex(itemCount - 1, spanCount)
	}

	private fun Rect.set(spacing: Int) = set(spacing, spacing, spacing, spacing)

	private fun ListItemType?.isEdgeToEdge() = this == ListItemType.MANGA_NESTED_GROUP
		|| this == ListItemType.FILTER_SORT
		|| this == ListItemType.FILTER_TAG
		|| this == ListItemType.CHAPTER_LIST
		|| this == ListItemType.CHAPTER_GRID
		|| this == ListItemType.MISSING_CHAPTERS

	private companion object {
		const val PAGER_BOTTOM_CLEARANCE_DP = 64f
	}
}
