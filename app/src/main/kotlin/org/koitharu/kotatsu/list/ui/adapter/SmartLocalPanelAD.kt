package org.koitharu.kotatsu.list.ui.adapter

import androidx.core.widget.doAfterTextChanged
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.databinding.ItemSmartLocalPanelBinding
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.SmartLocalPanelModel

fun smartLocalPanelAD(listener: MangaListListener) =
	adapterDelegateViewBinding<SmartLocalPanelModel, ListModel, ItemSmartLocalPanelBinding>(
		{ inflater, parent -> ItemSmartLocalPanelBinding.inflate(inflater, parent, false) },
	) {
		binding.editTextSearch.doAfterTextChanged { text ->
			val query = text?.toString().orEmpty()
			if (query != item.query) listener.onSmartLocalQueryChanged(query)
		}
		binding.buttonFilter.setOnClickListener { listener.onSmartLocalFilterClick(it) }

		bind {
			binding.textViewManga.text = context.getString(R.string.smart_local_stat_manga, item.mangaCount)
			binding.textViewChapters.text = context.getString(R.string.smart_local_stat_chapters, item.chapterCount)
			binding.textViewReading.text = context.getString(R.string.smart_local_stat_reading, item.readingCount)
			binding.textViewNew.text = context.getString(R.string.smart_local_stat_new, item.newCount)
			if (binding.editTextSearch.text?.toString() != item.query) {
				binding.editTextSearch.setText(item.query)
				binding.editTextSearch.setSelection(item.query.length)
			}
		}
	}
