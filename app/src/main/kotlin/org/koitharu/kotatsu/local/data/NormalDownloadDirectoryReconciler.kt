package org.koitharu.kotatsu.local.data

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.local.data.output.LocalMangaOutput
import org.koitharu.kotatsu.local.data.index.DownloadPathAlias
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.util.longHashCode
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.sources.compat.SourceAliasRegistry
import java.io.File
import java.util.Locale

/**
 * Scanner-only reconciliation of standalone chapter directories.
 *
 * The caller must already have established the active root, scope and container identity.
 * Null means unsupported/ambiguous/incomplete, not an observed empty directory.
 * No ownership/alias updates, parser fallback, archive reads or publication here.
 */
internal object NormalDownloadDirectoryReconciler {

	/**
	 * Keep exact surviving sidecar mappings and independently represent finished extras.
	 * Verified migrated identities and durable prior locator provenance remain separate
	 * reconciliation work; do not publish this intermediate projection as authoritative.
	 */
	suspend fun reconcileIndexed(
		remote: Manga,
		container: File,
		verifiedPrevious: Collection<MangaChapter> = emptyList(),
	): LocalManga? = reconcile(remote, container, indexedLayout = true, verifiedPrevious = verifiedPrevious)

	/**
	 * Previous local-only chapters must be independently verified against this manga, scope and physical
	 * locator. Never pass raw migration redirects or a newly re-parsed ordinal as prior identity.
	 */
	suspend fun reconcileSidecarFree(
		remote: Manga,
		container: File,
		verifiedPrevious: Collection<MangaChapter> = emptyList(),
	): LocalManga? = reconcile(remote, container, indexedLayout = false, verifiedPrevious = verifiedPrevious)

	/**
	 * Only for an independently space-verified, exact migration container. A redirect is
	 * accepted only for an indexed old ID with the same source-owned URL as its current target.
	 * Sidecar-free ordinal redirects never enter this path.
	 */
	suspend fun reconcileMigratedIndexed(
		remote: Manga,
		container: File,
		alias: DownloadPathAlias,
		sourceAliases: SourceAliasRegistry,
	): LocalManga? = reconcile(remote, container, indexedLayout = true, verifiedPrevious = emptyList(),
		migration = alias, sourceAliases = sourceAliases)

	private suspend fun reconcile(
		remote: Manga,
		container: File,
		indexedLayout: Boolean,
		verifiedPrevious: Collection<MangaChapter>,
		migration: DownloadPathAlias? = null,
		sourceAliases: SourceAliasRegistry? = null,
	): LocalManga? = withContext(Dispatchers.IO) {
		runCatchingCancellable {
			val root = container.canonicalFile
			if (!root.isDirectory || !root.canRead()) return@runCatchingCancellable null
			val entries = root.listFiles() ?: return@runCatchingCancellable null
			if (entries.any { it.name == ".notamanga" }) return@runCatchingCancellable null
			val sidecar = entries.singleOrNull { it.name == LocalMangaOutput.ENTRY_NAME_INDEX }
			if (indexedLayout != (sidecar != null)) return@runCatchingCancellable null
			val index = if (sidecar != null) {
				if (!sidecar.isFile || sidecar.canonicalFile != File(root, sidecar.name)) return@runCatchingCancellable null
				MangaIndex.read(sidecar) ?: return@runCatchingCancellable null
			} else null
			val indexed = if (index != null) {
				val info = index.getMangaInfo() ?: return@runCatchingCancellable null
				if (info.url != remote.url) return@runCatchingCancellable null
				if (migration == null) {
					if (info.id != remote.id || info.source.name != remote.source.name) return@runCatchingCancellable null
				} else {
					if (migration.path != root.path || migration.localMangaId != info.id ||
						info.id == remote.id || sourceAliases == null ||
						!sourceAliases.areAliases(info.source.name, remote.source.name)
					) return@runCatchingCancellable null
				}
				info
			} else null

			// One shallow listing; never interpret worker .cbz.tmp/.bak.tmp as final files.
			val artifacts = LinkedHashMap<String, File>()
			val foldedNames = HashSet<String>()
			for (file in entries) {
				currentCoroutineContext().ensureActive()
				if (file.name.startsWith(".")) continue
				// A chapter-directory layout is not an empty standalone archive layout.
				if (file.isDirectory) return@runCatchingCancellable null
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
			val indexedChapters = indexed?.chapters.orEmpty()
			val redirects = migration?.chapterIds.orEmpty()
			if (migration != null) {
				// Require actual indexed locators, not just persisted numeric redirects. A map
				// from an earlier sidecar-free ordinal cannot be trusted on this inventory.
				if (redirects.isEmpty() || redirects.values.toSet().size != redirects.size) {
					return@runCatchingCancellable null
				}
				val oldById = indexedChapters.associateBy { it.id }
				for ((oldId, newId) in redirects) {
					val old = oldById[oldId] ?: return@runCatchingCancellable null
					val current = remoteById[newId] ?: return@runCatchingCancellable null
					if (old.url.isBlank() || old.url != current.url ||
						old.source.name != indexed?.source?.name || current.source.name != remote.source.name
					) return@runCatchingCancellable null
				}
			}
			val reservedIds = remoteById.keys.toMutableSet()
			reservedIds.addAll(indexedChapters.map { it.id })
			val represented = HashSet<String>()
			val linked = ArrayList<MangaChapter>(artifacts.size)
			val linkedByName = HashMap<String, Int>()
			val indexedLinkedIds = HashSet<Long>()
			for (chapter in indexedChapters) {
				val name = index?.getChapterFileName(chapter.id) ?: return@runCatchingCancellable null
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
				val current = if (migration == null) remoteById[chapter.id] else {
					val target = redirects[chapter.id]
					// A surviving unmapped old ID may be independent, but cannot collide with
					// a current remote ID. No inference from numeric equality.
					if (target == null && chapter.id in remoteById) return@runCatchingCancellable null
					target?.let(remoteById::get)
				}
				// A contradictory current source locator cannot be silently re-keyed by a stale ID.
				if (current != null && current.url != chapter.url) return@runCatchingCancellable null
				if (!indexedLinkedIds.add((current ?: chapter).id)) return@runCatchingCancellable null
				linkedByName[name] = linked.size
				linked += (current ?: chapter).copy(
					url = Uri.fromFile(file).toString(),
					source = LocalMangaSource,
				)
			}

			// Reserve existing exact mappings before filename matching. Compute ambiguity against
			// the entire remote list, including already represented canonical chapters.
			val extras = artifacts.keys.filterNot { it in represented }
			val matches = LegacyChapterDownloadCompat.matchStandaloneNames(remoteChapters, extras)
			val linkedIds = linked.mapTo(HashSet()) { it.id }
			for ((name, chapter) in matches) {
				if (chapter.id in linkedIds) continue
				val file = artifacts.getValue(name)
				linkedByName[name] = linked.size
				linked += chapter.copy(url = Uri.fromFile(file).toString(), source = LocalMangaSource)
				linkedIds += chapter.id
				represented += name
			}

			// Preserve an independently verified legacy identity only for the identical physical
			// locator. Keep its exact URI representation (including directory fragment), not a
			// normalized replacement which would silently change a source+URL personal key.
			val priorByName = LinkedHashMap<String, MangaChapter>()
			val priorIds = HashSet<Long>()
			for (chapter in verifiedPrevious) {
				if (chapter.source != LocalMangaSource) return@runCatchingCancellable null
				val uri = Uri.parse(chapter.url)
				if (uri.scheme != "file" || uri.query != null || !uri.authority.isNullOrEmpty()) {
					return@runCatchingCancellable null
				}
				val path = uri.path?.let(::File) ?: return@runCatchingCancellable null
				val fragment = uri.fragment
				val file = if (fragment != null) {
					if (fragment != File(fragment).name || path.canonicalFile != root) return@runCatchingCancellable null
					File(root, fragment)
				} else path
				if (file.canonicalFile.parentFile != root || file.name != file.canonicalFile.name) {
					return@runCatchingCancellable null
				}
				if (priorByName.put(file.name, chapter) != null || !priorIds.add(chapter.id)) {
					return@runCatchingCancellable null
				}
			}
			// Reserve all prior IDs (including missing artifacts) before minting any provisional ID.
			// A conflicting prior local-only ID is unresolved rather than reassigned.
			for ((name, prior) in priorByName) {
				val existingPosition = linkedByName[name]
				if (existingPosition != null) {
					// A retired indexed chapter can already have the same verified ID. Keep its
					// previous personal locator representation, but never absorb it into a
					// canonical remote chapter or override a different indexed identity.
					if (prior.id in remoteById || linked[existingPosition].id != prior.id) {
						return@runCatchingCancellable null
					}
					linked[existingPosition] = prior
					continue
				}
				if (!reservedIds.add(prior.id)) return@runCatchingCancellable null
				if (name !in artifacts) continue
				if (name in represented) return@runCatchingCancellable null
				linked += prior
				represented += name
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
