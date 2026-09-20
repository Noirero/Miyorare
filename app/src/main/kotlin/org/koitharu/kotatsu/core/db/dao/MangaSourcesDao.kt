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
		var afterSource: String? = null
		while (currentCoroutineContext().isActive) {
			val list = afterSource?.let { findEnabledAfter(it, window) } ?: findFirstEnabled(window)
			if (list.isEmpty()) break
			list.forEach { emit(it) }
			afterSource = list.last().source
		}
	}

	@Query("SELECT * FROM sources WHERE enabled = 1 ORDER BY source LIMIT :limit")
	protected abstract suspend fun findFirstEnabled(limit: Int): List<MangaSourceEntity>

	@Query("SELECT * FROM sources WHERE enabled = 1 AND source > :afterSource ORDER BY source LIMIT :limit")
	protected abstract suspend fun findEnabledAfter(afterSource: String, limit: Int): List<MangaSourceEntity>
}
