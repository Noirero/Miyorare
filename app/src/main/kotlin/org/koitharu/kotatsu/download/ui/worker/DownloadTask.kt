package org.koitharu.kotatsu.download.ui.worker

import android.os.Parcelable
import androidx.work.Data
import kotlinx.parcelize.Parcelize
import org.koitharu.kotatsu.core.prefs.DownloadFormat
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.parsers.util.find
import java.io.File
import java.security.MessageDigest

@Parcelize
class DownloadTask(
	val mangaId: Long,
	val isPaused: Boolean,
	val isSilent: Boolean,
	val chaptersIds: LongArray?,
	val destination: File?,
	val format: DownloadFormat?,
	val allowMeteredNetwork: Boolean,
	/** Logical library space that initiated the download. Existing callers remain NORMAL by default. */
	val favouriteSpace: FavouriteSpace = FavouriteSpace.NORMAL,
	/** Explicit ExHentai chapter filename policy for this queued request, not a global setting. */
	val appendGalleryIdToChapterFile: Boolean = false,
	/** Explicit ExHentai request to write a separate copy instead of reusing a matching download. */
	val downloadSeparateCopy: Boolean = false,
) : Parcelable {

	constructor(data: Data) : this(
		mangaId = data.getLong(MANGA_ID, 0L),
		isPaused = data.getBoolean(START_PAUSED, false),
		isSilent = data.getBoolean(IS_SILENT, false),
		chaptersIds = data.getLongArray(CHAPTERS)?.takeUnless(LongArray::isEmpty),
		destination = data.getString(DESTINATION)?.let { File(it) },
		format = data.getString(FORMAT)?.let { DownloadFormat.entries.find(it) },
		allowMeteredNetwork = data.getBoolean(ALLOW_METERED, true),
		favouriteSpace = FavouriteSpace.fromArgument(data.getInt(FAVOURITE_SPACE, FavouriteSpace.NORMAL.dbValue)),
		appendGalleryIdToChapterFile = data.getBoolean(APPEND_GALLERY_ID, false),
		downloadSeparateCopy = data.getBoolean(SEPARATE_COPY, false),
	)

	fun toData(): Data = Data.Builder()
		.putLong(MANGA_ID, mangaId)
		.putBoolean(START_PAUSED, isPaused)
		.putBoolean(IS_SILENT, isSilent)
		.putLongArray(CHAPTERS, chaptersIds ?: LongArray(0))
		.putString(DESTINATION, destination?.path)
		.putString(FORMAT, format?.name)
		.putBoolean(ALLOW_METERED, allowMeteredNetwork)
		.putInt(FAVOURITE_SPACE, favouriteSpace.dbValue)
		.putBoolean(APPEND_GALLERY_ID, appendGalleryIdToChapterFile)
		.putBoolean(SEPARATE_COPY, downloadSeparateCopy)
		.build()

	/**
	 * Stable identity for equivalent physical download work.
	 *
	 * WorkManager uses this key with ExistingWorkPolicy.KEEP, making scheduler-level dedup atomic.
	 * Chapter order is normalized so the same subset cannot bypass dedup by arriving in a different
	 * order. Execution-only flags (pause/silent/network constraint) intentionally do not create a
	 * second physical download for the same manga/chapter/destination/format/space.
	 */
	internal fun uniqueWorkName(): String {
		val semanticKey = buildString {
			append(mangaId)
			append('|')
			append(favouriteSpace.dbValue)
			append('|')
			append(destination?.canonicalOrAbsolute().orEmpty())
			append('|')
			append(format?.name.orEmpty())
			append('|')
			val chapters = chaptersIds
			if (chapters == null) {
				append('*')
			} else {
				chapters.distinct().sorted().joinTo(this, separator = ",")
			}
			// Preserve the existing key for tasks already enqueued before this option existed.
			if (appendGalleryIdToChapterFile) append("|gallery-id-filename")
			if (downloadSeparateCopy) append("|separate-copy")
		}
		val digest = MessageDigest.getInstance("SHA-256").digest(semanticKey.toByteArray(Charsets.UTF_8))
		return buildString(UNIQUE_WORK_PREFIX.length + digest.size * 2) {
			append(UNIQUE_WORK_PREFIX)
			for (byte in digest) append("%02x".format(byte.toInt() and 0xff))
		}
	}

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (javaClass != other?.javaClass) return false

		other as DownloadTask

		if (mangaId != other.mangaId) return false
		if (isPaused != other.isPaused) return false
		if (isSilent != other.isSilent) return false
		if (!(chaptersIds contentEquals other.chaptersIds)) return false
		if (destination != other.destination) return false
		if (format != other.format) return false
		if (allowMeteredNetwork != other.allowMeteredNetwork) return false
		if (favouriteSpace != other.favouriteSpace) return false
		if (appendGalleryIdToChapterFile != other.appendGalleryIdToChapterFile) return false
		if (downloadSeparateCopy != other.downloadSeparateCopy) return false

		return true
	}

	override fun hashCode(): Int {
		var result = mangaId.hashCode()
		result = 31 * result + isPaused.hashCode()
		result = 31 * result + isSilent.hashCode()
		result = 31 * result + (chaptersIds?.contentHashCode() ?: 0)
		result = 31 * result + (destination?.hashCode() ?: 0)
		result = 31 * result + (format?.hashCode() ?: 0)
		result = 31 * result + allowMeteredNetwork.hashCode()
		result = 31 * result + favouriteSpace.hashCode()
		result = 31 * result + appendGalleryIdToChapterFile.hashCode()
		result = 31 * result + downloadSeparateCopy.hashCode()
		return result
	}

	private fun File.canonicalOrAbsolute(): String = runCatching { canonicalPath }.getOrDefault(absolutePath)

	private companion object {
		const val UNIQUE_WORK_PREFIX = "download:"
		const val MANGA_ID = "manga_id"
		const val IS_SILENT = "silent"
		const val START_PAUSED = "paused"
		const val CHAPTERS = "chapters"
		const val DESTINATION = "dest"
		const val FORMAT = "format"
		const val ALLOW_METERED = "metered"
		const val FAVOURITE_SPACE = "favourite_space"
		const val APPEND_GALLERY_ID = "exhentai_gallery_id_filename"
		const val SEPARATE_COPY = "exhentai_separate_copy"
	}
}
