package org.koitharu.kotatsu.local.data

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.dao.NormalScannerTitleClaim
import org.koitharu.kotatsu.favourites.data.FavouriteDownloadIndexEntity
import org.koitharu.kotatsu.download.domain.ActiveNormalDownloadRoots
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.domain.NormalDownloadScope
import org.koitharu.kotatsu.local.data.index.LocalMangaIndex
import org.koitharu.kotatsu.local.data.output.LocalMangaOutput
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.sources.compat.SourceAliasRegistry
import org.koitharu.kotatsu.sources.compat.VerifiedSourceAliases
import java.io.File
import java.util.TreeMap
import javax.inject.Inject

/** Bounded candidates, not a confirmed presence/absence observation or authorization. */
internal data class NormalDirectoryCandidates(
	val roots: ActiveNormalDownloadRoots,
	val available: Boolean,
	val deterministicPaths: Set<File>,
	val persistedHints: Set<File>,
)

/**
 * Read-only metadata snapshot, reusable across a scanner work batch. Canonicalizing persisted
 * owner paths once avoids missing noncanonical legacy rows and avoids a per-title global lookup.
 * No enumeration of files outside the active root. This is not a publication generation fence.
 */
internal class NormalScannerVerificationEvidence(
	private val owners: TreeMap<String, MutableList<FavouriteDownloadIndexEntity>>,
	private val claimsByTitle: Map<String, List<NormalScannerTitleClaim>>,
) {
	fun overlappingOwners(file: File): List<FavouriteDownloadIndexEntity> = buildList {
		for (ancestor in generateSequence(file) { it.parentFile }) addAll(owners[ancestor.path].orEmpty())
		for (rows in owners.subMap(file.path + File.separator, true, file.path + "0", false).values) addAll(rows)
	}

	fun titleClaims(title: String): List<NormalScannerTitleClaim> =
		LocalMangaOutput.normalScannerTitleKeys(title).flatMap { claimsByTitle[it].orEmpty() }.distinct()
}

/**
 * Enumerate exact title directory candidates under the active root before looking at any artifact.
 * Persisted aliases/index/ownership are hints only; they must still pass container identity and
 * space isolation checks. A stale hint never masks a deterministic active candidate.
 */
internal class NormalDownloadDirectoryDiscovery @Inject constructor(
	private val scope: NormalDownloadScope,
	private val sourceAliases: SourceAliasRegistry,
	private val db: MangaDatabase,
	private val index: LocalMangaIndex,
) {
	suspend fun discover(manga: Manga, space: FavouriteSpace?): NormalDirectoryCandidates? {
		val roots = scope.resolveActiveLookup(manga, space) ?: return null
		return withContext(Dispatchers.IO) {
			val root = roots.normalRoot
			if (root == null || !root.isDirectory || !root.canRead()) {
				return@withContext NormalDirectoryCandidates(roots, false, emptySet(), emptySet())
			}
			val canonicalSource = sourceAliases.canonicalId(manga.source.name)
			val alias = VerifiedSourceAliases.entries.singleOrNull { it.canonicalId == canonicalSource }
			val paths = LocalMangaOutput.normalScannerDirectoryCandidates(
				root, manga, alias?.let { it.canonicalDisplayName to it.language },
			)
			val hints = buildList {
				db.getFavouriteDownloadIndexDao().findEntry(FavouriteSpace.NORMAL.dbValue, manga.id)?.path?.let { add(File(it)) }
				db.getLocalMangaIndexDao().findPath(manga.id)?.let { add(File(it)) }
				index.getDownloadAliasPaths(listOf(manga.id))[manga.id]?.let { add(File(it)) }
			}
			fun containedDirectories(files: Collection<File>): Set<File> = files.mapNotNullTo(LinkedHashSet()) { file ->
				if (!scope.isWithinActiveRoot(file, root, roots.privateRoot)) return@mapNotNullTo null
				runCatching {
					file.canonicalFile.takeIf { it.isDirectory && !it.name.startsWith(".") &&
						!File(it, ".notamanga").exists() }
				}.getOrNull()
			}
			NormalDirectoryCandidates(roots, true, containedDirectories(paths), containedDirectories(hints))
		}
	}

	/**
	 * Capture ownership and title metadata once at the scan work boundary. Existing owners may
	 * contain absolute-but-not-canonical paths (legacy writers/restore). A lexical query over the
	 * canonical candidate alone cannot exclude them. Fail unresolved on unnormalizable evidence.
	 */
	suspend fun captureVerificationEvidence(): NormalScannerVerificationEvidence? = withContext(Dispatchers.IO) {
		runCatchingCancellable {
			val (rows, claims) = db.withTransaction {
				(FavouriteSpace.entries.flatMap { db.getFavouriteDownloadIndexDao().findEntries(it.dbValue) }) to
					db.getMangaDao().findNormalScannerTitleClaims()
			}
			val owners = TreeMap<String, MutableList<FavouriteDownloadIndexEntity>>()
			for (row in rows) {
				currentCoroutineContext().ensureActive()
				val file = File(row.path)
				if (!file.isAbsolute) return@runCatchingCancellable null
				val path = file.canonicalPath
				owners.getOrPut(path) { ArrayList(1) }.add(row.copy(path = path))
			}
			val byTitle = HashMap<String, MutableList<NormalScannerTitleClaim>>()
			for (claim in claims) {
				currentCoroutineContext().ensureActive()
				for (key in LocalMangaOutput.normalScannerTitleKeys(claim.title)) {
					byTitle.getOrPut(key) { ArrayList(1) }.add(claim)
				}
			}
			NormalScannerVerificationEvidence(owners, byTitle)
		}.getOrNull()
	}

	/**
	 * Verify one indexed candidate, not authoritative presence/absence or action authorization.
	 * A batched caller must share one evidence snapshot, not recapture per manga/card.
	 */
	suspend fun verifyIndexedCandidate(
		manga: Manga, space: FavouriteSpace?, candidate: File,
		evidence: NormalScannerVerificationEvidence? = null,
	): LocalManga? = verifyCandidate(manga, space, candidate, indexed = true, evidence = evidence)

	/**
	 * Positive sidecar-free subset: exact Normal ownership plus chapter evidence, or a registry-
	 * verified source-qualified title path, unambiguous stored title claims and chapter evidence.
	 * A source display name or a persisted alias alone never proves the physical manga.
	 */
	suspend fun verifySidecarFreeCandidate(
		manga: Manga, space: FavouriteSpace?, candidate: File,
		evidence: NormalScannerVerificationEvidence? = null,
	): LocalManga? = verifyCandidate(manga, space, candidate, indexed = false, evidence = evidence)

	private suspend fun verifyCandidate(
		manga: Manga, space: FavouriteSpace?, candidate: File, indexed: Boolean,
		evidence: NormalScannerVerificationEvidence?,
	): LocalManga? {
		val candidates = discover(manga, space) ?: return null
		if (!candidates.available) return null
		val proof = evidence ?: captureVerificationEvidence() ?: return null
		return withContext(Dispatchers.IO) {
			runCatchingCancellable {
				val physical = candidate.canonicalFile
				if (physical !in candidates.deterministicPaths && physical !in candidates.persistedHints) {
					return@runCatchingCancellable null
				}
				val normalRoot = candidates.roots.normalRoot?.canonicalFile ?: return@runCatchingCancellable null
				val privateRoot = candidates.roots.privateRoot?.canonicalFile ?: return@runCatchingCancellable null
				if (privateRoot != normalRoot && privateRoot.path.startsWith(physical.path + File.separator)) {
					return@runCatchingCancellable null
				}
				val owners = proof.overlappingOwners(physical)
				if (owners.any { it.space != FavouriteSpace.NORMAL.dbValue || it.mangaId != manga.id }) {
					return@runCatchingCancellable null
				}
				val exactNormal = owners.any { it.path == physical.path }
				val privateHistory = candidates.roots.historicalPrivateRoots.map { it.canonicalFile }
				val privatePaths = (privateHistory + privateRoot).distinct()
				// A historical Private root is not a discovery location. It only constrains
				// space proof when it overlaps the current physical candidate.
				if (privateHistory.any { it.path.startsWith(physical.path + File.separator) && it.exists() }) {
					return@runCatchingCancellable null
				}
				val inPrivate = privatePaths.any { other ->
					physical == other || physical.path.startsWith(other.path.trimEnd(File.separatorChar) + File.separator)
				}
				if (inPrivate && !exactNormal) return@runCatchingCancellable null

				if (indexed) {
					NormalDownloadDirectoryReconciler.reconcileIndexed(manga, physical, requireFinalized = true)?.let {
						return@runCatchingCancellable it
					}
					if (!exactNormal) return@runCatchingCancellable null
					val alias = index.getDownloadAliasForScanner(manga.id) ?: return@runCatchingCancellable null
					return@runCatchingCancellable NormalDownloadDirectoryReconciler.reconcileMigratedIndexed(
						manga, physical, alias, sourceAliases, requireFinalized = true,
					)
				}
				if (!exactNormal && !hasUnambiguousManualIdentity(manga, normalRoot, physical, proof)) {
					return@runCatchingCancellable null
				}
				val projection = NormalDownloadDirectoryReconciler.reconcileSidecarFree(manga, physical, requireFinalized = true)
					?: return@runCatchingCancellable null
				val remoteIds = manga.chapters.orEmpty().mapTo(HashSet()) { it.id }
				// At least one canonical match is required for this first-observation subset.
				// A renamed-only unowned container needs durable prior proof; never guess.
				projection.takeIf { it.manga.chapters.orEmpty().any { chapter -> chapter.id in remoteIds } }
			}.getOrNull()
		}
	}

	private suspend fun hasUnambiguousManualIdentity(
		manga: Manga, root: File, physical: File, proof: NormalScannerVerificationEvidence,
	): Boolean {
		val canonicalSource = sourceAliases.canonicalId(manga.source.name)
		val alias = VerifiedSourceAliases.entries.singleOrNull { it.canonicalId == canonicalSource } ?: return false
		val expected = LocalMangaOutput.normalScannerVerifiedAliasCandidates(
			root, manga.title, alias.canonicalDisplayName, alias.language,
		)
		// Do not allow an in-root symlink to turn a deterministic source path into proof.
		if (physical !in expected) return false
		val claims = proof.titleClaims(manga.title)
		if (claims.none { it.mangaId == manga.id && it.source == manga.source.name && it.url == manga.url }) return false
		for (claim in claims) {
			if (claim.mangaId == manga.id) continue
			val otherSource = sourceAliases.canonicalId(claim.source)
			val other = VerifiedSourceAliases.entries.singleOrNull { it.canonicalId == otherSource } ?: return false
			// Same-source normalized title collisions are ambiguous even on case-folding
			// storage. Distinct verified sources are safe only with distinct physical paths.
			if (other.canonicalId == alias.canonicalId) return false
			if (LocalMangaOutput.normalScannerVerifiedAliasCandidates(
					root, claim.title, other.canonicalDisplayName, other.language,
				).any { it.path.equals(physical.path, ignoreCase = true) || it.canonicalFile == physical }
			) return false
		}
		return true
	}
}
