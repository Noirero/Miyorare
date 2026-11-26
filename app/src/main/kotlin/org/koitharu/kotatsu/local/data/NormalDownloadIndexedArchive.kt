package org.koitharu.kotatsu.local.data

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.local.data.index.DownloadPathAlias
import org.koitharu.kotatsu.local.data.output.LocalMangaOutput
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.sources.compat.SourceAliasRegistry
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.zip.ZipFile

/** Remote identity and actual embedded Reader selector are deliberately separate. Not authority. */
internal data class NormalMigratedArchiveIdentity(
	val physical: LocalManga,
	val selectorsByRemoteId: Map<Long, MangaChapter>,
)

/**
 * Read-only proof for the current/legacy flat embedded-index CBZ layout. No page decoding,
 * cover extraction, repair, or full-content hash. The caller proves active scope/ownership.
 * Unreadable or unsupported archives remain unresolved, never observed absence.
 */
internal object NormalDownloadIndexedArchive {
	private const val MAX_INDEX_BYTES = 4 * 1024 * 1024
	private const val MAX_ENTRIES = 200_000
	private val encodedPrefix = Regex("-?[0-9]+_[0-9]+")

	suspend fun reconcile(remote: Manga, candidate: File): LocalManga? =
		reconcileArchive(remote, candidate)?.physical

	/** Caller must independently prove exact committed Normal ownership and active containment. */
	suspend fun reconcileMigrated(
		remote: Manga, candidate: File, alias: DownloadPathAlias, sourceAliases: SourceAliasRegistry,
	): NormalMigratedArchiveIdentity? = reconcileArchive(remote, candidate, alias, sourceAliases)

	private suspend fun reconcileArchive(
		remote: Manga, candidate: File, migration: DownloadPathAlias? = null,
		sourceAliases: SourceAliasRegistry? = null,
	): NormalMigratedArchiveIdentity? = withContext(Dispatchers.IO) {
		runCatchingCancellable {
			val file = candidate.canonicalFile
			if (file != candidate.absoluteFile.normalize() || !file.isFile || !file.canRead() ||
				!(file.extension.equals("cbz", true) || file.extension.equals("zip", true))
			) return@runCatchingCancellable null
			// The actual root replacement helper can expose the final name during a copy.
			// Never invoke its recovery branch during discovery, even for backup-only state.
			if (hasStaging(file)) return@runCatchingCancellable null
			val length = file.length()
			val modified = file.lastModified()
			val projection = ZipFile(file).use { zip ->
				if (zip.size() > MAX_ENTRIES) return@runCatchingCancellable null
				val indexEntry = zip.getEntry(LocalMangaOutput.ENTRY_NAME_INDEX)
					?: return@runCatchingCancellable null
				if (indexEntry.isDirectory || indexEntry.size !in 1L..MAX_INDEX_BYTES.toLong()) {
					return@runCatchingCancellable null
				}
				val text = zip.getInputStream(indexEntry).use { input ->
					val out = ByteArrayOutputStream()
					val buffer = ByteArray(8192)
					while (true) {
						currentCoroutineContext().ensureActive()
						val read = input.read(buffer)
						if (read < 0) break
						if (out.size() + read > MAX_INDEX_BYTES) return@runCatchingCancellable null
						out.write(buffer, 0, read)
					}
					out.toString(Charsets.UTF_8.name())
				}
				val index = MangaIndex(text)
				val info = index.getMangaInfo() ?: return@runCatchingCancellable null
				if (info.url != remote.url) return@runCatchingCancellable null
				if (migration == null) {
					if (info.id != remote.id || info.source.name != remote.source.name) return@runCatchingCancellable null
				} else if (migration.path != file.path || migration.localMangaId != info.id ||
					info.id == remote.id || sourceAliases == null ||
					!sourceAliases.areAliases(info.source.name, remote.source.name)
				) return@runCatchingCancellable null
				val stored = info.chapters.orEmpty()
				if (stored.isEmpty() || stored.map { it.id }.toSet().size != stored.size) return@runCatchingCancellable null
				val current = remote.chapters.orEmpty()
				val remoteById = current.associateBy { it.id }
				if (remoteById.size != current.size) return@runCatchingCancellable null
				val redirects = migration?.chapterIds.orEmpty()
				if (migration != null) {
					if (redirects.isEmpty() || redirects.values.toSet().size != redirects.size) return@runCatchingCancellable null
					val oldById = stored.associateBy { it.id }
					for ((oldId, newId) in redirects) {
						val old = oldById[oldId] ?: return@runCatchingCancellable null
						val target = remoteById[newId] ?: return@runCatchingCancellable null
						if (old.url.isBlank() || old.url != target.url ||
							old.source.name != info.source.name || target.source.name != remote.source.name
						) return@runCatchingCancellable null
					}
					// An unmapped old ID must never impersonate a current chapter.
					if (stored.any { it.id !in redirects && it.id in remoteById }) return@runCatchingCancellable null
				}
				// Restrict stored regex to the exact producer grammar. Do not execute arbitrary
				// regular expressions from an untrusted archive. Single chapter additionally
				// allows the numeric page names supported by MangaIndex.getChapterNamesPattern.
				val prefixes = HashMap<String, Long>()
				for (chapter in stored) {
					if (index.getChapterFileName(chapter.id) != null) return@runCatchingCancellable null
					if (migration == null && remoteById[chapter.id]?.let { it.url != chapter.url } == true) return@runCatchingCancellable null
					val encoded = index.getStoredChapterEntriesPattern(chapter.id) ?: return@runCatchingCancellable null
					if (!encoded.endsWith("\\d{4}")) return@runCatchingCancellable null
					val prefix = encoded.removeSuffix("\\d{4}")
					if (!encodedPrefix.matches(prefix) || prefixes.put(prefix, chapter.id) != null) {
						return@runCatchingCancellable null
					}
				}
				val seen = HashSet<String>()
				val present = HashSet<Long>()
				val cover = index.getCoverEntry()
				val entries = zip.entries()
				while (entries.hasMoreElements()) {
					currentCoroutineContext().ensureActive()
					val entry = entries.nextElement()
					if (!seen.add(entry.name)) return@runCatchingCancellable null
					if (entry.isDirectory || entry.name == LocalMangaOutput.ENTRY_NAME_INDEX || entry.name == cover) continue
					// This checkpoint supports flat writer entries; other legacy layouts need
					// their own evidence. Only central-directory metadata is inspected.
					if ('/' in entry.name || '\\' in entry.name) return@runCatchingCancellable null
					val stem = entry.name.substringBefore('.')
					val id = if (stored.size == 1 && stem.isNotEmpty() && stem.all { it in '0'..'9' }) {
						stored.single().id
					} else {
						if (stem.length <= 4 || !stem.takeLast(4).all { it in '0'..'9' }) return@runCatchingCancellable null
						prefixes[stem.dropLast(4)] ?: return@runCatchingCancellable null
					}
					present += id
				}
				if (present.size != stored.size) return@runCatchingCancellable null
				val uri = Uri.fromFile(file).toString()
				val localChapters = stored.map {
					// Reader's MangaIndex lookup uses the embedded chapter ID, not the
					// migrated remote ID. Preserve the actual selector and existing URI.
					(if (migration == null) remoteById[it.id] ?: it else it).copy(url = uri, source = LocalMangaSource)
				}
				val physical = LocalManga(remote.copy(url = uri, publicUrl = uri, source = LocalMangaSource,
					chapters = localChapters, largeCoverUrl = null), file)
				val selectors = localChapters.mapNotNull { chapter ->
					redirects[chapter.id]?.let { it to chapter }
				}.toMap()
				NormalMigratedArchiveIdentity(physical, selectors)
			}
			if (hasStaging(file) || file.length() != length || file.lastModified() != modified) {
				return@runCatchingCancellable null
			}
			projection
		}.getOrNull()
	}

	private fun hasStaging(file: File): Boolean =
		File(file.path + LocalMangaOutput.SUFFIX_TMP).exists() ||
			File(file.path + ".bak" + LocalMangaOutput.SUFFIX_TMP).exists()
}
