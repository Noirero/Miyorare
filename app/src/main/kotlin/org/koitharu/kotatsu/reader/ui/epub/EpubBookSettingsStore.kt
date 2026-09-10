package org.koitharu.kotatsu.reader.ui.epub

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koitharu.kotatsu.core.prefs.AppSettings
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight per-book EPUB/novel overrides.
 *
 * These are deliberately kept out of Room: reader presentation is preference state, not library
 * content. A book with overrides disabled always reads the current global [AppSettings] values, so
 * existing users keep exactly the behaviour they had before this layer existed.
 */
@Singleton
class EpubBookSettingsStore @Inject constructor(
	@ApplicationContext private val context: Context,
	private val global: AppSettings,
) {

	private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

	fun forBook(mangaId: Long): BookSettings = BookSettings(mangaId)

	/** Emits only presentation/behaviour changes; TTS slider ticks intentionally use a separate key. */
	fun observeReader(mangaId: Long): Flow<Int> = callbackFlow {
		val book = forBook(mangaId)
		val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
			if (key == book.readerRevisionKey) trySend(book.readerRevision)
		}
		prefs.registerOnSharedPreferenceChangeListener(listener)
		trySend(book.readerRevision)
		awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
	}.distinctUntilChanged()

	inner class BookSettings internal constructor(
		val mangaId: Long,
	) {
		private val prefix = "$PREFIX$mangaId."
		private fun key(name: String) = prefix + name

		internal val readerRevisionKey = key("reader_revision")
		private val ttsRevisionKey = key("tts_revision")
		internal val readerRevision: Int get() = prefs.getInt(readerRevisionKey, 0)
		private val ttsRevision: Int get() = prefs.getInt(ttsRevisionKey, 0)

		var enabled: Boolean
			get() = prefs.getBoolean(key("enabled"), false)
			set(value) {
				if (value == enabled) return
				if (value && !prefs.getBoolean(key("initialized"), false)) {
					seedFromGlobal()
					return
				}
				val nextReader = readerRevision + 1
				val nextTts = ttsRevision + 1
				prefs.edit {
					putBoolean(key("enabled"), value)
					putInt(readerRevisionKey, nextReader)
					putInt(ttsRevisionKey, nextTts)
				}
			}

		var fontSize: Int
			get() = if (enabled) prefs.getInt(key("font_size"), global.epubFontSize).coerceIn(50, 200) else global.epubFontSize
			set(value) = writeReaderInt("font_size", value.coerceIn(50, 200)) { global.epubFontSize = it }

		var fontFamily: String
			get() = if (enabled) prefs.getString(key("font_family"), global.epubFontFamily) ?: global.epubFontFamily else global.epubFontFamily
			set(value) = writeReaderString("font_family", value) { global.epubFontFamily = it }

		val customFontFile: File
			get() = if (enabled) bookCustomFontFile() else globalCustomFontFile()

		val customFontName: String
			get() = if (enabled) {
				prefs.getString(key("custom_font_name"), "") ?: ""
			} else {
				global.epubCustomFontName
			}

		val customFontRevision: Int
			get() = if (enabled) prefs.getInt(key("custom_font_revision"), 0) else global.epubCustomFontRevision

		fun installCustomFont(source: File, displayName: String) {
			if (enabled) {
				source.copyTo(bookCustomFontFile(), overwrite = true)
				val revision = customFontRevision + 1
				updateReader {
					putString(key("custom_font_name"), displayName)
					putInt(key("custom_font_revision"), revision)
				}
			} else {
				source.copyTo(globalCustomFontFile(), overwrite = true)
				global.epubCustomFontName = displayName
				global.epubCustomFontRevision++
			}
		}

		fun removeCustomFont() {
			if (enabled) {
				bookCustomFontFile().delete()
				val revision = customFontRevision + 1
				updateReader {
					putString(key("custom_font_name"), "")
					putInt(key("custom_font_revision"), revision)
				}
			} else {
				globalCustomFontFile().delete()
				global.epubCustomFontName = ""
				global.epubCustomFontRevision++
			}
		}

		private fun globalCustomFontFile() = File(context.filesDir, AppSettings.EPUB_CUSTOM_FONT_FILE)

		private fun bookCustomFontFile() = File(context.filesDir, "$BOOK_FONT_PREFIX$mangaId")

		var lineHeight: Int
			get() = if (enabled) prefs.getInt(key("line_height"), global.epubLineHeight).coerceIn(100, 240) else global.epubLineHeight
			set(value) = writeReaderInt("line_height", value.coerceIn(100, 240)) { global.epubLineHeight = it }

		var paragraphSpacing: Int
			get() = if (enabled) prefs.getInt(key("paragraph_spacing"), global.epubParagraphSpacing).coerceIn(0, 48) else global.epubParagraphSpacing
			set(value) = writeReaderInt("paragraph_spacing", value.coerceIn(0, 48)) { global.epubParagraphSpacing = it }

		var horizontalPadding: Int
			get() = if (enabled) prefs.getInt(key("horizontal_padding"), global.epubHorizontalPadding).coerceIn(0, 64) else global.epubHorizontalPadding
			set(value) = writeReaderInt("horizontal_padding", value.coerceIn(0, 64)) { global.epubHorizontalPadding = it }

		var verticalPadding: Int
			get() = if (enabled) prefs.getInt(key("vertical_padding"), global.epubVerticalPadding).coerceIn(0, 112) else global.epubVerticalPadding
			set(value) = writeReaderInt("vertical_padding", value.coerceIn(0, 112)) { global.epubVerticalPadding = it }

		var textAlign: String
			get() = if (enabled) prefs.getString(key("text_align"), global.epubTextAlign) ?: global.epubTextAlign else global.epubTextAlign
			set(value) = writeReaderString("text_align", value) { global.epubTextAlign = it }

		var readingMode: String
			get() = if (enabled) prefs.getString(key("reading_mode"), global.epubReadingMode) ?: global.epubReadingMode else global.epubReadingMode
			set(value) = writeReaderString("reading_mode", value) { global.epubReadingMode = it }

		var pagedTapGestures: Boolean
			get() = if (enabled) prefs.getBoolean(key("paged_tap_gestures"), global.isEpubPagedTapGesturesEnabled) else global.isEpubPagedTapGesturesEnabled
			set(value) = writeReaderBoolean("paged_tap_gestures", value) { global.isEpubPagedTapGesturesEnabled = it }

		var publisherStyle: Boolean
			get() = if (enabled) prefs.getBoolean(key("publisher_style"), global.isEpubPublisherStyleEnabled) else global.isEpubPublisherStyleEnabled
			set(value) = writeReaderBoolean("publisher_style", value) { global.isEpubPublisherStyleEnabled = it }

		var bionicReading: Boolean
			get() = if (enabled) prefs.getBoolean(key("bionic_reading"), global.isEpubBionicReadingEnabled) else global.isEpubBionicReadingEnabled
			set(value) = writeReaderBoolean("bionic_reading", value) { global.isEpubBionicReadingEnabled = it }

		var theme: String
			get() = if (enabled) prefs.getString(key("theme"), global.epubTheme) ?: global.epubTheme else global.epubTheme
			set(value) = writeReaderString("theme", value) { global.epubTheme = it }

		var customBackgroundColor: Int
			get() = if (enabled) prefs.getInt(key("custom_background"), global.epubCustomBackgroundColor) else global.epubCustomBackgroundColor
			set(value) = writeReaderInt("custom_background", value) { global.epubCustomBackgroundColor = it }

		var customTextColor: Int
			get() = if (enabled) prefs.getInt(key("custom_text"), global.epubCustomTextColor) else global.epubCustomTextColor
			set(value) = writeReaderInt("custom_text", value) { global.epubCustomTextColor = it }

		var customHighlightColor: Int
			get() = if (enabled) prefs.getInt(key("custom_highlight"), global.epubCustomHighlightColor) else global.epubCustomHighlightColor
			set(value) = writeReaderInt("custom_highlight", value) { global.epubCustomHighlightColor = it }

		var ttsSpeed: Float
			get() = if (enabled) prefs.getFloat(key("tts_speed"), global.epubTtsSpeed).coerceIn(0.25f, 3f) else global.epubTtsSpeed
			set(value) = writeTtsFloat("tts_speed", value.coerceIn(0.25f, 3f)) { global.epubTtsSpeed = it }

		var ttsPitch: Float
			get() = if (enabled) prefs.getFloat(key("tts_pitch"), global.epubTtsPitch).coerceIn(0.5f, 2f) else global.epubTtsPitch
			set(value) = writeTtsFloat("tts_pitch", value.coerceIn(0.5f, 2f)) { global.epubTtsPitch = it }

		var ttsVoiceIndex: Int
			get() = if (enabled) prefs.getInt(key("tts_voice"), global.epubTtsVoiceIndex) else global.epubTtsVoiceIndex
			set(value) = writeTtsInt("tts_voice", value) { global.epubTtsVoiceIndex = it }

		private fun seedFromGlobal() {
			val nextReader = readerRevision + 1
			val nextTts = ttsRevision + 1
			val copiedCustomFont = globalCustomFontFile().takeIf { it.isFile && global.epubCustomFontName.isNotBlank() }
				?.let { source -> runCatching { source.copyTo(bookCustomFontFile(), overwrite = true); true }.getOrDefault(false) }
				?: false
			prefs.edit {
				putBoolean(key("initialized"), true)
				putBoolean(key("enabled"), true)
				putInt(key("font_size"), global.epubFontSize)
				putString(key("font_family"), global.epubFontFamily)
				if (copiedCustomFont) {
					putString(key("custom_font_name"), global.epubCustomFontName)
					putInt(key("custom_font_revision"), global.epubCustomFontRevision)
				}
				putInt(key("line_height"), global.epubLineHeight)
				putInt(key("paragraph_spacing"), global.epubParagraphSpacing)
				putInt(key("horizontal_padding"), global.epubHorizontalPadding)
				putInt(key("vertical_padding"), global.epubVerticalPadding)
				putString(key("text_align"), global.epubTextAlign)
				putString(key("reading_mode"), global.epubReadingMode)
				putBoolean(key("paged_tap_gestures"), global.isEpubPagedTapGesturesEnabled)
				putBoolean(key("publisher_style"), global.isEpubPublisherStyleEnabled)
				putBoolean(key("bionic_reading"), global.isEpubBionicReadingEnabled)
				putString(key("theme"), global.epubTheme)
				putInt(key("custom_background"), global.epubCustomBackgroundColor)
				putInt(key("custom_text"), global.epubCustomTextColor)
				putInt(key("custom_highlight"), global.epubCustomHighlightColor)
				putFloat(key("tts_speed"), global.epubTtsSpeed)
				putFloat(key("tts_pitch"), global.epubTtsPitch)
				putInt(key("tts_voice"), global.epubTtsVoiceIndex)
				putInt(readerRevisionKey, nextReader)
				putInt(ttsRevisionKey, nextTts)
			}
		}

		private fun updateReader(block: SharedPreferences.Editor.() -> Unit) {
			val next = readerRevision + 1
			prefs.edit {
				block()
				putInt(readerRevisionKey, next)
			}
		}

		private fun updateTts(block: SharedPreferences.Editor.() -> Unit) {
			val next = ttsRevision + 1
			prefs.edit {
				block()
				putInt(ttsRevisionKey, next)
			}
		}

		private fun writeReaderInt(name: String, value: Int, globalSetter: (Int) -> Unit) {
			if (enabled) updateReader { putInt(key(name), value) } else globalSetter(value)
		}

		private fun writeReaderString(name: String, value: String, globalSetter: (String) -> Unit) {
			if (enabled) updateReader { putString(key(name), value) } else globalSetter(value)
		}

		private fun writeReaderBoolean(name: String, value: Boolean, globalSetter: (Boolean) -> Unit) {
			if (enabled) updateReader { putBoolean(key(name), value) } else globalSetter(value)
		}

		private fun writeTtsFloat(name: String, value: Float, globalSetter: (Float) -> Unit) {
			if (enabled) updateTts { putFloat(key(name), value) } else globalSetter(value)
		}

		private fun writeTtsInt(name: String, value: Int, globalSetter: (Int) -> Unit) {
			if (enabled) updateTts { putInt(key(name), value) } else globalSetter(value)
		}
	}

	private companion object {
		const val PREFIX = "epub_book_settings."
		const val BOOK_FONT_PREFIX = "epub_custom_font_book_"
	}
}
