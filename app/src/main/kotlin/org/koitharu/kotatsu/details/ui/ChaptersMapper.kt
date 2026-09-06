package org.koitharu.kotatsu.details.ui

import android.content.Context
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.bookmarks.domain.Bookmark
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.details.ui.model.ChapterListItem
import org.koitharu.kotatsu.details.ui.model.toListItem
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.MissingChapters
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.util.mapToSet

fun MangaDetails.mapChapters(
	currentChapterId: Long,
	newCount: Int,
	branch: String?,
	bookmarks: List<Bookmark>,
	isGrid: Boolean,
	isDownloadedOnly: Boolean,
	readOverrides: Map<Long, Boolean> = emptyMap(),
): List<ChapterListItem> {
	// Some third-party sources occasionally return the exact same chapter more than once.
	// Remove only exact chapter identities here: different chapters that merely collide on `id`
	// must remain visible and are handled by a collision-safe Compose key in the details screen.
	val remoteChapters = chapters[branch].orEmpty().distinctBy { it.mappingIdentity() }
	val localChapters = local?.manga?.getChapters(branch).orEmpty().distinctBy { it.mappingIdentity() }
	if (remoteChapters.isEmpty() && localChapters.isEmpty()) {
		return emptyList()
	}
	val bookmarked = bookmarks.mapToSet { it.chapterId }
	val newFrom = if (newCount == 0 || remoteChapters.isEmpty()) Int.MAX_VALUE else remoteChapters.size - newCount
	val ids = buildSet(maxOf(remoteChapters.size, localChapters.size)) {
		remoteChapters.mapTo(this) { it.id }
		localChapters.mapTo(this) { it.id }
	}
	val result = ArrayList<ChapterListItem>(ids.size)
	val localMap = if (localChapters.isNotEmpty()) {
		localChapters.associateByTo(LinkedHashMap(localChapters.size)) { it.id }
	} else {
		null
	}
	var isUnread = currentChapterId !in ids
	if (!isDownloadedOnly || local?.manga?.chapters == null) {
		for (chapter in remoteChapters) {
			// CBZ-only downloads intentionally have no index.json. Their locally parsed chapter IDs are
			// therefore not guaranteed to equal the remote source IDs. Match by ID first, then by the
			// chapter's visible/download-file identity so an existing CBZ is recognised as downloaded.
			val local = localMap?.remove(chapter.id) ?: localMap?.findAndRemoveEquivalent(chapter)
			val isCurrent = chapter.id == currentChapterId
			// Swipe actions operate on the chapter object shown by the adapter. For downloaded chapters
			// that object may be the local chapter and have a different ID from the remote source chapter.
			// Accept an override saved under either identity so the visual read state always follows the swipe.
			val readOverride = readOverrides[local?.id] ?: readOverrides[chapter.id]
			result += (local ?: chapter).toListItem(
				isCurrent = isCurrent,
				isUnread = readOverride?.not() ?: (isUnread && !isCurrent),
				isNew = !isCurrent && isUnread && result.size >= newFrom,
				isDownloaded = local != null,
				isBookmarked = chapter.id in bookmarked,
				isGrid = isGrid,
			)
			if (isCurrent) {
				isUnread = true
			}
		}
	}
	if (!localMap.isNullOrEmpty()) {
		for (chapter in localMap.values) {
			val isCurrent = chapter.id == currentChapterId
			result += chapter.toListItem(
				isCurrent = isCurrent,
				isUnread = readOverrides[chapter.id]?.not() ?: (isUnread && !isCurrent),
				isNew = false,
				isDownloaded = !isLocal,
				isBookmarked = chapter.id in bookmarked,
				isGrid = isGrid,
			)
			if (isCurrent) {
				isUnread = true
			}
		}
	}
	return result
}

private data class ChapterMappingIdentity(
	val id: Long,
	val url: String,
	val title: String?,
	val number: Float,
	val volume: Int,
	val scanlator: String?,
	val uploadDate: Long,
	val branch: String?,
)

private fun MangaChapter.mappingIdentity() = ChapterMappingIdentity(
	id = id,
	url = url,
	title = title,
	number = number,
	volume = volume,
	scanlator = scanlator,
	uploadDate = uploadDate,
	branch = branch,
)

private fun MutableMap<Long, MangaChapter>.findAndRemoveEquivalent(remote: MangaChapter): MangaChapter? {
	val entry = entries.firstOrNull { (_, local) -> local.isEquivalentDownloadOf(remote) } ?: return null
	remove(entry.key)
	return entry.value
}

private fun MangaChapter.isEquivalentDownloadOf(other: MangaChapter): Boolean {
	// Chapter numbers are the most stable cross-parser identity. Keep volume in the comparison when
	// both sides expose one so chapter 1 of two different volumes cannot be merged accidentally.
	if (number >= 0f && other.number >= 0f && kotlin.math.abs(number - other.number) < 0.0001f) {
		if (volume <= 0 || other.volume <= 0 || volume == other.volume) return true
	}

	val thisTitle = title.normalizedChapterTitle()
	val otherTitle = other.title.normalizedChapterTitle()
	if (thisTitle.isNotEmpty() && thisTitle == otherTitle) return true

	// Downloads whose remote title is only "Chapter" are saved using the scanlator/group name,
	// e.g. "nounanka, nounanka sedai_Chapter.cbz". Without index.json the local parser derives its
	// title from that filename, so compare against the exact generated visible identity as well.
	val thisDownloadTitle = generatedDownloadTitle().normalizedChapterTitle()
	val otherDownloadTitle = other.generatedDownloadTitle().normalizedChapterTitle()
	return thisDownloadTitle.isNotEmpty() && thisDownloadTitle == otherDownloadTitle
}

private fun MangaChapter.generatedDownloadTitle(): String {
	val rawTitle = title?.trim().orEmpty()
	val group = scanlator?.trim().orEmpty()
	return when {
		rawTitle.isEmpty() && group.isNotEmpty() -> "${group}_Chapter"
		rawTitle.equals("Chapter", ignoreCase = true) && group.isNotEmpty() -> "${group}_Chapter"
		else -> rawTitle
	}
}

private fun String?.normalizedChapterTitle(): String = this
	.orEmpty()
	.lowercase()
	.replace(Regex("[^\\p{L}\\p{N}]+"), " ")
	.trim()

fun List<ChapterListItem>.withVolumeHeaders(
	context: Context,
	showMissingChapters: Boolean,
): MutableList<ListModel> {
	var prevVolume = 0
	val result = ArrayList<ListModel>((size * 1.4).toInt())
	for (i in indices) {
		val item = this[i]
		val chapter = item.chapter
		if (chapter.volume != prevVolume) {
			val text = if (chapter.volume == 0) {
				context.getString(R.string.volume_unknown)
			} else {
				context.getString(R.string.volume_, chapter.volume)
			}
			result.add(ListHeader(text))
			prevVolume = chapter.volume
		}
		result.add(item)

		if (showMissingChapters) {
			val nextItem = getOrNull(i + 1)
			if (nextItem != null) {
				val gap = calculateChapterGap(chapter.number, nextItem.chapter.number)
				if (gap > 0) {
					result.add(MissingChapters(id = "missing-${chapter.id}-${nextItem.chapter.id}", count = gap))
				}
			}
		}
	}
	if (showMissingChapters) {
		val trailingGap = calculateTrailingGap()
		if (trailingGap > 0) {
			val last = lastOrNull() ?: return result
			result.add(MissingChapters(id = "missing-start-${last.chapter.id}", count = trailingGap))
		}
	}
	return result
}

private fun calculateChapterGap(num1: Float, num2: Float): Int {
	if (num1 < 0f || num2 < 0f) return 0
	val higher = maxOf(num1, num2)
	val lower = minOf(num1, num2)
	return kotlin.math.floor(higher).toInt() - kotlin.math.floor(lower).toInt() - 1
}

// Chapters the user is missing from the beginning of the manga (e.g. their list starts at
// chapter 43 of a 1-indexed series → 42 missing). The `insertSeparators`-style footer row sits
// after the last chapter in Mihon's layout, so we mirror that with a count derived from whichever
// end of the ordered list holds the lowest chapter number.
private fun List<ChapterListItem>.calculateTrailingGap(): Int {
	if (isEmpty()) return 0
	val first = first().chapter.number
	val last = last().chapter.number
	if (first < 0f || last < 0f) return 0
	val lowest = kotlin.math.floor(minOf(first, last)).toInt()
	return (lowest - 1).coerceAtLeast(0)
}
