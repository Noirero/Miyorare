package org.koitharu.kotatsu.scrobbling.discord.ui

import com.my.kizzyrpc.logger.Logger
import com.my.kizzyrpc.logger.NoOpLogger
import org.koitharu.kotatsu.core.diagnostics.PerformanceDiagnostics.DiscordEvent

/** Export only approved outcomes. Raw Gateway messages can contain session identifiers. */
internal class DiscordGatewayDiagnostics(
	private val record: (DiscordEvent, Int?) -> Unit,
) : Logger by NoOpLogger {

	override fun d(tag: String, event: String) {
		if (tag == "Gateway" && event.startsWith("Received op:") && event.endsWith(", event :READY")) {
			record(DiscordEvent.GATEWAY_READY, null)
		}
	}

	override fun i(tag: String, event: String) {
		if (tag == "Gateway" && event == "Sending PRESENCE_UPDATE") {
			record(DiscordEvent.PRESENCE_ATTEMPT, null)
		}
	}

	override fun w(tag: String, event: String) {
		if (tag != "Gateway" || !event.startsWith("Closed with code: ")) return
		val code = event.removePrefix("Closed with code: ").substringBefore(',').toIntOrNull()
		if (code != null && code in 1000..4999) record(DiscordEvent.GATEWAY_CLOSED, code)
	}

	override fun e(tag: String, event: String) {
		if (tag == "Gateway" && event != "Connection to gateway closed") {
			record(DiscordEvent.GATEWAY_ERROR, null)
		}
	}
}
