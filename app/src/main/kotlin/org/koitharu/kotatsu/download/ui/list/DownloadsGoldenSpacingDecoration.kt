package org.koitharu.kotatsu.download.ui.list

import android.content.Context
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.list.ui.adapter.ListItemType
import kotlin.math.roundToInt

/**
 * Downloads' golden reference uses the screen's 16dp content inset as the card edge.
 * Do not add TypedListSpacingDecoration's extra 8dp horizontal inset on top of it.
 */
class DownloadsGoldenSpacingDecoration(context: Context) : RecyclerView.ItemDecoration() {

	private val cardVertical = (5f * context.resources.displayMetrics.density).roundToInt()

	override fun getItemOffsets(
		outRect: Rect,
		view: View,
		parent: RecyclerView,
		state: RecyclerView.State,
	) {
		val type = parent.getChildViewHolder(view)?.itemViewType?.let(ListItemType.entries::getOrNull)
		if (type == ListItemType.DOWNLOAD) {
			outRect.set(0, cardVertical, 0, cardVertical)
		} else {
			outRect.set(0, 0, 0, 0)
		}
	}
}
