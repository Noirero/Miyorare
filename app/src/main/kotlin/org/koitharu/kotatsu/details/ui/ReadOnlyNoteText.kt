package org.koitharu.kotatsu.details.ui

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink

private val NOTE_URL_REGEX = Regex("""https?://[^\s<>]+""", RegexOption.IGNORE_CASE)
private val NOTE_URL_TRAILING_PUNCTUATION = setOf('.', ',', ';', ':', '!', '?', ')', ']', '}')

internal fun noteWithLinks(note: String, linkStyle: SpanStyle) = buildAnnotatedString {
	var cursor = 0
	for (match in NOTE_URL_REGEX.findAll(note)) {
		var urlEnd = match.range.last + 1
		while (urlEnd > match.range.first && note[urlEnd - 1] in NOTE_URL_TRAILING_PUNCTUATION) {
			urlEnd--
		}
		if (urlEnd <= match.range.first) continue
		append(note.substring(cursor, match.range.first))
		val url = note.substring(match.range.first, urlEnd)
		withLink(LinkAnnotation.Url(url)) {
			withStyle(linkStyle) { append(url) }
		}
		append(note.substring(urlEnd, match.range.last + 1))
		cursor = match.range.last + 1
	}
	append(note.substring(cursor))
}

/** Shared Manga/Chapter note rendering; Compose handles URL taps through LocalUriHandler. */
@Composable
internal fun ReadOnlyNoteText(note: String) {
	val linkStyle = SpanStyle(
		color = MaterialTheme.colorScheme.primary,
		textDecoration = TextDecoration.Underline,
	)
	val annotatedNote = remember(note, linkStyle) { noteWithLinks(note, linkStyle) }
	SelectionContainer {
		Text(
			text = annotatedNote,
			style = MaterialTheme.typography.bodyLarge,
			color = MaterialTheme.colorScheme.onSurface,
		)
	}
}
