package org.koitharu.kotatsu.favourites.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface FavouriteCategorySetsDao {
	@Query("SELECT * FROM favourite_category_sets WHERE space = :space AND deleted_at = 0 ORDER BY sort_key, sync_key")
	fun observeSets(space: Int): Flow<List<FavouriteCategorySetEntity>>

	@Query("SELECT * FROM favourite_category_sets WHERE space = :space AND deleted_at = 0 ORDER BY sort_key, sync_key")
	suspend fun getSets(space: Int): List<FavouriteCategorySetEntity>

	@Query("SELECT * FROM favourite_category_sets WHERE space = :space ORDER BY sort_key, sync_key")
	suspend fun getSetsForSync(space: Int): List<FavouriteCategorySetEntity>

	@Query("SELECT * FROM favourite_category_sets WHERE set_id = :id")
	suspend fun findSet(id: Long): FavouriteCategorySetEntity?

	@Query("SELECT * FROM favourite_category_sets WHERE sync_key = :key AND space = :space")
	suspend fun findSetByKey(key: String, space: Int): FavouriteCategorySetEntity?

	@Insert
	suspend fun insert(entity: FavouriteCategorySetEntity): Long

	@Upsert
	suspend fun upsert(entity: FavouriteCategorySetEntity)

	@Upsert
	suspend fun assign(entity: FavouriteCategorySetAssignmentEntity)

	@Query("SELECT * FROM favourite_category_set_assignments WHERE category_id = :id")
	suspend fun getAssignment(id: Long): FavouriteCategorySetAssignmentEntity?

	@Query("SELECT c.* FROM favourite_categories c INNER JOIN favourite_category_set_assignments a ON a.category_id = c.category_id WHERE a.category_sync_key = :key AND c.space = :space")
	suspend fun findCategoryByKey(key: String, space: Int): FavouriteCategoryEntity?

	@Query("SELECT a.* FROM favourite_category_set_assignments a INNER JOIN favourite_categories c ON c.category_id = a.category_id WHERE c.space = :space")
	suspend fun getAssignments(space: Int): List<FavouriteCategorySetAssignmentEntity>

	@Query("SELECT c.* FROM favourite_categories c INNER JOIN favourite_category_set_assignments a ON a.category_id = c.category_id INNER JOIN favourite_category_sets s ON s.set_id = a.set_id WHERE a.set_id = :setId AND c.space = :space AND s.space = :space AND c.deleted_at = 0 AND s.deleted_at = 0 ORDER BY c.sort_key, c.category_id")
	fun observeCategories(setId: Long, space: Int): Flow<List<FavouriteCategoryEntity>>

	@Query("SELECT c.* FROM favourite_categories c INNER JOIN favourite_category_set_assignments a ON a.category_id = c.category_id INNER JOIN favourite_category_sets s ON s.set_id = a.set_id WHERE a.set_id = :setId AND c.space = :space AND s.space = :space AND c.deleted_at = 0 AND s.deleted_at = 0 ORDER BY c.sort_key, c.category_id")
	suspend fun getCategories(setId: Long, space: Int): List<FavouriteCategoryEntity>
}
