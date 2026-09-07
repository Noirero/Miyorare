package org.koitharu.kotatsu.favourites.groups.ui

import android.content.Intent
import android.view.View
import androidx.core.view.isVisible
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.textAndVisible
import org.koitharu.kotatsu.databinding.ItemMangaListBinding
import org.koitharu.kotatsu.favourites.ui.FavouritesActivity
import org.koitharu.kotatsu.list.ui.model.ListModel

fun libraryGroupAD(
	onManage: (LibraryGroupListModel, View) -> Unit,
) = adapterDelegateViewBinding<LibraryGroupListModel, ListModel, ItemMangaListBinding>(
	{ inflater, parent -> ItemMangaListBinding.inflate(inflater, parent, false) },
) {
	itemView.setOnClickListener { view ->
		view.context.startActivity(
			Intent(view.context, FavouritesActivity::class.java)
				.putExtra(FavouritesActivity.EXTRA_LIBRARY_GROUP_ID, item.group.id),
		)
	}
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
		binding.imageViewContinue.isVisible = true
		binding.imageViewContinue.setImageResource(R.drawable.ic_more_vert)
		binding.imageViewContinue.contentDescription = context.getString(R.string.library_group_manage)
		binding.imageViewContinue.setOnClickListener { view -> onManage(item, view) }
	}
}
