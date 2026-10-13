package org.koitharu.kotatsu.details.ui

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.details.ui.model.ChapterListItem

private val noteUrlRegex = Regex("""https?://[^\s<>]+""", RegexOption.IGNORE_CASE)
private val noteTrailingPunctuation = setOf('.', ',', ';', ':', '!', '?', ')', ']', '}')

internal fun showChapterPersonalNote(context: Context, item: ChapterListItem) {
	val note = item.personalMetadata.note?.takeIf(String::isNotBlank) ?: return
	val linked = SpannableStringBuilder()
	appendLinkedChapterNote(linked, note)
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

internal fun appendLinkedChapterNote(builder: SpannableStringBuilder, note: String) {
	val start = builder.length
	builder.append(note)
	for (match in noteUrlRegex.findAll(note)) {
		var end = match.range.last + 1
		while (end > match.range.first && note[end - 1] in noteTrailingPunctuation) end--
		if (end > match.range.first) {
			val url = note.substring(match.range.first, end)
			builder.setSpan(URLSpan(url), start + match.range.first, start + end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
		}
	}
}
