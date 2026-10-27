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
	fun `novel built in translation does not bundle a translation runtime`() {
		val gradle = projectFile("app/build.gradle").readText().lowercase()
		val engine = source("org/koitharu/kotatsu/reader/ui/epub/translation/MiyorareBuiltInTranslationEngine.kt")

		assertFalse(gradle.contains("com.google.mlkit:translate"))
		assertFalse(gradle.contains("com.google.mlkit:language-id"))
		assertTrue(gradle.contains("com.google.android.gms:play-services-mlkit-language-id:17.0.0"))
		assertFalse(gradle.contains("tensorflow-lite-task-text"))
		assertTrue(engine.contains("TranslationManager"))
		assertTrue(engine.contains("getOnDeviceTranslationCapabilities"))
		assertTrue(engine.contains("createOnDeviceTranslator"))
		assertTrue(engine.contains("CancellationSignal"))
		assertTrue(engine.contains("TranslationRequestValue.forText"))
		assertFalse(engine.contains("com.google.mlkit.nl.translate"))
		assertFalse(engine.contains("downloadModelIfNeeded"))
	}

	@Test
	fun `system built in translation has no provider branding overlay or silent web fallback`() {
		val controller = source("org/koitharu/kotatsu/reader/ui/epub/translation/NovelTranslationDialogController.kt")
		val engine = source("org/koitharu/kotatsu/reader/ui/epub/translation/MiyorareBuiltInTranslationEngine.kt")
		val layout = projectFile("app/src/main/res/layout/fragment_reader_epub.xml").readText()
		val strings = projectFile("app/src/main/res/values/strings_novel_translation_hub.xml").readText()

		assertFalse(controller.contains("GoogleTranslate"))
		assertFalse(controller.contains("translate.google.com"))
		assertFalse(layout.contains("BuiltInTranslationAttributionView"))
		assertFalse(strings.contains("powered by Google Translate"))
		assertTrue(controller.contains("MiyorareBuiltInTranslationSupport.settingsPendingIntent"))
		assertFalse(engine.contains("MiyorareOnlineTranslationEngine"))
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
