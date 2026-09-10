package org.koitharu.kotatsu.favourites.vault

import androidx.room.withTransaction
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.TABLE_FAVOURITES
import org.koitharu.kotatsu.core.db.TABLE_FAVOURITE_CATEGORIES
import org.koitharu.kotatsu.core.db.TABLE_LIBRARY_GROUPS
import org.koitharu.kotatsu.core.db.TABLE_PRIVATE_FAVOURITES
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import javax.inject.Inject

/**
 * Permanently collapses the Private favourites space back into the Normal library.
 * Shared manga metadata, chapters, downloads and history are not touched.
 */
class DisablePrivateFavouritesUseCase @Inject constructor(
	private val database: MangaDatabase,
	private val security: PrivateFavouritesSecurityStore,
	private val session: PrivateFavouritesSession,
) {
	suspend operator fun invoke() {
		database.withTransaction {
			val db = database.openHelper.writableDatabase
			db.execSQL(
				"""
				INSERT OR IGNORE INTO $TABLE_FAVOURITES
					(manga_id, category_id, sort_key, pinned, created_at, deleted_at)
				SELECT private_membership.manga_id,
					private_membership.category_id,
					private_membership.sort_key,
					private_membership.pinned,
					private_membership.created_at,
					0
				FROM $TABLE_PRIVATE_FAVOURITES AS private_membership
				INNER JOIN $TABLE_FAVOURITE_CATEGORIES AS category
					ON category.category_id = private_membership.category_id
				WHERE private_membership.deleted_at = 0
					AND category.deleted_at = 0
					AND category.space = ${FavouriteSpace.PRIVATE.dbValue}
				""".trimIndent(),
			)
			db.execSQL(
				"UPDATE $TABLE_FAVOURITE_CATEGORIES SET space = ${FavouriteSpace.NORMAL.dbValue} " +
					"WHERE space = ${FavouriteSpace.PRIVATE.dbValue}",
			)
			db.execSQL(
				"UPDATE $TABLE_LIBRARY_GROUPS SET space = ${FavouriteSpace.NORMAL.dbValue} " +
					"WHERE space = ${FavouriteSpace.PRIVATE.dbValue}",
			)
			db.execSQL("DELETE FROM $TABLE_PRIVATE_FAVOURITES")
		}

		security.disableAllPrivateProtection()
		session.unlock()
	}
}
