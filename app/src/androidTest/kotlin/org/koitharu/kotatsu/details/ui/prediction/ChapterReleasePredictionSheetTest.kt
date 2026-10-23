package org.koitharu.kotatsu.details.ui.prediction

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.CalendarContract
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
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
import org.koitharu.kotatsu.details.domain.NextChapterReleaseState
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

	@Test fun overdueGraceKeepsOriginalDateShowsAnyDayNowAndCannotCreatePastCalendarEvent() {
		val expected = date.minusDays(1)
		val overdue = original.copy(
			prediction = NextChapterReleasePrediction(expected, 0, NextChapterReleaseState.OVERDUE_GRACE),
		)
		compose.setContent { MaterialTheme {
			ChapterReleasePredictionSheet(1, overdue, UpcomingPredictions(listOf(overdue), loading = false), loader) {}
		} }
		compose.onNodeWithText(context.getString(R.string.release_calendar_overdue)).assertIsDisplayed()
		compose.onNodeWithText(context.getString(R.string.release_calendar_today)).assertDoesNotExist()
		compose.onNodeWithText(context.getString(R.string.release_calendar_add)).assertDoesNotExist()
		compose.onNodeWithTag("release-prediction-date").assertTextEquals(
			expected.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.FULL)),
		)
		assertThrows(IllegalArgumentException::class.java) {
			releasePredictionCalendarIntent(context, overdue)
		}
	}

	@Test fun backgroundSurfacesOwnTextColorsAcrossTheLightDarkAndBlurMatrix() {
		val prefs = context.getSharedPreferences(PREDICTION_PREFS, android.content.Context.MODE_PRIVATE)
		val previous = prefs.all
		val cases = listOf(lightColorScheme(), darkColorScheme()).flatMap { scheme ->
			listOf(
				Triple(scheme, PredictionCalendarBackgroundMode.DEFAULT, true),
				Triple(scheme, PredictionCalendarBackgroundMode.MANGA_COVER, true),
				Triple(scheme, PredictionCalendarBackgroundMode.MANGA_COVER, false),
				Triple(scheme, PredictionCalendarBackgroundMode.CUSTOM, true),
			)
		}
		val appearance = mutableStateOf(cases.first())
		try {
			PredictionCalendarBackgroundStore.writeMode(context, cases.first().second)
			compose.setContent { key(appearance.value) {
				MaterialTheme(colorScheme = appearance.value.first) {
					// A transparent/alpha Surface must never inherit this unrelated parent color.
					CompositionLocalProvider(LocalContentColor provides Color.Magenta) {
						ChapterReleasePredictionSheet(1, original, UpcomingPredictions(listOf(original, alternate), loading = false), loader) {}
					}
				}
			} }
			for (case in cases) {
				compose.runOnIdle {
					PredictionCalendarBackgroundStore.writeMode(context, case.second)
					PredictionCalendarBackgroundStore.writeCoverBlur(context, case.third)
					appearance.value = case
				}
				val imageBackground = case.second != PredictionCalendarBackgroundMode.DEFAULT
				val scheme = predictionCalendarColorScheme(case.first, imageBackground)
				if (imageBackground) compose.onNodeWithTag("release-prediction-handle")
					.assert(hasAnyAncestor(hasTestTag("release-prediction-backdrop")))
				else compose.onNodeWithTag("release-prediction-handle").assertDoesNotExist()
				compose.onNodeWithText(context.getString(R.string.release_calendar_title)).assertTextColor(scheme.onSurface)
				compose.onNodeWithTag("release-prediction-active-title").assertTextColor(scheme.onSurface)
				compose.onNodeWithTag("release-prediction-date").assertTextColor(scheme.onSurface)
				compose.onNodeWithText(date.format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy"))).assertTextColor(scheme.onSurface)
				compose.onNodeWithText(context.getString(R.string.release_calendar_predicted)).assertTextColor(scheme.onSurface)
				compose.onNodeWithText(context.getString(R.string.release_calendar_recent_history)).assertTextColor(scheme.onSurfaceVariant)
				compose.onNodeWithText(context.getString(R.string.release_calendar_details)).performClick()
				val dialogDate = date.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))
				val dialogExpected = context.resources.getQuantityString(
					R.plurals.release_calendar_expected_days,
					original.prediction.daysUntilNext.toInt(),
					original.prediction.daysUntilNext,
				)
				compose.onNode(
					hasTextExactly(dialogDate) and
						hasAnySibling(hasTextExactly(dialogExpected)) and
						hasAnyAncestor(isDialog()),
				).assertTextColor(scheme.onPrimaryContainer)
				compose.onNode(hasText(context.getString(R.string.close)) and hasAnyAncestor(isDialog())).performClick()
				compose.onNodeWithTag("release-prediction-pager").performTouchInput { swipeLeft() }
				compose.onNodeWithText(context.getString(R.string.release_calendar_upcoming)).assertTextColor(scheme.onSurface)
				compose.onNode(hasText(alternate.manga.title) and hasAnyAncestor(hasTestTag("release-prediction-manga:2")), useUnmergedTree = true)
					.assertTextColor(scheme.onSurface)
			}
		} finally { restorePreferences(previous) }
	}

	@Test fun imageBackdropIncludesAccessibleHandleAndDismissLeavesParentUnchanged() {
		val prefs = context.getSharedPreferences(PREDICTION_PREFS, android.content.Context.MODE_PRIVATE)
		val previous = prefs.all
		val visible = mutableStateOf(true)
		try {
			PredictionCalendarBackgroundStore.writeMode(context, PredictionCalendarBackgroundMode.CUSTOM)
			compose.setContent { MaterialTheme { Column {
				Text("Parent: ${original.manga.title}")
				if (visible.value) ChapterReleasePredictionSheet(1, original, UpcomingPredictions(loading = false), loader) { visible.value = false }
			} } }
			val sheetBounds = compose.onNodeWithTag("release-prediction-sheet").getUnclippedBoundsInRoot()
			val backdropBounds = compose.onNodeWithTag("release-prediction-backdrop").getUnclippedBoundsInRoot()
			assertEquals(sheetBounds.top, backdropBounds.top)
			assertEquals(sheetBounds.bottom, backdropBounds.bottom)
			compose.onNodeWithTag("release-prediction-handle")
				.assert(hasAnyAncestor(hasTestTag("release-prediction-backdrop")))
				.performSemanticsAction(SemanticsActions.Dismiss) { it() }
			compose.onNodeWithTag("release-prediction-active-title").assertDoesNotExist()
			compose.onNodeWithText("Parent: ${original.manga.title}").assertExists()
		} finally { restorePreferences(previous) }
	}

	@Test fun coverBlurTogglePersistsWithoutBecomingACustomImageSetting() {
		val prefs = context.getSharedPreferences(PREDICTION_PREFS, android.content.Context.MODE_PRIVATE)
		val previous = prefs.all
		try {
			prefs.edit().remove(KEY_COVER_BLUR).apply()
			assertTrue(PredictionCalendarBackgroundStore.readCoverBlur(context))
			PredictionCalendarBackgroundStore.writeMode(context, PredictionCalendarBackgroundMode.DEFAULT)
			compose.setContent { MaterialTheme { ChapterReleasePredictionSheet(1, original, UpcomingPredictions(loading = false), loader) {} } }
			compose.onNodeWithContentDescription(context.getString(R.string.release_calendar_background)).performClick()
			compose.onNodeWithText(context.getString(R.string.release_calendar_cover_blur)).assertDoesNotExist()
			compose.onNodeWithText(context.getString(R.string.release_calendar_background_cover)).performClick()
			compose.onNodeWithText(context.getString(R.string.release_calendar_cover_blur)).assertIsOn().performClick().assertIsOff()
			assertFalse(PredictionCalendarBackgroundStore.readCoverBlur(context))
			compose.onNodeWithText(context.getString(R.string.close)).performClick()
			compose.onNodeWithContentDescription(context.getString(R.string.release_calendar_background)).performClick()
			compose.onNodeWithText(context.getString(R.string.release_calendar_cover_blur)).assertIsOff().performClick().assertIsOn()
			assertTrue(PredictionCalendarBackgroundStore.readCoverBlur(context))
			compose.onNodeWithText(context.getString(R.string.release_calendar_background_default)).performClick()
			compose.onNodeWithContentDescription(context.getString(R.string.release_calendar_background)).performClick()
			compose.onNodeWithText(context.getString(R.string.release_calendar_cover_blur)).assertDoesNotExist()
		} finally { restorePreferences(previous) }
	}

	private fun SemanticsNodeInteraction.assertTextColor(expected: Color) {
		val layouts = mutableListOf<TextLayoutResult>()
		performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
		assertEquals(expected, layouts.single().layoutInput.style.color)
	}

	private fun restorePreferences(previous: Map<String, *>) {
		context.getSharedPreferences(PREDICTION_PREFS, android.content.Context.MODE_PRIVATE).edit().clear().apply {
			previous.forEach { (key, value) -> when (value) {
				is String -> putString(key, value)
				is Int -> putInt(key, value)
				is Boolean -> putBoolean(key, value)
			} }
		}.apply()
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
			restorePreferences(previous)
		}
	}
}
