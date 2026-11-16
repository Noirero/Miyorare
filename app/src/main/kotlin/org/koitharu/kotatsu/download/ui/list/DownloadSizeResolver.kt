package org.koitharu.kotatsu.download.ui.list

import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.download.ui.worker.DownloadTask
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.local.data.ExhentaiDownloadedCopy
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.parsers.model.Manga
import java.io.File
import java.util.UUID
import javax.inject.Inject

class DownloadSizeResolver @Inject constructor(
	private val database: MangaDatabase,
	private val localMangaRepository: LocalMangaRepository,
) {

	internal suspend fun findSizeArtifact(
		workId: UUID,
		manga: Manga,
		task: DownloadTask?,
		committedPath: String?,
		favouriteSpace: FavouriteSpace,
	): File? {
		// Missing legacy destination is not evidence that today's configured root was used.
		val root = task?.destination ?: return null
		if (task.mangaId != manga.id || task.favouriteSpace != favouriteSpace) return null
		val official = ExhentaiDownloadedCopy.isOfficial(manga)
		if (official && (task.downloadSeparateCopy || committedPath != null)) {
			val file = downloadSizeArtifactInRoot(root, committedPath?.let(::File)) ?: return null
			val match = localMangaRepository.findExhentaiAtPath(
				manga, file, withDetails = false, space = task.favouriteSpace, request = workId,
			)
			return file.takeIf { it.isFile && match?.verified == true && match.local.file.canonicalFile == it }
		}
		val dao = database.getFavouriteDownloadIndexDao()
		val entry = dao.findEntry(task.favouriteSpace.dbValue, manga.id) ?: return null
		val file = downloadSizeArtifactInRoot(root, File(entry.path)) ?: return null
		if (dao.findEntriesByPaths(listOf(entry.path, file.path).distinct()).any {
				it.mangaId != manga.id || it.space != task.favouriteSpace.dbValue
			}
		) return null
		if (!official) return file
		val match = localMangaRepository.findExhentaiAtPath(
			manga, file, withDetails = false, space = task.favouriteSpace,
		)
		return file.takeIf { match?.verified == true && match.local.file.canonicalFile == it }
	}
}
