package org.koitharu.kotatsu.history.ui

import android.content.Context
import org.koitharu.kotatsu.core.ui.list.fastscroll.FastScroller
import org.koitharu.kotatsu.list.ui.adapter.MangaListAdapter
import org.koitharu.kotatsu.list.ui.adapter.ListItemType
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.list.ui.adapter.MangaListListener
import org.koitharu.kotatsu.list.ui.model.TipModel
import org.koitharu.kotatsu.list.ui.model.ModernCoverContext
import org.koitharu.kotatsu.list.ui.size.ItemSizeResolver

class HistoryListAdapter(
	listener: MangaListListener,
	sizeResolver: ItemSizeResolver,
	titleTapToRead: Boolean = false,
	onTipClose: ((TipModel) -> Unit)? = null,
	onAccordionClick: (String, Boolean) -> Unit,
) : MangaListAdapter(listener, sizeResolver, titleTapToRead, onTipClose, coverContext = ModernCoverContext.BROWSING,
	headerRowClickable = true), FastScroller.SectionIndexer {

	init {
		addDelegate(ListItemType.HISTORY_ACCORDION, historyAccordionItemAD { action -> onAccordionClick(action.key, action.loadMore) })
	}

	override fun getSectionText(context: Context, position: Int): CharSequence? {
		for (i in position.coerceAtMost(items.lastIndex) downTo 0) {
			when (val row = items[i]) {
				is HistoryAccordionItem -> if (row.kind != HistoryAccordionItem.Kind.MORE) return row.title(context)
				is ListHeader -> return row.getText(context)
			}
		}
		return null
	}
}
