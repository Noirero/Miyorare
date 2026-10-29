package org.koitharu.kotatsu.list.ui.adapter

import androidx.core.view.isVisible
import androidx.core.content.ContextCompat
import org.koitharu.kotatsu.R
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.core.util.ext.centerContentOnDisplay
import org.koitharu.kotatsu.core.util.ext.setTextAndVisible
import org.koitharu.kotatsu.databinding.ItemEmptyStateBinding
import org.koitharu.kotatsu.list.ui.model.EmptyState
import org.koitharu.kotatsu.list.ui.model.ListModel

fun emptyStateListAD(
	listener: ListStateHolderListener?,
	localCinematic: Boolean = false,
) = adapterDelegateViewBinding<EmptyState, ListModel, ItemEmptyStateBinding>(
	{ inflater, parent -> ItemEmptyStateBinding.inflate(inflater, parent, false) },
) {

	if (listener != null) {
		binding.buttonRetry.setOnClickListener { listener.onEmptyActionClick() }
	}

	bind {
		if (localCinematic) {
			binding.root.setBackgroundColor(ContextCompat.getColor(context, R.color.smart_local_ink))
			binding.textPrimary.setTextColor(ContextCompat.getColor(context, R.color.smart_local_cream))
			binding.textSecondary.setTextColor(ContextCompat.getColor(context, R.color.smart_local_muted))
		}
		binding.root.centerContentOnDisplay()
		if (item.icon == 0) {
			binding.icon.isVisible = false
			binding.icon.disposeImage()
		} else {
			binding.icon.isVisible = true
			binding.icon.setImageAsync(item.icon)
		}
		binding.textPrimary.setText(item.textPrimary)
		binding.textSecondary.setTextAndVisible(item.textSecondary)
		if (listener != null) {
			binding.buttonRetry.setTextAndVisible(item.actionStringRes)
		}
	}
}
