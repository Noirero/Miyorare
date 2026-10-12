package org.koitharu.kotatsu.details.ui.adapter

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.list.AdapterDelegateClickListenerAdapter
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.util.ext.drawableStart
import org.koitharu.kotatsu.core.util.ext.findActivity
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.getThemeColorStateList
import org.koitharu.kotatsu.core.util.ext.textAndVisible
import org.koitharu.kotatsu.databinding.ItemChapterBinding
import org.koitharu.kotatsu.details.ui.DetailsExpressiveActivity
import org.koitharu.kotatsu.details.ui.DetailsViewModel
import org.koitharu.kotatsu.details.ui.model.ChapterListItem
import org.koitharu.kotatsu.list.ui.model.ListModel
import com.google.android.material.R as materialR

private val noteUrlRegex = Regex("""https?://[^\s<>]+""", RegexOption.IGNORE_CASE)
private val noteTrailingPunctuation = setOf('.', ',', ';', ':', '!', '?', ')', ']', '}')

fun chapterListItemAD(
	clickListener: OnListItemClickListener<ChapterListItem>,
	onDownloadClick: (ChapterListItem) -> Unit,
	onDeleteClick: (ChapterListItem) -> Unit,
	accentColorProvider: () -> Int? = { null },
) = adapterDelegateViewBinding<ChapterListItem, ListModel, ItemChapterBinding>(
	viewBinding = { inflater, parent -> ItemChapterBinding.inflate(inflater, parent, false) },
	on = { item, _, _ -> item is ChapterListItem && !item.isGrid },
) {

	AdapterDelegateClickListenerAdapter(this, clickListener).attach(itemView)
	itemView.isFocusable = false
	itemView.isFocusableInTouchMode = false

	binding.imageButtonDownload.setOnClickListener {
		if (item.canDownload && !item.isDownloaded && !item.isDownloading) onDownloadClick(item)
	}
	binding.imageViewDownloaded.setOnClickListener { anchor ->
		if (!item.isDownloaded) return@setOnClickListener
		PopupMenu(context, anchor).apply {
			menu.add(0, R.id.action_delete, 0, R.string.delete)
			setOnMenuItemClickListener { menuItem ->
				if (menuItem.itemId == R.id.action_delete) {
					onDeleteClick(item)
					true
				} else false
			}
			show()
		}
	}
	binding.imageViewPersonal.setOnClickListener {
		val activity = context.findActivity() as? DetailsExpressiveActivity ?: return@setOnClickListener
		ViewModelProvider(activity)[DetailsViewModel::class.java].editChapterPersonal(item)
	}
	binding.imageViewPersonal.setOnNoteClickListener {
		val note = item.personalMetadata.note?.takeIf(String::isNotBlank) ?: return@setOnNoteClickListener
		val linked = SpannableString(note)
		for (match in noteUrlRegex.findAll(note)) {
			var end = match.range.last + 1
			while (end > match.range.first && note[end - 1] in noteTrailingPunctuation) end--
			if (end > match.range.first) {
				val url = note.substring(match.range.first, end)
				linked.setSpan(URLSpan(url), match.range.first, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
			}
		}
		val message = TextView(context).apply {
			text = linked
			setTextIsSelectable(true)
			movementMethod = LinkMovementMethod.getInstance()
			setTextColor(context.getThemeColor(android.R.attr.textColorPrimary))
			val padding = (24 * resources.displayMetrics.density).toInt()
			setPadding(padding, padding / 2, padding, 0)
		}
		MaterialAlertDialogBuilder(context)
			.setTitle(R.string.chapter_personal_view_note)
			.setView(message)
			.setPositiveButton(android.R.string.ok, null)
			.show()
	}

	bind {
		binding.textViewTitle.text = item.getTitle(context.resources)
		binding.textViewDescription.textAndVisible = item.description
		if (item.isUnread) {
			binding.textViewTitle.setTextColor(context.getThemeColorStateList(android.R.attr.textColorPrimary))
			binding.textViewDescription.setTextColor(context.getThemeColorStateList(materialR.attr.colorOutline))
			binding.textViewTitle.drawableStart = if (item.isNew) ContextCompat.getDrawable(context, R.drawable.ic_new) else null
		} else {
			binding.textViewTitle.setTextColor(context.getThemeColorStateList(android.R.attr.textColorHint))
			binding.textViewDescription.setTextColor(context.getThemeColorStateList(android.R.attr.textColorHint))
			binding.textViewTitle.drawableStart = null
		}
		if (item.isCurrent) {
			val accent = accentColorProvider() ?: context.getThemeColor(androidx.appcompat.R.attr.colorPrimary)
			val radius = context.resources.displayMetrics.density * 2f
			binding.viewCurrentIndicator.background = GradientDrawable().apply {
				shape = GradientDrawable.RECTANGLE
				cornerRadius = radius
				setColor(accent)
			}
			binding.viewCurrentIndicator.isVisible = true
			binding.textViewTitle.typeface = Typeface.DEFAULT_BOLD
			binding.textViewDescription.typeface = Typeface.DEFAULT_BOLD
			binding.textViewTitle.textSize = 17f
		} else {
			binding.viewCurrentIndicator.background = null
			binding.viewCurrentIndicator.isVisible = false
			binding.textViewTitle.typeface = Typeface.DEFAULT
			binding.textViewDescription.typeface = Typeface.DEFAULT
			binding.textViewTitle.textSize = 16f
		}
		binding.imageViewBookmarked.isVisible = item.isBookmarked
		binding.imageViewPersonal.bind(
			item.personalMetadata,
			accentColorProvider() ?: context.getThemeColor(androidx.appcompat.R.attr.colorPrimary),
			context.getThemeColor(materialR.attr.colorOnSurfaceVariant),
		)
		binding.imageViewBookmarked.imageTintList = accentColorProvider()?.let { ColorStateList.valueOf(it) }
			?: context.getThemeColorStateList(androidx.appcompat.R.attr.colorPrimary)
		binding.imageViewDownloaded.isVisible = item.isDownloaded
		binding.progressBarDownload.isVisible = item.isDownloading && !item.isDownloaded
		binding.imageButtonDownload.isVisible = item.canDownload && !item.isDownloaded && !item.isDownloading
	}
}
