package org.koitharu.kotatsu.download.ui.list

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.os.SystemClock
import android.provider.MediaStore
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import org.koitharu.kotatsu.core.ui.model.DateTimeAgo
import org.koitharu.kotatsu.download.ui.list.chapters.DownloadChapter
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.parsers.model.Manga
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

/**
 * Visual-evidence harness for the production Downloads layout and adapter.
 *
 * Fixture values live only in androidTest. Production presentation still consumes WorkManager /
 * repository state; this test provides deterministic models so CI can capture the exact geometry
 * against the approved reference without starting network downloads.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DownloadsGoldenVisualTest {

	@get:Rule
	val hiltRule = HiltAndroidRule(this)

	@Inject
	lateinit var settings: AppSettings

	private val instrumentation = InstrumentationRegistry.getInstrumentation()
	private val context get() = instrumentation.targetContext

	@Before
	fun setUp() {
		hiltRule.inject()
		runCatching { WorkManager.getInstance(context) }.getOrElse {
			WorkManager.initialize(context, Configuration.Builder().build())
		}
		settings.setMiyorareDesignStyle(MiyorareDesignStyle.MODERN)
		settings.setMiyorareThemePreset(MiyorareThemePreset.MIYORARE)
		settings.isAmoledTheme = false
	}

	@Test
	fun captureCanonicalDownloads() = runBlocking {
		AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("id-ID"))
		val activity = instrumentation.startActivitySync(
			Intent(context, DownloadsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
		) as DownloadsActivity
		try {
			val models = buildFixtureModels()
			val adapterRef = AtomicReference<DownloadsAdapter>()
			instrumentation.runOnMainSync {
				val recycler = activity.findViewById<RecyclerView>(R.id.recyclerView)
				val adapter = DownloadsAdapter(activity, activity, true)
				recycler.adapter = adapter
				adapterRef.set(adapter)
			}
			adapterRef.get().emit(models)
			instrumentation.runOnMainSync {
				activity.javaClass.declaredMethods
					.first { it.name == "renderModernDownloadsHeader" }
					.apply { isAccessible = true }
					.invoke(activity, models)
				activity.findViewById<RecyclerView>(R.id.recyclerView).scrollToPosition(0)
			}
			waitUntilReady(activity)
			SystemClock.sleep(900)
			instrumentation.waitForIdleSync()

			val geometryRef = AtomicReference<JSONObject>()
			instrumentation.runOnMainSync {
				geometryRef.set(captureGeometry(activity))
			}
			val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
			assertEquals(CANONICAL_WIDTH_PX, screenshot.width)
			assertEquals(CANONICAL_HEIGHT_PX, screenshot.height)
			writeEvidence(screenshot, geometryRef.get().toString(2))
		} finally {
			instrumentation.runOnMainSync { activity.finish() }
			AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
		}
	}

	private fun buildFixtureModels(): List<ListModel> {
		val covers = listOf(
			createCover("remielle", Color.rgb(8, 44, 86), Color.rgb(10, 126, 188)),
			createCover("acheron", Color.rgb(70, 92, 112), Color.rgb(210, 224, 234)),
			createCover("kiana", Color.rgb(116, 84, 105), Color.rgb(244, 212, 224)),
			createCover("tank", Color.rgb(56, 30, 86), Color.rgb(190, 90, 216)),
		)
		val now = Instant.now()
		val emptyChapters = MutableStateFlow<List<DownloadChapter>?>(emptyList())
		fun model(
			seed: String,
			title: String,
			state: WorkInfo.State,
			paused: Boolean = false,
			progress: Int = 0,
			max: Int = 0,
			chapters: Int = 0,
			sizeMb: Long = 0,
			cover: String = covers.last(),
			timestamp: Instant = now,
		): DownloadItemModel = DownloadItemModel(
			id = UUID.nameUUIDFromBytes(seed.toByteArray()),
			workState = state,
			isIndeterminate = false,
			isPaused = paused,
			manga = manga(seed.hashCode().toLong(), title, cover),
			error = null,
			max = max,
			progress = progress,
			eta = -1L,
			isStuck = false,
			timestamp = timestamp,
			chaptersDownloaded = chapters,
			downloadSizeBytes = sizeMb * 1024L * 1024L,
			isExpanded = false,
			chapters = emptyChapters,
		)

		val active = model(
			seed = "remielle",
			title = "Remielle 1",
			state = WorkInfo.State.RUNNING,
			paused = true,
			progress = 18,
			max = 465,
			sizeMb = 72,
			cover = covers[0],
		)
		val today = listOf(
			model("acheron", "Acheron 4", WorkInfo.State.CANCELLED, progress = 0, max = 210, cover = covers[1]),
			model("kiana", "Kiana 2", WorkInfo.State.SUCCEEDED, progress = 245, max = 245, chapters = 1, sizeMb = 95, cover = covers[2]),
			model("tank", "The Returned C-Rank Tank Won't Die!", WorkInfo.State.SUCCEEDED, progress = 12, max = 12, sizeMb = 28, cover = covers[3]),
		)
		val older = buildList {
			repeat(20) { index ->
				add(
					model(
						"older-success-$index",
						"Downloaded archive ${index + 1}",
						WorkInfo.State.SUCCEEDED,
						progress = 24,
						max = 24,
						chapters = 1,
						sizeMb = 40,
						timestamp = now.minusSeconds(86_400),
					),
				)
			}
			repeat(14) { index ->
				add(
					model(
						"older-cancel-$index",
						"Cancelled archive ${index + 1}",
						WorkInfo.State.CANCELLED,
						max = 100,
						timestamp = now.minusSeconds(86_400),
					),
				)
			}
		}
		return buildList {
			add(ListHeader(R.string.in_progress, payload = 1))
			add(active)
			add(ListHeader(DateTimeAgo.Today, payload = today.size))
			addAll(today)
			add(ListHeader(DateTimeAgo.Yesterday, payload = older.size))
			addAll(older)
		}
	}

	private fun manga(id: Long, title: String, cover: String) = Manga(
		id = id,
		title = title,
		altTitles = emptySet(),
		url = "/downloads-golden/$id",
		publicUrl = "https://fixture.invalid/downloads-golden/$id",
		rating = -1f,
		contentRating = null,
		coverUrl = cover,
		largeCoverUrl = null,
		description = null,
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		chapters = null,
		source = MangaSource("UNKNOWN"),
	)

	private fun createCover(name: String, start: Int, end: Int): String {
		val file = File(context.cacheDir, "downloads-golden-$name.png")
		val bitmap = Bitmap.createBitmap(240, 360, Bitmap.Config.ARGB_8888)
		val canvas = Canvas(bitmap)
		val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			shader = LinearGradient(0f, 0f, 240f, 360f, start, end, Shader.TileMode.CLAMP)
		}
		canvas.drawRect(0f, 0f, 240f, 360f, paint)
		paint.shader = null
		paint.color = Color.argb(120, 255, 255, 255)
		paint.strokeWidth = 5f
		for (i in 0..4) {
			val x = 30f + i * 42f
			canvas.drawLine(x, 310f, x + 28f, 100f + i * 28f, paint)
		}
		file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
		bitmap.recycle()
		return file.toUri().toString()
	}

	private fun waitUntilReady(activity: DownloadsActivity) {
		val deadline = SystemClock.elapsedRealtime() + 20_000L
		while (SystemClock.elapsedRealtime() < deadline) {
			instrumentation.waitForIdleSync()
			val ready = AtomicReference(false)
			instrumentation.runOnMainSync {
				val recycler = activity.findViewById<RecyclerView>(R.id.recyclerView)
				ready.set(
					activity.findViewById<android.view.View>(R.id.modernDownloadsSummary)?.isLaidOut == true &&
						recycler.childCount >= 4,
				)
			}
			if (ready.get()) return
			SystemClock.sleep(150)
		}
		error("Downloads golden screen did not reach deterministic state")
	}

	private fun captureGeometry(activity: DownloadsActivity): JSONObject {
		val recycler = activity.findViewById<RecyclerView>(R.id.recyclerView)
		val children = JSONArray()
		for (index in 0 until recycler.childCount) {
			val child = recycler.getChildAt(index)
			children.put(
				JSONObject()
					.put("adapterPosition", recycler.getChildAdapterPosition(child))
					.put("title", child.findViewById<TextView?>(R.id.textView_title)?.text?.toString())
					.put("bounds", child.screenRect().toJson()),
			)
		}
		return JSONObject()
			.put("toolbar", activity.findViewById<android.view.View>(R.id.toolbar).screenRect().toJson())
			.put("summary", activity.findViewById<android.view.View>(R.id.modernDownloadsSummary).screenRect().toJson())
			.put("recycler", recycler.screenRect().toJson())
			.put("visibleChildren", children)
	}

	private fun writeEvidence(screenshot: Bitmap, geometryJson: String) {
		val resolver = context.contentResolver
		val relativePath = "Download/miyorare-downloads-golden/"
		fun replace(name: String, mimeType: String, write: (java.io.OutputStream) -> Unit) {
			resolver.delete(
				MediaStore.Downloads.EXTERNAL_CONTENT_URI,
				"${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
				arrayOf(relativePath, name),
			)
			val uri = checkNotNull(
				resolver.insert(
					MediaStore.Downloads.EXTERNAL_CONTENT_URI,
					ContentValues().apply {
						put(MediaStore.MediaColumns.DISPLAY_NAME, name)
						put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
						put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
					},
				),
			)
			resolver.openOutputStream(uri, "w").use { output -> write(checkNotNull(output)) }
		}
		replace("implementation.png", "image/png") { output ->
			assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output))
		}
		replace("geometry.json", "application/json") { output ->
			output.write(geometryJson.toByteArray())
		}
	}

	private fun android.view.View.screenRect(): Rect {
		val location = IntArray(2)
		getLocationOnScreen(location)
		return Rect(location[0], location[1], location[0] + width, location[1] + height)
	}

	private fun Rect.toJson() = JSONObject()
		.put("left", left)
		.put("top", top)
		.put("right", right)
		.put("bottom", bottom)
		.put("width", width())
		.put("height", height())

	private companion object {
		const val CANONICAL_WIDTH_PX = 864
		const val CANONICAL_HEIGHT_PX = 1536
	}
}
