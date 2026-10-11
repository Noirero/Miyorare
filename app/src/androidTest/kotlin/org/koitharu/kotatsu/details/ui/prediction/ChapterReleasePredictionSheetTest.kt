package org.koitharu.kotatsu.details.ui.prediction

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.CalendarContract
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.details.domain.ChapterReleasePredictionItem
import org.koitharu.kotatsu.details.domain.NextChapterReleasePrediction
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset

class ChapterReleasePredictionSheetTest {
	@get:Rule val compose = createComposeRule()
	private val context = InstrumentationRegistry.getInstrumentation().targetContext
	private val loader = ImageLoader.Builder(context).components {
		add(object : Interceptor { override suspend fun intercept(chain: Interceptor.Chain): ImageResult = throw IOException("Fixture-only loader") })
	}.build()
	private val date = LocalDate.of(2026, 10, 10)
	private val original = ChapterReleasePredictionItem(SampleData.manga.copy(id = 1, title = "Original manga"), NextChapterReleasePrediction(date, 3), listOf(date.minusDays(7)), "English")
	private val alternate = original.copy(manga = original.manga.copy(id = 2, title = "Alternate manga"), prediction = NextChapterReleasePrediction(date.plusDays(2), 5), releaseDates = listOf(date.minusDays(5)))

	@After fun close() { loader.shutdown() }

	@Test fun calendarGesturesAndSheetSelectionLeaveParentMangaUnchanged() {
		val visible = mutableStateOf(true)
		val upcoming = mutableStateOf(UpcomingPredictions(listOf(original, alternate), loading = false))
		compose.setContent { MaterialTheme { Column {
			Text("Parent: ${original.manga.title}")
			if (visible.value) ChapterReleasePredictionSheet(1, original, upcoming.value, loader) { visible.value = false }
		} } }
		compose.onNodeWithTag("release-prediction-calendar").performTouchInput { swipeLeft() }
		compose.onNodeWithTag("release-prediction-active-title").assertTextEquals(original.manga.title)
		compose.onNodeWithTag("release-prediction-pager").performTouchInput { swipeLeft() }
		compose.onNodeWithTag("release-prediction-manga:2").performClick()
		compose.onNodeWithTag("release-prediction-active-title").assertTextEquals(alternate.manga.title)
		compose.onNodeWithText(context.getString(R.string.release_calendar_add)).assertExists()
		compose.runOnIdle { upcoming.value = UpcomingPredictions(listOf(original, alternate.copy(manga = alternate.manga.copy(title = "Updated alternate"))), loading = false) }
		compose.onNodeWithTag("release-prediction-active-title").assertTextEquals("Updated alternate")
		compose.runOnIdle { visible.value = false }
		compose.onNodeWithText("Parent: ${original.manga.title}").assertExists()
	}

	@Test fun invalidatedSelectionCannotKeepUsingAnOldPrediction() {
		val upcoming = mutableStateOf(UpcomingPredictions(listOf(alternate), loading = false))
		compose.setContent { MaterialTheme { ChapterReleasePredictionSheet(1, original, upcoming.value, loader) {} } }
		compose.onNodeWithTag("release-prediction-pager").performTouchInput { swipeLeft() }
		compose.onNodeWithTag("release-prediction-manga:2").performClick()
		compose.runOnIdle { upcoming.value = UpcomingPredictions(loading = false) }
		compose.onNodeWithText(context.getString(R.string.release_calendar_add)).assertDoesNotExist()
		compose.onNodeWithTag("release-prediction-active-title").assertDoesNotExist()
	}

	@Test fun unavailableDetailsBranchCanBeReplacedByAnExplicitFavoriteContextForTheSameManga() {
		val origin = mutableStateOf<ChapterReleasePredictionItem?>(original)
		val favorite = alternate.copy(manga = original.manga, branch = "Spanish")
		compose.setContent { MaterialTheme {
			ChapterReleasePredictionSheet(1, origin.value, UpcomingPredictions(listOf(favorite), loading = false), loader) {}
		} }
		compose.runOnIdle { origin.value = null }
		compose.onNodeWithTag("release-prediction-active-title").assertDoesNotExist()
		compose.onNodeWithTag("release-prediction-pager").performTouchInput { swipeLeft() }
		compose.onNodeWithTag("release-prediction-manga:1").performClick()
		compose.onNodeWithTag("release-prediction-active-title").assertTextEquals(original.manga.title)
		compose.onNodeWithTag("release-prediction-date").assertTextEquals(
			favorite.prediction.expectedDate.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.FULL)),
		)
		assertNull(origin.value)
	}

	@Test fun eventUsesActiveExpectedDateAndUtcAllDayDatesRatherThanCountdown() {
		val item = alternate.copy(prediction = alternate.prediction.copy(daysUntilNext = 999))
		val intent = releasePredictionCalendarIntent(context, item)
		assertEquals(Intent.ACTION_INSERT, intent.action)
		assertEquals(CalendarContract.Events.CONTENT_URI, intent.data)
		assertTrue(intent.getBooleanExtra(CalendarContract.Events.ALL_DAY, false))
		val start = item.prediction.expectedDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
		assertEquals(start, intent.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, 0))
		assertEquals(start + 86400000L, intent.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, 0))
		assertEquals(context.getString(R.string.release_calendar_event_title, item.manga.title), intent.getStringExtra(CalendarContract.Events.TITLE))
		assertTrue(intent.getStringExtra(CalendarContract.Events.DESCRIPTION).orEmpty().contains("not an official"))
	}

	@Test fun calendarBackgroundModePersistsAndInvalidImportsPreserveOwnedImage() = runBlocking {
		val prefs = context.getSharedPreferences(PREDICTION_PREFS, android.content.Context.MODE_PRIVATE)
		val previous = prefs.all
		val target = PredictionCalendarBackgroundStore.file(context)
		val previousBytes = target.takeIf { it.isFile }?.readBytes()
		val image = File.createTempFile("prediction-test", ".png", context.cacheDir)
		try {
			val bitmap = Bitmap.createBitmap(30, 40, Bitmap.Config.ARGB_8888)
			image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
			bitmap.recycle()
			PredictionCalendarBackgroundStore.import(context, Uri.fromFile(image))
			val imported = target.readBytes()
			assertTrue(imported.isNotEmpty())
			PredictionCalendarBackgroundStore.writeMode(context, PredictionCalendarBackgroundMode.CUSTOM)
			assertEquals(PredictionCalendarBackgroundMode.CUSTOM, PredictionCalendarBackgroundStore.readMode(context))
			image.writeText("invalid image")
			assertTrue(runCatching { PredictionCalendarBackgroundStore.import(context, Uri.fromFile(image)) }.isFailure)
			assertArrayEquals(imported, target.readBytes())
		} finally {
			image.delete()
			if (previousBytes != null) target.writeBytes(previousBytes) else target.delete()
			prefs.edit().clear().apply()
			prefs.edit().apply {
				previous.forEach { (key, value) -> when (value) {
					is String -> putString(key, value)
					is Int -> putInt(key, value)
				} }
			}.apply()
		}
	}
}
