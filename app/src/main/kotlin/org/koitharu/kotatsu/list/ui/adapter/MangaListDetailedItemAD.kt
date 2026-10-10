package org.koitharu.kotatsu.list.ui.adapter

import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.constraintlayout.widget.ConstraintLayout
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.list.AdapterDelegateClickListenerAdapter
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.util.ext.textAndVisible
import org.koitharu.kotatsu.databinding.ItemMangaListDetailsBinding
import org.koitharu.kotatsu.list.ui.ListModelDiffCallback
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.MangaDetailedListModel
import org.koitharu.kotatsu.list.ui.model.MangaListModel

fun mangaListDetailedItemAD(
	clickListener: MangaDetailsClickListener,
	titleClickListener: OnListItemClickListener<MangaListModel>? = null,
) = adapterDelegateViewBinding<MangaDetailedListModel, ListModel, ItemMangaListDetailsBinding>(
	{ inflater, parent -> ItemMangaListDetailsBinding.inflate(inflater, parent, false) },
) {

	AdapterDelegateClickListenerAdapter(this, clickListener)
		.attach(itemView)
	if (titleClickListener != null) {
		binding.textViewTitle.attachTitleClickToRead(itemView) { view ->
			titleClickListener.onItemClick(item, view)
		}
	}

	bind { payloads ->
		binding.textViewTitle.text = item.title
		val secondary = buildList {
			item.manga.authors.joinToString(", ").takeIf { it.isNotBlank() }?.let(::add)
			item.languageLabel?.let(::add)
			if (item.isLocalSource) add(context.getString(R.string.local_storage))
		}
		binding.textViewAuthor.textAndVisible = secondary.joinToString(" • ")
		binding.progressView.setProgress(
			value = item.progress,
			animate = ListModelDiffCallback.PAYLOAD_PROGRESS_CHANGED in payloads,
		)
		binding.iconsView.bind(item.isSaved, item.isLocalSource, item.isFavorite)
		binding.iconsView.updateLayoutParams<ConstraintLayout.LayoutParams> {
			horizontalBias = if (item.isSaved || item.isFavorite) 1f else 0f
			topMargin = if (item.isSaved || item.isFavorite) 0 else (10f * context.resources.displayMetrics.density).toInt()
			marginEnd = if (item.isSaved || item.isFavorite) context.resources.getDimensionPixelSize(R.dimen.card_indicator_offset) else 0
		}
		binding.imageViewPin.updateLayoutParams<ConstraintLayout.LayoutParams> {
			topMargin = context.resources.getDimensionPixelSize(R.dimen.card_indicator_offset) +
				if (item.isSaved || item.isFavorite) {
					context.resources.getDimensionPixelSize(R.dimen.manga_status_ribbon_height) +
						context.resources.getDimensionPixelSize(R.dimen.library_indicator_spacing)
				} else 0
		}
		binding.imageViewPin.isVisible = item.isPinned
		binding.imageViewCover.setImageAsync(item.coverUrl, item.manga)
		binding.textViewTags.text = item.tags.joinToString(separator = ", ") { it.title ?: "" }
		binding.badge.number = item.counter
		binding.badge.isVisible = item.counter > 0
		binding.imageViewContinue.isVisible = item.showContinueReading
		if (item.showContinueReading) {
			binding.imageViewContinue.setOnClickListener { view ->
				clickListener.onReadClick(item.toMangaWithOverride(), view)
			}
		} else {
			binding.imageViewContinue.setOnClickListener(null)
		}
	}
}
