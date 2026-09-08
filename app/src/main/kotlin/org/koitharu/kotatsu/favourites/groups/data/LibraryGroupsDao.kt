package org.koitharu.kotatsu.favourites.groups.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
abstract class LibraryGroupsDao {

	@Query("SELECT * FROM library_groups WHERE space = :space ORDER BY created_at ASC, group_id ASC")
	abstract fun observeGroups(space: Int): Flow<List<LibraryGroupEntity>>

	@Query("SELECT * FROM library_groups WHERE space = :space ORDER BY created_at ASC, group_id ASC")
	abstract suspend fun findAllGroups(space: Int): List<LibraryGroupEntity>

	@Query(
		"SELECT gm.* FROM library_group_members gm " +
			"INNER JOIN library_groups g ON g.group_id = gm.group_id " +
			"WHERE g.space = :space ORDER BY gm.group_id ASC, gm.position ASC, gm.manga_id ASC",
	)
	abstract fun observeMembers(space: Int): Flow<List<LibraryGroupMemberEntity>>

	@Query(
		"SELECT gc.* FROM library_group_categories gc " +
			"INNER JOIN library_groups g ON g.group_id = gc.group_id " +
			"WHERE g.space = :space ORDER BY gc.group_id ASC, gc.category_id ASC",
	)
	abstract fun observeCategories(space: Int): Flow<List<LibraryGroupCategoryEntity>>

	@Query(
		"SELECT gm.group_id AS group_id, gm.manga_id AS manga_id, gm.position AS position, " +
			"COALESCE(p.title_override, m.title) AS display_title, " +
			"COALESCE(p.cover_override, m.cover_url) AS display_cover_url, " +
			"m.nsfw AS is_nsfw, COALESCE(p.content_rating_override, m.content_rating) AS content_rating, " +
			"m.source AS source " +
			"FROM library_group_members gm " +
			"INNER JOIN library_groups g ON g.group_id = gm.group_id " +
			"INNER JOIN manga m ON m.manga_id = gm.manga_id " +
			"LEFT JOIN preferences p ON p.manga_id = gm.manga_id " +
			"WHERE g.space = :space AND (" +
			"(:space = 0 AND EXISTS(SELECT 1 FROM favourites f WHERE f.manga_id = gm.manga_id AND f.deleted_at = 0)) OR " +
			"(:space = 1 AND EXISTS(SELECT 1 FROM private_favourites pf WHERE pf.manga_id = gm.manga_id AND pf.deleted_at = 0))" +
			") ORDER BY gm.group_id ASC, gm.position ASC, gm.manga_id ASC",
	)
	abstract fun observeMemberDisplays(space: Int): Flow<List<LibraryGroupMemberDisplay>>

	@Query("SELECT * FROM library_groups WHERE group_id = :groupId LIMIT 1")
	abstract suspend fun findGroup(groupId: Long): LibraryGroupEntity?

	@Query("SELECT * FROM library_group_members WHERE group_id = :groupId ORDER BY position ASC, manga_id ASC")
	abstract suspend fun findMembers(groupId: Long): List<LibraryGroupMemberEntity>

	@Query("SELECT * FROM library_group_categories WHERE group_id = :groupId ORDER BY category_id ASC")
	abstract suspend fun findCategories(groupId: Long): List<LibraryGroupCategoryEntity>

	@Query(
		"SELECT gm.group_id AS group_id, gm.manga_id AS manga_id, gm.position AS position, " +
			"COALESCE(p.title_override, m.title) AS display_title, " +
			"COALESCE(p.cover_override, m.cover_url) AS display_cover_url, " +
			"m.nsfw AS is_nsfw, COALESCE(p.content_rating_override, m.content_rating) AS content_rating, " +
			"m.source AS source " +
			"FROM library_group_members gm " +
			"INNER JOIN library_groups g ON g.group_id = gm.group_id " +
			"INNER JOIN manga m ON m.manga_id = gm.manga_id " +
			"LEFT JOIN preferences p ON p.manga_id = gm.manga_id " +
			"WHERE gm.group_id = :groupId AND g.space = :space AND (" +
			"(:space = 0 AND EXISTS(SELECT 1 FROM favourites f WHERE f.manga_id = gm.manga_id AND f.deleted_at = 0)) OR " +
			"(:space = 1 AND EXISTS(SELECT 1 FROM private_favourites pf WHERE pf.manga_id = gm.manga_id AND pf.deleted_at = 0))" +
			") ORDER BY gm.position ASC, gm.manga_id ASC",
	)
	abstract suspend fun findMemberDisplays(groupId: Long, space: Int): List<LibraryGroupMemberDisplay>

	@Query(
		"SELECT gm.* FROM library_group_members gm " +
			"INNER JOIN library_groups g ON g.group_id = gm.group_id " +
			"WHERE g.space = :space AND gm.manga_id IN (:mangaIds)",
	)
	abstract suspend fun findMembersByMangaIds(mangaIds: Collection<Long>, space: Int): List<LibraryGroupMemberEntity>

	@Query("SELECT * FROM library_group_timeline WHERE group_id = :groupId ORDER BY position ASC, manga_id ASC, chapter_id ASC")
	abstract fun observeTimeline(groupId: Long): Flow<List<LibraryGroupTimelineItemEntity>>

	@Query("SELECT * FROM library_group_timeline WHERE group_id = :groupId ORDER BY position ASC, manga_id ASC, chapter_id ASC")
	abstract suspend fun findTimeline(groupId: Long): List<LibraryGroupTimelineItemEntity>

	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertGroup(entity: LibraryGroupEntity): Long

	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertMembers(entities: Collection<LibraryGroupMemberEntity>)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertTimeline(entities: Collection<LibraryGroupTimelineItemEntity>)

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	abstract suspend fun insertCategories(entities: Collection<LibraryGroupCategoryEntity>)

	@Query("UPDATE library_groups SET title = :title, cover_url = :coverUrl WHERE group_id = :groupId")
	abstract suspend fun updateGroup(groupId: Long, title: String, coverUrl: String?)

	@Query("UPDATE library_group_members SET position = :position WHERE group_id = :groupId AND manga_id = :mangaId")
	abstract suspend fun updateMemberPosition(groupId: Long, mangaId: Long, position: Int)

	@Query("DELETE FROM library_group_timeline WHERE group_id = :groupId")
	abstract suspend fun deleteTimeline(groupId: Long)

	@Query("DELETE FROM library_group_categories WHERE group_id = :groupId")
	abstract suspend fun deleteCategories(groupId: Long)

	@Query("DELETE FROM library_group_members WHERE group_id = :groupId AND manga_id = :mangaId")
	abstract suspend fun deleteMember(groupId: Long, mangaId: Long)

	@Query(
		"DELETE FROM library_group_members WHERE " +
			"group_id IN (SELECT group_id FROM library_groups WHERE space = :space) AND NOT (" +
			"(:space = 0 AND EXISTS(SELECT 1 FROM favourites f WHERE f.manga_id = library_group_members.manga_id AND f.deleted_at = 0)) OR " +
			"(:space = 1 AND EXISTS(SELECT 1 FROM private_favourites pf WHERE pf.manga_id = library_group_members.manga_id AND pf.deleted_at = 0))" +
			")",
	)
	abstract suspend fun deleteMembersNotInLibrary(space: Int)

	@Query(
		"DELETE FROM library_group_categories WHERE " +
			"group_id IN (SELECT group_id FROM library_groups WHERE space = :space) AND " +
			"category_id NOT IN (SELECT category_id FROM favourite_categories WHERE deleted_at = 0 AND space = :space)",
	)
	abstract suspend fun deleteCategoriesNotInLibrary(space: Int)

	@Query("DELETE FROM library_groups WHERE group_id = :groupId")
	abstract suspend fun deleteGroup(groupId: Long)

	@Query("SELECT COUNT(*) FROM library_group_members WHERE group_id = :groupId")
	abstract suspend fun countMembers(groupId: Long): Int

	@Query(
		"DELETE FROM library_groups WHERE space = :space AND group_id IN (" +
			"SELECT g.group_id FROM library_groups g " +
			"LEFT JOIN library_group_members gm ON gm.group_id = g.group_id " +
			"WHERE g.space = :space GROUP BY g.group_id HAVING COUNT(gm.manga_id) < 2" +
			")",
	)
	abstract suspend fun deleteInvalidGroups(space: Int)
}
