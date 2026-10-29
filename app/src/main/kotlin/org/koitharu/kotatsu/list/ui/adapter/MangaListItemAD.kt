package org.koitharu.kotatsu.list.ui.adapter

import androidx.core.view.isVisible
import androidx.core.content.ContextCompat
import android.view.ViewGroup
import androidx.core.view.updateLayoutParams
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.list.AdapterDelegateClickListenerAdapter
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.util.ext.setTooltipCompat
import org.koitharu.kotatsu.core.util.ext.textAndVisible
import org.koitharu.kotatsu.databinding.ItemMangaListBinding
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.MangaCompactListModel
import org.koitharu.kotatsu.list.ui.model.MangaListModel
import org.koitharu.kotatsu.list.ui.model.ModernCoverContext

fun mangaListItemAD(
	clickListener: MangaDetailsClickListener,
	titleClickListener: OnListItemClickListener<MangaListModel>? = null,
	coverContext: ModernCoverContext = ModernCoverContext.LEGACY,
	localCinematic: Boolean = false,
) = adapterDelegateViewBinding<MangaCompactListModel, ListModel, ItemMangaListBinding>(
	{ inflater, parent -> ItemMangaListBinding.inflate(inflater, parent, false) },
) {

	AdapterDelegateClickListenerAdapter(this, clickListener).attach(itemView)
	if (titleClickListener != null) {
		binding.textViewTitle.attachTitleClickToRead(itemView) { view ->
			titleClickListener.onItemClick(item, view)
		}
	}

	bind {
		if (localCinematic) {
			itemView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
				val density = context.resources.displayMetrics.density
				val horizontal = (10f * density).toInt()
				val vertical = (5f * density).toInt()
				setMargins(horizontal, vertical, horizontal, vertical)
			}
			itemView.background = ContextCompat.getDrawable(context, R.drawable.bg_smart_local_cinematic_selectable)
			binding.imageViewCover.shapeAppearanceModel = binding.imageViewCover.shapeAppearanceModel.toBuilder()
				.setAllCornerSizes(12f * context.resources.displayMetrics.density).build()
			binding.imageViewCover.strokeColor = android.content.res.ColorStateList.valueOf(
				ContextCompat.getColor(context, R.color.smart_local_edge))
			binding.imageViewCover.strokeWidth = context.resources.displayMetrics.density
			binding.textViewTitle.setTextColor(ContextCompat.getColor(context, R.color.smart_local_cream))
			binding.textViewSubtitle.setTextColor(ContextCompat.getColor(context, R.color.smart_local_muted))
		}
		itemView.setTooltipCompat(item.getSummary(context))
		binding.textViewTitle.text = item.title
		val info = buildList {
			if (item.isFavorite) add(context.getString(R.string.in_library))
			item.subtitle.takeIf { it.isNotBlank() }?.let(::add)
			item.languageLabel?.let(::add)
			if (item.isLocalSource) add(context.getString(R.string.local_storage))
			if (item.isSaved) add(context.getString(R.string.favourites_show_downloaded))
		}
		binding.textViewSubtitle.textAndVisible = info.joinToString(" • ")
		binding.imageViewPin.isVisible = item.isPinned
		binding.imageViewCover.setImageAsync(
			item.coverUrl,
			item.manga,
			persistCover = coverContext == ModernCoverContext.COLLECTION,
		)
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
