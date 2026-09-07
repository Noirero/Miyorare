package org.koitharu.kotatsu.favourites.groups.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
abstract class LibraryGroupsDao {

	@Query("SELECT * FROM library_groups ORDER BY created_at ASC, group_id ASC")
	abstract fun observeGroups(): Flow<List<LibraryGroupEntity>>

	@Query("SELECT * FROM library_group_members ORDER BY group_id ASC, position ASC, manga_id ASC")
	abstract fun observeMembers(): Flow<List<LibraryGroupMemberEntity>>

	@Query(
		"SELECT gm.group_id AS group_id, gm.manga_id AS manga_id, gm.position AS position, " +
			"COALESCE(p.title_override, m.title) AS display_title, " +
			"COALESCE(p.cover_override, m.cover_url) AS display_cover_url, " +
			"m.nsfw AS is_nsfw, COALESCE(p.content_rating_override, m.content_rating) AS content_rating, " +
			"m.source AS source " +
			"FROM library_group_members gm " +
			"INNER JOIN manga m ON m.manga_id = gm.manga_id " +
			"LEFT JOIN preferences p ON p.manga_id = gm.manga_id " +
			"ORDER BY gm.group_id ASC, gm.position ASC, gm.manga_id ASC",
	)
	abstract fun observeMemberDisplays(): Flow<List<LibraryGroupMemberDisplay>>

	@Query("SELECT * FROM library_groups WHERE group_id = :groupId LIMIT 1")
	abstract suspend fun findGroup(groupId: Long): LibraryGroupEntity?

	@Query("SELECT * FROM library_group_members WHERE group_id = :groupId ORDER BY position ASC, manga_id ASC")
	abstract suspend fun findMembers(groupId: Long): List<LibraryGroupMemberEntity>

	@Query(
		"SELECT gm.group_id AS group_id, gm.manga_id AS manga_id, gm.position AS position, " +
			"COALESCE(p.title_override, m.title) AS display_title, " +
			"COALESCE(p.cover_override, m.cover_url) AS display_cover_url, " +
			"m.nsfw AS is_nsfw, COALESCE(p.content_rating_override, m.content_rating) AS content_rating, " +
			"m.source AS source " +
			"FROM library_group_members gm " +
			"INNER JOIN manga m ON m.manga_id = gm.manga_id " +
			"LEFT JOIN preferences p ON p.manga_id = gm.manga_id " +
			"WHERE gm.group_id = :groupId " +
			"ORDER BY gm.position ASC, gm.manga_id ASC",
	)
	abstract suspend fun findMemberDisplays(groupId: Long): List<LibraryGroupMemberDisplay>

	@Query("SELECT * FROM library_group_members WHERE manga_id IN (:mangaIds)")
	abstract suspend fun findMembersByMangaIds(mangaIds: Collection<Long>): List<LibraryGroupMemberEntity>

	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertGroup(entity: LibraryGroupEntity): Long

	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertMembers(entities: Collection<LibraryGroupMemberEntity>)

	@Query("UPDATE library_groups SET title = :title, cover_url = :coverUrl WHERE group_id = :groupId")
	abstract suspend fun updateGroup(groupId: Long, title: String, coverUrl: String?)

	@Query("UPDATE library_group_members SET position = :position WHERE group_id = :groupId AND manga_id = :mangaId")
	abstract suspend fun updateMemberPosition(groupId: Long, mangaId: Long, position: Int)

	@Query("DELETE FROM library_group_members WHERE group_id = :groupId AND manga_id = :mangaId")
	abstract suspend fun deleteMember(groupId: Long, mangaId: Long)

	@Query("DELETE FROM library_groups WHERE group_id = :groupId")
	abstract suspend fun deleteGroup(groupId: Long)

	@Query("SELECT COUNT(*) FROM library_group_members WHERE group_id = :groupId")
	abstract suspend fun countMembers(groupId: Long): Int

	@Query(
		"DELETE FROM library_groups WHERE group_id IN (" +
			"SELECT library_groups.group_id FROM library_groups " +
			"LEFT JOIN library_group_members ON library_group_members.group_id = library_groups.group_id " +
			"GROUP BY library_groups.group_id HAVING COUNT(library_group_members.manga_id) < 2" +
			")",
	)
	abstract suspend fun deleteInvalidGroups()
}
