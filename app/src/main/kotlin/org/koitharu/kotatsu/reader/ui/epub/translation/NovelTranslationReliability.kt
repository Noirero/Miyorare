package org.koitharu.kotatsu.reader.ui.epub.translation

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Output-affecting provider identity. Raw endpoint/model data is reduced to a digest before storage. */
data class NovelTranslationProviderSemanticIdentity(
	val configurationFingerprint: String,
	val contractVersion: String,
	val circuitKey: String,
)

data class NovelTranslationSemanticKey(val digest: String) {
	val operationId: String get() = digest.take(OPERATION_ID_CHARS)

	companion object {
		private const val OPERATION_ID_CHARS = 12
	}
}

internal fun buildNovelTranslationSemanticKey(
	provider: NovelTranslationProvider,
	selection: NovelTranslationSelection,
	segmentText: String,
	beforeContext: String,
	afterContext: String,
): NovelTranslationSemanticKey = NovelTranslationSemanticKey(
	novelTranslationDigest(
		SEMANTIC_CACHE_SCHEMA_VERSION,
		provider.capabilities.providerId,
		provider.semanticIdentity.configurationFingerprint,
		provider.semanticIdentity.contractVersion,
		selection.sourceLanguage.trim().lowercase(),
		selection.targetLanguage.trim().lowercase(),
		selection.style.id,
		selection.contextAware.toString(),
		novelTranslationDigest(segmentText),
		if (selection.contextAware) novelTranslationDigest(beforeContext) else NO_CONTEXT,
		if (selection.contextAware) novelTranslationDigest(afterContext) else NO_CONTEXT,
	),
)

internal fun novelTranslationDigest(vararg values: String): String {
	val digest = MessageDigest.getInstance("SHA-256")
	values.forEach { value ->
		val bytes = value.toByteArray(Charsets.UTF_8)
		digest.update((bytes.size ushr 24).toByte())
		digest.update((bytes.size ushr 16).toByte())
		digest.update((bytes.size ushr 8).toByte())
		digest.update(bytes.size.toByte())
		digest.update(bytes)
	}
	return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

interface NovelTranslationResultCache {
	suspend fun read(key: NovelTranslationSemanticKey): String?
	suspend fun write(key: NovelTranslationSemanticKey, translatedText: String): Boolean
}

object NoOpNovelTranslationResultCache : NovelTranslationResultCache {
	override suspend fun read(key: NovelTranslationSemanticKey): String? = null
	override suspend fun write(key: NovelTranslationSemanticKey, translatedText: String): Boolean = false
}

internal class NovelTranslationCacheCorruptionException(
	message: String,
	cause: Throwable? = null,
) : IOException(message, cause)

/** App-private, bounded persistent segment-result cache. Source text and provider secrets are never stored. */
class NovelTranslationSemanticCache internal constructor(
	private val directory: File,
	private val maxBytes: Long = MAX_BYTES,
	private val maxEntries: Int = MAX_ENTRIES,
	private val maxIdleMillis: Long = MAX_IDLE_MILLIS,
	private val nowMillis: () -> Long = System::currentTimeMillis,
) : NovelTranslationResultCache {

	constructor(context: Context) : this(File(context.applicationContext.noBackupFilesDir, DIRECTORY_NAME))

	private val mutex = Mutex()
	private var initialized = false
	private var lastAccess = 0L

	override suspend fun read(key: NovelTranslationSemanticKey): String? = withContext(Dispatchers.IO) {
		mutex.withLock {
			initializeLocked()
			readLocked(key)
		}
	}

	override suspend fun write(key: NovelTranslationSemanticKey, translatedText: String): Boolean = withContext(Dispatchers.IO) {
		val bytes = translatedText.toByteArray(Charsets.UTF_8)
		if (bytes.isEmpty() || bytes.size > MAX_ENTRY_BYTES) return@withContext false
		mutex.withLock {
			initializeLocked()
			writeLocked(key, bytes)
		}
	}

	internal suspend fun clear() = withContext(Dispatchers.IO) {
		mutex.withLock {
			initializeLocked()
			directory.listFiles().orEmpty().forEach { file ->
				if (file.isFile && (file.name.endsWith(ENTRY_SUFFIX) || file.name.endsWith(PARTIAL_SUFFIX))) file.delete()
			}
		}
	}

	internal suspend fun entryCount(): Int = withContext(Dispatchers.IO) {
		mutex.withLock {
			initializeLocked()
			entriesLocked().size
		}
	}

	private fun initializeLocked() {
		if (initialized) return
		check(directory.isDirectory || directory.mkdirs()) { "Cannot create novel translation cache" }
		directory.listFiles().orEmpty().filter { it.name.endsWith(PARTIAL_SUFFIX) }.forEach { it.delete() }
		trimLocked()
		lastAccess = entriesLocked().maxOfOrNull { it.lastModified() } ?: 0L
		initialized = true
	}

	private fun readLocked(key: NovelTranslationSemanticKey): String? {
		val file = File(directory, key.digest + ENTRY_SUFFIX)
		if (!file.isFile) return null
		return try {
			DataInputStream(file.inputStream().buffered()).use { input ->
				if (input.readInt() != FILE_MAGIC) throw IOException("Invalid translation cache header")
				if (input.readInt() != FILE_VERSION) throw IOException("Unsupported translation cache version")
				val length = input.readInt()
				if (length !in 1..MAX_ENTRY_BYTES) throw IOException("Invalid translation cache length")
				val expectedDigest = input.readUTF()
				val payload = ByteArray(length)
				input.readFully(payload)
				if (input.read() != -1 || novelTranslationDigest(payload.toString(Charsets.UTF_8)) != expectedDigest) {
					throw IOException("Corrupt translation cache entry")
				}
				touchLocked(file)
				payload.toString(Charsets.UTF_8)
			}
		} catch (error: IOException) {
			file.delete()
			throw NovelTranslationCacheCorruptionException("Corrupt translation cache entry recovered", error)
		}
	}

	private fun writeLocked(key: NovelTranslationSemanticKey, payload: ByteArray): Boolean {
		val target = File(directory, key.digest + ENTRY_SUFFIX)
		if (target.isFile) {
			touchLocked(target)
			return true
		}
		val temporary = File.createTempFile("translation-", PARTIAL_SUFFIX, directory)
		return try {
			temporary.outputStream().use { stream ->
				val output = DataOutputStream(stream)
				output.writeInt(FILE_MAGIC)
				output.writeInt(FILE_VERSION)
				output.writeInt(payload.size)
				output.writeUTF(novelTranslationDigest(payload.toString(Charsets.UTF_8)))
				output.write(payload)
				output.flush()
				stream.fd.sync()
			}
			if (!temporary.renameTo(target)) return false
			touchLocked(target)
			trimLocked()
			true
		} catch (_: IOException) {
			false
		} finally {
			temporary.delete()
		}
	}

	private fun touchLocked(file: File) {
		lastAccess = maxOf(nowMillis(), lastAccess + 1L)
		file.setLastModified(lastAccess)
	}

	private fun entriesLocked(): List<File> = directory.listFiles().orEmpty()
		.filter { it.isFile && it.name.endsWith(ENTRY_SUFFIX) }

	private fun trimLocked() {
		val entries = entriesLocked().sortedBy { it.lastModified() }
		var bytes = entries.sumOf { it.length() }
		var count = entries.size
		val obsoleteBefore = nowMillis() - maxIdleMillis
		for (entry in entries) {
			if (bytes <= maxBytes && count <= maxEntries && entry.lastModified() >= obsoleteBefore) break
			val size = entry.length()
			if (entry.delete()) {
				bytes -= size
				count--
			}
		}
	}

	internal companion object {
		private const val DIRECTORY_NAME = "novel-translation-cache"
		private const val ENTRY_SUFFIX = ".translation"
		private const val PARTIAL_SUFFIX = ".partial"
		private const val FILE_MAGIC = 0x4E545231
		private const val FILE_VERSION = 1
		private const val MAX_ENTRY_BYTES = 1024 * 1024
		private const val MAX_BYTES = 32L * 1024L * 1024L
		private const val MAX_ENTRIES = 4096
		private const val MAX_IDLE_MILLIS = 30L * 24L * 60L * 60L * 1000L
	}
}

sealed interface NovelTranslationFlightEvent {
	data class Attempt(val attempt: Int) : NovelTranslationFlightEvent
	data class Retry(val nextAttempt: Int, val delayMillis: Long, val failure: NovelTranslationFailure) : NovelTranslationFlightEvent
}

/** Shares provider work while keeping each subscriber's cancellation independent. */
class NovelTranslationSingleFlight(
	private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
	private class Flight(
		val result: kotlinx.coroutines.Deferred<String>,
		val events: MutableSharedFlow<NovelTranslationFlightEvent>,
		var subscribers: Int,
	)

	private val flights = HashMap<String, Flight>()

	suspend fun execute(
		key: NovelTranslationSemanticKey,
		onEvent: suspend (NovelTranslationFlightEvent) -> Unit = {},
		producer: suspend (emit: suspend (NovelTranslationFlightEvent) -> Unit) -> String,
	): String {
		val flight = synchronized(flights) {
			flights[key.digest]?.also { it.subscribers++ } ?: run {
				val events = MutableSharedFlow<NovelTranslationFlightEvent>(extraBufferCapacity = 16)
				val result = scope.async(start = CoroutineStart.LAZY) { producer { event -> events.emit(event) } }
				Flight(result, events, 1).also { flights[key.digest] = it }
			}
		}
		return coroutineScope {
			val eventJob = launch(start = CoroutineStart.UNDISPATCHED) {
				flight.events.collect { event -> onEvent(event) }
			}
			try {
				flight.result.start()
				flight.result.await()
			} finally {
				eventJob.cancel()
				release(key.digest, flight)
			}
		}
	}

	private fun release(key: String, flight: Flight) {
		synchronized(flights) {
			if (flights[key] !== flight) return
			flight.subscribers--
			if (flight.subscribers <= 0) {
				if (!flight.result.isCompleted) flight.result.cancel()
				flights.remove(key)
			}
		}
	}
}

enum class NovelTranslationCircuitState { CLOSED, OPEN, HALF_OPEN }

class NovelTranslationCircuitBreaker(
	private val failureThreshold: Int = DEFAULT_FAILURE_THRESHOLD,
	private val cooldownMillis: Long = DEFAULT_COOLDOWN_MILLIS,
	private val nowMillis: () -> Long = System::currentTimeMillis,
) {
	private data class ProviderState(
		var transientFailures: Int = 0,
		var openUntil: Long = 0L,
		var halfOpenProbeInFlight: Boolean = false,
	)

	private val providers = HashMap<String, ProviderState>()

	fun beforeRequest(providerKey: String): NovelTranslationCircuitState = synchronized(providers) {
		val state = providers.getOrPut(providerKey) { ProviderState() }
		if (state.openUntil > 0L) {
			if (nowMillis() < state.openUntil || state.halfOpenProbeInFlight) throw circuitOpenFailure()
			state.halfOpenProbeInFlight = true
			return@synchronized NovelTranslationCircuitState.HALF_OPEN
		}
		NovelTranslationCircuitState.CLOSED
	}

	fun onSuccess(providerKey: String) = synchronized(providers) {
		providers[providerKey] = ProviderState()
	}

	/** Cancellation is neutral: release a half-open probe without counting success or failure. */
	fun onCancellation(providerKey: String) = synchronized(providers) {
		providers[providerKey]?.halfOpenProbeInFlight = false
	}

	fun onFailure(providerKey: String, failure: NovelTranslationFailure) = synchronized(providers) {
		val state = providers.getOrPut(providerKey) { ProviderState() }
		if (!failure.category.isCircuitEligible()) {
			state.transientFailures = 0
			state.openUntil = 0L
			state.halfOpenProbeInFlight = false
			return@synchronized
		}
		state.transientFailures++
		if (state.halfOpenProbeInFlight || state.transientFailures >= failureThreshold.coerceAtLeast(1)) {
			state.openUntil = nowMillis() + cooldownMillis.coerceAtLeast(0L)
			state.halfOpenProbeInFlight = false
		}
	}

	fun state(providerKey: String): NovelTranslationCircuitState = synchronized(providers) {
		val state = providers[providerKey] ?: return@synchronized NovelTranslationCircuitState.CLOSED
		when {
			state.openUntil == 0L -> NovelTranslationCircuitState.CLOSED
			nowMillis() < state.openUntil -> NovelTranslationCircuitState.OPEN
			else -> NovelTranslationCircuitState.HALF_OPEN
		}
	}

	private fun NovelTranslationFailureCategory.isCircuitEligible(): Boolean = when (this) {
		NovelTranslationFailureCategory.RATE_LIMITED,
		NovelTranslationFailureCategory.NETWORK,
		NovelTranslationFailureCategory.TIMEOUT,
		NovelTranslationFailureCategory.PROVIDER_UNAVAILABLE -> true
		else -> false
	}

	private fun circuitOpenFailure() = NovelTranslationFailure(
		category = NovelTranslationFailureCategory.CIRCUIT_OPEN,
		message = "Translation provider is temporarily cooling down",
	)

	companion object {
		private const val DEFAULT_FAILURE_THRESHOLD = 3
		private const val DEFAULT_COOLDOWN_MILLIS = 30_000L
	}
}

enum class NovelTranslationDiagnosticKind {
	CACHE_HIT,
	CACHE_MISS,
	CACHE_CORRUPTION_RECOVERED,
	CACHE_READ_ERROR_RECOVERED,
	CACHE_STORE,
	REQUEST_ATTEMPT,
	RETRY,
	SUCCESS,
	FAILURE,
	CIRCUIT_REJECTED,
	CANCELLED,
}

data class NovelTranslationDiagnosticEvent(
	val kind: NovelTranslationDiagnosticKind,
	val provider: String,
	val operationId: String,
	val errorCategory: NovelTranslationFailureCategory? = null,
	val httpStatus: Int? = null,
	val latencyMillis: Long? = null,
	val attempt: Int? = null,
	val retryDelayMillis: Long? = null,
	val segmentCount: Int? = null,
	val circuitState: NovelTranslationCircuitState? = null,
	val cancellationCategory: String? = null,
)

/** Metadata-only ring buffer. It intentionally has no fields for source/result text, keys, or headers. */
class NovelTranslationDiagnostics(private val capacity: Int = 128) {
	private val events = ArrayDeque<NovelTranslationDiagnosticEvent>()

	fun record(event: NovelTranslationDiagnosticEvent) = synchronized(events) {
		if (capacity <= 0) return@synchronized
		while (events.size >= capacity) events.removeFirst()
		events.addLast(event)
	}

	fun snapshot(): List<NovelTranslationDiagnosticEvent> = synchronized(events) { events.toList() }
	fun clear() = synchronized(events) { events.clear() }
}

class NovelTranslationReliabilityServices(
	val cache: NovelTranslationResultCache,
	val singleFlight: NovelTranslationSingleFlight,
	val circuitBreaker: NovelTranslationCircuitBreaker,
	val diagnostics: NovelTranslationDiagnostics,
)

/** Process-shared reliability state; disk results survive process/Reader recreation independently. */
object NovelTranslationProcessReliability {
	private val singleFlight = NovelTranslationSingleFlight()
	private val circuitBreaker = NovelTranslationCircuitBreaker()
	private val diagnostics = NovelTranslationDiagnostics()
	@Volatile private var cache: NovelTranslationSemanticCache? = null

	fun services(context: Context): NovelTranslationReliabilityServices {
		val appContext = context.applicationContext
		val persistentCache = cache ?: synchronized(this) {
			cache ?: NovelTranslationSemanticCache(appContext).also { cache = it }
		}
		return NovelTranslationReliabilityServices(persistentCache, singleFlight, circuitBreaker, diagnostics)
	}
}

private const val SEMANTIC_CACHE_SCHEMA_VERSION = "novel-translation-semantic-v1"
private const val NO_CONTEXT = "no-context"
