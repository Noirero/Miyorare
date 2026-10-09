package org.koitharu.kotatsu.local.library

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import org.koitharu.kotatsu.local.library.SmartLocalCoverDiagnostics.Event
import org.koitharu.kotatsu.local.library.SmartLocalCoverDiagnostics.Reason

/** Owns only small derived images. Never opens sources or touches Reader/Coil storage. */
@Singleton
class SmartLocalCoverCache internal constructor(
    private val directory: File,
    private val maxBytes: Long = MAX_BYTES,
    private val maxEntries: Int = MAX_ENTRIES,
    internal val diagnostics: SmartLocalCoverDiagnostics = SmartLocalCoverDiagnostics(),
) {
    @Inject constructor(@ApplicationContext context: Context, diagnostics: SmartLocalCoverDiagnostics = SmartLocalCoverDiagnostics()) :
        this(File(context.filesDir, "smart-local-covers"), diagnostics = diagnostics)

    private val files = Mutex()
    private val flights = HashMap<Long, Flight>()
    private var initialized = false
    @Volatile internal var clearEpoch = 0L
        private set
    private var storedBytes = 0L
    private var storedEntries = 0
    private var lastAccess = 0L

    internal suspend fun getOrGenerate(id: Long, plan: LocalCoverPlan, isCurrent: () -> Boolean = { true }, generate: suspend () -> GeneratedLocalCover?): ByteArray? =
        getOrGenerateScoped(id, plan, isCurrent) { publish -> generate()?.let { publish(it) } }

    /** The generator publishes while holding its resource permit, bounding completed buffers too.
     * Cache lookup remains outside source scheduling, and this owner alone validates/writes bytes.
     */
    internal suspend fun getOrGenerateScoped(
        id: Long, plan: LocalCoverPlan, isCurrent: () -> Boolean = { true },
        generate: suspend (publish: suspend (GeneratedLocalCover) -> ByteArray) -> ByteArray?,
    ): ByteArray? = withContext(Dispatchers.IO) {
        val key = coverDigest(id.toString().toByteArray())
        withFlight(id) {
            if (!isCurrent()) return@withFlight null
            val (epoch, cached) = files.withLock {
                initializeLocked()
                clearEpoch to readLocked(key, plan)
            }
            if (cached != null) return@withFlight cached
            val started = System.nanoTime()
            try { generate { result ->
                check(result.candidateIndex in plan.candidates.indices)
                val payloadLimit = if (result.cacheable) MAX_THUMBNAIL_BYTES else BoundedArchiveCoverReader.MAX_CANDIDATE_BYTES
                check(result.bytes.size in 1..payloadLimit) { "Oversized local cover payload" }
                currentCoroutineContext().ensureActive()
                files.withLock {
                    // A settings clear during generation must not resurrect the removed entry.
                    event(Reason.GENERATED, key, plan, result.candidateIndex, result.bytes.size.toLong(),
                        System.nanoTime() - started, sourceOpened = true)
                    when {
                        !result.cacheable -> event(Reason.NOT_CACHEABLE, key, plan, result.candidateIndex,
                            result.bytes.size.toLong(), sourceOpened = true)
                        epoch != clearEpoch || !isCurrent() -> event(Reason.CLEAR_REJECTED, key, plan,
                            result.candidateIndex, result.bytes.size.toLong(), sourceOpened = true)
                        else -> try { writeLocked(key, plan, result) } catch (error: Exception) {
                            event(Reason.PUBLICATION_FAILED, key, plan, result.candidateIndex, sourceOpened = true)
                            throw error
                        }
                    }
                }
                result.bytes
            }.also { bytes ->
                if (bytes == null) diagnostics.record(Event(Reason.GENERATION_FAILED, cacheKey = key,
                    elapsedNanos = System.nanoTime() - started))
            } } catch (error: Exception) {
                diagnostics.record(Event(if (error is CancellationException) Reason.CANCELLED else Reason.GENERATION_FAILED,
                    cacheKey = key, elapsedNanos = System.nanoTime() - started))
                throw error
            }
        }
    }

    // Exact keys avoid an unrelated lightweight cover queueing behind a heavy stripe collision.
    // A waiting/cancelled caller owns a reference until finally, so the mutex cannot be replaced
    // while another caller still uses it. Finished flights retain no per-title state.
    private suspend fun <T> withFlight(id: Long, block: suspend () -> T): T {
        val flight = synchronized(flights) { flights.getOrPut(id) { Flight() }.also { it.users++ } }
        try {
            return flight.mutex.withLock { block() }
        } finally {
            synchronized(flights) { if (--flight.users == 0) flights.remove(id) }
        }
    }

    private class Flight(val mutex: Mutex = Mutex(), var users: Int = 0)

    suspend fun size(): Long = stats().bytes

    suspend fun stats(): SmartLocalCoverCacheStats = withContext(Dispatchers.IO) {
        files.withLock {
            initializeLocked()
            val sizes = directory.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".thumb") }.map { it.length() }.sorted()
            storedBytes = sizes.sum(); storedEntries = sizes.size
            fun percentile(percent: Int) = sizes.getOrNull(((sizes.size * percent + 99) / 100 - 1).coerceAtLeast(0)) ?: 0L
            SmartLocalCoverCacheStats(sizes.sum(), sizes.size, percentile(50), percentile(90), percentile(95), maxBytes, maxEntries)
        }
    }

    suspend fun report(): SmartLocalCoverCacheReport = SmartLocalCoverCacheReport(stats(), diagnostics.snapshot())
    fun resetDiagnostics() = diagnostics.reset()

    suspend fun clear() = withContext(Dispatchers.IO) {
        files.withLock {
            initializeLocked()
            clearEpoch++
            var cleared = false
            try {
                directory.listFiles().orEmpty().forEach { if (!it.delete()) throw IOException("Cannot clear derived cover") }
                cleared = true
            } finally {
                val remaining = directory.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".thumb") }
                storedBytes = remaining.sumOf { it.length() }; storedEntries = remaining.size
                event(if (cleared) Reason.CLEAR else Reason.CLEAR_FAILED)
            }
        }
    }

    private fun initializeLocked() {
        if (initialized) return
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create derived cover storage" }
        // Crash leftovers are never valid entries. Writes and cleanup share the same lock.
        directory.listFiles().orEmpty().filter { it.name.endsWith(".partial") }.forEach { it.delete() }
        val entries = directory.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".thumb") }
        storedBytes = entries.sumOf { it.length() }; storedEntries = entries.size
        trimLocked()
        lastAccess = directory.listFiles().orEmpty().maxOfOrNull { it.lastModified() } ?: 0L
        initialized = true
    }

    private fun readLocked(key: String, plan: LocalCoverPlan): ByteArray? {
        val file = File(directory, "$key.thumb")
        if (!file.isFile) { event(Reason.MISS_ABSENT, key, plan); return null }
        return try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC) throw IOException("Invalid thumbnail header")
                val index = input.readInt()
                val fingerprint = input.readUTF()
                if (index !in plan.candidates.indices || fingerprint != plan.fingerprintThrough(index)) {
                    event(Reason.MISS_FINGERPRINT, key, plan, index, file.length(), fingerprintMismatch = true)
                    return null
                }
                val length = input.readInt()
                if (length !in 1..MAX_THUMBNAIL_BYTES) throw IOException("Invalid thumbnail length")
                val digest = input.readUTF()
                val bytes = ByteArray(length)
                input.readFully(bytes)
                if (input.read() != -1 || coverDigest(bytes) != digest) throw IOException("Incomplete thumbnail")
                touchLocked(file)
                event(Reason.CACHE_HIT, key, plan, index, length.toLong())
                bytes
            }
        } catch (_: IOException) {
            val size = file.length()
            if (file.delete()) {
                // A corrupted/truncated entry can have changed size since the last publication.
                // Rescan only on this failure path rather than reporting stale byte totals.
                val remaining = directory.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".thumb") }
                storedBytes = remaining.sumOf { it.length() }; storedEntries = remaining.size
            }
            event(Reason.MISS_CORRUPT, key, plan, entryBytes = size)
            null
        }
    }

    private fun writeLocked(key: String, plan: LocalCoverPlan, result: GeneratedLocalCover) {
        val started = System.nanoTime()
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
            val previousBytes = target.length()
            val replacing = target.isFile
            check(temporary.renameTo(target)) { "Cannot publish derived thumbnail" }
            storedBytes += target.length() - previousBytes
            if (!replacing) storedEntries++
            touchLocked(target)
            event(Reason.PUBLISHED, key, plan, result.candidateIndex, result.bytes.size.toLong(),
                System.nanoTime() - started, sourceOpened = true)
            trimLocked()
        } finally { temporary.delete() }
    }

    private fun touchLocked(file: File) {
        lastAccess = maxOf(System.currentTimeMillis(), lastAccess + 1)
        file.setLastModified(lastAccess)
    }

    private fun trimLocked() {
        val entries = directory.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".thumb") }.sortedBy { it.lastModified() }
        var bytes = entries.sumOf { it.length() }
        var count = entries.size
        storedBytes = bytes; storedEntries = count
        val obsoleteBefore = System.currentTimeMillis() - MAX_IDLE_MS
        for (entry in entries) {
            if (bytes <= maxBytes && count <= maxEntries && entry.lastModified() >= obsoleteBefore) break
            val size = entry.length()
            val reason = when {
                bytes > maxBytes -> Reason.EVICT_BYTES
                count > maxEntries -> Reason.EVICT_ENTRY_COUNT
                else -> Reason.EVICT_IDLE
            }
            if (entry.delete()) {
                bytes -= size; count--
                storedBytes = bytes; storedEntries = count
                event(reason, entry.name.removeSuffix(".thumb"), entryBytes = size)
            }
        }
    }

    // Called under the file lock; counter/ring updates are O(1), with no extra file/source I/O.
    private fun event(reason: Reason, key: String? = null, plan: LocalCoverPlan? = null, index: Int? = null,
        entryBytes: Long = 0, elapsedNanos: Long = 0, sourceOpened: Boolean = false, fingerprintMismatch: Boolean = false) {
        val node = index?.let { plan?.candidates?.getOrNull(it) }
        val kind = node?.let {
            when (LocalTreeScanner.extension(it.name)) {
                "pdf" -> "PDF"
                "epub" -> "EPUB"
                "cbz", "zip" -> "ARCHIVE"
                else -> if (LocalTreeScanner.isSidecar(it.name)) "SIDECAR" else "IMAGE"
            }
        }
        diagnostics.record(Event(reason, key, kind, index, entryBytes, storedBytes, storedEntries,
            elapsedNanos, sourceOpened, fingerprintMismatch))
    }

    internal companion object {
        const val MAX_BYTES = 128L * 1024 * 1024
        const val MAX_ENTRIES = 1024
        const val MAX_THUMBNAIL_BYTES = 4 * 1024 * 1024
        private const val MAX_IDLE_MS = 30L * 24 * 60 * 60 * 1000
        private const val MAGIC = 0x534C4301
    }
}

