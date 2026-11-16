package org.koitharu.kotatsu.download.ui.list

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription
import org.koitharu.kotatsu.local.domain.model.LocalManga
import java.io.File

/** Subscribe before the first lookup so an invalidation during that lookup is not missed. */
internal fun observeDownloadChapterInvalidations(
	changes: SharedFlow<LocalManga?>,
	mangaId: Long,
	roots: List<File>,
) = changes.onSubscription { emit(null) }
	.filter { change ->
		change == null || (change.manga.id == mangaId &&
			(roots.isEmpty() || roots.any { change.file.isInside(it) }))
	}
	.map { Unit }
	.conflate()

private fun File.isInside(root: java.io.File): Boolean {
	val normalizedRoot = runCatching { root.canonicalFile }.getOrDefault(root.absoluteFile)
	val normalizedFile = runCatching { canonicalFile }.getOrDefault(absoluteFile)
	return normalizedFile == normalizedRoot ||
		normalizedFile.path.startsWith(normalizedRoot.path + File.separator)
}
