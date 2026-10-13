package org.koitharu.kotatsu.details.ui.adapter

import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.databinding.ItemChapterRangeBinding
import org.koitharu.kotatsu.details.ui.model.ChapterRange
import org.koitharu.kotatsu.list.ui.model.ListModel

internal fun chapterRangeAD(onToggle: (ChapterRange) -> Unit) =
	adapterDelegateViewBinding<ChapterRange, ListModel, ItemChapterRangeBinding>(
		viewBinding = { inflater, parent -> ItemChapterRangeBinding.inflate(inflater, parent, false) },
		on = { item, _, _ -> item is ChapterRange },
	) {
		binding.root.setOnClickListener { onToggle(item) }
		bind {
			val read = item.readCount
			binding.textViewRange.text = item.numberLabel ?: context.getString(R.string.chapter_grouping_special)
			binding.textViewProgress.text = context.getString(R.string.chapter_range_read_progress, read, item.totalCount)
			binding.imageViewExpand.rotation = if (item.expanded) 90f else 0f
			binding.imageViewComplete.isVisible = read == item.totalCount
			binding.progressRead.max = item.totalCount
			binding.progressRead.progress = read
			ViewCompat.setStateDescription(binding.root, context.getString(if (item.expanded) R.string.collapse else R.string.expand))
		}
	}
