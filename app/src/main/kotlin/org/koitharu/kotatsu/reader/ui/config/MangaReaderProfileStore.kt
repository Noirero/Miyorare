package org.koitharu.kotatsu.reader.ui.config

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koitharu.kotatsu.core.model.ZoomMode
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ReaderBackground
import org.koitharu.kotatsu.core.prefs.ReaderMode
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight opt-in reader profile for a single manga.
 *
 * Reader mode and color filter are already stored per manga in Room. This store captures the
 * remaining reader preferences that are global by default, without adding a database migration.
 */
@Singleton
class MangaReaderProfileStore @Inject constructor(
	@ApplicationContext context: Context,
) {

	data class Profile(
		val zoomMode: ZoomMode,
		val background: ReaderBackground,
		val isReaderOptimizationEnabled: Boolean,
		val isUpscaleEnabled: Boolean,
		val is32BitColorsEnabled: Boolean,
		val isPagesNumbersEnabled: Boolean,
		val isPagesCropEnabledStandard: Boolean,
		val isPagesCropEnabledWebtoon: Boolean,
	)

	private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

	fun get(mangaId: Long): Profile? {
		val prefix = prefix(mangaId)
		if (!prefs.getBoolean(prefix + ENABLED, false)) return null
		return Profile(
			zoomMode = enumValue(prefs.getString(prefix + ZOOM_MODE, null), ZoomMode.FIT_CENTER),
			background = enumValue(prefs.getString(prefix + BACKGROUND, null), ReaderBackground.DEFAULT),
			isReaderOptimizationEnabled = prefs.getBoolean(prefix + OPTIMIZE, false),
			isUpscaleEnabled = prefs.getBoolean(prefix + UPSCALE, false),
			is32BitColorsEnabled = prefs.getBoolean(prefix + COLOR_32BIT, false),
			isPagesNumbersEnabled = prefs.getBoolean(prefix + PAGE_NUMBERS, false),
			isPagesCropEnabledStandard = prefs.getBoolean(prefix + CROP_STANDARD, false),
			isPagesCropEnabledWebtoon = prefs.getBoolean(prefix + CROP_WEBTOON, false),
		)
	}

	fun saveCurrent(mangaId: Long, settings: AppSettings) {
		write(
			mangaId,
			Profile(
				zoomMode = settings.zoomMode,
				background = settings.readerBackground,
				isReaderOptimizationEnabled = settings.isReaderOptimizationEnabled,
				isUpscaleEnabled = settings.isReaderUpscaleEnabled,
				is32BitColorsEnabled = settings.is32BitColorsEnabled,
				isPagesNumbersEnabled = settings.isPagesNumbersEnabled,
				isPagesCropEnabledStandard = settings.isPagesCropEnabled(ReaderMode.STANDARD),
				isPagesCropEnabledWebtoon = settings.isPagesCropEnabled(ReaderMode.WEBTOON),
			),
		)
	}

	/**
	 * Move a profile when source migration changes the manga id.
	 * The destination wins if it already has a profile. Copy+delete uses one preference editor
	 * transaction so the in-memory state changes atomically without blocking on a disk write.
	 */
	fun move(oldMangaId: Long, newMangaId: Long) {
		if (oldMangaId == newMangaId) return
		val oldProfile = get(oldMangaId) ?: return
		val editor = prefs.edit()
		if (get(newMangaId) == null) {
			putProfile(editor, prefix(newMangaId), oldProfile)
		}
		removeProfile(editor, prefix(oldMangaId))
		editor.apply()
	}

	fun clear(mangaId: Long) {
		prefs.edit().also { removeProfile(it, prefix(mangaId)) }.apply()
	}

	fun observe(mangaId: Long): Flow<Profile?> = callbackFlow {
		val prefix = prefix(mangaId)
		val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
			if (key != null && key.startsWith(prefix)) trySend(get(mangaId))
		}
		prefs.registerOnSharedPreferenceChangeListener(listener)
		trySend(get(mangaId))
		awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
	}.distinctUntilChanged()

	private fun write(mangaId: Long, profile: Profile) {
		prefs.edit().also { putProfile(it, prefix(mangaId), profile) }.apply()
	}

	private fun putProfile(editor: SharedPreferences.Editor, prefix: String, profile: Profile) {
		editor
			.putBoolean(prefix + ENABLED, true)
			.putString(prefix + ZOOM_MODE, profile.zoomMode.name)
			.putString(prefix + BACKGROUND, profile.background.name)
			.putBoolean(prefix + OPTIMIZE, profile.isReaderOptimizationEnabled)
			.putBoolean(prefix + UPSCALE, profile.isUpscaleEnabled)
			.putBoolean(prefix + COLOR_32BIT, profile.is32BitColorsEnabled)
			.putBoolean(prefix + PAGE_NUMBERS, profile.isPagesNumbersEnabled)
			.putBoolean(prefix + CROP_STANDARD, profile.isPagesCropEnabledStandard)
			.putBoolean(prefix + CROP_WEBTOON, profile.isPagesCropEnabledWebtoon)
	}

	private fun removeProfile(editor: SharedPreferences.Editor, prefix: String) {
		editor
			.remove(prefix + ENABLED)
			.remove(prefix + ZOOM_MODE)
			.remove(prefix + BACKGROUND)
			.remove(prefix + OPTIMIZE)
			.remove(prefix + UPSCALE)
			.remove(prefix + COLOR_32BIT)
			.remove(prefix + PAGE_NUMBERS)
			.remove(prefix + CROP_STANDARD)
			.remove(prefix + CROP_WEBTOON)
	}

	private inline fun <reified T : Enum<T>> enumValue(raw: String?, fallback: T): T =
		raw?.let { value -> enumValues<T>().firstOrNull { it.name == value } } ?: fallback

	private fun prefix(mangaId: Long) = "$mangaId:"

	private companion object {
		const val PREFS_NAME = "manga_reader_profiles"
		const val ENABLED = "enabled"
		const val ZOOM_MODE = "zoom_mode"
		const val BACKGROUND = "background"
		const val OPTIMIZE = "optimize"
		const val UPSCALE = "upscale"
		const val COLOR_32BIT = "color_32bit"
		const val PAGE_NUMBERS = "page_numbers"
		const val CROP_STANDARD = "crop_standard"
		const val CROP_WEBTOON = "crop_webtoon"
	}
}
