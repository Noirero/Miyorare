package org.koitharu.kotatsu.favourites.domain

import android.content.Context
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.favourites.data.FavouriteMembership
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight persistent undo journal for Normal-library membership changes.
 *
 * Deltas are recorded by [FavouritesRepository] at the mutation source, so one action never requires
 * rescanning the whole library. Private Favourites never call this journal. Category deletion itself
 * is intentionally outside v1: restoring rows into a deleted category would create invisible state.
 */
@Singleton
class LibraryTimeMachine @Inject constructor(
	@ApplicationContext context: Context,
	private val database: MangaDatabase,
) {

	private data class Membership(val mangaId: Long, val categoryId: Long)
	private data class Action(
		val id: Long,
		val createdAt: Long,
		val added: Set<Membership>,
		val removed: Set<Membership>,
	)

	private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
	private val mutex = Mutex()

	suspend fun recordAdded(categoryId: Long, mangaIds: Collection<Long>): Long? {
		if (mangaIds.isEmpty()) return null
		return recordDelta(
			added = mangaIds.mapTo(LinkedHashSet(mangaIds.size)) { Membership(it, categoryId) },
			removed = emptySet(),
		)
	}

	suspend fun recordRemoved(categoryId: Long, mangaIds: Collection<Long>): Long? {
		if (mangaIds.isEmpty()) return null
		return recordDelta(
			added = emptySet(),
			removed = mangaIds.mapTo(LinkedHashSet(mangaIds.size)) { Membership(it, categoryId) },
		)
	}

	suspend fun recordRemoved(memberships: Collection<FavouriteMembership>): Long? {
		if (memberships.isEmpty()) return null
		return recordDelta(
			added = emptySet(),
			removed = memberships.mapTo(LinkedHashSet(memberships.size)) {
				Membership(it.mangaId, it.categoryId)
			},
		)
	}

	/** Remove one journal entry when the existing snackbar ReversibleHandle already reversed it. */
	suspend fun discard(actionId: Long?) {
		if (actionId == null) return
		mutex.withLock {
			val journal = readJournalLocked().filterNot { it.id == actionId }
			writeJournalLocked(journal)
		}
	}

	/** Reverts the latest recorded Normal-library membership delta. */
	suspend fun undoLatest(): Boolean = mutex.withLock {
		val now = System.currentTimeMillis()
		val journal = readJournalLocked().filter { now - it.createdAt <= RETENTION_MS }.toMutableList()
		val action = journal.removeLastOrNull() ?: return@withLock false
		val favouritesDao = database.getFavouritesDao()
		val activeCategoryIds = database.getFavouriteCategoriesDao().findAll().mapTo(HashSet()) { it.categoryId.toLong() }
		var changed = false
		database.withTransaction {
			for (item in action.added) {
				favouritesDao.delete(item.mangaId, item.categoryId)
				changed = true
			}
			for (item in action.removed) {
				if (item.categoryId !in activeCategoryIds) continue
				favouritesDao.recover(item.categoryId, item.mangaId)
				changed = true
			}
		}
		writeJournalLocked(journal)
		changed
	}

	fun hasUndo(): Boolean {
		val now = System.currentTimeMillis()
		return runCatching { readJournalLocked().any { now - it.createdAt <= RETENTION_MS } }.getOrDefault(false)
	}

	private suspend fun recordDelta(added: Set<Membership>, removed: Set<Membership>): Long? = mutex.withLock {
		if (added.isEmpty() && removed.isEmpty()) return@withLock null
		val deltaSize = added.size + removed.size
		if (deltaSize > MAX_DELTA_MEMBERSHIPS) {
			// Never leave an older action looking like it represents this oversized operation.
			writeJournalLocked(emptyList())
			return@withLock null
		}

		val now = System.currentTimeMillis()
		val journal = readJournalLocked()
			.filter { now - it.createdAt <= RETENTION_MS }
			.toMutableList()
		val id = maxOf(now, (journal.lastOrNull()?.id ?: 0L) + 1L)
		journal += Action(id = id, createdAt = now, added = added, removed = removed)
		while (
			journal.size > MAX_ACTIONS ||
			journal.sumOf { it.added.size + it.removed.size } > MAX_JOURNAL_MEMBERSHIPS
		) {
			journal.removeAt(0)
		}
		writeJournalLocked(journal)
		id
	}

	private fun readJournalLocked(): List<Action> {
		val raw = preferences.getString(KEY_JOURNAL, null) ?: return emptyList()
		return runCatching {
			val array = JSONArray(raw)
			buildList(array.length()) {
				for (index in 0 until array.length()) {
					val item = array.getJSONObject(index)
					val createdAt = item.optLong("at")
					add(
						Action(
							id = item.optLong("id", createdAt),
							createdAt = createdAt,
							added = item.optJSONArray("added").toMemberships(),
							removed = item.optJSONArray("removed").toMemberships(),
						),
					)
				}
			}
		}.getOrDefault(emptyList())
	}

	private fun writeJournalLocked(actions: List<Action>) {
		val array = JSONArray()
		for (action in actions) {
			array.put(
				JSONObject()
					.put("id", action.id)
					.put("at", action.createdAt)
					.put("added", action.added.toJson())
					.put("removed", action.removed.toJson()),
			)
		}
		preferences.edit().putString(KEY_JOURNAL, array.toString()).apply()
	}

	private fun Set<Membership>.toJson(): JSONArray = JSONArray().also { array ->
		for (item in this) array.put(JSONArray().put(item.mangaId).put(item.categoryId))
	}

	private fun JSONArray?.toMemberships(): Set<Membership> {
		if (this == null) return emptySet()
		return buildSet(length()) {
			for (index in 0 until length()) {
				val item = optJSONArray(index) ?: continue
				if (item.length() < 2) continue
				add(Membership(item.optLong(0), item.optLong(1)))
			}
		}
	}

	private companion object {
		const val PREFS_NAME = "library_time_machine"
		const val KEY_JOURNAL = "membership_journal"
		const val MAX_ACTIONS = 20
		const val MAX_DELTA_MEMBERSHIPS = 30_000
		const val MAX_JOURNAL_MEMBERSHIPS = 30_000
		const val RETENTION_MS = 7L * 24L * 60L * 60L * 1_000L
	}
}
