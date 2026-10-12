package org.koitharu.kotatsu.details.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle

/**
 * Keeps styled link construction local to Details without requiring callers to manage
 * AnnotatedString style stack state directly.
 */
internal inline fun AnnotatedString.Builder.withStyle(
	style: SpanStyle,
	block: AnnotatedString.Builder.() -> Unit,
) {
	pushStyle(style)
	try {
		block()
	} finally {
		pop()
	}
}
