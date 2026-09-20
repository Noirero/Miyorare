package org.koitharu.kotatsu.sync.domain

import org.koitharu.kotatsu.backup.local.data.model.BookmarkBackup
import org.koitharu.kotatsu.backup.local.data.model.ScrobblingBackup
import org.koitharu.kotatsu.backup.local.data.model.StatsBackup
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

	/**
	 * Category ids are per-device autoincrements, so the same id on two devices usually means two
	 * DIFFERENT categories — merging by raw id would overwrite one with the other and misfile its
	 * favourites. The normalized title is the only cross-device identity: rewrite remote category
	 * ids into the local id space (matched by title; unmatched remote categories keep their id or
	 * get a free one on collision) before any id-keyed merge. Favourites follow their category.
	 */
	fun remapRemoteCategories(
		remoteCategories: List<SyncCategory>,
		remoteFavourites: List<SyncFavourite>,
		localCategories: List<SyncCategory>,
	): Pair<List<SyncCategory>, List<SyncFavourite>> {
		if (remoteCategories.isEmpty()) {
			return remoteCategories to remoteFavourites
		}
		val localIdByTitle = HashMap<String, Int>(localCategories.size)
		// Live rows first so a recreated category wins over an old tombstone with the same title.
		for (c in localCategories.sortedBy { it.deletedAt != 0L }) {
			localIdByTitle.putIfAbsent(c.titleKey(), c.categoryId)
		}
		val usedIds = localCategories.mapTo(HashSet()) { it.categoryId }
		val remap = HashMap<Int, Int>(remoteCategories.size)
		for (c in remoteCategories) {
			val newId = localIdByTitle[c.titleKey()]
				?: c.categoryId.takeIf(usedIds::add)
				?: ((usedIds.max() + 1).also(usedIds::add))
			if (newId != c.categoryId) {
				remap[c.categoryId] = newId
			}
		}
		if (remap.isEmpty()) {
			return remoteCategories to remoteFavourites
		}
		val categories = remoteCategories.map { c ->
			remap[c.categoryId]?.let { c.copy(categoryId = it) } ?: c
		}
		val favourites = remoteFavourites.map { f ->
			remap[f.categoryId.toInt()]?.let { f.copy(categoryId = it.toLong()) } ?: f
		}
		return categories to favourites
	}

	private fun SyncCategory.titleKey(): String = title.trim().lowercase()

	fun mergeCategories(
		local: List<SyncCategory>,
		remote: List<SyncCategory>,
		propagateDeletions: Boolean = true,
	): List<SyncCategory> = mergeBy(
		local = local.filterDeleted(propagateDeletions) { it.deletedAt },
		remote = remote.filterDeleted(propagateDeletions) { it.deletedAt },
		key = { it.categoryId },
		timestamp = { maxOf(it.createdAt, it.deletedAt) },
	)

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
		snapshots.size == 1 -> snapshots.single()
		else -> snapshots.reduce(::mergeSnapshots)
	}

	private fun mergeSnapshots(a: SyncSnapshot, b: SyncSnapshot): SyncSnapshot {
		val config = when {
			a.config == null -> b.config
			b.config == null -> a.config
			b.config.revision > a.config.revision -> b.config
			else -> a.config
		}
		return SyncSnapshot(
			deviceId = a.deviceId,
			syncedAt = maxOf(a.syncedAt, b.syncedAt),
			categories = mergeCategories(a.categories, b.categories),
			favourites = mergeFavourites(a.favourites, b.favourites),
			history = mergeHistory(a.history, b.history),
			bookmarks = mergeBookmarks(a.bookmarks, b.bookmarks),
			scrobblings = mergeScrobblings(a.scrobblings, b.scrobblings),
			tracks = mergeTracks(a.tracks, b.tracks),
			feed = mergeFeed(a.feed, b.feed),
			stats = mergeStats(a.stats, b.stats),
			config = config,
		)
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
