package org.koitharu.kotatsu.local.data

import org.koitharu.kotatsu.core.util.ext.withChildren
import org.koitharu.kotatsu.local.data.output.LocalMangaOutput
import java.io.File
import java.util.Locale

/** Canonical, read-only current/legacy container discovery. Used only at explicit scan boundaries. */
internal object LocalDownloadScanner {
	fun scan(roots: Collection<File>, unavailable: (File) -> Unit = {}): List<File> {
		val result = ArrayList<File>()
		for (root in roots) {
			if (!root.isDirectory || !root.canRead()) {
				unavailable(root)
				continue
			}
			try {
			root.withChildren { children ->
				children.filterNot { it.isHidden || it.shouldSkip() }.forEach { child ->
					if (child.isDirectory && child.name == LocalMangaOutput.DOWNLOADS_DIR_NAME) scanDownloadRoot(child, result)
					else scanLegacyEntry(child, result)
				}
			}
			} catch (_: java.io.IOException) { unavailable(root) }
		}
		return result.filter { file -> roots.any { root -> file.canonicalPath.startsWith(root.canonicalPath.trimEnd(File.separatorChar) + File.separator) } }.distinctBy { it.canonicalPath }
	}

	private fun scanDownloadRoot(downloads: File, result: MutableList<File>) {
		downloads.withChildren { children ->
			children.filterNot { it.isHidden || it.shouldSkip() }.forEach { child ->
				when {
					child.isDirectory && child.name == LocalMangaOutput.NOVEL_DIR_NAME -> scanNovelRoot(child, result)
					child.isDirectory && child.isDownloadSourceDirectory() -> child.withChildren { mangaDirs ->
						mangaDirs.filterNot { it.isHidden || it.shouldSkip() }.forEach(result::add)
					}
					else -> result.add(child)
				}
			}
		}
	}

	private fun scanNovelRoot(novelRoot: File, result: MutableList<File>) {
		novelRoot.withChildren { children ->
			children.filterNot { it.isHidden || it.shouldSkip() }.forEach { child ->
				if (child.isDirectory && child.isDownloadSourceDirectory()) {
					child.withChildren { novels -> novels.filterNot { it.isHidden || it.shouldSkip() }.forEach(result::add) }
				} else result.add(child)
			}
		}
	}

	private fun scanLegacyEntry(child: File, result: MutableList<File>) {
		when {
			child.isDirectory && child.name == LocalMangaOutput.NOVEL_DIR_NAME -> scanNovelRoot(child, result)
			child.isDirectory && child.isDownloadSourceDirectory() -> child.withChildren { sourceChildren ->
				sourceChildren.filterNot { it.isHidden || it.shouldSkip() }.forEach(result::add)
			}
			else -> result.add(child)
		}
	}

	private fun File.isDownloadSourceDirectory(): Boolean {
		if (File(this, LocalMangaOutput.SOURCE_DIR_MARKER).isFile) return true
		// Structural fallback keeps downloads from older Miyorare builds readable after marker renames.
		return withChildren { titles ->
			val sample = titles.filterNot { it.isHidden || it.shouldSkip() }.take(LEGACY_SOURCE_PROBE_LIMIT).toList()
			if (sample.any { it.isFile && it.isSupportedDownloadArtifact() }) return@withChildren false
			sample.any { title ->
				title.isDirectory && title.withChildren { artifacts -> artifacts.any { it.isFile && it.isSupportedDownloadArtifact() } }
			} || sample.isNotEmpty() && sample.all { it.isDirectory }
		}
	}

	private fun File.isSupportedDownloadArtifact(): Boolean = when (extension.lowercase(Locale.ROOT)) {
		"cbz", "zip", "epub", "pdf" -> true
		else -> false
	}


	private fun File.shouldSkip(): Boolean = isDirectory && File(this, ".notamanga").exists()
	private const val LEGACY_SOURCE_PROBE_LIMIT = 8
}
