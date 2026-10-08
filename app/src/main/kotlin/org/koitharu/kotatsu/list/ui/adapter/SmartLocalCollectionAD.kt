package org.koitharu.kotatsu.list.ui.adapter

import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.ListMode
import org.koitharu.kotatsu.databinding.ItemSmartLocalResumeBinding
import org.koitharu.kotatsu.databinding.ItemSmartLocalCollectionHeaderBinding
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.SmartLocalResumeModel
import org.koitharu.kotatsu.list.ui.model.SmartLocalCollectionHeaderModel
import java.text.NumberFormat

fun smartLocalResumeAD(listener: MangaListListener) =
	adapterDelegateViewBinding<SmartLocalResumeModel, ListModel, ItemSmartLocalResumeBinding>(
		{ inflater, parent -> ItemSmartLocalResumeBinding.inflate(inflater, parent, false) },
	) {
		binding.root.setOnClickListener { listener.onSmartLocalResume(item.manga) }
		bind {
			binding.imageViewCover.setImageAsync(item.manga.coverUrl, item.manga)
			binding.textViewTitle.text = item.manga.title
			binding.textViewSubtitle.text = item.subtitle
			binding.progress.progress = item.progress
			binding.textViewProgress.text = NumberFormat.getPercentInstance().format(item.progress / 100.0)
			binding.root.contentDescription = context.getString(R.string.smart_local_resume_description, item.manga.title, item.subtitle)
		}
	}

fun smartLocalCollectionHeaderAD(listener: MangaListListener) =
	adapterDelegateViewBinding<SmartLocalCollectionHeaderModel, ListModel, ItemSmartLocalCollectionHeaderBinding>(
		{ inflater, parent -> ItemSmartLocalCollectionHeaderBinding.inflate(inflater, parent, false) },
	) {
		binding.buttonGrid.setOnClickListener { listener.onSmartLocalListModeChanged(ListMode.GRID) }
		binding.buttonList.setOnClickListener { listener.onSmartLocalListModeChanged(ListMode.LIST) }
		bind {
			binding.buttonGrid.isChecked = item.mode == ListMode.GRID || item.mode == ListMode.COVER_ONLY
			binding.buttonList.isChecked = !binding.buttonGrid.isChecked
		}
	}
