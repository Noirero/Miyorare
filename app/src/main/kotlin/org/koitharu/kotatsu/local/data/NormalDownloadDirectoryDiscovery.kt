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
import java.util.Locale
import javax.inject.Inject

/** Bounded candidates, not a confirmed presence/absence observation or authorization. */
internal data class NormalDirectoryCandidates(
	val roots: ActiveNormalDownloadRoots,
	val available: Boolean,
	val deterministicPaths: Set<File>,
	val persistedHints: Set<File>,
	val unresolvedPaths: Set<File> = emptySet(),
	val inventoryComplete: Boolean = true,
)

/** All candidates retained; neither an unresolved inventory nor multiple copies selects a winner. */
internal data class NormalCandidateResolution(
	val verified: List<LocalManga>,
	val unresolvedPaths: Set<File>,
	val inventoryComplete: Boolean,
	// Separate durable identity evidence from container/physical candidate selection.
	// Null means not assessed or unavailable, never confirmed absence.
	val directoryIdentity: NormalPriorDirectoryReconciliation? = null,
) {
	// This is a candidate choice, NOT publication/read/delete authorization or identity authority.
	val uniqueCandidate: LocalManga?
		get() = verified.singleOrNull()?.takeIf { inventoryComplete && unresolvedPaths.isEmpty() }
}

/**
 * One shallow parent inventory, indexed by exact title and positive suffix base.
 * Reuse only within a work batch; caller must discard on root/producer invalidation.
 * Names are indexed once, not scanned again for every favourite.
 */
internal class NormalScannerParentListing(children: List<File>) {
	private val byTitle = HashMap<String, MutableList<File>>()

	init {
		for (file in children) {
			val lower = file.name.lowercase(Locale.ROOT)
			if (lower.startsWith(".")) continue
			val staging = lower.endsWith(LocalMangaOutput.SUFFIX_TMP)
			val finalName = if (staging) lower.removeSuffix(LocalMangaOutput.SUFFIX_TMP).removeSuffix(".bak") else lower
			val archive = finalName.endsWith(".cbz") || finalName.endsWith(".zip")
			val stem = if (archive) finalName.substringBeforeLast('.') else finalName
			byTitle.getOrPut(stem) { ArrayList(1) }.add(file)
			val suffix = stem.substringAfterLast('_', "").toIntOrNull()
			if (suffix != null && suffix > 0) {
				byTitle.getOrPut(stem.substringBeforeLast('_')) { ArrayList(1) }.add(file)
			}
		}
	}

	fun matching(titleKeys: Set<String>): Set<File> =
		titleKeys.flatMapTo(LinkedHashSet()) { byTitle[it].orEmpty() }
}

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
	private val priorIdentity: NormalDownloadPriorIdentityReader,
) {
	suspend fun discover(
		manga: Manga, space: FavouriteSpace?,
		parentListings: MutableMap<File, NormalScannerParentListing?> = HashMap(),
	): NormalDirectoryCandidates? {
		val roots = scope.resolveActiveLookup(manga, space) ?: return null
		return withContext(Dispatchers.IO) {
			val root = roots.normalRoot?.let { runCatching { it.canonicalFile }.getOrNull() }
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
			val unresolved = LinkedHashSet<File>()
			var complete = true
			val discovered = LinkedHashSet<File>()
			// Enumerate only immediate children of known layout parents, once per work batch.
			// Numeric suffixes may have gaps; never walk arbitrary descendants or stop at _1.
			for ((parent, bases) in paths.groupBy { it.parentFile }) {
				currentCoroutineContext().ensureActive()
				if (parent == null) continue
				val canonical = runCatching { parent.canonicalFile }.getOrNull()
				if (canonical != parent) {
					complete = false
					continue
				}
				if (parent != root && !scope.isWithinActiveRoot(parent, root, roots.privateRoot)) continue
				if (!parent.exists()) continue
				if (!parentListings.containsKey(parent)) {
					parentListings[parent] = parent.listFiles()?.toList()?.let(::NormalScannerParentListing)
				}
				val children = parentListings[parent]
				if (children == null) {
					complete = false
					continue
				}
				val titleKeys = bases.mapTo(HashSet()) { it.name.lowercase(Locale.ROOT) }
				for (file in children.matching(titleKeys)) {
					val lower = file.name.lowercase(Locale.ROOT)
					if (lower.startsWith(".")) continue
					val staging = lower.endsWith(LocalMangaOutput.SUFFIX_TMP)
					val finalName = if (staging) lower.removeSuffix(LocalMangaOutput.SUFFIX_TMP).removeSuffix(".bak") else lower
					val archive = finalName.endsWith(".cbz") || finalName.endsWith(".zip")
					if (staging && archive) {
						val withoutTmp = file.name.dropLast(LocalMangaOutput.SUFFIX_TMP.length)
						val target = File(parent, if (withoutTmp.endsWith(".bak", true)) withoutTmp.dropLast(4) else withoutTmp)
						if (scope.isWithinActiveRoot(target, root, roots.privateRoot)) unresolved += target
						continue
					}
					if (file.isDirectory || archive) discovered += file
				}
			}
			fun containedCandidates(files: Collection<File>, deterministic: Boolean): Set<File> =
				files.mapNotNullTo(LinkedHashSet()) { file ->
				val physical = runCatching { file.canonicalFile }.getOrNull()
				if (physical == null) {
					complete = false
					return@mapNotNullTo null
				}
				// Do not follow deterministic symlink candidates into another title or root.
				// Persisted hints can use a legacy absolute representation; their canonical
				// target still requires independent space and metadata proof.
				if (deterministic && physical != file.absoluteFile.normalize()) {
					unresolved += file.absoluteFile.normalize()
					return@mapNotNullTo null
				}
				if (!scope.isWithinActiveRoot(physical, root, roots.privateRoot)) return@mapNotNullTo null
				runCatching {
					if (!physical.canRead()) {
						unresolved += physical
						return@runCatching null
					}
					physical.takeIf { !it.name.startsWith(".") &&
						((it.isDirectory && !File(it, ".notamanga").exists()) ||
							(it.isFile && (it.extension.equals("cbz", true) || it.extension.equals("zip", true)))) }
				}.getOrNull()
			}
			val deterministic = containedCandidates(discovered, deterministic = true)
			// Missing persisted hints are not a candidate and must not mask an active copy.
			val persisted = containedCandidates(hints.filter { it.exists() }, deterministic = false)
			NormalDirectoryCandidates(roots, true, deterministic, persisted, unresolved, complete)
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

	/** Embedded-index root archive only. No parser fallback or recovery. */
	suspend fun verifyArchiveCandidate(
		manga: Manga, space: FavouriteSpace?, candidate: File,
		evidence: NormalScannerVerificationEvidence? = null,
	): LocalManga? = verifyCandidate(manga, space, candidate, indexed = null, evidence = evidence)

	suspend fun resolveCandidates(
		manga: Manga, space: FavouriteSpace?,
		evidence: NormalScannerVerificationEvidence? = null,
		parentListings: MutableMap<File, NormalScannerParentListing?> = HashMap(),
	): NormalCandidateResolution? {
		val candidates = discover(manga, space, parentListings) ?: return null
		if (!candidates.available) return NormalCandidateResolution(emptyList(), emptySet(), false)
		val proof = evidence ?: captureVerificationEvidence() ?: return NormalCandidateResolution(emptyList(),
			candidates.deterministicPaths + candidates.persistedHints + candidates.unresolvedPaths, false)
		return withContext(Dispatchers.IO) {
			val verified = ArrayList<LocalManga>()
			val unresolved = candidates.unresolvedPaths.toMutableSet()
			for (file in candidates.deterministicPaths + candidates.persistedHints) {
				currentCoroutineContext().ensureActive()
				val layout = if (file.isFile) null else File(file, LocalMangaOutput.ENTRY_NAME_INDEX).exists()
				val copy = verifyCandidate(manga, space, file, layout, proof, candidates)
				if (copy == null) unresolved += file else verified += copy
			}
			val resolution = NormalCandidateResolution(verified, unresolved, candidates.inventoryComplete)
			// A canonical sibling proves neither the ID nor personal key of a local-only
			// chapter. Reconcile stored evidence only after a unique, complete directory
			// candidate is established. Keep physical evidence even if identity is unknown.
			// This is not a publication fence; migrated directories and root archives do
			// not acquire identity authority from this standalone reconciliation.
			val directory = resolution.uniqueCandidate?.takeIf { it.file.isDirectory }
			if (directory == null) resolution else resolution.copy(
				directoryIdentity = priorIdentity.reconcileVerifiedDirectory(manga, directory),
			)
		}
	}

	private suspend fun verifyCandidate(
		manga: Manga, space: FavouriteSpace?, candidate: File, indexed: Boolean?,
		evidence: NormalScannerVerificationEvidence?,
		captured: NormalDirectoryCandidates? = null,
	): LocalManga? {
		val candidates = captured ?: discover(manga, space) ?: return null
		if (!candidates.available) return null
		val proof = evidence ?: captureVerificationEvidence() ?: return null
		return withContext(Dispatchers.IO) {
			runCatchingCancellable {
				val physical = candidate.canonicalFile
				// Explicit lexical symlink targets cannot borrow another candidate's proof.
				if (physical != candidate.absoluteFile.normalize()) return@runCatchingCancellable null
				if (physical in candidates.unresolvedPaths || (physical !in candidates.deterministicPaths && physical !in candidates.persistedHints)) {
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

				if (indexed == null) {
					return@runCatchingCancellable NormalDownloadIndexedArchive.reconcile(manga, physical)
				}
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
				if (projection.manga.chapters.orEmpty().any { it.id in remoteIds }) {
					return@runCatchingCancellable projection
				}
				// At this point either exact Normal ownership or independently verified
				// source-qualified title/path proof has passed, including space conflicts.
				// A renamed-only directory additionally needs corroborated durable ID/URI
				// and a surviving artifact. No receipt requirement for a proven manual copy.
				// Never infer from an ordinal, renamed title or personal key alone.
				val prior = priorIdentity.read(manga, physical) ?: return@runCatchingCancellable null
				if (prior.verified.isEmpty() || prior.unresolvedIds.isNotEmpty() ||
					prior.unresolvedLocalKeys.isNotEmpty()
				) return@runCatchingCancellable null
				val reconciled = NormalDownloadDirectoryReconciler.reconcileSidecarFree(
					manga, physical, prior.verified, requireFinalized = true,
				) ?: return@runCatchingCancellable null
				val priorById = prior.verified.associateBy { it.id }
				reconciled.takeIf { copy ->
					copy.manga.chapters.orEmpty().any { chapter ->
						priorById[chapter.id]?.url == chapter.url
					}
				}
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
