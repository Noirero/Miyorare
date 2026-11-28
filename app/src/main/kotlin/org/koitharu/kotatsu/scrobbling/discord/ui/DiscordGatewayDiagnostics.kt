package org.koitharu.kotatsu.scrobbling.discord.ui

import com.my.kizzyrpc.logger.Logger
import com.my.kizzyrpc.logger.NoOpLogger
import org.koitharu.kotatsu.core.diagnostics.PerformanceDiagnostics.DiscordEvent

/** Export only approved outcomes. Raw Gateway messages can contain session identifiers. */
internal class DiscordGatewayDiagnostics(
	private val recordingId: () -> String?,
	private val record: (String, DiscordEvent, Int?) -> Unit,
) : Logger by NoOpLogger {

	private var eligible = false
	private var retired = false

	@Synchronized fun setEligible(value: Boolean) {
		if (!retired) eligible = value
	}

	@Synchronized fun retire() {
		retired = true
		eligible = false
	}

	// Resolve the current opt-in recording at event time, not client creation.
	// Retired clients must never contribute delayed callbacks to a later recording.
	@Synchronized private fun emit(event: DiscordEvent, code: Int?) {
		if (!eligible || retired) return
		val recording = recordingId() ?: return
		record(recording, event, code)
	}

	override fun d(tag: String, event: String) {
		if (tag == "Gateway" && event.startsWith("Received op:") && event.endsWith(", event :READY")) {
			emit(DiscordEvent.GATEWAY_READY, null)
		}
	}

	override fun i(tag: String, event: String) {
		if (tag == "Gateway" && event == "Sending PRESENCE_UPDATE") {
			emit(DiscordEvent.PRESENCE_ATTEMPT, null)
		}
	}

	override fun w(tag: String, event: String) {
		if (tag != "Gateway" || !event.startsWith("Closed with code: ")) return
		val code = event.removePrefix("Closed with code: ").substringBefore(',').toIntOrNull()
		if (code != null && code in 1000..4999) emit(DiscordEvent.GATEWAY_CLOSED, code)
	}

	override fun e(tag: String, event: String) {
		if (tag == "Gateway" && event != "Connection to gateway closed") {
			emit(DiscordEvent.GATEWAY_ERROR, null)
		}
	}
}
