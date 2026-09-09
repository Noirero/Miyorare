package org.koitharu.kotatsu.favourites.groups.ui

import android.content.Intent
import android.view.View
import androidx.core.view.isVisible
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.databinding.ItemLibraryGroupGridBinding
import org.koitharu.kotatsu.favourites.groups.domain.LibraryGroup
import org.koitharu.kotatsu.favourites.ui.FavouritesActivity
import org.koitharu.kotatsu.image.ui.CoverImageView
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.size.ItemSizeResolver

fun libraryGroupGridAD(
	sizeResolver: ItemSizeResolver,
	onManage: (LibraryGroup, View) -> Unit,
	onLongClick: (LibraryGroup, View) -> Boolean,
) = adapterDelegateViewBinding<LibraryGroupGridModel, ListModel, ItemLibraryGroupGridBinding>(
	{ inflater, parent -> ItemLibraryGroupGridBinding.inflate(inflater, parent, false) },
) {
	sizeResolver.attachToView(itemView, binding.textViewTitle, null)
	itemView.setOnClickListener { view ->
		view.context.startActivity(
			Intent(view.context, FavouritesActivity::class.java)
				.putExtra(FavouritesActivity.EXTRA_LIBRARY_GROUP_ID, item.group.id),
		)
	}
	itemView.setOnLongClickListener { view -> onLongClick(item.group, view) }
	binding.imageViewMore.setOnClickListener { view -> onManage(item.group, view) }

	val collageViews: List<CoverImageView> = listOf(
		binding.imageViewCover1,
		binding.imageViewCover2,
		binding.imageViewCover3,
		binding.imageViewCover4,
	)

	bind {
		binding.textViewTitle.text = item.title
		binding.textViewSubtitle.text = context.resources.getQuantityString(
			R.plurals.library_group_members,
			item.memberCount,
			item.memberCount,
		)
		binding.imageViewPin.isVisible = item.isPinned

		val customCover = item.group.coverUrl
		binding.imageViewCustom.isVisible = customCover != null
		binding.layoutCollage.isVisible = customCover == null
		if (customCover != null) {
			binding.imageViewCustom.setImageAsync(customCover, manga = null)
		} else {
			val members = item.group.members.take(collageViews.size)
			collageViews.forEachIndexed { index, imageView ->
				// Library groups have at least two members. Repeating a member only when the group has
				// fewer than four keeps the 2x2 collage filled instead of leaving a black quadrant.
				val member = members[index % members.size]
				imageView.isVisible = true
				imageView.setImageAsync(member.displayCoverUrl, MangaSource(member.source))
			}
		}
	}
}
