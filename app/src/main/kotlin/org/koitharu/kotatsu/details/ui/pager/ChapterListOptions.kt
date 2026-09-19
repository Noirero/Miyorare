package org.koitharu.kotatsu.details.ui.pager

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import javax.inject.Inject
import javax.inject.Singleton

enum class ChapterSortMode {
	SOURCE,
	NUMBER,
	UPLOAD_DATE,
	ALPHABETICAL,
}

enum class ChapterTitleMode {
	SOURCE,
	NUMBER,
}

data class ChapterListOptions(
	val downloadedOnly: Boolean = false,
	val unreadOnly: Boolean = false,
	val bookmarkedOnly: Boolean = false,
	val newOnly: Boolean = false,
	val sortMode: ChapterSortMode = ChapterSortMode.SOURCE,
	val descending: Boolean = false,
	val titleMode: ChapterTitleMode = ChapterTitleMode.SOURCE,
	val grid: Boolean = false,
) {
	val hasStatusFilter: Boolean
		get() = downloadedOnly || unreadOnly || bookmarkedOnly || newOnly
}

/**
 * Stores the user's Details chapter defaults separately for Manga/Novel and Normal/Private.
 *
 * Branch/scanlator selection is intentionally not persisted here because branch names are specific
 * to one title/source. Reusing such a branch as a global default would make unrelated titles appear
 * empty.
 */
@Singleton
class ChapterListOptionsStore @Inject constructor(
	@ApplicationContext context: Context,
	private val appSettings: AppSettings,
) {

	private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

	fun getDefault(space: FavouriteSpace, isNovel: Boolean): ChapterListOptions {
		val raw = prefs.getString(key(space, isNovel), null)
		if (raw.isNullOrBlank()) {
			// Preserve the user's existing chapter direction/layout on first upgrade.
			return ChapterListOptions(
				descending = appSettings.isChaptersReverse,
				grid = appSettings.isChaptersGridView,
			)
		}
		return decode(raw) ?: ChapterListOptions(
			descending = appSettings.isChaptersReverse,
			grid = appSettings.isChaptersGridView,
		)
	}

	fun setDefault(space: FavouriteSpace, isNovel: Boolean, options: ChapterListOptions) {
		prefs.edit {
			putString(key(space, isNovel), encode(options))
		}
	}

	private fun key(space: FavouriteSpace, isNovel: Boolean): String {
		val content = if (isNovel) "novel" else "manga"
		val workspace = if (space == FavouriteSpace.PRIVATE) "private" else "normal"
		return "details_chapter_options_v1_${content}_${workspace}"
	}

	private fun encode(options: ChapterListOptions): String = buildString {
		append(VERSION)
		append('|').append(options.downloadedOnly.toDigit())
		append('|').append(options.unreadOnly.toDigit())
		append('|').append(options.bookmarkedOnly.toDigit())
		append('|').append(options.newOnly.toDigit())
		append('|').append(options.sortMode.name)
		append('|').append(options.descending.toDigit())
		append('|').append(options.titleMode.name)
		append('|').append(options.grid.toDigit())
	}

	private fun decode(raw: String): ChapterListOptions? {
		val parts = raw.split('|')
		if (parts.size != 9 || parts[0] != VERSION) return null
		return runCatching {
			ChapterListOptions(
				downloadedOnly = parts[1].toBooleanDigit(),
				unreadOnly = parts[2].toBooleanDigit(),
				bookmarkedOnly = parts[3].toBooleanDigit(),
				newOnly = parts[4].toBooleanDigit(),
				sortMode = enumValueOf(parts[5]),
				descending = parts[6].toBooleanDigit(),
				titleMode = enumValueOf(parts[7]),
				grid = parts[8].toBooleanDigit(),
			)
		}.getOrNull()
	}

	private fun Boolean.toDigit(): Char = if (this) '1' else '0'

	private fun String.toBooleanDigit(): Boolean = when (this) {
		"1" -> true
		"0" -> false
		else -> error("Invalid boolean digit")
	}

	private companion object {
		private const val VERSION = "1"
	}
}
