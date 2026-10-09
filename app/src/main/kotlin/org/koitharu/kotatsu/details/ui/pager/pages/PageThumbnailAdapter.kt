package org.koitharu.kotatsu.details.ui.pager.pages

import android.content.Context
import org.koitharu.kotatsu.core.ui.BaseListAdapter
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.ui.list.fastscroll.FastScroller
import org.koitharu.kotatsu.list.ui.adapter.ListItemType
import org.koitharu.kotatsu.list.ui.adapter.listHeaderAD
import org.koitharu.kotatsu.list.ui.model.ListModel

class PageThumbnailAdapter(
	clickListener: OnListItemClickListener<PageThumbnail>,
	headerListener: org.koitharu.kotatsu.list.ui.adapter.ListHeaderClickListener? = null,
	stateListener: org.koitharu.kotatsu.list.ui.adapter.ListStateHolderListener? = null,
) : BaseListAdapter<ListModel>(), FastScroller.SectionIndexer {

	init {
		addDelegate(ListItemType.PAGE_THUMB, pageThumbnailAD(clickListener))
		addDelegate(ListItemType.HEADER, listHeaderAD(headerListener))
		addDelegate(ListItemType.FOOTER_LOADING, org.koitharu.kotatsu.list.ui.adapter.loadingFooterAD())
		addDelegate(ListItemType.FOOTER_ERROR, org.koitharu.kotatsu.list.ui.adapter.errorFooterAD(stateListener))
	}

	override fun getSectionText(context: Context, position: Int): CharSequence? {
		return findHeader(position)?.getText(context)
	}
}
