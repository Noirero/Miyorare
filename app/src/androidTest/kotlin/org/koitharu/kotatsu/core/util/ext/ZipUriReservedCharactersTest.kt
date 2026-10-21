package org.koitharu.kotatsu.core.util.ext

import androidx.core.net.toUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ZipUriReservedCharactersTest {

	@Test
	fun epubArchiveAndEntryPreserveReservedCharacters() {
		assertZipUriRoundTrip(
			archive = File("/tmp/Novel #1 100%.epub"),
			entry = "OEBPS/Chapter #1 100%.xhtml",
		)
	}

	@Test
	fun cbzArchiveAndEntryPreserveReservedCharacters() {
		assertZipUriRoundTrip(
			archive = File("/tmp/Manga #1 100%.cbz"),
			entry = "pages/Page #1 100%.jpg",
		)
	}

	private fun assertZipUriRoundTrip(archive: File, entry: String) {
		val uri = archive.toZipUri(entry)
		assertTrue(uri.isZipUri())
		assertEquals(archive.absolutePath, uri.schemeSpecificPart)
		assertEquals(entry, uri.fragment)

		val reparsed = uri.toString().toUri()
		assertEquals(archive.absolutePath, reparsed.schemeSpecificPart)
		assertEquals(entry, reparsed.fragment)
	}
}
