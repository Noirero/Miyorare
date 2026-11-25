package org.koitharu.kotatsu.local.domain.model

import android.net.Uri
import androidx.core.net.toFile
import androidx.core.net.toUri
import org.koitharu.kotatsu.core.util.ext.contains
import org.koitharu.kotatsu.core.util.ext.creationTime
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.util.longHashCode
import java.io.File

data class LocalManga(
	val manga: Manga,
	val file: File = manga.url.toUri().toFile(),
	val exhentaiContentVersion: ExhentaiContentVersion? = null,
) {

	var createdAt: Long = -1L
		private set
		get() {
			if (field == -1L) {
				field = file.creationTime
			}
			return field
		}

	fun toUri(): Uri = manga.url.toUri()

	fun isMatchesQuery(query: String): Boolean {
		return manga.title.contains(query, ignoreCase = true) ||
			manga.altTitles.contains(query, ignoreCase = true) ||
			manga.authors.contains(query, ignoreCase = true)
	}

	fun containsTags(tags: Collection<String>): Boolean {
		return tags.all { tag -> tag in manga.tags }
	}

	fun containsAnyTag(tags: Collection<String>): Boolean {
		return tags.any { tag -> tag in manga.tags }
	}

	private operator fun Collection<MangaTag>.contains(title: String): Boolean {
		return any { it.title.equals(title, ignoreCase = true) }
	}

	override fun toString(): String {
		return "LocalManga(${file.path}: ${manga.title})"
	}
}

/** Read-only projection metadata for a single selected Main/Gekko CBZ. */
data class ExhentaiContentVersion(
	val logicalChapterId: Long,
	val chapterId: Long,
	val path: String,
	val sha256: String,
) {
	fun pageId(chapterId: Long, originalPageId: Long): Long =
		if (chapterId == this.chapterId && chapterId != logicalChapterId) {
			"exhentai-version-page:$chapterId:$originalPageId".longHashCode() and Long.MAX_VALUE
		} else originalPageId
}
