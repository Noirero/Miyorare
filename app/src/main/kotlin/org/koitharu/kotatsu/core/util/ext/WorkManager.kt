package org.koitharu.kotatsu.core.util.ext

import android.annotation.SuppressLint
import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.WorkRequest
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.model.WorkSpec
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

@SuppressLint("RestrictedApi")
suspend fun WorkManager.deleteWork(id: UUID) = suspendCoroutine { cont ->
	workManagerImpl.workTaskExecutor.executeOnTaskThread {
		try {
			workManagerImpl.workDatabase.workSpecDao().delete(id.toString())
			cont.resume(Unit)
		} catch (e: Exception) {
			cont.resumeWithException(e)
		}
	}
}

@SuppressLint("RestrictedApi")
suspend fun WorkManager.deleteWorks(ids: Collection<UUID>) = suspendCoroutine { cont ->
	workManagerImpl.workTaskExecutor.executeOnTaskThread {
		try {
			val db = workManagerImpl.workDatabase
			db.runInTransaction {
				for (id in ids) {
					db.workSpecDao().delete(id.toString())
				}
			}
			cont.resume(Unit)
		} catch (e: Exception) {
			cont.resumeWithException(e)
		}
	}
}

@SuppressLint("RestrictedApi")
suspend fun WorkManager.awaitWorkInfosByTag(tag: String): List<WorkInfo> {
	return getWorkInfosByTag(tag).await()
}

@SuppressLint("RestrictedApi")
suspend fun WorkManager.awaitFinishedWorkInfosByTag(tag: String): List<WorkInfo> {
	val query = WorkQuery.Builder.fromTags(listOf(tag))
		.addStates(listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.CANCELLED, WorkInfo.State.FAILED))
		.build()
	return getWorkInfos(query).await()
}

@SuppressLint("RestrictedApi")
suspend fun WorkManager.awaitUniqueWorkInfoByName(name: String): List<WorkInfo> {
	return getWorkInfosForUniqueWork(name).await()
}

@SuppressLint("RestrictedApi")
suspend fun WorkManager.awaitUpdateWork(request: WorkRequest): WorkManager.UpdateResult {
	return updateWork(request).await()
}

@SuppressLint("RestrictedApi")
suspend fun WorkManager.getWorkSpec(id: UUID): WorkSpec? = suspendCoroutine { cont ->
	workManagerImpl.workTaskExecutor.executeOnTaskThread {
		try {
			val spec = workManagerImpl.workDatabase.workSpecDao().getWorkSpec(id.toString())
			cont.resume(spec)
		} catch (e: Exception) {
			cont.resumeWithException(e)
		}
	}
}

@SuppressLint("RestrictedApi")
suspend fun WorkManager.getWorkInputData(id: UUID): Data? = getWorkSpec(id)?.input

internal data class WorkSpecInput(val data: Data, val scheduleRequestedAt: Long)

private const val WORK_SPEC_READ_BATCH = 32

/** Fresh inputs for one model conversion; yield the WorkManager executor between bounded batches. */
@SuppressLint("RestrictedApi")
internal suspend fun WorkManager.readWorkSpecInputs(ids: List<UUID>): Map<UUID, WorkSpecInput> {
	val inputs = HashMap<UUID, WorkSpecInput>(ids.size)
	for (batch in ids.chunked(WORK_SPEC_READ_BATCH)) {
		val result = suspendCancellableCoroutine<List<Pair<UUID, WorkSpecInput>>> { cont ->
			workManagerImpl.workTaskExecutor.executeOnTaskThread {
				if (!cont.isActive) return@executeOnTaskThread
				try {
					val dao = workManagerImpl.workDatabase.workSpecDao()
					cont.resume(batch.mapNotNull { id ->
						dao.getWorkSpec(id.toString())?.let { id to WorkSpecInput(it.input, it.scheduleRequestedAt) }
					})
				} catch (e: Exception) {
					cont.resumeWithException(e)
				}
			}
		}
		for ((id, input) in result) inputs[id] = input
	}
	return inputs
}

val Data.isEmpty: Boolean
	get() = this == Data.EMPTY

private val WorkManager.workManagerImpl
	@SuppressLint("RestrictedApi") inline get() = this as WorkManagerImpl
