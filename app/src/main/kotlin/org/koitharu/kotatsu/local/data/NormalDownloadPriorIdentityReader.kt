package org.koitharu.kotatsu.local.data

import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.bookmarks.domain.BOOKMARK_ORIGIN_READER
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.URI_SCHEME_ZIP
import org.koitharu.kotatsu.details.data.ChapterPersonalKey
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.local.data.index.DownloadPathAlias
import org.koitharu.kotatsu.local.data.output.LocalMangaOutput
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.util.longHashCode
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.sources.compat.SourceAliasRegistry
import java.io.File
import javax.inject.Inject

/** Partial durable evidence, not a complete identity observation or a personal-state migration. */
internal data class NormalPriorIdentityEvidence(
	val verified: List<MangaChapter>,
	val unresolvedLocalKeys: Set<ChapterPersonalKey>,
	val unresolvedIds: Set<Long>,
)

/** Prior evidence remains separate from already verified physical presence. */
internal data class NormalPriorDirectoryReconciliation(
	val evidence: NormalPriorIdentityEvidence,
	val projection: LocalManga?,
)

/**
 * Recover only a narrow, independently corroborated pre-scanner local identity. Caller must
 * already have verified the Normal container and space. The old ordinal is never recomputed.
 * Reader's local ZIP bookmark stores the actual page locator and chapter ID; a matching exact
 * local personal/progress key corroborates the chapter URI. No archive read or DB writes.
 */
internal class NormalDownloadPriorIdentityReader @Inject constructor(
	private val db: MangaDatabase,
	private val settings: AppSettings,
) {
	suspend fun read(remote: Manga, container: File): NormalPriorIdentityEvidence? = withContext(Dispatchers.IO) {
		runCatchingCancellable {
			val root = container.canonicalFile
			val remoteIds = remote.chapters.orEmpty().mapTo(HashSet()) { it.id }
			val stored = db.withTransaction {
				val progress = db.getChapterProgressDao().findAll(listOf(remote.id), FavouriteSpace.NORMAL.dbValue)
				val personal = db.getChapterPersonalDao().findAll(listOf(remote.id))
				val keys = (personal.map { it.key() } + progress.map { it.key() })
					.filter { it.source == LocalMangaSource.name }.toSet()
				Triple(keys, db.getBookmarksDao().findAll(remote.id),
					db.getHistoryDao().findIncludingDeleted(remote.id))
			}
			val (keys, bookmarks, history) = stored
			val unresolvedIds = buildSet {
				bookmarks.mapTo(this) { it.chapterId }
				if (history != null && history.space == FavouriteSpace.NORMAL.dbValue) add(history.chapterId)
				addAll(settings.getChapterReadOverrides(remote.id).keys)
			}.filterTo(HashSet()) { it != 0L && it !in remoteIds }
			val keysByPath = HashMap<File, MutableSet<ChapterPersonalKey>>()
			for (key in keys) {
				val path = chapterFile(key.url, root) ?: continue
				keysByPath.getOrPut(path) { LinkedHashSet() }.add(key)
			}
			val idsByPath = HashMap<File, MutableSet<Long>>()
			val pathsById = HashMap<Long, MutableSet<File>>()
			for (bookmark in bookmarks) {
				if (bookmark.origin != BOOKMARK_ORIGIN_READER || bookmark.chapterId == 0L ||
					bookmark.chapterId in remoteIds || bookmark.pageId != bookmark.imageUrl.longHashCode()
				) continue
				val uri = Uri.parse(bookmark.imageUrl)
				if (uri.scheme != URI_SCHEME_ZIP || uri.fragment.isNullOrBlank() ||
					uri.query != null || !uri.authority.isNullOrEmpty()
				) continue
				val path = uri.path?.let(::File)?.takeIf { it.isAbsolute }?.canonicalFile ?: continue
				// The old ordinal hash excludes the container root. A credible persisted
				// bookmark for the same ID at another root is conflicting evidence, even
				// though that root is not a positive discovery location.
				pathsById.getOrPut(bookmark.chapterId) { LinkedHashSet() }.add(path)
				if (!isStandaloneChild(path, root)) continue
				idsByPath.getOrPut(path) { LinkedHashSet() }.add(bookmark.chapterId)
			}
			val verified = ArrayList<MangaChapter>()
			val resolvedKeys = HashSet<ChapterPersonalKey>()
			for ((file, localKeys) in keysByPath) {
				val key = localKeys.singleOrNull() ?: continue
				val id = idsByPath[file]?.singleOrNull() ?: continue
				if (pathsById[id]?.singleOrNull() != file) continue
				verified += MangaChapter(
					id = id,
					title = file.nameWithoutExtension,
					number = 0f,
					volume = 0,
					source = LocalMangaSource,
					uploadDate = 0L,
					url = key.url,
					scanlator = null,
					branch = null,
				)
				resolvedKeys += key
				unresolvedIds.remove(id)
			}
			NormalPriorIdentityEvidence(verified, keys - resolvedKeys, unresolvedIds)
		}.getOrNull()
	}

	/**
	 * Reconcile a caller-verified directory using persisted evidence. Unknown personal keys/IDs
	 * withhold this identity projection, not the caller's independently verified physical
	 * presence. No new identity becomes authoritative even when the prior evidence is complete.
	 * The caller may carry the alias from successful migrated-index verification;
	 * it is revalidated, never used as proof on its own. Only target manga records
	 * are consulted. No old-owner personal data is copied or reassigned.
	 */
	suspend fun reconcileVerifiedDirectory(
		remote: Manga, candidate: LocalManga,
		verifiedMigration: DownloadPathAlias? = null,
		sourceAliases: SourceAliasRegistry? = null,
	): NormalPriorDirectoryReconciliation? = withContext(Dispatchers.IO) {
		if (candidate.manga.id != remote.id || candidate.manga.source != LocalMangaSource ||
			!candidate.file.isDirectory
		) return@withContext null
		val evidence = read(remote, candidate.file) ?: return@withContext null
		val projection = if (evidence.unresolvedIds.isNotEmpty() || evidence.unresolvedLocalKeys.isNotEmpty()) {
			null
		} else if (verifiedMigration != null) {
			// No direct/sidecar-free fallback if the migrated index no longer validates.
			sourceAliases?.let {
				NormalDownloadDirectoryReconciler.reconcileMigratedIndexed(
					remote, candidate.file, verifiedMigration, it,
					requireFinalized = true, verifiedPrevious = evidence.verified,
				)
			}
		} else if (File(candidate.file, LocalMangaOutput.ENTRY_NAME_INDEX).exists()) {
			NormalDownloadDirectoryReconciler.reconcileIndexed(remote, candidate.file, evidence.verified, requireFinalized = true)
		} else {
			NormalDownloadDirectoryReconciler.reconcileSidecarFree(remote, candidate.file, evidence.verified, requireFinalized = true)
		}
		NormalPriorDirectoryReconciliation(evidence, projection)
	}

	private fun chapterFile(url: String, root: File): File? {
		val uri = Uri.parse(url)
		if (uri.scheme != "file" || uri.query != null || !uri.authority.isNullOrEmpty()) return null
		val path = uri.path?.let(::File) ?: return null
		val fragment = uri.fragment
		val file = if (fragment == null) path else {
			if (fragment != File(fragment).name || path.canonicalFile != root) return null
			File(root, fragment)
		}
		val canonical = file.canonicalFile
		return canonical.takeIf { isStandaloneChild(it, root) && file.name == it.name }
	}

	private fun isStandaloneChild(file: File, root: File): Boolean =
		file.parentFile == root && !file.name.startsWith(".") &&
			(file.extension.equals("cbz", true) || file.extension.equals("zip", true))
}
