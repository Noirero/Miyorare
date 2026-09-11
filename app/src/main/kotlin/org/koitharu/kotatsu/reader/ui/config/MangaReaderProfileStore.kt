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
 * Reader mode and color filter already live in the database per manga. This store only captures the
 * remaining visual/reader preferences that are otherwise global, avoiding a database migration and
 * keeping the feature inert for manga that have no profile.
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

	/** Move an opt-in profile when source migration re-keys a manga. Existing target profile wins. */
	fun move(oldMangaId: Long, newMangaId: Long) {
		if (oldMangaId == newMangaId) return
		val oldProfile = get(oldMangaId) ?: return
		if (get(newMangaId) == null) write(newMangaId, oldProfile)
		clear(oldMangaId)
	}

	fun clear(mangaId: Long) {
		val prefix = prefix(mangaId)
		prefs.edit()
			.remove(prefix + ENABLED)
			.remove(prefix + ZOOM_MODE)
			.remove(prefix + BACKGROUND)
			.remove(prefix + OPTIMIZE)
			.remove(prefix + UPSCALE)
			.remove(prefix + COLOR_32BIT)
			.remove(prefix + PAGE_NUMBERS)
			.remove(prefix + CROP_STANDARD)
			.remove(prefix + CROP_WEBTOON)
			.apply()
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
		val prefix = prefix(mangaId)
		prefs.edit()
			.putBoolean(prefix + ENABLED, true)
			.putString(prefix + ZOOM_MODE, profile.zoomMode.name)
			.putString(prefix + BACKGROUND, profile.background.name)
			.putBoolean(prefix + OPTIMIZE, profile.isReaderOptimizationEnabled)
			.putBoolean(prefix + UPSCALE, profile.isUpscaleEnabled)
			.putBoolean(prefix + COLOR_32BIT, profile.is32BitColorsEnabled)
			.putBoolean(prefix + PAGE_NUMBERS, profile.isPagesNumbersEnabled)
			.putBoolean(prefix + CROP_STANDARD, profile.isPagesCropEnabledStandard)
			.putBoolean(prefix + CROP_WEBTOON, profile.isPagesCropEnabledWebtoon)
			.apply()
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
