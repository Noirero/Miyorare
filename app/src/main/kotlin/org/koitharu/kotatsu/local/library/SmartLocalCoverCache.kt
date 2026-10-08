package org.koitharu.kotatsu.local.library

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Owns only small derived images. Never opens sources or touches Reader/Coil storage. */
@Singleton
class SmartLocalCoverCache internal constructor(
    private val directory: File,
    private val maxBytes: Long = MAX_BYTES,
    private val maxEntries: Int = MAX_ENTRIES,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.filesDir, "smart-local-covers"))

    private val files = Mutex()
    private val flights = Array(64) { Mutex() }
    private val generation = Semaphore(GENERATION_PARALLELISM)
    private var initialized = false
    private var clearEpoch = 0L
    private var lastAccess = 0L

    internal suspend fun getOrGenerate(id: Long, plan: LocalCoverPlan, isCurrent: () -> Boolean = { true }, generate: suspend () -> GeneratedLocalCover?): ByteArray? =
        withContext(Dispatchers.IO) {
            val key = coverDigest(id.toString().toByteArray())
            flights[(key.hashCode() and Int.MAX_VALUE) % flights.size].withLock {
                if (!isCurrent()) return@withContext null
                val epoch = files.withLock {
                    initializeLocked()
                    readLocked(key, plan)?.let { return@withContext it }
                    clearEpoch
                }
                generation.withPermit {
                    val result = generate() ?: return@withPermit null
                    check(result.candidateIndex in plan.candidates.indices)
                    val payloadLimit = if (result.cacheable) MAX_THUMBNAIL_BYTES else BoundedArchiveCoverReader.MAX_CANDIDATE_BYTES
                    check(result.bytes.size in 1..payloadLimit) { "Oversized local cover payload" }
                    currentCoroutineContext().ensureActive()
                    files.withLock {
                        // A settings clear during generation must not resurrect the removed entry.
                        if (result.cacheable && epoch == clearEpoch && isCurrent()) writeLocked(key, plan, result)
                    }
                    result.bytes
                }
            }
        }

    suspend fun size(): Long = withContext(Dispatchers.IO) {
        files.withLock { initializeLocked(); directory.listFiles().orEmpty().sumOf { it.length() } }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        files.withLock {
            clearEpoch++
            directory.listFiles().orEmpty().forEach { if (!it.delete()) throw IOException("Cannot clear derived cover") }
        }
    }

    private fun initializeLocked() {
        if (initialized) return
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create derived cover storage" }
        // Crash leftovers are never valid entries. Writes and cleanup share the same lock.
        directory.listFiles().orEmpty().filter { it.name.endsWith(".partial") }.forEach { it.delete() }
        trimLocked()
        lastAccess = directory.listFiles().orEmpty().maxOfOrNull { it.lastModified() } ?: 0L
        initialized = true
    }

    private fun readLocked(key: String, plan: LocalCoverPlan): ByteArray? {
        val file = File(directory, "$key.thumb")
        if (!file.isFile) return null
        return try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC) return null
                val index = input.readInt()
                if (index !in plan.candidates.indices || input.readUTF() != plan.fingerprintThrough(index)) return null
                val length = input.readInt()
                if (length !in 1..MAX_THUMBNAIL_BYTES) throw IOException("Invalid thumbnail length")
                val digest = input.readUTF()
                val bytes = ByteArray(length)
                input.readFully(bytes)
                if (input.read() != -1 || coverDigest(bytes) != digest) throw IOException("Incomplete thumbnail")
                touchLocked(file)
                bytes
            }
        } catch (_: IOException) {
            file.delete()
            null
        }
    }

    private fun writeLocked(key: String, plan: LocalCoverPlan, result: GeneratedLocalCover) {
        val temporary = File.createTempFile("cover-", ".partial", directory)
        try {
            temporary.outputStream().use { stream ->
                val output = DataOutputStream(stream)
                output.writeInt(MAGIC)
                output.writeInt(result.candidateIndex)
                output.writeUTF(plan.fingerprintThrough(result.candidateIndex))
                output.writeInt(result.bytes.size)
                output.writeUTF(coverDigest(result.bytes))
                output.write(result.bytes)
                output.flush()
                stream.fd.sync()
            }
            val target = File(directory, "$key.thumb")
            check(temporary.renameTo(target)) { "Cannot publish derived thumbnail" }
            touchLocked(target)
            trimLocked()
        } finally { temporary.delete() }
    }

    private fun touchLocked(file: File) {
        lastAccess = maxOf(System.currentTimeMillis(), lastAccess + 1)
        file.setLastModified(lastAccess)
    }

    private fun trimLocked() {
        val entries = directory.listFiles().orEmpty().filter { it.name.endsWith(".thumb") }.sortedBy { it.lastModified() }
        var bytes = entries.sumOf { it.length() }
        var count = entries.size
        val obsoleteBefore = System.currentTimeMillis() - MAX_IDLE_MS
        for (entry in entries) {
            if (bytes <= maxBytes && count <= maxEntries && entry.lastModified() >= obsoleteBefore) break
            val size = entry.length()
            if (entry.delete()) { bytes -= size; count-- }
        }
    }

    internal companion object {
        const val GENERATION_PARALLELISM = 2
        const val MAX_BYTES = 128L * 1024 * 1024
        const val MAX_ENTRIES = 1024
        const val MAX_THUMBNAIL_BYTES = 4 * 1024 * 1024
        private const val MAX_IDLE_MS = 30L * 24 * 60 * 60 * 1000
        private const val MAGIC = 0x534C4301
    }
}
