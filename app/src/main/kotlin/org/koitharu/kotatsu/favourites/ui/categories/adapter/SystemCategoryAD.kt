package org.koitharu.kotatsu.favourites.ui.categories.adapter

import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.setTooltipCompat
import org.koitharu.kotatsu.databinding.ItemCategoryBinding
import org.koitharu.kotatsu.favourites.ui.categories.FavouriteCategoriesListListener
import org.koitharu.kotatsu.list.ui.model.ListModel

fun systemCategoryAD(
	clickListener: FavouriteCategoriesListListener,
) = adapterDelegateViewBinding<SystemCategoryListModel, ListModel, ItemCategoryBinding>(
	{ inflater, parent -> ItemCategoryBinding.inflate(inflater, parent, false) },
) {
	binding.imageViewEdit.setOnClickListener {
		clickListener.onSystemCategoryVisibilityClick(item.id, !item.isVisible)
	}

	bind {
		binding.coversView.isGone = true
		binding.imageViewHandle.isGone = true
		binding.imageViewTracker.isGone = true
		binding.imageViewDownload.isGone = true
		binding.imageViewHidden.isGone = true
		binding.imageViewEdit.isVisible = item.isActionsEnabled
		binding.imageViewEdit.setImageResource(if (item.isVisible) R.drawable.ic_eye else R.drawable.ic_eye_off)
		binding.imageViewEdit.contentDescription = getString(if (item.isVisible) R.string.hide else R.string.show)
		binding.imageViewEdit.setTooltipCompat(if (item.isVisible) R.string.hide else R.string.show)
		binding.textViewTitle.text = item.title
		binding.textViewSubtitle.setText(
			if (item.isVisible) R.string.favourites_category_visibility_shown
			else R.string.favourites_category_visibility_hidden,
		)
		itemView.alpha = if (item.isVisible) 1f else 0.62f
	}
}
