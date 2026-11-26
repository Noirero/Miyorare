package org.koitharu.kotatsu.local.data

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.local.data.output.LocalMangaOutput
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.util.longHashCode
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.io.File
import java.util.Locale

/**
 * Scanner-only reconciliation of the indexed standalone chapter layout.
 *
 * The caller must already have established the active root, scope and container identity.
 * Null means unsupported/ambiguous/incomplete, not an observed empty directory.
 * No ownership/alias updates, parser fallback, archive reads or publication here.
 */
internal object NormalDownloadDirectoryReconciler {

	/**
	 * Keep exact surviving sidecar mappings and independently represent finished extras.
	 * Unindexed canonical-name matching and verified migrated identities are separate
	 * reconciliation work; do not publish this intermediate projection as authoritative.
	 */
	suspend fun reconcileIndexed(remote: Manga, container: File): LocalManga? = withContext(Dispatchers.IO) {
		runCatchingCancellable {
			val root = container.canonicalFile
			if (!root.isDirectory || !root.canRead()) return@runCatchingCancellable null
			val entries = root.listFiles() ?: return@runCatchingCancellable null
			if (entries.any { it.name == ".notamanga" }) return@runCatchingCancellable null
			val sidecar = entries.singleOrNull { it.name == LocalMangaOutput.ENTRY_NAME_INDEX }
				?: return@runCatchingCancellable null
			if (!sidecar.isFile || sidecar.canonicalFile != File(root, sidecar.name)) return@runCatchingCancellable null
			val index = MangaIndex.read(sidecar) ?: return@runCatchingCancellable null
			val indexed = index.getMangaInfo() ?: return@runCatchingCancellable null
			if (indexed.id != remote.id || indexed.source.name != remote.source.name || indexed.url != remote.url) {
				return@runCatchingCancellable null
			}

			// One shallow listing; never interpret worker .cbz.tmp/.bak.tmp as final files.
			val artifacts = LinkedHashMap<String, File>()
			val foldedNames = HashSet<String>()
			for (file in entries) {
				if (file.name.startsWith(".")) continue
				when (file.extension.lowercase(Locale.ROOT)) {
					"epub", "pdf" -> return@runCatchingCancellable null // Other layouts have their own contracts.
					"cbz", "zip" -> Unit
					else -> continue
				}
				if (!file.isFile || file.canonicalFile != File(root, file.name)) return@runCatchingCancellable null
				if (!foldedNames.add(file.name.lowercase(Locale.ROOT))) return@runCatchingCancellable null
				artifacts[file.name] = file
			}

			val remoteChapters = remote.chapters.orEmpty()
			val remoteById = remoteChapters.associateBy { it.id }
			if (remoteById.size != remoteChapters.size) return@runCatchingCancellable null
			val indexedChapters = indexed.chapters.orEmpty()
			val reservedIds = remoteById.keys.toMutableSet()
			reservedIds.addAll(indexedChapters.map { it.id })
			val represented = HashSet<String>()
			val linked = ArrayList<MangaChapter>(artifacts.size)
			for (chapter in indexedChapters) {
				val name = index.getChapterFileName(chapter.id) ?: return@runCatchingCancellable null
				if (name != File(name).name || name.startsWith(".") ||
					File(name).extension.lowercase(Locale.ROOT) !in setOf("cbz", "zip") ||
					!represented.add(name)
				) return@runCatchingCancellable null
				val file = artifacts[name]
				if (file == null) {
					// A case-only mismatch is not proof of absence on all storage providers.
					if (name.lowercase(Locale.ROOT) in foldedNames) return@runCatchingCancellable null
					continue
				}
				val current = remoteById[chapter.id]
				// A contradictory current source locator cannot be silently re-keyed by a stale ID.
				if (current != null && current.url != chapter.url) return@runCatchingCancellable null
				linked += (current ?: chapter).copy(
					url = Uri.fromFile(file).toString(),
					source = LocalMangaSource,
				)
			}

			// A newly observed independent artifact has an ID tied to its scoped locator,
			// not its ordinal in a listing or archive metadata. Keep the existing directory
			// fragment URI representation for local-only artifacts. Verified legacy keys
			// must be reconciled separately before these provisional IDs become authoritative.
			for ((name, file) in artifacts.toSortedMap()) {
				if (name in represented) continue
				val id = "normal-download-local:${remote.id}:${file.path}".longHashCode() and Long.MAX_VALUE
				// Do not assign another chapter's ID or let listing order resolve a collision.
				if (id == 0L || !reservedIds.add(id)) return@runCatchingCancellable null
				linked += MangaChapter(
					id = id,
					title = file.nameWithoutExtension,
					number = 0f,
					volume = 0,
					source = LocalMangaSource,
					uploadDate = 0L,
					url = Uri.fromFile(root).buildUpon().fragment(name).build().toString(),
					scanlator = null,
					branch = null,
				)
			}
			val rootUri = Uri.fromFile(root).toString()
			LocalManga(
				manga = remote.copy(
					url = rootUri,
					publicUrl = rootUri,
					source = LocalMangaSource,
					chapters = linked,
					largeCoverUrl = null,
				),
				file = root,
			)
		}.getOrNull()
	}
}
