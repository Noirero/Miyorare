package org.koitharu.kotatsu.details.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.details.data.ChapterPersonalMetadata

class NoteWithLinksTest {
	private val style = SpanStyle(color = Color.Blue, textDecoration = TextDecoration.Underline)

	@Test fun ordinaryTextAndNonHttpAddressesStayUnlinked() {
		for (note in listOf("", "Ordinary text\nStill selectable", "example.com www.example.com ftp://example.com")) {
			assertLinks(note)
		}
	}

	@Test fun urlOnlyAndBothExplicitSchemesAreLinked() {
		for (url in listOf("http://example.com", "https://example.com/path?q=1#part", "HTTPS://example.com")) {
			assertLinks(url, url)
		}
	}

	@Test fun sentenceAndMultilineNotesKeepTheirFullTextAndEachLinkRange() {
		assertLinks(
			"Read https://example.com/one, then\nhttp://example.org/two! Keep the rest.",
			"https://example.com/one", "http://example.org/two",
		)
	}

	@Test fun mangaNoteTrailingPunctuationIsExcludedFromTheOpenedUrl() {
		val url = "https://example.com/path"
		for (punctuation in listOf(".", ",", ";", ":", "!", "?", ")", "]", "}", ").!?")) {
			assertLinks("See ($url$punctuation next", url)
		}
	}

	@Test fun repeatedUrlsHaveSeparateAnnotations() {
		val url = "https://example.com"
		val note = "$url\n$url"
		val links = noteWithLinks(note, style).getLinkAnnotations(0, note.length)
		assertEquals(listOf(0, url.length + 1), links.map { it.start })
		assertEquals(listOf(url.length, note.length), links.map { it.end })
		assertEquals(listOf(url, url), links.map { (it.item as LinkAnnotation.Url).url })
	}

	@Test fun fullChapterNoteAtTheExistingLimitIsPreserved() {
		val url = "https://example.com/last"
		val note = "x".repeat(ChapterPersonalMetadata.MAX_NOTE_LENGTH - url.length - 1) + "\n" + url
		assertEquals(ChapterPersonalMetadata.MAX_NOTE_LENGTH, note.length)
		assertLinks(note, url)
	}

	private fun assertLinks(note: String, vararg expectedUrls: String) {
		val annotated = noteWithLinks(note, style)
		assertEquals("Selection/copy must retain the entire original note", note, annotated.text)
		val links = annotated.getLinkAnnotations(0, annotated.length)
		assertEquals(expectedUrls.toList(), links.map { (it.item as LinkAnnotation.Url).url })
		for ((link, url) in links.zip(expectedUrls)) {
			assertEquals(note.indexOf(url), link.start)
			assertEquals(link.start + url.length, link.end)
			assertNull("Use normal Compose/Android URL handling", link.item.linkInteractionListener)
			assertTrue(annotated.spanStyles.any { it.start == link.start && it.end == link.end && it.item == style })
		}
	}
}
