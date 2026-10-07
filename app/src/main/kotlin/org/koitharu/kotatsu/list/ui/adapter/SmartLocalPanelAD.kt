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
		binding.buttonFilter.setOnClickListener { listener.onSmartLocalFilterClick(it) }
		binding.buttonManageFolders.setOnClickListener { listener.onSmartLocalManageFoldersClick(it) }
		binding.chipAll.setOnClickListener { listener.onSmartLocalTypeChanged(null) }
		binding.chipManga.setOnClickListener { listener.onSmartLocalTypeChanged(LocalContentType.MANGA) }
		binding.chipNovel.setOnClickListener { listener.onSmartLocalTypeChanged(LocalContentType.NOVEL) }

		bind {
			binding.textViewSummary.text = context.getString(
				R.string.smart_local_collection_summary,
				item.folderCount,
				item.titleCount,
				item.chapterCount,
			)
			binding.textViewReading.text = context.getString(R.string.smart_local_stat_reading, item.readingCount)
			binding.textViewNew.text = context.getString(R.string.smart_local_stat_new, item.newCount)
			binding.chipAll.isChecked = item.contentType == null
			binding.chipManga.isChecked = item.contentType == LocalContentType.MANGA
			binding.chipNovel.isChecked = item.contentType == LocalContentType.NOVEL
			if (binding.editTextSearch.text?.toString() != item.query) {
				binding.editTextSearch.setText(item.query)
				binding.editTextSearch.setSelection(item.query.length)
			}
		}
	}
