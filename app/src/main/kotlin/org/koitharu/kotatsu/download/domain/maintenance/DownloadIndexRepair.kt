package org.koitharu.kotatsu.download.domain.maintenance

import android.util.AtomicFile
import androidx.core.net.toUri
import org.koitharu.kotatsu.local.data.MangaIndex
import org.koitharu.kotatsu.local.data.LegacySplitChapterCompat
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import java.io.File

/** Plans a complete replacement before opening AtomicFile. Chapter artifacts are never written. */
internal object DownloadIndexRepair {
	data class Plan(val content: String?, val complete: Boolean, val recognized: Int, val total: Int)

	fun restoreValidMappings(root: File, index: MangaIndex?, physical: LocalManga): LocalManga {
		if (index == null) return physical
		val info = index.getMangaInfo() ?: return physical
		val byPath = info.chapters.orEmpty().mapNotNull { chapter ->
			index.getChapterFileName(chapter.id)?.let { safeChild(root, it) }?.takeIf { it.exists() }?.let { it.canonicalPath to chapter }
		}.groupBy({ it.first }, { it.second })
		return physical.copy(manga = physical.manga.copy(id = info.id, chapters = physical.manga.chapters.orEmpty().map { chapter ->
			val path = artifact(root, chapter)?.canonicalPath
			byPath[path]?.singleOrNull()?.copy(url = chapter.url, source = chapter.source) ?: chapter
		}))
	}

	fun plan(root: File, oldText: String?, identity: Manga, physical: LocalManga, linked: LocalManga): Plan {
		val physicalChapters = physical.manga.chapters.orEmpty()
		val linkedChapters = linked.manga.chapters.orEmpty()
		val old = oldText?.let { runCatching { MangaIndex(it) }.getOrNull() }
		val oldInfo = old?.getMangaInfo()
		val oldPaths = oldInfo?.chapters.orEmpty().mapNotNull { old?.getChapterFileName(it.id) }
		if (oldPaths.distinct().size != oldPaths.size) return Plan(null, false, 0, physicalChapters.size)
		val oldByPath = oldInfo?.chapters.orEmpty().mapNotNull { chapter ->
			old?.getChapterFileName(chapter.id)?.let { safeChild(root, it) }?.takeIf { it.exists() }?.let { it.canonicalPath to chapter }
		}.toMap()
		val remoteIds = identity.chapters.orEmpty().mapTo(HashSet()) { it.id }
		var recognized = 0
		var incomplete = false
		val entries = LinkedHashMap<Long, Pair<MangaChapter, String>>()
		for (chapter in linkedChapters) {
			// Split chapters are a virtual multi-artifact mapping. They stay sidecar-free; never encode a
			// composite URL as a single file field in a legacy index.
			if (LegacySplitChapterCompat.componentUrls(chapter.url) != null) return Plan(null, false, recognized, physicalChapters.size)
			val file = artifact(root, chapter)
			if (file == null) { incomplete = true; continue }
			val retained = oldByPath[file.canonicalPath]
			val mapped = retained ?: chapter.takeIf { it.id in remoteIds }
			if (mapped == null || entries.containsKey(mapped.id)) { incomplete = true; continue }
			recognized++
			entries[mapped.id] = mapped to file.relativeTo(root).path
		}
		// A parser view is not evidence that all physical files are represented. Check independently,
		// including unsupported extras, before allowing the replacement to become authoritative.
		val represented = entries.values.map { safeChild(root, it.second)?.canonicalPath }.toSet()
		val artifacts = root.listFiles() ?: return Plan(null, false, recognized, physicalChapters.size)
		val unrepresented = artifacts.any { file ->
			!file.isHidden && file.name != "index.json" && file.name != "index.json.bak" && file.name != "index.json.new" &&
				file != old?.getCoverEntry()?.let { safeChild(root, it) } &&
				file.extension.lowercase() !in setOf("jpg", "jpeg", "png", "webp", "gif") &&
				file.canonicalPath !in represented
		}
		if (incomplete || unrepresented || entries.isEmpty() || linkedChapters.size != physicalChapters.size) return Plan(null, false, recognized, physicalChapters.size)
		val oldMappings = oldInfo?.chapters.orEmpty().associate { it.id to old?.getChapterFileName(it.id) }
		val mappings = entries.mapValues { it.value.second }
		val oldCoverEntry = old?.getCoverEntry()
		val oldCoverIsValid = oldCoverEntry?.let { safeChild(root, it)?.isFile == true } == true
		val fallbackCoverEntry = listOf("cover.jpg", "cover.jpeg", "cover.png", "cover.webp", "cover.gif")
			.firstOrNull { safeChild(root, it)?.isFile == true }
		val coverNeedsRepair = when {
			oldInfo == null -> false
			oldCoverEntry != null -> !oldCoverIsValid
			else -> fallbackCoverEntry != null
		}
		if (oldInfo != null && oldMappings == mappings && !coverNeedsRepair) return Plan(null, true, recognized, physicalChapters.size)
		// An invalid legacy cover_entry must never be copied into a replacement index. Reconstruct from
		// validated manga metadata instead; chapter mappings are re-added below and physical files stay untouched.
		val result = when {
			oldInfo == null -> MangaIndex(null).apply { setMangaInfo(identity) }
			oldCoverEntry != null && !oldCoverIsValid && fallbackCoverEntry == null -> MangaIndex(null).apply { setMangaInfo(oldInfo) }
			else -> MangaIndex(requireNotNull(oldText))
		}
		oldInfo?.chapters.orEmpty().forEach { result.removeChapter(it.id) }
		entries.values.forEachIndexed { index, (chapter, path) -> result.addChapter(IndexedValue(index, chapter), path) }
		when {
			oldCoverIsValid -> Unit
			fallbackCoverEntry != null -> result.setCoverEntry(fallbackCoverEntry)
		}
		return Plan(result.toString(), true, recognized, physicalChapters.size)
	}

	fun write(file: File, content: String, beforeCommit: () -> Unit = {}) {
		val atomic = AtomicFile(file)
		val stream = atomic.startWrite()
		try {
			stream.write(content.toByteArray(Charsets.UTF_8))
			beforeCommit()
			atomic.finishWrite(stream)
		} catch (error: Throwable) {
			atomic.failWrite(stream)
			throw error
		}
	}

	fun artifact(root: File, chapter: MangaChapter): File? {
		val uri = chapter.url.toUri()
		val base = uri.path?.let(::File) ?: return null
		val file = if (base.isDirectory && !uri.fragment.isNullOrBlank()) safeChild(base, uri.fragment!!) ?: return null else base
		return file.takeIf { it.exists() && (it.canonicalPath == root.canonicalPath || it.canonicalPath.startsWith(root.canonicalPath + File.separator)) }
	}

	private fun safeChild(root: File, relative: String): File? {
		if (File(relative).isAbsolute) return null
		val file = File(root, relative)
		return file.takeIf { it.canonicalPath.startsWith(root.canonicalPath + File.separator) }
	}
}
