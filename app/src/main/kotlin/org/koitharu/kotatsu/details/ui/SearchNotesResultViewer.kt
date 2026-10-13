package org.koitharu.kotatsu.details.ui

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.details.ui.model.ChapterListItem

private val searchNoteUrlRegex = Regex("""https?://[^\s<>]+""", RegexOption.IGNORE_CASE)
private val searchNoteTrailingPunctuation = setOf('.', ',', ';', ':', '!', '?', ')', ']', '}')

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
				0 -> showSearchNotesViewer(context, listOf(selected))
				1 -> showSearchNotesViewer(context, results)
			}
		}
		.setNegativeButton(android.R.string.cancel, null)
		.show()
}

private fun showSearchNotesViewer(context: Context, items: List<ChapterListItem>) {
	if (items.isEmpty()) return
	val text = SpannableStringBuilder()
	items.forEachIndexed { index, item ->
		if (index > 0) text.append("\n\n")
		val titleStart = text.length
		text.append(item.getTitle(context.resources))
		text.setSpan(StyleSpan(Typeface.BOLD), titleStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
		text.append('\n')
		appendLinkedNote(text, item.personalMetadata.note.orEmpty())
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
		.setTitle(if (items.size == 1) R.string.chapter_personal_note_title else R.string.chapter_search_notes_results_title)
		.setView(message)
		.setPositiveButton(R.string.close, null)
		.show()
}

private fun appendLinkedNote(builder: SpannableStringBuilder, note: String) {
	val start = builder.length
	builder.append(note)
	for (match in searchNoteUrlRegex.findAll(note)) {
		var end = match.range.last + 1
		while (end > match.range.first && note[end - 1] in searchNoteTrailingPunctuation) end--
		if (end > match.range.first) {
			val url = note.substring(match.range.first, end)
			builder.setSpan(URLSpan(url), start + match.range.first, start + end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
		}
	}
}
