package org.koitharu.kotatsu.core.util.ext

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

@RunWith(AndroidJUnit4::class)
class PreferencesFlowTest {

	private val context: Context = ApplicationProvider.getApplicationContext()
	private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

	@Before
	fun setUp() {
		prefs.edit().clear().commit()
	}

	@After
	fun tearDown() {
		prefs.edit().clear().commit()
	}

	@Test
	fun burstCoalescesRepeatedKeysWithoutDroppingDistinctKeys() = runBlocking {
		val received = Collections.synchronizedList(mutableListOf<String?>())
		val collectorStarted = CompletableDeferred<Unit>()
		val firstReceived = CompletableDeferred<Unit>()
		val releaseCollector = CompletableDeferred<Unit>()
		val collector = launch(Dispatchers.Default) {
			collectorStarted.complete(Unit)
			prefs.observeChanges().collect { key ->
				received += key
				if (!firstReceived.isCompleted) {
					firstReceived.complete(Unit)
					releaseCollector.await()
				}
			}
		}

		collectorStarted.await()
		delay(100)
		prefs.edit().putInt("hot", 1).commit()
		withTimeout(5_000) { firstReceived.await() }

		// Keep the downstream collector suspended while producing a restore-like burst. The
		// observer must retain every distinct key but must not enqueue every repeated hot-key write.
		repeat(1_000) { value ->
			prefs.edit().putInt("hot", value + 2).commit()
		}
		val distinctKeys = (0 until 32).map { "restored_$it" }
		prefs.edit().apply {
			distinctKeys.forEachIndexed { index, key -> putInt(key, index) }
		}.commit()

		releaseCollector.complete(Unit)
		withTimeout(5_000) {
			while (!received.containsAll(distinctKeys)) delay(10)
		}

		// The initial hot event plus at most a small race around drain snapshots is expected;
		// hundreds of queued duplicates would reproduce the old unbounded burst behavior.
		assertTrue("hot key was queued ${received.count { it == "hot" }} times", received.count { it == "hot" } < 10)
		assertTrue("distinct restored keys were dropped: $received", received.containsAll(distinctKeys))
		collector.cancelAndJoin()
	}

	private companion object {
		const val PREFS_NAME = "preferences_flow_test"
	}
}
