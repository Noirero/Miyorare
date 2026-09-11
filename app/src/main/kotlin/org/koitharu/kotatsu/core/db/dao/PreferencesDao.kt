package org.koitharu.kotatsu.core.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import org.koitharu.kotatsu.core.db.entity.MangaPrefsEntity

@Dao
abstract class PreferencesDao {

	@Query("SELECT * FROM preferences WHERE manga_id = :mangaId")
	abstract suspend fun find(mangaId: Long): MangaPrefsEntity?

	@Query("SELECT * FROM preferences WHERE manga_id IN (:mangaIds)")
	abstract suspend fun findAll(mangaIds: Collection<Long>): List<MangaPrefsEntity>

	@Query("SELECT * FROM preferences WHERE manga_id = :mangaId")
	abstract fun observe(mangaId: Long): Flow<MangaPrefsEntity?>

	/**
	 * Global export/sync view. Private-only overrides join it when the user explicitly disables all
	 * Private isolation while keeping the collection in the Private workspace.
	 */
	@Query(
		"""
		SELECT * FROM preferences
		WHERE (
			title_override IS NOT NULL OR cover_override IS NOT NULL OR content_rating_override IS NOT NULL OR
			author_override IS NOT NULL OR artist_override IS NOT NULL OR description_override IS NOT NULL OR
			merge_scanlators = 1
		)
		AND (
			EXISTS(SELECT 1 FROM favourite_categories private_isolation_mode WHERE private_isolation_mode.category_id = -2147483000 AND private_isolation_mode.space = -1 AND private_isolation_mode.deleted_at = 0)
			OR NOT EXISTS(SELECT 1 FROM private_favourites pf WHERE pf.manga_id = preferences.manga_id AND pf.deleted_at = 0)
			OR EXISTS(SELECT 1 FROM favourites f WHERE f.manga_id = preferences.manga_id AND f.deleted_at = 0)
		)
		""",
	)
	abstract suspend fun getOverrides(): List<MangaPrefsEntity>

	/** Internal rendering view: Private screens still need their per-manga overrides locally. */
	@Query(
		"""
		SELECT * FROM preferences
		WHERE title_override IS NOT NULL OR cover_override IS NOT NULL OR content_rating_override IS NOT NULL OR
			author_override IS NOT NULL OR artist_override IS NOT NULL OR description_override IS NOT NULL OR
			merge_scanlators = 1
		""",
	)
	abstract suspend fun getOverridesIncludingPrivate(): List<MangaPrefsEntity>

	@Query("UPDATE preferences SET author_override = :author, artist_override = :artist, description_override = :description WHERE manga_id = :mangaId")
	abstract suspend fun updateExtendedOverrides(mangaId: Long, author: String?, artist: String?, description: String?)

	@Query("UPDATE preferences SET cf_brightness = 0, cf_contrast = 0, cf_invert = 0, cf_grayscale = 0, cf_book = 0")
	abstract suspend fun resetColorFilters()

	@Query("DELETE FROM preferences WHERE manga_id = :mangaId")
	abstract suspend fun delete(mangaId: Long)

	@Upsert
	abstract suspend fun upsert(pref: MangaPrefsEntity)
}
