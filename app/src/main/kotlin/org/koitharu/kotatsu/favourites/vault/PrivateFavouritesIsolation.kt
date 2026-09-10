package org.koitharu.kotatsu.favourites.vault

import androidx.room.withTransaction
import org.koitharu.kotatsu.core.db.MangaDatabase

/**
 * Database-backed switch for the exceptional "keep it in Private, but remove all isolation" mode.
 *
 * The marker is stored as an internal favourite-category row with an impossible user-facing space
 * value, so it survives process restarts while staying out of Normal/Private category lists, local
 * backup category exports, and cloud category sync. App-wide privacy filters can cheaply test the
 * same marker from SQL without depending on SharedPreferences or DI.
 */
object PrivateFavouritesIsolation {

	const val DISABLED_MARKER_CATEGORY_ID = -2_147_483_000
	const val DISABLED_MARKER_SPACE = -1
	const val DISABLED_MARKER_EXISTS_SQL =
		"EXISTS(SELECT 1 FROM favourite_categories private_isolation_mode " +
			"WHERE private_isolation_mode.category_id = -2147483000 " +
			"AND private_isolation_mode.space = -1 AND private_isolation_mode.deleted_at = 0)"

	suspend fun setDisabled(database: MangaDatabase, disabled: Boolean) {
		database.withTransaction {
			setDisabledInCurrentTransaction(database, disabled)
		}
	}

	fun setDisabledInCurrentTransaction(database: MangaDatabase, disabled: Boolean) {
		val db = database.openHelper.writableDatabase
		if (disabled) {
			db.execSQL(
				"""
				INSERT OR REPLACE INTO favourite_categories
					(category_id, created_at, sort_key, title, `order`, `track`, download_new_chapters, show_in_lib, deleted_at, space)
				VALUES
					($DISABLED_MARKER_CATEGORY_ID, 0, 0, '__miyorare_private_isolation_disabled__', 'ALPHABETIC', 0, 0, 0, 0, $DISABLED_MARKER_SPACE)
				""".trimIndent(),
			)
		} else {
			db.execSQL(
				"DELETE FROM favourite_categories WHERE category_id = $DISABLED_MARKER_CATEGORY_ID AND space = $DISABLED_MARKER_SPACE",
			)
		}
	}

	fun isDisabled(database: MangaDatabase): Boolean {
		val query = "SELECT CASE WHEN $DISABLED_MARKER_EXISTS_SQL THEN 1 ELSE 0 END"
		return database.openHelper.readableDatabase.query(query).use { cursor ->
			cursor.moveToFirst() && cursor.getInt(0) != 0
		}
	}
}
