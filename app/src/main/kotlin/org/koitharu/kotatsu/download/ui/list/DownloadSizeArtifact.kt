package org.koitharu.kotatsu.download.ui.list

import org.koitharu.kotatsu.local.data.output.LocalMangaOutput
import java.io.File

/** A size candidate must be a readable artifact strictly under the original downloads directory. */
internal fun downloadSizeArtifactInRoot(root: File, candidate: File?): File? {
	if (!root.isDirectory || !root.canRead()) return null
	val downloads = File(root.canonicalFile, LocalMangaOutput.DOWNLOADS_DIR_NAME)
	val file = candidate?.canonicalFile ?: return null
	return file.takeIf {
		it != downloads && it.toPath().startsWith(downloads.toPath()) &&
			(it.isFile || it.isDirectory) && it.canRead()
	}
}
