package org.koitharu.kotatsu.core.diagnostics

/** Pure, bounded session accounting. All times are monotonic; labels come from code, never content. */
internal class DiagnosticsSession(
	val id: String,
	private val clock: () -> Long,
	private val sink: (String) -> Unit,
) {
	class Trace internal constructor(val id: String, val screen: String, val started: Long) {
		internal val milestones = HashSet<String>()
		internal val pendingDraws = HashSet<String>()
	}
	class Span internal constructor(
		val id: Long,
		val kind: String,
		val name: String,
		val trace: Trace?,
		val started: Long,
	) {
		internal var ended = false
	}
	private data class Worst(val kind: String, val name: String, val trace: String?, val ms: Long, val outcome: String)
	private data class Aggregate(var count: Long = 0, var failures: Long = 0, var total: Long = 0, var worst: Long = 0)
	private data class Frames(var count: Long = 0, var janky: Long = 0, var worst: Long = 0, var dropped: Long = 0)
	private val started = clock()
	private var sequence = 0L
	private var active = true
	private var stoppedSummary: String? = null
	private var emitted = 0
	private var suppressed = 0L
	private var droppedSeries = 0L
	private var openTrackingOverflow = 0L
	private val traces = LinkedHashMap<String, Trace>()
	private val aggregates = LinkedHashMap<String, Aggregate>()
	private val frames = LinkedHashMap<String, Frames>()
	private val worst = ArrayList<Worst>(30)
	private val open = LinkedHashMap<Long, Span>()
	private var frameCount = 0L
	private var jankyCount = 0L
	private var worstFrameNs = 0L
	private val sampleWindows = HashMap<String, Pair<Long, Int>>()

	@Synchronized fun navigation(from: String, to: String): Trace {
		val trace = Trace("$id-t${++sequence}", to, clock())
		if (traces.size >= 64) traces.remove(traces.keys.first())
		traces[trace.id] = trace
		event("navigation", trace, "from=$from to=$to")
		return trace
	}

	@Synchronized fun findTrace(id: String?): Trace? = traces[id]

	@Synchronized fun reserveDraw(trace: Trace, name: String): Boolean =
		active && owns(trace) && name !in trace.milestones && trace.pendingDraws.add(name)

	@Synchronized fun releaseDraw(trace: Trace, name: String) { trace.pendingDraws.remove(name) }

	@Synchronized fun milestone(trace: Trace?, name: String) {
		if (!active || trace == null || !owns(trace) || !trace.milestones.add(name)) return
		val ms = elapsed(trace.started)
		event("milestone", trace, "name=$name elapsed_ms=$ms")
		if (name == "first_draw" || name == "first_meaningful_content" || name == "enter_animation_complete") {
			record("transition", "${trace.screen}.$name", trace, ms, "complete", log = false)
		}
	}

	@Synchronized fun begin(kind: String, name: String, trace: Trace?): Span? {
		if (!active || (trace != null && !owns(trace))) return null
		val span = Span(++sequence, kind, name, trace, clock())
		if (open.size < 256) open[span.id] = span else openTrackingOverflow++
		if (kind == "work") event("work_start", trace, "op=${span.id} name=$name")
		return span
	}

	@Synchronized fun end(span: Span?, outcome: String = "complete", detail: String = "") {
		if (!active || span == null || span.ended) return
		span.ended = true
		open.remove(span.id)
		val ms = elapsed(span.started)
		record(span.kind, span.name, span.trace, ms, outcome,
			log = (span.kind != "image" && span.kind != "network") || ms >= 100 || outcome == "failure",
			detail = "op=${span.id} $detail")
	}

	@Synchronized fun event(kind: String, trace: Trace?, detail: String) {
		if (!active) return
		if (emitted >= 2500) {
			suppressed++
			return
		}
		emitted++
		// Diagnostics must never throw into the measured production operation.
		runCatching { sink("PERF session=$id t_ms=${elapsed(started)} trace=${trace?.id ?: "none"} $kind $detail") }
	}

	@Synchronized fun frame(trace: Trace?, durationNs: Long, budgetNs: Long, dropped: Int) {
		if (!active || (trace != null && !owns(trace)) || durationNs < 0 || budgetNs <= 0) return
		val key = trace?.id ?: "unattributed"
		if (key !in frames && frames.size >= 64) flushFrames(frames.keys.first())
		val value = frames.getOrPut(key) { Frames() }
		value.count++
		frameCount++
		if (durationNs > budgetNs) { value.janky++; jankyCount++ }
		worstFrameNs = maxOf(worstFrameNs, durationNs)
		value.worst = maxOf(value.worst, durationNs)
		value.dropped += dropped.coerceAtLeast(0)
	}

	@Synchronized fun flushFrames(trace: Trace?) = flushFrames(trace?.id ?: "unattributed")

	private fun flushFrames(key: String) {
		val value = frames.remove(key) ?: return
		val ms = value.worst / 1_000_000
		event("frames", traces[key], "frames=${value.count} janky=${value.janky} worst_ms=$ms callback_dropped=${value.dropped}")
		record("jank", "frame_window", traces[key], ms, "complete", log = false)
	}

	@Synchronized fun stall(trace: Trace?, durationMs: Long, outcome: String = "recovered") {
		if (active && durationMs >= 250) record("stall", "main_thread_heartbeat", trace, durationMs, outcome, log = true)
	}

	@Synchronized fun observed(kind: String, name: String, trace: Trace?, durationNs: Long, outcome: String = "complete") {
		if (!active || (trace != null && !owns(trace))) return
		val ms = (durationNs / 1_000_000).coerceAtLeast(0)
		record(kind, name, trace, ms, outcome, log = ms >= 100 || outcome == "failure")
	}

	private fun record(kind: String, name: String, trace: Trace?, ms: Long, outcome: String, log: Boolean, detail: String = "") {
		val key = "$kind.$name.$outcome"
		if (key !in aggregates && aggregates.size >= 128) { droppedSeries++; return }
		val aggregate = aggregates.getOrPut(key) { Aggregate() }
		aggregate.count++
		if (outcome == "failure") aggregate.failures++
		aggregate.total += ms
		aggregate.worst = maxOf(aggregate.worst, ms)
		val lastOfKind = worst.indexOfLast { it.kind == kind }
		if ((worst.count { it.kind == kind } < 3 || ms > worst[lastOfKind].ms) &&
			(worst.size < 30 || ms > worst.last().ms)) {
			worst.add(Worst(kind, name, trace?.id, ms, outcome))
			worst.sortByDescending { it.ms }
			// Keep each category represented even if a long-lived producer dwarfs rendering/network.
			if (worst.count { it.kind == kind } > 3) worst.removeAt(worst.indexOfLast { it.kind == kind })
			if (worst.size > 30) worst.removeAt(30)
		}
		if (log && sample(kind)) event(kind, trace, "name=$name outcome=$outcome duration_ms=$ms $detail")
	}

	private fun sample(kind: String): Boolean {
		if (kind != "image" && kind != "network") return true
		val second = clock() / 1_000_000_000
		val window = sampleWindows[kind]
		val count = if (window?.first == second) window.second else 0
		sampleWindows[kind] = second to count + 1
		if (count >= 4) suppressed++
		return count < 4
	}

	@Synchronized fun stop(): String {
		stoppedSummary?.let { return it }
		if (active) {
			frames.keys.toList().forEach(::flushFrames)
			open.values.forEach { span -> record(span.kind, span.name, span.trace, elapsed(span.started), "in_progress_at_stop", log = false) }
			open.clear()
			active = false
		}
		return buildString {
			appendLine("PERF SUMMARY session=$id duration_ms=${elapsed(started)} suppressed=$suppressed dropped_series=$droppedSeries open_tracking_overflow=$openTrackingOverflow")
			appendLine("frames=$frameCount janky=$jankyCount worst_frame_ms=${worstFrameNs / 1_000_000}")
			aggregates.forEach { (key, v) ->
				appendLine("aggregate=$key count=${v.count} failures=${v.failures} total_ms=${v.total} worst_ms=${v.worst}")
			}
			worst.forEach { v ->
				appendLine("worst kind=${v.kind} name=${v.name} trace=${v.trace ?: "none"} duration_ms=${v.ms} outcome=${v.outcome}")
			}
		}.also { stoppedSummary = it }
	}

	private fun owns(trace: Trace) = trace.id.startsWith("$id-t")

	private fun elapsed(start: Long) = ((clock() - start) / 1_000_000).coerceAtLeast(0)
}
