package org.koitharu.kotatsu.list.ui.adapter

import androidx.core.widget.doAfterTextChanged
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.databinding.ItemSmartLocalPanelBinding
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.SmartLocalPanelModel
import org.koitharu.kotatsu.local.library.LocalContentType

fun smartLocalPanelAD(listener: MangaListListener) =
	adapterDelegateViewBinding<SmartLocalPanelModel, ListModel, ItemSmartLocalPanelBinding>(
		{ inflater, parent -> ItemSmartLocalPanelBinding.inflate(inflater, parent, false) },
	) {
		binding.editTextSearch.doAfterTextChanged { text ->
			val query = text?.toString().orEmpty()
			if (query != item.query) listener.onSmartLocalQueryChanged(query)
		}
		binding.buttonSort.setOnClickListener { listener.onSmartLocalSortClick(it) }
		binding.buttonFilter.setOnClickListener { listener.onSmartLocalFilterClick(it) }
		binding.buttonManageFolders.setOnClickListener { listener.onSmartLocalManageFoldersClick(it) }
		binding.chipAll.setOnClickListener { listener.onSmartLocalTypeChanged(null) }
		binding.chipManga.setOnClickListener { listener.onSmartLocalTypeChanged(LocalContentType.MANGA) }
		binding.chipNovel.setOnClickListener { listener.onSmartLocalTypeChanged(LocalContentType.NOVEL) }

		bind {
			binding.textViewSummary.text = listOf(
				context.resources.getQuantityString(R.plurals.smart_local_folders, item.folderCount, item.folderCount),
				context.resources.getQuantityString(R.plurals.smart_local_titles, item.titleCount, item.titleCount),
				context.resources.getQuantityString(R.plurals.smart_local_chapters, item.chapterCount, item.chapterCount),
			).joinToString(" • ")
			binding.buttonSort.text = context.resources.getStringArray(R.array.smart_local_sorts)[item.sort.ordinal]
			binding.buttonFilter.text = if (item.readingFilter == org.koitharu.kotatsu.local.library.LocalReadingFilter.ALL) context.getString(R.string.filter)
				else context.resources.getStringArray(R.array.smart_local_reading_filters)[item.readingFilter.ordinal]
			binding.chipAll.isChecked = item.contentType == null
			binding.chipManga.isChecked = item.contentType == LocalContentType.MANGA
			binding.chipNovel.isChecked = item.contentType == LocalContentType.NOVEL
			if (binding.editTextSearch.text?.toString() != item.query) {
				binding.editTextSearch.setText(item.query)
				binding.editTextSearch.setSelection(item.query.length)
			}
		}
	}
