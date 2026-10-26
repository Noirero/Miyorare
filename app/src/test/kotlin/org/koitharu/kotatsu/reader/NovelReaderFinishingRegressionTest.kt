package org.koitharu.kotatsu.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NovelReaderFinishingRegressionTest {

	@Test
	fun `novel typography exposes weight center and sepia without replacing existing controls`() {
		val config = source("org/koitharu/kotatsu/reader/ui/config/ReaderConfigSheet.kt")
		val reader = source("org/koitharu/kotatsu/reader/ui/epub/EpubReaderFragment.kt")
		val prefs = source("org/koitharu/kotatsu/core/prefs/AppSettings.kt")

		assertTrue(config.contains("EpubFontWeightSection"))
		assertTrue(config.contains("\"center\"tostringResource(R.string.epub_align_center)"))
		assertTrue(config.contains("\"sepia\"toR.string.epub_theme_sepia"))
		assertTrue(reader.contains("EPUB_THEME_SEPIA"))
		assertTrue(reader.contains("activeFontWeight"))
		assertTrue(prefs.contains("KEY_EPUB_FONT_WEIGHT"))
	}

	@Test
	fun `novel appearance includes reader-only brightness with system fallback`() {
		val config = source("org/koitharu/kotatsu/reader/ui/config/ReaderConfigSheet.kt")
		val reader = source("org/koitharu/kotatsu/reader/ui/epub/EpubReaderFragment.kt")
		val brightness = source("org/koitharu/kotatsu/reader/ui/epub/EpubBrightnessSettings.kt")
		val bookSettings = source("org/koitharu/kotatsu/reader/ui/epub/EpubBookSettingsStore.kt")

		assertTrue(config.contains("epubSettings.screenBrightness"))
		assertTrue(reader.contains("applyScreenBrightness()"))
		assertTrue(reader.contains("BRIGHTNESS_OVERRIDE_NONE"))
		assertTrue(brightness.contains("KEY_EPUB_SCREEN_BRIGHTNESS"))
		assertTrue(brightness.contains("epubScreenBrightness"))
		assertTrue(bookSettings.contains("screenBrightness"))
	}

	@Test
	fun `novel progress is persisted during reading and remains exact-offset based`() {
		val reader = source("org/koitharu/kotatsu/reader/ui/epub/EpubReaderFragment.kt")

		assertTrue(reader.contains("schedulePersistentProgress()"))
		assertTrue(reader.contains("PROGRESS_PERSIST_MAX_INTERVAL_MS"))
		assertTrue(reader.contains("viewModel.saveCurrentState(state)"))
		assertTrue(reader.contains("ReaderState.encodeEpubOffset(locator.offset)"))
	}

	@Test
	fun `selection paragraph and chapter translation keep source offset mapping`() {
		val reader = source("org/koitharu/kotatsu/reader/ui/epub/EpubReaderFragment.kt")

		assertTrue(reader.contains("ACTION_TRANSLATE_SELECTION"))
		assertTrue(reader.contains("ACTION_TRANSLATE_PARAGRAPH"))
		assertTrue(reader.contains("buildInlineTranslatedSpanned"))
		assertTrue(reader.contains("sourceToDisplayOffset"))
		assertTrue(reader.contains("displayToSourceOffset"))
		assertTrue(reader.contains("translationOriginals"))
	}

	@Test
	fun `novel built in translation uses approved on-device sdk`() {
		val gradle = projectFile("app/build.gradle").readText().lowercase()

		assertTrue(gradle.contains("com.google.mlkit:translate:17.0.3"))
		assertTrue(gradle.contains("com.google.mlkit:language-id:17.0.6"))
		assertFalse(gradle.contains("tensorflow-lite-task-text"))
	}

	@Test
	fun `built in translation keeps required google attribution outside novel text`() {
		val controller = source("org/koitharu/kotatsu/reader/ui/epub/translation/NovelTranslationDialogController.kt")
		val attributionView = source("org/koitharu/kotatsu/reader/ui/epub/translation/BuiltInTranslationAttributionView.kt")
		val layout = projectFile("app/src/main/res/layout/fragment_reader_epub.xml").readText()
		val strings = projectFile("app/src/main/res/values/strings_novel_translation_hub.xml").readText()

		assertTrue(controller.contains("novel_translation_translate_with_google"))
		assertTrue(controller.contains("novel_translation_google_disclaimer"))
		assertTrue(attributionView.contains("novel_translation_powered_by_google_translate"))
		assertTrue(layout.contains("BuiltInTranslationAttributionView"))
		assertTrue(strings.contains("Translate with Google"))
		assertTrue(strings.contains("powered by Google Translate"))
	}

	private fun source(relativePath: String): String {
		return (
			sequenceOf(
				File("src/main/kotlin", relativePath),
				File("app/src/main/kotlin", relativePath),
			).firstOrNull(File::isFile)?.readText()
				?: error("Cannot find production source: $relativePath")
			)
			.replace(Regex("""//[^\r\n]*"""), "")
			.replace(Regex("""\s+"""), "")
	}

	private fun projectFile(path: String): File =
		sequenceOf(File(path), File("..", path))
			.firstOrNull(File::isFile)
			?: error("Cannot find project file: $path")
}
