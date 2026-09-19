package org.koitharu.kotatsu.local.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExternalEpubImportRegressionTest {

	@Test
	fun `manifest exposes only the epub view handler`() {
		val manifest = source("AndroidManifest.xml")
		val activity = manifest
			.substringAfter("android:name=\"org.koitharu.kotatsu.local.ui.ExternalEpubImportActivity\"")
			.substringBefore("</activity>")

		assertTrue(activity.contains("android.intent.action.VIEW"))
		assertTrue(activity.contains("application/epub+zip"))
		assertFalse(activity.contains("*/*"))
		assertFalse(activity.contains("android.intent.action.SEND"))
	}

	@Test
	fun `external entry point reuses the existing single manga importer`() {
		val activity = source("kotlin/org/koitharu/kotatsu/local/ui/ExternalEpubImportActivity.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(activity.contains("lateinitvarimporter:SingleMangaImporter"))
		assertTrue(activity.contains("importer.previewEpub(uri)"))
		assertTrue(activity.contains("importer.import(uri).manga"))
		assertTrue(activity.contains("R.string.external_epub_reimport"))
		assertTrue(activity.contains("R.string.external_epub_open_details"))
		assertFalse(activity.contains("MainActivity"))
	}

	private fun source(relativePath: String): String {
		return sequenceOf(
			File("src/main", relativePath),
			File("app/src/main", relativePath),
		)
			.firstOrNull(File::isFile)
			?.readText()
			?: error("Cannot find production source: $relativePath")
	}
}
