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

/** Filesystem eligibility must be checked off the main thread and again before mutation. */
internal fun canRenameStandaloneChapterFile(root: File, file: File): Boolean = runCatching {
	file.parentFile == root && file.extension.equals("cbz", ignoreCase = true) &&
		file.isFile && !Files.isSymbolicLink(file.toPath()) &&
		file.canonicalFile.parentFile == root.canonicalFile &&
		!File(root, LocalMangaOutput.ENTRY_NAME_INDEX).exists()
}.getOrDefault(false)

internal fun renameStandaloneChapterFile(root: File, file: File, input: String): Boolean {
	val stem = requireNotNull(chapterDownloadStem(input)) { "Invalid chapter file name" }
	check(canRenameStandaloneChapterFile(root, file)) { "Only standalone chapter downloads can be renamed" }
	val destination = File(root, "$stem.${file.extension}")
	if (destination == file) return false
	val entries = checkNotNull(root.listFiles()) { "Unable to read download directory" }
	if (entries.any { it.name.equals(destination.name, ignoreCase = true) ||
			it.name.equals(destination.name + LocalMangaOutput.SUFFIX_TMP, ignoreCase = true)
		}
	) throw FileAlreadyExistsException(destination.name)
	Files.move(file.toPath(), destination.toPath())
	return true
}
