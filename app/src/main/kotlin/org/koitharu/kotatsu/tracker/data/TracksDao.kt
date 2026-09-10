package org.koitharu.kotatsu.tracker.data

import android.database.DatabaseUtils
import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow
import org.koitharu.kotatsu.core.db.MangaQueryBuilder
import org.koitharu.kotatsu.favourites.data.FavouriteCategoryEntity
import org.koitharu.kotatsu.favourites.data.FavouriteEntity
import org.koitharu.kotatsu.favourites.data.PrivateFavouriteEntity
import org.koitharu.kotatsu.favourites.vault.PrivateFavouritesIsolation
import org.koitharu.kotatsu.list.domain.ListFilterOption

@Dao
abstract class TracksDao : MangaQueryBuilder.ConditionCallback {

	@Transaction
	@Query(
		"""
		SELECT * FROM tracks
		WHERE EXISTS(SELECT 1 FROM favourite_categories private_isolation_mode WHERE private_isolation_mode.category_id = -2147483000 AND private_isolation_mode.space = -1 AND private_isolation_mode.deleted_at = 0)
			OR NOT EXISTS(SELECT 1 FROM private_favourites pf WHERE pf.manga_id = tracks.manga_id AND pf.deleted_at = 0)
			OR EXISTS(SELECT 1 FROM favourites f WHERE f.manga_id = tracks.manga_id AND f.deleted_at = 0)
		ORDER BY last_check_time ASC LIMIT :limit OFFSET :offset
		""",
	)
	abstract suspend fun findAll(offset: Int, limit: Int): List<TrackWithManga>

	@Transaction
	@Query(
		"""
		SELECT * FROM tracks
		WHERE (
			EXISTS(SELECT 1 FROM favourite_categories private_isolation_mode WHERE private_isolation_mode.category_id = -2147483000 AND private_isolation_mode.space = -1 AND private_isolation_mode.deleted_at = 0)
			OR NOT EXISTS(SELECT 1 FROM private_favourites pf WHERE pf.manga_id = tracks.manga_id AND pf.deleted_at = 0)
			OR EXISTS(SELECT 1 FROM favourites nf WHERE nf.manga_id = tracks.manga_id AND nf.deleted_at = 0)
		)
		AND (
			(:trackHistory AND manga_id IN (SELECT manga_id FROM history WHERE deleted_at = 0))
			OR (
				:trackFavourites AND (
					manga_id IN (
						SELECT DISTINCT manga_id FROM favourites
						WHERE deleted_at = 0 AND category_id IN (
							SELECT category_id FROM favourite_categories
							WHERE (`track` = 1 OR download_new_chapters = 1) AND deleted_at = 0 AND space = 0
						)
					)
					OR (
						EXISTS(SELECT 1 FROM favourite_categories private_isolation_mode WHERE private_isolation_mode.category_id = -2147483000 AND private_isolation_mode.space = -1 AND private_isolation_mode.deleted_at = 0)
						AND manga_id IN (
							SELECT DISTINCT pf.manga_id FROM private_favourites pf
							WHERE pf.deleted_at = 0 AND pf.category_id IN (
								SELECT category_id FROM favourite_categories
								WHERE (`track` = 1 OR download_new_chapters = 1) AND deleted_at = 0 AND space = 1
							)
						)
					)
				)
			)
		)
		AND (NOT :skipCompleted OR manga_id NOT IN (SELECT manga_id FROM manga WHERE state = 'FINISHED'))
		AND (NOT :skipUnstarted OR manga_id IN (SELECT manga_id FROM history WHERE deleted_at = 0 AND percent > 0))
		AND (NOT :skipUnread OR IFNULL(chapters_new, 0) = 0)
		ORDER BY last_check_time ASC LIMIT :limit OFFSET :offset
		""",
	)
	abstract suspend fun findAllForChecking(
		trackHistory: Boolean,
		trackFavourites: Boolean,
		skipCompleted: Boolean,
		skipUnstarted: Boolean,
		skipUnread: Boolean,
		offset: Int,
		limit: Int,
	): List<TrackWithManga>

	@Transaction
	@Query(
		"""
		SELECT * FROM tracks
		WHERE EXISTS(SELECT 1 FROM favourite_categories private_isolation_mode WHERE private_isolation_mode.category_id = -2147483000 AND private_isolation_mode.space = -1 AND private_isolation_mode.deleted_at = 0)
			OR NOT EXISTS(SELECT 1 FROM private_favourites pf WHERE pf.manga_id = tracks.manga_id AND pf.deleted_at = 0)
			OR EXISTS(SELECT 1 FROM favourites f WHERE f.manga_id = tracks.manga_id AND f.deleted_at = 0)
		ORDER BY last_check_time DESC
		""",
	)
	abstract fun observeAll(): Flow<List<TrackWithManga>>

	@Query("SELECT manga_id FROM tracks")
	abstract suspend fun findAllIds(): LongArray

	@Query(
		"""
		SELECT * FROM tracks
		WHERE EXISTS(SELECT 1 FROM favourite_categories private_isolation_mode WHERE private_isolation_mode.category_id = -2147483000 AND private_isolation_mode.space = -1 AND private_isolation_mode.deleted_at = 0)
			OR NOT EXISTS(SELECT 1 FROM private_favourites pf WHERE pf.manga_id = tracks.manga_id AND pf.deleted_at = 0)
			OR EXISTS(SELECT 1 FROM favourites f WHERE f.manga_id = tracks.manga_id AND f.deleted_at = 0)
		""",
	)
	abstract suspend fun findAllForSync(): List<TrackEntity>

	@Query("SELECT * FROM tracks WHERE manga_id = :mangaId")
	abstract suspend fun find(mangaId: Long): TrackEntity?

	@Query("SELECT * FROM tracks WHERE manga_id IN (:mangaIds)")
	abstract suspend fun findByIds(mangaIds: Collection<Long>): List<TrackEntity>

	@Query("SELECT IFNULL(chapters_new,0) FROM tracks WHERE manga_id = :mangaId")
	abstract suspend fun findNewChapters(mangaId: Long): Int

	@Query(
		"""
		SELECT COUNT(*) FROM tracks
		WHERE EXISTS(SELECT 1 FROM favourite_categories private_isolation_mode WHERE private_isolation_mode.category_id = -2147483000 AND private_isolation_mode.space = -1 AND private_isolation_mode.deleted_at = 0)
			OR NOT EXISTS(SELECT 1 FROM private_favourites pf WHERE pf.manga_id = tracks.manga_id AND pf.deleted_at = 0)
			OR EXISTS(SELECT 1 FROM favourites f WHERE f.manga_id = tracks.manga_id AND f.deleted_at = 0)
		""",
	)
	abstract suspend fun getTracksCount(): Int

	@Query("SELECT IFNULL(chapters_new, 0) FROM tracks WHERE manga_id = :mangaId")
	abstract fun observeNewChapters(mangaId: Long): Flow<Int>

	@Transaction
	@Query(
		"""
		SELECT * FROM tracks
		WHERE chapters_new > 0
			AND (
				EXISTS(SELECT 1 FROM favourite_categories private_isolation_mode WHERE private_isolation_mode.category_id = -2147483000 AND private_isolation_mode.space = -1 AND private_isolation_mode.deleted_at = 0)
				OR NOT EXISTS(SELECT 1 FROM private_favourites pf WHERE pf.manga_id = tracks.manga_id AND pf.deleted_at = 0)
				OR EXISTS(SELECT 1 FROM favourites f WHERE f.manga_id = tracks.manga_id AND f.deleted_at = 0)
			)
		ORDER BY last_chapter_date DESC
		""",
	)
	abstract fun observeUpdatedManga(): Flow<List<MangaWithTrack>>

	fun observeUpdatedManga(
		limit: Int,
		filterOptions: Set<ListFilterOption>,
	): Flow<List<MangaWithTrack>> = observeMangaImpl(
		MangaQueryBuilder("tracks", this)
			.where("chapters_new > 0")
			.where(PRIVATE_SAFE_CONDITION)
			.filters(filterOptions)
			.limit(limit)
			.orderBy("last_chapter_date DESC")
			.build(),
	)

	fun observeAllTracks(
		limit: Int,
		filterOptions: Set<ListFilterOption>,
	): Flow<List<MangaWithTrack>> = observeMangaImpl(
		MangaQueryBuilder("tracks", this)
			.where(PRIVATE_SAFE_CONDITION)
			.filters(filterOptions)
			.limit(limit)
			.orderBy("last_chapter_date DESC")
			.build(),
	)

	@Query("DELETE FROM tracks")
	abstract suspend fun clear()

	@Query("UPDATE tracks SET chapters_new = 0")
	abstract suspend fun clearCounters()

	@Query("UPDATE tracks SET chapters_new = 0 WHERE manga_id = :mangaId")
	abstract suspend fun clearCounter(mangaId: Long)

	@Query("UPDATE tracks SET chapters_new = :count WHERE manga_id = :mangaId")
	abstract suspend fun setCounter(mangaId: Long, count: Int)

	@Query("DELETE FROM tracks WHERE manga_id = :mangaId")
	abstract suspend fun delete(mangaId: Long)

	@Query("DELETE FROM tracks WHERE manga_id NOT IN (SELECT manga_id FROM history WHERE history.deleted_at = 0 UNION SELECT manga_id FROM favourites WHERE favourites.deleted_at = 0 AND category_id IN (SELECT category_id FROM favourite_categories WHERE favourite_categories.deleted_at = 0 AND track = 1) UNION SELECT manga_id FROM private_favourites WHERE private_favourites.deleted_at = 0 AND EXISTS(SELECT 1 FROM favourite_categories private_isolation_mode WHERE private_isolation_mode.category_id = -2147483000 AND private_isolation_mode.space = -1 AND private_isolation_mode.deleted_at = 0) UNION SELECT manga_id FROM track_logs)")
	abstract suspend fun gc()

	@Upsert
	abstract suspend fun upsert(entity: TrackEntity)

	@Transaction
	@RawQuery(observedEntities = [TrackEntity::class, FavouriteEntity::class, PrivateFavouriteEntity::class, FavouriteCategoryEntity::class])
	protected abstract fun observeMangaImpl(query: SupportSQLiteQuery): Flow<List<MangaWithTrack>>

	override fun getCondition(option: ListFilterOption): String? = when (option) {
		ListFilterOption.Macro.FAVORITE -> "EXISTS(SELECT * FROM favourites WHERE favourites.manga_id = tracks.manga_id)"
		is ListFilterOption.Favorite -> "EXISTS(SELECT * FROM favourites WHERE favourites.manga_id = tracks.manga_id AND favourites.category_id = ${option.category.id})"
		is ListFilterOption.Tag -> "EXISTS(SELECT * FROM manga_tags WHERE manga_tags.manga_id = tracks.manga_id AND tag_id = ${option.tagId})"
		ListFilterOption.Macro.NSFW -> "(SELECT nsfw FROM manga WHERE manga.manga_id = tracks.manga_id) = 1"
		is ListFilterOption.State -> option.state?.let {
			"(SELECT state FROM manga WHERE manga.manga_id = tracks.manga_id) = ${DatabaseUtils.sqlEscapeString(it.name)}"
		}

		else -> null
	}

	private companion object {
		val PRIVATE_SAFE_CONDITION =
			"(" + PrivateFavouritesIsolation.DISABLED_MARKER_EXISTS_SQL +
				" OR NOT EXISTS(SELECT 1 FROM private_favourites pf WHERE pf.manga_id = tracks.manga_id AND pf.deleted_at = 0) " +
				"OR EXISTS(SELECT 1 FROM favourites f WHERE f.manga_id = tracks.manga_id AND f.deleted_at = 0))"
	}
}
