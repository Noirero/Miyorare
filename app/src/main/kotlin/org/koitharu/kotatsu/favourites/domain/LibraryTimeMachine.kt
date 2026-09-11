package org.koitharu.kotatsu.favourites.domain

import android.content.Context
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.TABLE_FAVOURITES
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight persistent undo journal for Normal-library membership changes.
 *
 * The hot database stays untouched: snapshots live in memory and only the small delta for a change
 * is persisted. Private Favourites are deliberately outside this observer because they use a
 * separate table. Category deletion is also skipped: restoring membership into a deleted category
 * would create invisible/stale rows rather than a trustworthy undo.
 */
@Singleton
class LibraryTimeMachine @Inject constructor(
	@ApplicationContext context: Context,
	private val database: MangaDatabase,
) {

	private data class Membership(val mangaId: Long, val categoryId: Long)
	private data class Action(
		val createdAt: Long,
		val added: Set<Membership>,
		val removed: Set<Membership>,
	)

	private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
	private val started = AtomicBoolean(false)
	private val mutex = Mutex()
	private var baseline: Set<Membership>? = null
	private var suppressedSnapshot: Set<Membership>? = null

	/** Starts one process-wide observer. This function intentionally collects for the process life. */
	suspend fun start() {
		if (!started.compareAndSet(false, true)) return
		mutex.withLock {
			baseline = currentMemberships()
			pruneJournalLocked(System.currentTimeMillis())
		}
		database.invalidationTracker
			.createFlow(TABLE_FAVOURITES, emitInitialState = false)
			.collect {
				mutex.withLock {
					val current = currentMemberships()
					val suppressed = suppressedSnapshot
					if (suppressed != null && current == suppressed) {
						suppressedSnapshot = null
						baseline = current
						return@withLock
					}

					val previous = baseline ?: current
					baseline = current
					if (previous == current) return@withLock
					val added = current - previous
					val removed = previous - current
					if (added.isEmpty() && removed.isEmpty()) return@withLock
					if (added.size + removed.size > MAX_DELTA_MEMBERSHIPS) return@withLock

					// Category deletion is a structurally different operation and cannot be made safe by merely
					// recovering membership tombstones. Do not create a misleading undo entry for it.
					for (categoryId in removed.asSequence().map { it.categoryId }.distinct()) {
						val category = database.getFavouriteCategoriesDao().find(categoryId.toInt())
						if (category.deletedAt != 0L) return@withLock
					}
					appendActionLocked(
						Action(
							createdAt = System.currentTimeMillis(),
							added = added,
							removed = removed,
						),
					)
				}
			}
	}

	/** Reverts the latest recorded Normal-library membership delta. */
	suspend fun undoLatest(): Boolean = mutex.withLock {
		val now = System.currentTimeMillis()
		val journal = readJournalLocked().filter { now - it.createdAt <= RETENTION_MS }.toMutableList()
		val action = journal.removeLastOrNull() ?: return@withLock false
		val dao = database.getFavouritesDao()
		database.withTransaction {
			// Rows that appeared in the recorded change are removed; rows that disappeared are recovered.
			for (item in action.added) dao.delete(item.mangaId, item.categoryId)
			for (item in action.removed) dao.recover(item.categoryId, item.mangaId)
		}
		writeJournalLocked(journal)
		val current = currentMemberships()
		baseline = current
		// Room emits invalidation after commit. Suppress exactly the snapshot produced by this replay so
		// undo itself never becomes a new Time Machine entry.
		suppressedSnapshot = current
		true
	}

	fun hasUndo(): Boolean {
		val now = System.currentTimeMillis()
		return runCatching { readJournalLocked().any { now - it.createdAt <= RETENTION_MS } }.getOrDefault(false)
	}

	private suspend fun currentMemberships(): Set<Membership> = database.getFavouritesDao()
		.findMemberships()
		.mapTo(LinkedHashSet()) { Membership(it.mangaId, it.categoryId) }

	private fun appendActionLocked(action: Action) {
		val journal = readJournalLocked().toMutableList()
		journal += action
		while (journal.size > MAX_ACTIONS) journal.removeAt(0)
		writeJournalLocked(journal)
	}

	private fun pruneJournalLocked(now: Long) {
		val current = readJournalLocked()
		val pruned = current.filter { now - it.createdAt <= RETENTION_MS }.takeLast(MAX_ACTIONS)
		if (pruned.size != current.size) writeJournalLocked(pruned)
	}

	private fun readJournalLocked(): List<Action> {
		val raw = preferences.getString(KEY_JOURNAL, null) ?: return emptyList()
		return runCatching {
			val array = JSONArray(raw)
			buildList(array.length()) {
				for (index in 0 until array.length()) {
					val item = array.getJSONObject(index)
					add(
						Action(
							createdAt = item.optLong("at"),
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
					.put("at", action.createdAt)
					.put("added", action.added.toJson())
					.put("removed", action.removed.toJson()),
			)
		}
		preferences.edit().putString(KEY_JOURNAL, array.toString()).apply()
	}

	private fun Set<Membership>.toJson(): JSONArray = JSONArray().also { array ->
		for (item in this) {
			array.put(JSONArray().put(item.mangaId).put(item.categoryId))
		}
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
		const val MAX_DELTA_MEMBERSHIPS = 500
		const val RETENTION_MS = 7L * 24L * 60L * 60L * 1_000L
	}
}
