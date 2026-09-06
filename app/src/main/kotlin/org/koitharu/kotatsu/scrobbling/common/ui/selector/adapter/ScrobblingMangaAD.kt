package org.koitharu.kotatsu.scrobbling.common.ui.selector.adapter

import android.view.HapticFeedbackConstants
import android.view.View
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.ui.dialog.DialogAction
import org.koitharu.kotatsu.core.ui.dialog.showActionChoiceDialog
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.util.ext.copyToClipboard
import org.koitharu.kotatsu.core.util.ext.textAndVisible
import org.koitharu.kotatsu.databinding.ItemMangaListBinding
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerManga

fun scrobblingMangaAD(
	clickListener: OnListItemClickListener<ScrobblerManga>,
) = adapterDelegateViewBinding<ScrobblerManga, ListModel, ItemMangaListBinding>(
	{ inflater, parent -> ItemMangaListBinding.inflate(inflater, parent, false) },
) {
	itemView.setOnClickListener {
		clickListener.onItemClick(item, it)
	}
	itemView.setOnLongClickListener { view ->
		showTrackerResultActions(view, item)
		true
	}

	bind {
		binding.textViewTitle.text = item.name
		val endIcon = if (item.isBestMatch) R.drawable.ic_star_small else 0
		binding.textViewTitle.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, endIcon, 0)
		binding.textViewSubtitle.textAndVisible = item.altName
		binding.imageViewCover.setImageAsync(item.cover, null)
	}
}

private fun showTrackerResultActions(view: View, item: ScrobblerManga) {
	val context = view.context
	val title = item.name.trim()
	if (title.isEmpty()) return

	view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
	val alternativeTitle = item.altName
		?.trim()
		?.takeIf { it.isNotEmpty() && !it.equals(title, ignoreCase = true) }
	val router = AppRouter.from(view)
	val actions = buildList {
		add(
			DialogAction(context.getString(R.string.tracker_copy_title)) {
				context.copyToClipboard(context.getString(R.string.tracker_copy_title), title)
			},
		)
		if (alternativeTitle != null) {
			add(
				DialogAction(context.getString(R.string.tracker_copy_alternative_title)) {
					context.copyToClipboard(
						context.getString(R.string.tracker_copy_alternative_title),
						alternativeTitle,
					)
				},
			)
		}
		if (router != null && item.url.isNotBlank()) {
			add(
				DialogAction(context.getString(R.string.tracker_open_page)) {
					if (!router.openExternalBrowser(item.url, context.getString(R.string.tracker_open_page))) {
						router.openBrowser(item.url, source = null, title = title)
					}
				},
			)
		}
	}

	showActionChoiceDialog(
		context = context,
		icon = R.drawable.ic_more_vert,
		title = title,
		actions = actions,
		dismissLabel = context.getString(R.string.close),
	)
}
