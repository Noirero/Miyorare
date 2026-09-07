package org.koitharu.kotatsu.favourites.groups.ui

import android.view.View
import androidx.core.view.isVisible
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.textAndVisible
import org.koitharu.kotatsu.databinding.ItemMangaListBinding
import org.koitharu.kotatsu.list.ui.model.ListModel

fun libraryGroupAD(
	onClick: (LibraryGroupListModel, View) -> Unit,
) = adapterDelegateViewBinding<LibraryGroupListModel, ListModel, ItemMangaListBinding>(
	{ inflater, parent -> ItemMangaListBinding.inflate(inflater, parent, false) },
) {
	itemView.setOnClickListener { view -> onClick(item, view) }
	bind {
		binding.textViewTitle.text = item.title
		binding.textViewSubtitle.textAndVisible = context.resources.getQuantityString(
			R.plurals.library_group_members,
			item.memberCount,
			item.memberCount,
		)
		if (item.group.coverUrl != null) {
			binding.imageViewCover.setImageAsync(item.coverUrl, manga = null)
		} else {
			binding.imageViewCover.setImageAsync(item.coverUrl, item.fallbackCoverSource)
		}
		binding.imageViewPin.isVisible = false
		binding.badge.isVisible = false
		binding.imageViewContinue.isVisible = false
		binding.imageViewContinue.setOnClickListener(null)
	}
}
