package org.koitharu.kotatsu.favourites.groups.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
abstract class LibraryGroupTrackingDao {

	@Query("SELECT * FROM library_group_tracking WHERE group_id = :groupId ORDER BY service ASC")
	abstract fun observe(groupId: Long): Flow<List<LibraryGroupTrackingEntity>>

	@Query("SELECT * FROM library_group_tracking WHERE group_id = :groupId ORDER BY service ASC")
	abstract suspend fun findAll(groupId: Long): List<LibraryGroupTrackingEntity>

	@Query("SELECT * FROM library_group_tracking WHERE group_id = :groupId AND service = :service LIMIT 1")
	abstract suspend fun find(groupId: Long, service: Int): LibraryGroupTrackingEntity?

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	abstract suspend fun upsert(entity: LibraryGroupTrackingEntity)

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	abstract suspend fun upsertAll(entities: Collection<LibraryGroupTrackingEntity>)

	@Query("DELETE FROM library_group_tracking WHERE group_id = :groupId AND service = :service")
	abstract suspend fun delete(groupId: Long, service: Int)

	@Query("DELETE FROM library_group_tracking WHERE group_id = :groupId")
	abstract suspend fun deleteAll(groupId: Long)
}
