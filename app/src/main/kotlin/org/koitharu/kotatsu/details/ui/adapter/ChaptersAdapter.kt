package org.koitharu.kotatsu.details.ui.adapter

import android.content.Context
import org.koitharu.kotatsu.core.ui.BaseListAdapter
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.ui.list.fastscroll.FastScroller
import org.koitharu.kotatsu.details.ui.model.ChapterListItem
import org.koitharu.kotatsu.details.ui.model.ChapterRange
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.list.ui.adapter.ListItemType
import org.koitharu.kotatsu.list.ui.adapter.listHeaderAD
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.list.ui.model.ListModel

class ChaptersAdapter(
	onItemClickListener: OnListItemClickListener<ChapterListItem>,
	onDownloadClick: (ChapterListItem) -> Unit,
	onDeleteClick: (ChapterListItem) -> Unit,
	accentColorProvider: () -> Int? = { null },
	onRangeToggle: (ChapterRange) -> Unit = {},
) : BaseListAdapter<ListModel>(), FastScroller.SectionIndexer {

	private var hasVolumes = false

	init {
		delegatesManager.addDelegate(R.layout.item_chapter_range, chapterRangeAD(onRangeToggle))
		addDelegate(ListItemType.HEADER, listHeaderAD(null))
		addDelegate(
			ListItemType.CHAPTER_LIST,
			chapterListItemAD(onItemClickListener, onDownloadClick, onDeleteClick, accentColorProvider),
		)
		addDelegate(ListItemType.CHAPTER_GRID, chapterGridItemAD(onItemClickListener, accentColorProvider))
		addDelegate(ListItemType.MISSING_CHAPTERS, missingChaptersAD())
	}

	override suspend fun emit(value: List<ListModel>?) {
		super.emit(value)
		hasVolumes = value != null && value.any { it is ListHeader }
	}

	override fun getSectionText(context: Context, position: Int): CharSequence? {
		(items.getOrNull(position) as? ChapterRange)?.let {
			return it.numberLabel ?: context.getString(R.string.chapter_grouping_special)
		}
		return if (hasVolumes) {
			findHeader(position)?.getText(context)
		} else {
			val chapter = (items.getOrNull(position) as? ChapterListItem)?.chapter ?: return null
			chapter.numberString()
		}
	}
}
