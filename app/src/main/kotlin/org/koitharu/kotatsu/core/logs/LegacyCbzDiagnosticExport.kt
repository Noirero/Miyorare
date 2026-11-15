package org.koitharu.kotatsu.core.logs

/** In-memory reason codes only; exported independently of the logcat reader. */
internal object LegacyCbzDiagnosticExport {
	private const val TAG = "MiyorareLegacyCbz"
	private val session = LegacyCbzDiagnosticSession()

	fun start() = session.start()
	fun record(reason: String) = session.record(reason)
	fun stop() = session.stop(TAG)
}

internal class LegacyCbzDiagnosticSession(private val capacity: Int = 64) {
	private val events = ArrayDeque<String>()
	private var active = false
	private var dropped = 0

	@Synchronized
	fun start() {
		events.clear()
		dropped = 0
		active = true
	}

	@Synchronized
	fun record(reason: String) {
		if (!active) return
		if (events.size == capacity) {
			events.removeFirst()
			dropped++
		}
		events.addLast(reason)
	}

	@Synchronized
	fun stop(tag: String): String = buildString {
		appendLine("$tag RECORDER_V2 active=$active events=${events.size} dropped=$dropped")
		for (event in events) appendLine("$tag $event")
		active = false
		events.clear()
		dropped = 0
	}
}
