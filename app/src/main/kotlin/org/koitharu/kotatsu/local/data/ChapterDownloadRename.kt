package org.koitharu.kotatsu.local.data

import androidx.core.net.toUri
import org.koitharu.kotatsu.local.data.output.LocalMangaOutput
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files

/** Both the fast matcher and the fallback parser can supply a standalone chapter URL. */
internal fun chapterDownloadFile(root: File, url: String): File? {
	val uri = url.toUri()
	if (uri.scheme != "file" || !uri.authority.isNullOrEmpty() || uri.query != null) return null
	val path = uri.path?.let(::File) ?: return null
	val fragment = uri.fragment
	val file = if (fragment == null) path else {
		if (path != root || '/' in fragment || '\\' in fragment) return null
		File(root, fragment)
	}
	return file.takeIf { it.parentFile == root && it.extension.equals("cbz", ignoreCase = true) }
}

internal fun chapterDownloadStem(input: String): String? {
	val trimmed = input.trim()
	val stem = if (trimmed.endsWith(".cbz", ignoreCase = true)) trimmed.dropLast(4).trimEnd() else trimmed
	return stem.takeIf {
		it.isNotEmpty() && !it.startsWith('.') && !it.endsWith('.') &&
			it.none { ch -> ch in "\\/:*?\"<>|" || ch.isISOControl() } &&
			it.toByteArray(Charsets.UTF_8).size <= 251
	}
}

internal fun renameStandaloneChapterFile(root: File, file: File, input: String) {
	val stem = requireNotNull(chapterDownloadStem(input)) { "Invalid chapter file name" }
	check(file.parentFile == root && file.canonicalFile.parentFile == root.canonicalFile &&
		!Files.isSymbolicLink(file.toPath()) && file.isFile && file.extension.equals("cbz", ignoreCase = true)
	) { "Only standalone chapter downloads can be renamed" }
	check(!File(root, LocalMangaOutput.ENTRY_NAME_INDEX).exists()) { "Indexed chapter downloads cannot be renamed" }
	val destination = File(root, "$stem.${file.extension}")
	if (destination == file) return
	val entries = checkNotNull(root.listFiles()) { "Unable to read download directory" }
	if (entries.any { it.name.equals(destination.name, ignoreCase = true) ||
			it.name.equals(destination.name + ".tmp", ignoreCase = true)
		}
	) throw FileAlreadyExistsException(destination.name)
	Files.move(file.toPath(), destination.toPath())
}
