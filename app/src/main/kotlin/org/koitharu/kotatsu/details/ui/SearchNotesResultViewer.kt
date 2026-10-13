package org.koitharu.kotatsu.details.ui

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.StyleSpan
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.details.ui.model.ChapterListItem

internal fun showSearchNoteResultChooser(
	context: Context,
	selected: ChapterListItem,
	visibleResults: List<ChapterListItem>,
) {
	val results = visibleResults.filter { it.personalMetadata.note?.isNotBlank() == true }
	if (selected.personalMetadata.note.isNullOrBlank()) return
	MaterialAlertDialogBuilder(context)
		.setTitle(R.string.chapter_personal_note_title)
		.setItems(
			arrayOf(
				context.getString(R.string.chapter_search_notes_view_this),
				context.getString(R.string.chapter_search_notes_view_all),
			),
		) { dialog, which ->
			dialog.dismiss()
			when (which) {
				0 -> showChapterPersonalNote(context, selected)
				1 -> showSearchNotesResultsViewer(context, results)
			}
		}
		.setNegativeButton(android.R.string.cancel, null)
		.show()
}

private fun showSearchNotesResultsViewer(context: Context, items: List<ChapterListItem>) {
	if (items.isEmpty()) return
	val text = SpannableStringBuilder()
	items.forEachIndexed { index, item ->
		if (index > 0) text.append("\n\n")
		val titleStart = text.length
		text.append(item.getTitle(context.resources))
		text.setSpan(StyleSpan(Typeface.BOLD), titleStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
		text.append('\n')
		appendLinkedChapterNote(text, item.personalMetadata.note.orEmpty())
	}
	val message = TextView(context).apply {
		this.text = text
		setTextIsSelectable(true)
		movementMethod = LinkMovementMethod.getInstance()
		setTextColor(context.getThemeColor(android.R.attr.textColorPrimary))
		val padding = (24 * resources.displayMetrics.density).toInt()
		setPadding(padding, padding / 2, padding, 0)
	}
	MaterialAlertDialogBuilder(context)
		.setTitle(R.string.chapter_search_notes_results_title)
		.setView(message)
		.setPositiveButton(R.string.close, null)
		.show()
}
