package org.koitharu.kotatsu.scrobbling.discord.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.koitharu.kotatsu.core.diagnostics.PerformanceDiagnostics.DiscordEvent

class DiscordGatewayDiagnosticsTest {

	@Test
	fun exportsOnlyApprovedGatewayOutcomesAndNumericCloseCode() {
		val events = mutableListOf<Pair<DiscordEvent, Int?>>()
		val logger = DiscordGatewayDiagnostics({ "recording" }) { _, event, code -> events.add(event to code) }
		logger.setEligible(true)

		logger.i("Gateway", "session_id updated to private-fixture")
		logger.d("Gateway", "Received op:0, seq:3, event :MESSAGE_CREATE")
		logger.d("Gateway", "Received op:0, seq:4, event :READY")
		logger.i("Gateway", "Sending PRESENCE_UPDATE")
		logger.w("Gateway", "Closed with code: null, reason: private-fixture")
		logger.w("Other", "Closed with code: 4004, reason: private-fixture")
		logger.w("Gateway", "Closed with code: 4004, reason: private-fixture")
		logger.e("Gateway", "Connection to gateway closed")
		logger.e("Gateway", "raw failure private-fixture")

		assertEquals(
			listOf(
				DiscordEvent.GATEWAY_READY to null,
				DiscordEvent.PRESENCE_ATTEMPT to null,
				DiscordEvent.GATEWAY_CLOSED to 4004,
				DiscordEvent.GATEWAY_ERROR to null,
			),
			events,
		)
	}

	@Test
	fun reusedClientFollowsActiveRecordingAndRetiredClientCannotExport() {
		var activeRecording: String? = null
		val events = mutableListOf<Pair<String, DiscordEvent>>()
		val logger = DiscordGatewayDiagnostics({ activeRecording }) { recording, event, _ ->
			events.add(recording to event)
		}
		logger.setEligible(true)
		logger.i("Gateway", "Sending PRESENCE_UPDATE")
		activeRecording = "first"
		logger.i("Gateway", "Sending PRESENCE_UPDATE")
		activeRecording = null
		logger.i("Gateway", "Sending PRESENCE_UPDATE")
		activeRecording = "second"
		logger.d("Gateway", "Received op:0, seq:4, event :READY")
		logger.setEligible(false)
		logger.e("Gateway", "private-fixture")
		logger.setEligible(true)
		logger.retire()
		logger.setEligible(true)
		logger.i("Gateway", "Sending PRESENCE_UPDATE")
		assertEquals(listOf(
			"first" to DiscordEvent.PRESENCE_ATTEMPT,
			"second" to DiscordEvent.GATEWAY_READY,
		), events)
	}
}
