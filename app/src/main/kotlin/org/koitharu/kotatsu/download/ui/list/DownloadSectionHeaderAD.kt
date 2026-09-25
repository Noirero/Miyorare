package org.koitharu.kotatsu.download.ui.list

import android.graphics.Color
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.databinding.ItemDownloadSectionHeaderBinding
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.list.ui.model.ListModel

fun downloadSectionHeaderAD() =
	adapterDelegateViewBinding<ListHeader, ListModel, ItemDownloadSectionHeaderBinding>(
		{ inflater, parent -> ItemDownloadSectionHeaderBinding.inflate(inflater, parent, false) },
	) {
		bind {
			val title = item.getText(context).orEmpty()
			val count = item.payload as? Int ?: 0
			binding.title.text = title
			binding.title.setTextColor(Color.rgb(245, 243, 250))
			binding.trailing.setTextColor(Color.rgb(196, 202, 218))
			binding.trailing.text = if (count > 0) {
				if (title == context.getString(R.string.in_progress)) {
					context.getString(R.string.downloads_active_count, count)
				} else {
					context.getString(R.string.downloads_section_count, count)
				}
			} else {
				""
			}
		}
	}
