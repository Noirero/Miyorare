package org.koitharu.kotatsu.details.ui

import org.koitharu.kotatsu.core.model.isNovelContent
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.details.ui.model.ChapterListItem
import org.koitharu.kotatsu.local.data.chapterDownloadFile
import java.io.File

internal fun MangaDetails.renameableChapterFile(item: ChapterListItem): File? {
	if (isLocal || isOfficialExhentai || sourceManga.isNovelContent || !item.isDownloaded || item.isDownloading) {
		return null
	}
	return local?.file?.let { chapterDownloadFile(it, item.chapter.url) }
}
