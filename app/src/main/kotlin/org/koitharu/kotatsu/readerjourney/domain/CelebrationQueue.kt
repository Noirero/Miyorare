package org.koitharu.kotatsu.readerjourney.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.core.prefs.ReaderJourneyCelebrationMode

data class CelebrationQueueItem(
	val event: ReaderJourneyCelebration,
	val mode: ReaderJourneyCelebrationMode,
	val reduceMotion: Boolean,
)

/**
 * Serializes Reader Journey feedback so later unlocks cannot replace an active celebration.
 *
 * Preferences are checked both when an event enters the queue and immediately before presentation:
 * switching celebrations OFF therefore also discards events that were waiting. Motion preference is
 * resolved at presentation time so queued feedback follows the user's latest accessibility choice.
 */
class CelebrationQueue(
	scope: CoroutineScope,
	private val modeProvider: () -> ReaderJourneyCelebrationMode,
	private val reduceMotionProvider: () -> Boolean,
	private val presenter: suspend (CelebrationQueueItem) -> Unit,
) {
	private val events = Channel<ReaderJourneyCelebration>(capacity = Channel.UNLIMITED)
	private val consumerJob = scope.launch {
			for (event in events) {
				val mode = modeProvider()
				if (mode == ReaderJourneyCelebrationMode.OFF) continue
				presenter(
					CelebrationQueueItem(
						event = event,
						mode = mode,
						reduceMotion = reduceMotionProvider(),
					),
				)
			}
	}

	fun enqueue(event: ReaderJourneyCelebration) {
		if (modeProvider() == ReaderJourneyCelebrationMode.OFF) return
		events.trySend(event)
	}

	fun close() {
		events.close()
		consumerJob.cancel()
	}
}
