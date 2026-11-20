package org.koitharu.kotatsu.favourites.vault

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.TABLE_FAVOURITES
import org.koitharu.kotatsu.core.db.TABLE_FAVOURITE_CATEGORIES
import org.koitharu.kotatsu.core.db.TABLE_LIBRARY_GROUPS
import org.koitharu.kotatsu.core.db.TABLE_PRIVATE_FAVOURITES
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

enum class DisablePrivateFavouritesDestination {
	KEEP_PRIVATE,
	MOVE_TO_NORMAL,
}

/**
 * Removes every Private-specific protection, then either keeps the library organised in the Private
 * space with app-wide isolation disabled, or permanently collapses that space back into Normal.
 * Shared manga metadata, chapters, downloads and history are never deleted.
 */
class DisablePrivateFavouritesUseCase @Inject constructor(
	private val database: MangaDatabase,
	private val security: PrivateFavouritesSecurityStore,
	private val session: PrivateFavouritesSession,
) {
	suspend operator fun invoke(destination: DisablePrivateFavouritesDestination) {
		// KEEP_PRIVATE must not silently opt the collection into backup. Preserve the user's explicit
		// choice made in Private settings; MOVE_TO_NORMAL no longer needs a separate Private payload.
		val includePrivateInBackup =
			destination == DisablePrivateFavouritesDestination.KEEP_PRIVATE && security.includePrivateInBackup
		database.withTransaction {
			when (destination) {
				DisablePrivateFavouritesDestination.KEEP_PRIVATE -> {
					// Keep every Private membership/category/group exactly where it is. The database marker
					// is consumed by global History/Search/Stats/Sync/etc. privacy filters, making these rows
					// behave like Normal content outside the Private workspace without moving them.
					PrivateFavouritesIsolation.setDisabledInCurrentTransaction(database, disabled = true)
				}

				DisablePrivateFavouritesDestination.MOVE_TO_NORMAL -> {
					PrivateFavouritesIsolation.setDisabledInCurrentTransaction(database, disabled = false)
					movePrivateCollectionToNormal()
				}
			}
		}

		security.disableAllPrivateProtection(includePrivateInBackup = includePrivateInBackup)
		session.unlock()
	}

	private fun movePrivateCollectionToNormal() {
		// This entire conversion runs inside the enclosing Room transaction.
		val db = database.openHelper.writableDatabase
		val normal = FavouriteSpace.NORMAL.dbValue
		val privateSpace = FavouriteSpace.PRIVATE.dbValue
		fun count(sql: String): Long = db.query(sql).use { cursor ->
			check(cursor.moveToFirst())
			cursor.getLong(0)
		}
		fun requireEmpty(sql: String, reason: String) {
			check(count(sql) == 0L) { reason }
		}

		// Do not silently repair corrupt ownership or drop Group data.
		requireEmpty(
			"""SELECT COUNT(*) FROM favourite_categories c
				LEFT JOIN favourite_category_set_assignments a ON a.category_id = c.category_id
				LEFT JOIN favourite_category_sets s ON s.set_id = a.set_id
				WHERE c.space = $privateSpace AND
				(a.category_id IS NULL OR s.set_id IS NULL OR s.space != $privateSpace
				 OR s.deleted_at != 0 OR a.category_sync_key = '')""",
			"Private category has an invalid Set assignment",
		)
		requireEmpty(
			"""SELECT COUNT(*) FROM $TABLE_LIBRARY_GROUPS g
				LEFT JOIN favourite_category_sets s ON s.set_id = g.set_id
				WHERE g.space = $privateSpace AND
				(s.set_id IS NULL OR s.space != $privateSpace OR s.deleted_at != 0)""",
			"Private group has an invalid Set owner",
		)
		requireEmpty(
			"""SELECT COUNT(*) FROM library_group_categories gc
				JOIN $TABLE_LIBRARY_GROUPS g ON g.group_id = gc.group_id
				LEFT JOIN favourite_categories c ON c.category_id = gc.category_id
				LEFT JOIN favourite_category_set_assignments a ON a.category_id = c.category_id
				WHERE g.space = $privateSpace AND
				(c.category_id IS NULL OR c.space != $privateSpace OR c.deleted_at != 0
				 OR a.set_id IS NULL OR a.set_id != g.set_id)""",
			"Private group placement has a different Set owner",
		)
		requireEmpty(
			"""SELECT COUNT(*) FROM $TABLE_PRIVATE_FAVOURITES p
				LEFT JOIN favourite_categories c ON c.category_id = p.category_id
				WHERE p.deleted_at = 0 AND
				(c.category_id IS NULL OR c.space != $privateSpace OR c.deleted_at != 0)""",
			"Private favourite has no active Private category",
		)
		requireEmpty(
			"""SELECT COUNT(*) FROM library_group_members m
				JOIN $TABLE_LIBRARY_GROUPS g ON g.group_id = m.group_id
				WHERE g.space = $privateSpace AND NOT EXISTS (
					SELECT 1 FROM $TABLE_PRIVATE_FAVOURITES p
					JOIN favourite_categories c ON c.category_id = p.category_id
					WHERE p.manga_id = m.manga_id AND p.deleted_at = 0
					  AND c.space = $privateSpace AND c.deleted_at = 0)""",
			"Private Group member is not an active Private favourite",
		)
		// Legacy Group repository and backup paths enforce uniqueness per space, not per Set.
		requireEmpty(
			"""SELECT COUNT(*) FROM library_group_members incoming
				JOIN $TABLE_LIBRARY_GROUPS g ON g.group_id = incoming.group_id
				JOIN library_group_members other ON other.manga_id = incoming.manga_id
					AND other.group_id != incoming.group_id
				JOIN $TABLE_LIBRARY_GROUPS owner ON owner.group_id = other.group_id
				WHERE g.space = $privateSpace AND owner.space IN ($normal, $privateSpace)""",
			"Private-to-Normal library group membership conflict",
		)
		requireEmpty(
			"""SELECT COUNT(*) FROM $TABLE_FAVOURITES f
				JOIN $TABLE_PRIVATE_FAVOURITES p
					ON p.manga_id = f.manga_id AND p.category_id = f.category_id
				WHERE p.deleted_at = 0""",
			"Normal library already contains a Private category membership",
		)

		data class SourceSet(val id: Long, val title: String, val createdAt: Long)
		data class SourceCategory(val id: Long, val owner: Long, val key: String, val updatedAt: Long)
		data class SourceGroup(val id: Long, val owner: Long)

		val sets = ArrayList<SourceSet>()
		db.query(
			"SELECT set_id, title, created_at FROM favourite_category_sets " +
				"WHERE space = $privateSpace AND deleted_at = 0 ORDER BY sort_key, sync_key",
		).use { c ->
			while (c.moveToNext()) sets += SourceSet(c.getLong(0), c.getString(1), c.getLong(2))
		}
		val categories = ArrayList<SourceCategory>()
		db.query(
			"""SELECT c.category_id, a.set_id, a.category_sync_key, a.updated_at
				FROM favourite_categories c
				JOIN favourite_category_set_assignments a ON a.category_id = c.category_id
				WHERE c.space = $privateSpace ORDER BY c.category_id""",
		).use { c ->
			while (c.moveToNext()) categories += SourceCategory(c.getLong(0), c.getLong(1), c.getString(2), c.getLong(3))
		}
		val groups = ArrayList<SourceGroup>()
		db.query("SELECT group_id, set_id FROM $TABLE_LIBRARY_GROUPS WHERE space = $privateSpace ORDER BY group_id").use { c ->
			while (c.moveToNext()) groups += SourceGroup(c.getLong(0), c.getLong(1))
		}
		// Avoid cloning the empty Private default Set on repeated calls.
		if (categories.isEmpty() && groups.isEmpty()) {
			check(count("SELECT COUNT(*) FROM $TABLE_PRIVATE_FAVOURITES WHERE deleted_at = 0") == 0L)
			return
		}
		check(sets.isNotEmpty()) { "No active Private Set" }

		val usedTitles = HashSet<String>()
		db.query("SELECT title FROM favourite_category_sets WHERE space = $normal AND deleted_at = 0").use { c ->
			while (c.moveToNext()) usedTitles += c.getString(0).trim().lowercase(Locale.ROOT)
		}
		var nextPosition = count("SELECT COALESCE(MAX(sort_key), -1) FROM favourite_category_sets WHERE space = $normal AND deleted_at = 0")
		val mapped = HashMap<Long, Long>(sets.size)
		val now = System.currentTimeMillis()
		for (set in sets) {
			val title = set.title.trim()
			check(title.isNotEmpty()) { "Blank Private Set title" }
			var label = title
			var suffix = 1
			while (!usedTitles.add(label.lowercase(Locale.ROOT))) {
				label = title + " (Private" + (if (suffix == 1) "" else " $suffix") + ")"
				suffix++
			}
			nextPosition++
			check(nextPosition <= Int.MAX_VALUE) { "Normal Set ordering overflow" }
			val values = ContentValues().apply {
				put("sync_key", UUID.randomUUID().toString())
				put("title", label)
				put("sort_key", nextPosition.toInt())
				put("created_at", set.createdAt)
				put("updated_at", now)
				put("deleted_at", 0L)
				put("space", normal)
			}
			val destinationId = db.insert("favourite_category_sets", SQLiteDatabase.CONFLICT_ABORT, values)
			check(destinationId > 0) { "Cannot create Normal destination Set" }
			mapped[set.id] = destinationId
		}

		// Preserve the original membership fields; never silently ignore an unexpected conflict.
		db.execSQL(
			"""INSERT INTO $TABLE_FAVOURITES(manga_id,category_id,sort_key,pinned,created_at,deleted_at)
				SELECT p.manga_id,p.category_id,p.sort_key,p.pinned,p.created_at,0
				FROM $TABLE_PRIVATE_FAVOURITES p
				JOIN favourite_categories c ON c.category_id = p.category_id
				WHERE p.deleted_at = 0 AND c.deleted_at = 0 AND c.space = $privateSpace""",
		)
		// The old assignment must be detached before changing Category.space.
		db.execSQL(
			"DELETE FROM favourite_category_set_assignments WHERE category_id IN " +
				"(SELECT category_id FROM favourite_categories WHERE space = $privateSpace)",
		)
		db.execSQL("UPDATE $TABLE_FAVOURITE_CATEGORIES SET space = $normal WHERE space = $privateSpace")
		for (category in categories) {
			val setId = checkNotNull(mapped[category.owner]) { "Unmapped Private category Set" }
			check(category.updatedAt < Long.MAX_VALUE)
			db.execSQL(
				"INSERT INTO favourite_category_set_assignments(category_id,set_id,category_sync_key,updated_at) VALUES(?,?,?,?)",
				arrayOf<Any>(category.id, setId, category.key, maxOf(category.updatedAt + 1, now)),
			)
		}
		for (group in groups) {
			val setId = checkNotNull(mapped[group.owner]) { "Unmapped Private Group owner" }
			db.execSQL(
				"UPDATE $TABLE_LIBRARY_GROUPS SET space = $normal, set_id = ? WHERE group_id = ?",
				arrayOf<Any>(setId, group.id),
			)
		}
		db.execSQL("DELETE FROM $TABLE_PRIVATE_FAVOURITES")
		requireEmpty(
			"""SELECT COUNT(*) FROM $TABLE_LIBRARY_GROUPS g
				LEFT JOIN favourite_category_sets s ON s.set_id = g.set_id
				WHERE g.space = $normal AND
				(s.set_id IS NULL OR s.space != $normal OR s.deleted_at != 0)""",
			"Invalid Normal Group owner",
		)
		requireEmpty(
			"""SELECT COUNT(*) FROM favourite_categories c
				LEFT JOIN favourite_category_set_assignments a ON a.category_id = c.category_id
				LEFT JOIN favourite_category_sets s ON s.set_id = a.set_id
				WHERE c.space = $normal AND
				(a.category_id IS NULL OR s.set_id IS NULL OR s.space != $normal OR s.deleted_at != 0)""",
			"Invalid Normal category assignment",
		)
		check(count("SELECT COUNT(*) FROM favourite_categories WHERE space = $privateSpace") == 0L)
		check(count("SELECT COUNT(*) FROM $TABLE_LIBRARY_GROUPS WHERE space = $privateSpace") == 0L)
		db.query("PRAGMA foreign_key_check").use { check(!it.moveToFirst()) { "Private-to-Normal broke a foreign key" } }
	}
}
