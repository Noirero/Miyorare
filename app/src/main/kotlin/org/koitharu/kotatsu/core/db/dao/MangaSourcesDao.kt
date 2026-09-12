package org.koitharu.kotatsu.core.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import org.koitharu.kotatsu.core.db.entity.MangaSourceEntity

@Dao
abstract class MangaSourcesDao {

	@Query("SELECT * FROM sources ORDER BY pinned DESC, sort_key")
	abstract suspend fun findAll(): List<MangaSourceEntity>

	@Query("SELECT * FROM sources ORDER BY pinned DESC, sort_key")
	abstract fun observeAll(): Flow<List<MangaSourceEntity>>

	@Query("UPDATE sources SET cf_state = :state WHERE source = :source")
	abstract suspend fun setCfState(source: String, state: Int)

	@Query("UPDATE sources SET title = :title WHERE source = :source")
	abstract suspend fun setTitle(source: String, title: String)

	@Upsert
	abstract suspend fun upsert(entry: MangaSourceEntity)

	fun dumpEnabled(): Flow<MangaSourceEntity> = flow {
		val window = 256
		var offset = 0
		while (currentCoroutineContext().isActive) {
			val list = findAllEnabled(offset, window)
			if (list.isEmpty()) {
				break
			}
			offset += window
			list.forEach { emit(it) }
		}
	}

	@Query("SELECT * FROM sources WHERE enabled = 1 ORDER BY source LIMIT :limit OFFSET :offset")
	protected abstract suspend fun findAllEnabled(offset: Int, limit: Int): List<MangaSourceEntity>
}
