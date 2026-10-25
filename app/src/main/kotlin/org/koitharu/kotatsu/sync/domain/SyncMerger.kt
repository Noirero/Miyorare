package org.koitharu.kotatsu.sync.domain

import org.koitharu.kotatsu.backup.local.data.model.BookmarkBackup
import org.koitharu.kotatsu.backup.local.data.model.ReaderAchievementBackup
import org.koitharu.kotatsu.backup.local.data.model.ReaderJourneyBackup
import org.koitharu.kotatsu.backup.local.data.model.ScrobblingBackup
import org.koitharu.kotatsu.backup.local.data.model.StatsBackup
import org.koitharu.kotatsu.favourites.domain.CategorySetIdentity
import org.koitharu.kotatsu.sync.data.model.SyncCategorySet
import org.koitharu.kotatsu.sync.data.model.SyncCategory
import org.koitharu.kotatsu.sync.data.model.SyncConfig
import org.koitharu.kotatsu.sync.data.model.SyncFavourite
import org.koitharu.kotatsu.sync.data.model.SyncFeedEntry
import org.koitharu.kotatsu.sync.data.model.SyncHistory
import org.koitharu.kotatsu.sync.data.model.SyncSnapshot
import org.koitharu.kotatsu.sync.data.model.SyncTrack

/**
 * Pure, side-effect-free merge of two snapshot halves. Records are unioned by their natural key and
 * the one with the newer "effective timestamp" wins — including its soft-delete state, which is how
 * deletions propagate across devices. On an exact timestamp tie the local record is kept (callers
 * pass local first), so a device never clobbers its own state with an equally-old remote copy.
 */
object SyncMerger {

	/** Stable keys own identity; numeric IDs only wire memberships into this device's namespace. */
	fun remapRemoteCategories(
		remoteCategories: List<SyncCategory>, remoteFavourites: List<SyncFavourite>,
		localCategories: List<SyncCategory>, reservedIds: Set<Int> = emptySet(),
	): Pair<List<SyncCategory>, List<SyncFavourite>> {
		val remoteIds = remoteCategories.mapTo(HashSet()) { it.categoryId.toLong() }
		require(remoteFavourites.all { it.categoryId in remoteIds }) { "Unmapped remote Favourite category" }
		val localKeys = categoryKeys(localCategories)
		val remoteKeys = categoryKeys(remoteCategories)
		val localIds = localCategories.associate { checkNotNull(localKeys[it.categoryId]) to it.categoryId }
		val usedIds = (localCategories.map { it.categoryId } + reservedIds).toHashSet()
		val remap = HashMap<Int, Int>()
		for (category in remoteCategories) {
			val newId = localIds[remoteKeys[category.categoryId]] ?: category.categoryId.takeIf { it > 0 && usedIds.add(it) }
				?: ((usedIds.maxOrNull()?.coerceAtLeast(0) ?: 0) + 1).also { require(it > 0); usedIds.add(it) }
			if (newId != category.categoryId) remap[category.categoryId] = newId
		}
		if (remap.isEmpty()) return remoteCategories to remoteFavourites
		return remoteCategories.map { c -> remap[c.categoryId]?.let { c.copy(categoryId = it) } ?: c } to
			remoteFavourites.map { f -> remap[f.categoryId.toInt()]?.let { f.copy(categoryId = it.toLong()) } ?: f }
	}

	private fun categoryKeys(categories: List<SyncCategory>): Map<Int, String> {
		val occurrences = HashMap<String, Int>()
		return categories.sortedBy { it.categoryId }.associate { category ->
			val normalized = CategorySetIdentity.normalizeTitle(category.title)
			val occurrence = occurrences[normalized] ?: 0
			occurrences[normalized] = occurrence + 1
			category.categoryId to category.syncKey.ifBlank { CategorySetIdentity.legacyCategory(0, category.title, occurrence) }
		}
	}

	fun mergeCategories(local: List<SyncCategory>, remote: List<SyncCategory>, propagateDeletions: Boolean = true): List<SyncCategory> {
		val keys = categoryKeys(local) // Real snapshots have explicit keys; blanks are legacy callers.
		val remoteKeys = categoryKeys(remote)
		val result = LinkedHashMap<String, SyncCategory>()
		for (c in local.filterDeleted(propagateDeletions) { it.deletedAt }) result[checkNotNull(keys[c.categoryId])] = c
		for (c in remote.filterDeleted(propagateDeletions) { it.deletedAt }) {
			val key = checkNotNull(remoteKeys[c.categoryId])
			val current = result[key]
			if (current == null || (c.deletedAt != 0L && current.deletedAt == 0L) || ((c.deletedAt == 0L) == (current.deletedAt == 0L) && categoryVersion(c) > categoryVersion(current))) result[key] = c
		}
		return result.values.toList()
	}

	private fun categoryVersion(c: SyncCategory): PairVersion = PairVersion(
		maxOf(c.createdAt, c.updatedAt, c.deletedAt),
		"${c.deletedAt != 0L}|${c.setSyncKey}|${c.title}|${c.sortKey}|${c.order}|${c.track}|${c.downloadNewChapters}|${c.isVisibleInLibrary}|${c.contentType}",
	)
	private data class PairVersion(val time: Long, val tie: String) : Comparable<PairVersion> {
		override fun compareTo(other: PairVersion): Int = compareValuesBy(this, other, { it.time }, { it.tie })
	}

	fun mergeSets(local: List<SyncCategorySet>, remote: List<SyncCategorySet>, propagateDeletions: Boolean = true): List<SyncCategorySet> {
		val result = LinkedHashMap<String, SyncCategorySet>()
		for (set in (local + remote).filterDeleted(propagateDeletions) { it.deletedAt }) {
			require(set.space == 0 && set.syncKey.isNotBlank()) { "Private or invalid Set in Normal sync" }
			val current = result[set.syncKey]
			fun version(s: SyncCategorySet) = PairVersion(maxOf(s.updatedAt, s.createdAt, s.deletedAt), "${s.deletedAt != 0L}|${s.title}|${s.sortKey}")
			if (current == null || (set.deletedAt != 0L && current.deletedAt == 0L) || ((set.deletedAt == 0L) == (current.deletedAt == 0L) && version(set) > version(current))) result[set.syncKey] = set
		}
		return result.values.sortedWith(compareBy<SyncCategorySet> { it.sortKey }.thenBy { it.syncKey })
	}

	/** Upgrade only the old wire format. New-schema identities are never inferred from titles. */
	fun upgradeCategorySets(snapshot: SyncSnapshot): SyncSnapshot {
		require(snapshot.schemaVersion <= SyncSnapshot.SCHEMA_VERSION) { "Unsupported newer sync schema" }
		if (snapshot.schemaVersion >= 5) {
			require(snapshot.categorySets.all { it.space == 0 && it.syncKey.isNotBlank() })
			require(snapshot.categories.all { it.syncKey.isNotBlank() && it.setSyncKey.isNotBlank() })
			return snapshot
		}
		val keys = categoryKeys(snapshot.categories)
		return snapshot.copy(schemaVersion = SyncSnapshot.SCHEMA_VERSION,
			categorySets = listOf(SyncCategorySet(CategorySetIdentity.defaultSet(0), "Utama", 0, 0, 0)),
			categories = snapshot.categories.map { it.copy(syncKey = checkNotNull(keys[it.categoryId]), setSyncKey = CategorySetIdentity.defaultSet(0), updatedAt = maxOf(it.createdAt, it.deletedAt)) },
		)
	}

	data class FavouriteState(val sets: List<SyncCategorySet>, val categories: List<SyncCategory>, val favourites: List<SyncFavourite>)

	/** Shared by Drive duplicate-file folding, two-way sync, and the transactional apply recheck. */
	fun mergeFavouriteState(local: SyncSnapshot, remote: SyncSnapshot?, propagateDeletions: Boolean = true, reservedIds: Set<Int> = emptySet()): FavouriteState {
		val a = upgradeCategorySets(local)
		val b = remote?.let(::upgradeCategorySets)
		val remapped = remapRemoteCategories(b?.categories.orEmpty(), b?.favourites.orEmpty(), a.categories, reservedIds)
		val sets = mergeSets(a.categorySets, b?.categorySets.orEmpty(), propagateDeletions).toMutableList()
		val categories = mergeCategories(a.categories, remapped.first, propagateDeletions)
		if (sets.none { it.deletedAt == 0L }) {
			val time = (sets.maxOfOrNull { maxOf(it.updatedAt, it.deletedAt) } ?: 0) + 1
			val key = if (sets.isEmpty()) CategorySetIdentity.defaultSet(0) else java.util.UUID.nameUUIDFromBytes(sets.map { it.syncKey }.sorted().joinToString("|").toByteArray(Charsets.UTF_8)).toString()
			sets += SyncCategorySet(key, "Utama", 0, time, time)
		}
		// Concurrent creates/renames must retain both identities without leaving ambiguous Set names.
		val usedTitles = HashSet<String>()
		val disambiguated = sets.sortedBy { it.syncKey }.map { set ->
			if (set.deletedAt != 0L || usedTitles.add(CategorySetIdentity.normalizeTitle(set.title))) set
			else {
				var suffix = 1
				var title: String
				do {
					title = "${set.title} (${set.syncKey.takeLast(8)}${if (suffix == 1) "" else "-$suffix"})"
					suffix++
				} while (!usedTitles.add(CategorySetIdentity.normalizeTitle(title)))
				set.copy(title = title, updatedAt = maxOf(set.updatedAt, set.createdAt) + 1)
			}
		}
		sets.clear()
		sets.addAll(disambiguated.sortedWith(compareBy<SyncCategorySet> { it.sortKey }.thenBy { it.syncKey }))
		val byKey = sets.associateBy { it.syncKey }
		val fallback = sets.filter { it.deletedAt == 0L }.minWith(compareBy<SyncCategorySet> { it.sortKey }.thenBy { it.syncKey })
		val repaired = categories.map { c ->
			val set = requireNotNull(byKey[c.setSyncKey]) { "Unknown category Set in sync" }
			if (c.deletedAt == 0L && set.deletedAt != 0L) c.copy(setSyncKey = fallback.syncKey, updatedAt = maxOf(c.updatedAt, set.deletedAt) + 1) else c
		}
		val deleted = repaired.filter { it.deletedAt != 0L }.associate { it.categoryId.toLong() to it.deletedAt }
		val favourites = mergeFavourites(a.favourites, remapped.second, propagateDeletions).map { f ->
			deleted[f.categoryId]?.let { f.copy(deletedAt = maxOf(it, f.deletedAt)) } ?: f
		}
		return FavouriteState(sets, repaired, favourites)
	}

	fun mergeFavourites(
		local: List<SyncFavourite>,
		remote: List<SyncFavourite>,
		propagateDeletions: Boolean = true,
	): List<SyncFavourite> =
		mergeBy(
			local.filterDeleted(propagateDeletions) { it.deletedAt },
			remote.filterDeleted(propagateDeletions) { it.deletedAt },
			key = { it.mangaId to it.categoryId },
			timestamp = { maxOf(it.createdAt, it.deletedAt) },
		)

	fun mergeHistory(
		local: List<SyncHistory>,
		remote: List<SyncHistory>,
		propagateDeletions: Boolean = true,
	): List<SyncHistory> =
		mergeBy(
			local.filterDeleted(propagateDeletions) { it.deletedAt },
			remote.filterDeleted(propagateDeletions) { it.deletedAt },
			key = { it.mangaId },
			timestamp = { maxOf(it.updatedAt, it.createdAt, it.deletedAt) },
		)

	fun mergeTracks(local: List<SyncTrack>, remote: List<SyncTrack>): List<SyncTrack> =
		mergeBy(local, remote, key = { it.mangaId }, timestamp = { it.lastCheckTime })

	/**
	 * Feed ids are local-only. Events are instead identified by manga plus their normalized chapter
	 * titles, so the same update detected independently on two devices still becomes one row.
	 */
	fun mergeFeed(
		local: List<SyncFeedEntry>,
		remote: List<SyncFeedEntry>,
		deletedHere: Set<String> = emptySet(),
		propagateDeletions: Boolean = true,
	): List<SyncFeedEntry> {
		val merged = LinkedHashMap<String, SyncFeedEntry>(local.size + remote.size)
		for (item in local + remote) {
			val key = feedIdentity(item)
			val existing = merged[key]
			merged[key] = if (existing == null) {
				item
			} else {
				SyncFeedEntry(
					mangaId = existing.mangaId,
					chapters = existing.chapters,
					chapterIds = when {
						existing.chapterIds.isBlank() -> item.chapterIds
						item.chapterIds.isBlank() -> existing.chapterIds
						item.createdAt > existing.createdAt -> item.chapterIds
						else -> existing.chapterIds
					},
					createdAt = listOf(existing.createdAt, item.createdAt)
						.filter { it > 0L }
						.minOrNull() ?: 0L,
					// A read on either device converges to read on every device.
					isUnread = existing.isUnread && item.isUnread,
					manga = if (item.createdAt > existing.createdAt) item.manga else existing.manga,
				)
			}
		}
		// Feed has no tombstones, so union alone resurrects anything deleted locally that still exists
		// remotely. When deletions propagate, drop the entries this device deleted since the last sync.
		if (propagateDeletions && deletedHere.isNotEmpty()) {
			merged.keys.removeAll(deletedHere)
		}
		return merged.values.toList()
	}

	fun feedIdentity(item: SyncFeedEntry): String = feedIdentity(item.mangaId, item.chapters)

	fun feedIdentity(mangaId: Long, chapters: String): String = buildString {
		append(mangaId)
		append(':')
		append(
			chapters.lineSequence()
				.map(String::trim)
				.filter(String::isNotEmpty)
				.map(String::lowercase)
				.distinct()
				.sorted()
				.joinToString("\n"),
		)
	}

	fun mergeStats(local: List<StatsBackup>, remote: List<StatsBackup>): List<StatsBackup> =
		mergeBy(local, remote, key = { it.mangaId to it.startedAt }, timestamp = { it.startedAt })

	fun mergeReaderAchievements(
		local: List<ReaderAchievementBackup>,
		remote: List<ReaderAchievementBackup>,
	): List<ReaderAchievementBackup> {
		val merged = LinkedHashMap<String, ReaderAchievementBackup>(local.size + remote.size)
		for (item in local + remote) {
			val existing = merged[item.achievementId]
			merged[item.achievementId] = if (existing == null) {
				item
			} else {
				ReaderAchievementBackup(
					achievementId = item.achievementId,
					unlockedAt = minPositive(existing.unlockedAt, item.unlockedAt),
				)
			}
		}
		return merged.values.toList()
	}


	/**
	 * Reader Journey is monotonic. Two devices reading the same chapter must converge to the largest
	 * known award/count instead of summing duplicate completion events.
	 */
	fun mergeReaderJourney(
		local: List<ReaderJourneyBackup>,
		remote: List<ReaderJourneyBackup>,
	): List<ReaderJourneyBackup> {
		val merged = LinkedHashMap<Pair<Long, Long>, ReaderJourneyBackup>(local.size + remote.size)
		for (item in local + remote) {
			val key = item.mangaId to item.chapterId
			val existing = merged[key]
			merged[key] = if (existing == null) {
				item
			} else {
				ReaderJourneyBackup(
					mangaId = existing.mangaId,
					chapterId = existing.chapterId,
					isNovel = existing.isNovel || item.isNovel,
					readingUnits = maxOf(existing.readingUnits, item.readingUnits),
					completionCount = maxOf(existing.completionCount, item.completionCount),
					awardedXp = maxOf(existing.awardedXp, item.awardedXp),
					firstCompletedAt = minPositive(existing.firstCompletedAt, item.firstCompletedAt),
					lastCompletedAt = maxOf(existing.lastCompletedAt, item.lastCompletedAt),
				)
			}
		}
		return merged.values
			.sortedWith(compareBy<ReaderJourneyBackup> { it.mangaId }.thenBy { it.chapterId })
	}

	// Scrobblings carry no timestamp column, so we use reading progress (chapter) as the effective
	// clock: progress only moves forward, so the higher chapter is the newer state. This lets
	// progress updates propagate across devices (the old "always keep local" never updated them).
	// Pure status/rating edits that don't advance the chapter still resolve to local on a tie.
	fun mergeScrobblings(
		local: List<ScrobblingBackup>,
		remote: List<ScrobblingBackup>,
	): List<ScrobblingBackup> =
		mergeBy(
			local,
			remote,
			key = { Triple(it.scrobbler, it.id, it.mangaId) },
			timestamp = { it.chapter.toLong() },
		)

	/** Union of bookmark groups by manga, and of items within a group by page (local wins). */
	fun mergeBookmarks(local: List<BookmarkBackup>, remote: List<BookmarkBackup>): List<BookmarkBackup> {
		val byManga = LinkedHashMap<Long, BookmarkBackup>(local.size + remote.size)
		for (group in local) {
			byManga[group.manga.id] = group
		}
		for (group in remote) {
			val existing = byManga[group.manga.id]
			if (existing == null) {
				byManga[group.manga.id] = group
			} else {
				val items = LinkedHashMap<Long, BookmarkBackup.Item>()
				for (item in existing.bookmarks) items[item.pageId] = item
				for (item in group.bookmarks) if (!items.containsKey(item.pageId)) items[item.pageId] = item
				byManga[group.manga.id] = BookmarkBackup(existing.manga, items.values.toList())
			}
		}
		return byManga.values.toList()
	}

	/**
	 * Combines several remote snapshots (duplicate Drive files from a first-run race) into one, using
	 * the same per-record rules. Row data is unioned; [SyncConfig] is taken from the higher revision.
	 * Folding is associative enough for this purpose — ties are vanishingly rare across duplicates.
	 */
	fun combine(snapshots: List<SyncSnapshot>): SyncSnapshot? = when {
		snapshots.isEmpty() -> null
		snapshots.size == 1 -> upgradeCategorySets(snapshots.single())
		else -> snapshots.map(::upgradeCategorySets).reduce(::mergeSnapshots)
	}

	private fun mergeSnapshots(a: SyncSnapshot, b: SyncSnapshot): SyncSnapshot {
		val config = when {
			a.config == null -> b.config
			b.config == null -> a.config
			b.config.revision > a.config.revision -> b.config
			else -> a.config
		}
		val favourites = mergeFavouriteState(a, b)
		return SyncSnapshot(
			deviceId = a.deviceId,
			syncedAt = maxOf(a.syncedAt, b.syncedAt),
			categorySets = favourites.sets,
			categories = favourites.categories,
			favourites = favourites.favourites,
			history = mergeHistory(a.history, b.history),
			bookmarks = mergeBookmarks(a.bookmarks, b.bookmarks),
			scrobblings = mergeScrobblings(a.scrobblings, b.scrobblings),
			tracks = mergeTracks(a.tracks, b.tracks),
			feed = mergeFeed(a.feed, b.feed),
			stats = mergeStats(a.stats, b.stats),
			readerJourney = mergeReaderJourney(a.readerJourney, b.readerJourney),
			readerAchievements = mergeReaderAchievements(a.readerAchievements, b.readerAchievements),
			config = config,
		)
	}

	private fun minPositive(a: Long, b: Long): Long = when {
		a <= 0L -> b
		b <= 0L -> a
		else -> minOf(a, b)
	}

	private inline fun <T, K> mergeBy(
		local: List<T>,
		remote: List<T>,
		key: (T) -> K,
		timestamp: (T) -> Long,
	): List<T> {
		val merged = LinkedHashMap<K, T>(local.size + remote.size)
		for (item in local) {
			merged[key(item)] = item
		}
		for (item in remote) {
			val k = key(item)
			val existing = merged[k]
			if (existing == null || timestamp(item) > timestamp(existing)) {
				merged[k] = item
			}
		}
		return merged.values.toList()
	}

	private inline fun <T> List<T>.filterDeleted(
		propagateDeletions: Boolean,
		deletedAt: (T) -> Long,
	): List<T> = if (propagateDeletions) this else filter { deletedAt(it) == 0L }
}
