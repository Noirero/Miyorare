package org.koitharu.kotatsu.favourites.domain

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import org.koitharu.kotatsu.core.model.isNovelContent
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.groups.domain.LibraryGroupsRepository
import org.koitharu.kotatsu.parsers.model.Manga
import java.text.Normalizer
import javax.inject.Inject
import kotlin.math.max

enum class LibraryScanConfidence {
	HIGH,
	REVIEW,
}

enum class LibraryScanReason {
	PRIMARY_TITLE,
	ALTERNATIVE_TITLE,
	DESCRIPTION_ALTERNATIVE_TITLE,
	FUZZY_TITLE,
	AUTHOR_MATCH,
}

data class LibraryScanCandidate(
	val title: String,
	val mangas: List<Manga>,
	val confidence: LibraryScanConfidence,
	val score: Float,
	val reasons: Set<LibraryScanReason>,
	internal val matchedPairKeys: Set<String>,
	val existingGroupIds: Set<Long>,
) {
	val canLink: Boolean
		get() = existingGroupIds.size <= 1
}

sealed class LibraryScanLinkResult {
	data class Created(val groupId: Long) : LibraryScanLinkResult()
	data class Added(val groupId: Long, val addedCount: Int) : LibraryScanLinkResult()
	data object AlreadyLinked : LibraryScanLinkResult()
	data object Conflict : LibraryScanLinkResult()
}

/**
 * Local-only scanner that discovers likely duplicate/alternate-source entries inside one library scope.
 *
 * It never requests source details and never mutates manga/history/download records. Confirmed matches
 * are represented with the existing reversible Library Group model.
 */
@Reusable
class LibraryDuplicateScanUseCase @Inject constructor(
	private val favouritesRepository: FavouritesRepository,
	private val groupsRepository: LibraryGroupsRepository,
	@ApplicationContext context: Context,
) {
	private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

	suspend fun scan(
		categoryId: Long,
		space: FavouriteSpace,
	): List<LibraryScanCandidate> {
		val mangas = (if (categoryId > 0L) {
			favouritesRepository.getManga(categoryId, space)
		} else {
			favouritesRepository.getAllManga(space)
		})
			.asSequence()
			.filterNot { it.isNovelContent }
			.distinctBy { it.id }
			.toList()
		if (mangas.size < 2) return emptyList()

		val ignored = ignoredPairs(space)
		val existingGroups = groupsRepository.observeGroups(space).first()
		val groupByManga = HashMap<Long, Long>()
		existingGroups.forEach { group -> group.memberIds.forEach { groupByManga[it] = group.id } }

		val indexed = mangas.map(::index)
		val byAlias = HashMap<String, MutableList<AliasRef>>()
		for (item in indexed) {
			item.aliases.forEach { alias ->
				if (alias.normalized.length >= MIN_NORMALIZED) {
					byAlias.getOrPut(alias.normalized) { ArrayList(2) }.add(AliasRef(item, alias.kind))
				}
			}
		}

		val matches = LinkedHashMap<String, PairMatch>()
		for (bucket in byAlias.values) {
			if (bucket.size < 2) continue
			for (i in 0 until bucket.lastIndex) {
				for (j in i + 1 until bucket.size) {
					val left = bucket[i]
					val right = bucket[j]
					if (left.item.manga.id == right.item.manga.id) continue
					val key = pairKey(left.item.manga.id, right.item.manga.id)
					if (key in ignored) continue
					val reason = when {
						left.kind == AliasKind.PRIMARY && right.kind == AliasKind.PRIMARY ->
							LibraryScanReason.PRIMARY_TITLE
						left.kind == AliasKind.DESCRIPTION || right.kind == AliasKind.DESCRIPTION ->
							LibraryScanReason.DESCRIPTION_ALTERNATIVE_TITLE
						else -> LibraryScanReason.ALTERNATIVE_TITLE
					}
					var score = when (reason) {
						LibraryScanReason.PRIMARY_TITLE -> 1f
						LibraryScanReason.ALTERNATIVE_TITLE -> 0.95f
						LibraryScanReason.DESCRIPTION_ALTERNATIVE_TITLE -> 0.91f
						else -> 0.9f
					}
					val reasons = linkedSetOf(reason)
					if (authorsMatch(left.item, right.item)) {
						score = (score + 0.03f).coerceAtMost(1f)
						reasons += LibraryScanReason.AUTHOR_MATCH
					}
					putBest(matches, PairMatch(key, left.item, right.item, score, reasons))
				}
			}
		}

		// Fuzzy pass is deliberately conservative and blocked by first normalized character + length.
		val fuzzyBuckets = indexed
			.filter { it.primary.length >= MIN_FUZZY_LENGTH }
			.groupBy { it.primary.firstOrNull() ?: '\u0000' }
		for (bucket in fuzzyBuckets.values) {
			for (i in 0 until bucket.lastIndex) {
				for (j in i + 1 until bucket.size) {
					val left = bucket[i]
					val right = bucket[j]
					val key = pairKey(left.manga.id, right.manga.id)
					if (key in ignored || key in matches) continue
					val maxLength = max(left.primary.length, right.primary.length)
					if (kotlin.math.abs(left.primary.length - right.primary.length) > max(4, maxLength / 5)) continue
					val similarity = titleSimilarity(left.primary, right.primary)
					if (similarity < FUZZY_THRESHOLD) continue
					var score = similarity
					val reasons = linkedSetOf(LibraryScanReason.FUZZY_TITLE)
					if (authorsMatch(left, right)) {
						score = (score + 0.04f).coerceAtMost(0.97f)
						reasons += LibraryScanReason.AUTHOR_MATCH
					}
					putBest(matches, PairMatch(key, left, right, score, reasons))
				}
			}
		}
		if (matches.isEmpty()) return emptyList()

		val ids = indexed.map { it.manga.id }
		val parent = ids.associateWith { it }.toMutableMap()
		fun root(id: Long): Long {
			var x = id
			while (parent[x] != x) x = parent.getValue(x)
			var y = id
			while (parent[y] != y) {
				val next = parent.getValue(y)
				parent[y] = x
				y = next
			}
			return x
		}
		fun union(a: Long, b: Long) {
			val ra = root(a)
			val rb = root(b)
			if (ra != rb) parent[rb] = ra
		}
		matches.values.forEach { union(it.left.manga.id, it.right.manga.id) }

		val componentIds = ids.groupBy(::root).values.filter { it.size >= 2 }
		val mangaById = indexed.associateBy { it.manga.id }
		return componentIds.mapNotNull { memberIds ->
			val memberSet = memberIds.toHashSet()
			val pairMatches = matches.values.filter {
				it.left.manga.id in memberSet && it.right.manga.id in memberSet
			}
			if (pairMatches.isEmpty()) return@mapNotNull null
			val groupIds = memberIds.mapNotNullTo(LinkedHashSet()) { groupByManga[it] }
			if (groupIds.size == 1 && memberIds.all { groupByManga[it] == groupIds.first() }) {
				return@mapNotNull null
			}
			val members = memberIds.mapNotNull { mangaById[it]?.manga }
				.sortedWith(compareBy<Manga> { normalize(it.title).length }.thenBy { it.title.lowercase() })
			// Linked Sources is intentionally cross-source. Same-source title collisions stay as ordinary
			// library entries and are not promoted into a source-alternative group.
			if (members.map { it.source }.distinct().size < 2) return@mapNotNull null
			val minScore = pairMatches.minOf { it.score }
			LibraryScanCandidate(
				title = members.first().title,
				mangas = members,
				confidence = if (minScore >= HIGH_CONFIDENCE) LibraryScanConfidence.HIGH else LibraryScanConfidence.REVIEW,
				score = minScore,
				reasons = pairMatches.flatMapTo(LinkedHashSet()) { it.reasons },
				matchedPairKeys = pairMatches.mapTo(LinkedHashSet()) { it.key },
				existingGroupIds = groupIds,
			)
		}.sortedWith(
			compareByDescending<LibraryScanCandidate> { it.confidence == LibraryScanConfidence.HIGH }
				.thenByDescending { it.score }
				.thenBy { it.title.lowercase() },
		)
	}

	suspend fun link(
		candidate: LibraryScanCandidate,
		categoryId: Long,
		space: FavouriteSpace,
	): LibraryScanLinkResult {
		val activeGroups = groupsRepository.observeGroups(space).first()
		val memberIds = candidate.mangas.map { it.id }
		val groups = activeGroups.filter { group -> group.memberIds.any { it in memberIds } }
		if (groups.size > 1) return LibraryScanLinkResult.Conflict
		val existing = groups.singleOrNull()
		if (existing != null) {
			val missing = memberIds.filterNot { it in existing.memberIds }
			if (missing.isEmpty()) return LibraryScanLinkResult.AlreadyLinked
			val result = groupsRepository.addMembers(
				groupId = existing.id,
				mangaIds = missing,
				moveFromExistingGroups = false,
				space = space,
			)
			return LibraryScanLinkResult.Added(existing.id, result.addedCount)
		}
		val groupId = groupsRepository.createGroup(
			title = candidate.title,
			mangaIds = memberIds,
			categoryIds = if (categoryId > 0L) listOf(categoryId) else emptyList(),
			space = space,
		)
		return LibraryScanLinkResult.Created(groupId)
	}

	fun reject(candidate: LibraryScanCandidate, space: FavouriteSpace) {
		if (candidate.matchedPairKeys.isEmpty()) return
		val values = ignoredPairs(space).toMutableSet()
		values += candidate.matchedPairKeys
		prefs.edit { putStringSet(ignoredKey(space), values) }
	}

	private fun ignoredPairs(space: FavouriteSpace): Set<String> =
		prefs.getStringSet(ignoredKey(space), emptySet()).orEmpty().toSet()

	private fun ignoredKey(space: FavouriteSpace) = "library_scan_ignored_pairs_${space.dbValue}"

	private fun index(manga: Manga): IndexedManga {
		val aliases = ArrayList<Alias>()
		aliases += Alias(normalize(manga.title), AliasKind.PRIMARY)
		manga.altTitles.forEach { aliases += Alias(normalize(it), AliasKind.ALTERNATIVE) }
		extractDescriptionTitles(manga.description).forEach {
			aliases += Alias(normalize(it), AliasKind.DESCRIPTION)
		}
		return IndexedManga(
			manga = manga,
			primary = normalize(manga.title),
			aliases = aliases.distinctBy { it.normalized to it.kind },
			authors = manga.authors.mapNotNullTo(LinkedHashSet()) {
				normalize(it).takeIf { value -> value.length >= 3 }
			},
		)
	}

	private fun extractDescriptionTitles(description: String?): List<String> {
		if (description.isNullOrBlank()) return emptyList()
		val values = ArrayList<String>()
		for (match in ALT_TITLE_LINE.findAll(description)) {
			val raw = match.groupValues[1]
			raw.split(';', '|', '/', '／').forEach { value ->
				value.trim()
					.removePrefix("-")
					.trim()
					.takeIf { it.length in 3..160 }
					?.let(values::add)
			}
		}
		return values.distinct().take(MAX_DESCRIPTION_ALIASES)
	}

	private fun authorsMatch(left: IndexedManga, right: IndexedManga): Boolean =
		left.authors.isNotEmpty() && right.authors.isNotEmpty() && left.authors.any { it in right.authors }

	private fun putBest(target: MutableMap<String, PairMatch>, value: PairMatch) {
		val old = target[value.key]
		if (old == null || value.score > old.score) target[value.key] = value
		else if (value.score == old.score) target[value.key] = old.copy(reasons = old.reasons + value.reasons)
	}

	private fun normalize(value: String): String {
		val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase()
		return buildString(normalized.length) {
			for (c in normalized) if (c.isLetterOrDigit()) append(c)
		}
	}

	private fun pairKey(a: Long, b: Long): String = if (a < b) "$a:$b" else "$b:$a"

	private fun titleSimilarity(a: String, b: String): Float {
		if (a == b) return 1f
		if (a.isEmpty() || b.isEmpty()) return 0f
		val previous = IntArray(b.length + 1) { it }
		val current = IntArray(b.length + 1)
		for (i in a.indices) {
			current[0] = i + 1
			for (j in b.indices) {
				val cost = if (a[i] == b[j]) 0 else 1
				current[j + 1] = minOf(
					current[j] + 1,
					previous[j + 1] + 1,
					previous[j] + cost,
				)
			}
			for (j in previous.indices) previous[j] = current[j]
		}
		return 1f - previous[b.length].toFloat() / max(a.length, b.length).toFloat()
	}

	private data class IndexedManga(
		val manga: Manga,
		val primary: String,
		val aliases: List<Alias>,
		val authors: Set<String>,
	)

	private data class Alias(val normalized: String, val kind: AliasKind)
	private data class AliasRef(val item: IndexedManga, val kind: AliasKind)
	private data class PairMatch(
		val key: String,
		val left: IndexedManga,
		val right: IndexedManga,
		val score: Float,
		val reasons: Set<LibraryScanReason>,
	)

	private enum class AliasKind {
		PRIMARY,
		ALTERNATIVE,
		DESCRIPTION,
	}

	private companion object {
		const val MIN_NORMALIZED = 4
		const val MIN_FUZZY_LENGTH = 7
		const val FUZZY_THRESHOLD = 0.88f
		const val HIGH_CONFIDENCE = 0.92f
		const val MAX_DESCRIPTION_ALIASES = 12
		val ALT_TITLE_LINE = Regex(
			"""(?im)^\\s*(?:alternative\\s+titles?|alt(?:ernative)?\\s*(?:titles?|names?)?|other\\s+(?:titles?|names?)|synonyms?|romaji|english(?:\\s+title)?|japanese(?:\\s+title)?|judul\\s+alternatif|judul\\s+lain|nama\\s+lain)\\s*[:：-]\\s*(.+?)\\s*$""",
		)
	}
}
