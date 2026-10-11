package org.koitharu.kotatsu.details.ui.prediction

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.details.domain.FavouriteReleasePredictionsUseCase
import org.koitharu.kotatsu.details.domain.NextChapterReleasePrediction
import org.koitharu.kotatsu.parsers.model.Manga
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private const val PREFS = "release_prediction_calendar"
private const val KEY_BACKGROUND = "background_mode"
private const val CUSTOM_BACKGROUND = "release_prediction_calendar_background"

enum class PredictionCalendarBackgroundMode { DEFAULT, MANGA_COVER, CUSTOM }

data class PredictionCalendarItem(
	val manga: Manga,
	val prediction: NextChapterReleasePrediction,
	val releaseDates: List<LocalDate>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterReleasePredictionSheet(
	origin: PredictionCalendarItem,
	favourites: List<FavouriteReleasePredictionsUseCase.Item>,
	imageLoader: ImageLoader,
	onDismiss: () -> Unit,
) {
	val context = LocalContext.current
	val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
	var backgroundMode by remember {
		mutableStateOf(runCatching { PredictionCalendarBackgroundMode.valueOf(prefs.getString(KEY_BACKGROUND, null).orEmpty()) }
			.getOrDefault(PredictionCalendarBackgroundMode.DEFAULT))
	}
	var selected by remember(origin.manga.id, favourites) {
		mutableStateOf(origin)
	}
	val candidates = remember(origin, favourites) {
		buildList {
			add(origin)
			favourites.asSequence().filter { it.manga.id != origin.manga.id }.forEach {
				add(PredictionCalendarItem(it.manga, it.prediction, it.releaseDates))
			}
		}
	}
	val pagerState = rememberPagerState(pageCount = { 2 })
	val scope = rememberCoroutineScope()
	var customRevision by remember { mutableStateOf(0) }
	val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
		if (uri != null) {
			scope.launch {
				val imported = withContext(Dispatchers.IO) {
					runCatching {
						val file = File(context.filesDir, CUSTOM_BACKGROUND)
						context.contentResolver.openInputStream(uri)?.use { input ->
							file.outputStream().use(input::copyTo)
						} ?: return@runCatching false
						true
					}.getOrDefault(false)
				}
				if (imported) {
					backgroundMode = PredictionCalendarBackgroundMode.CUSTOM
					prefs.edit().putString(KEY_BACKGROUND, backgroundMode.name).apply()
					customRevision++
				}
			}
		}
	}

	ModalBottomSheet(onDismissRequest = onDismiss) {
		Box(Modifier.fillMaxWidth()) {
			PredictionBackground(selected.manga, backgroundMode, customRevision, imageLoader)
			Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
				Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
					Column(Modifier.weight(1f)) {
						Text("Next Chapter Release Prediction", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
						Text(selected.manga.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
					}
					TextButton(onClick = {
						backgroundMode = PredictionCalendarBackgroundMode.entries[(backgroundMode.ordinal + 1) % PredictionCalendarBackgroundMode.entries.size]
						prefs.edit().putString(KEY_BACKGROUND, backgroundMode.name).apply()
						if (backgroundMode == PredictionCalendarBackgroundMode.CUSTOM && !File(context.filesDir, CUSTOM_BACKGROUND).exists()) {
							picker.launch(arrayOf("image/*"))
						}
					}) { Text("Background") }
				}
				Spacer(Modifier.height(8.dp))
				PredictionCalendar(selected.prediction.expectedDate, selected.releaseDates)
				Spacer(Modifier.height(10.dp))
				HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
					if (page == 0) {
						PredictionActions(selected, onChooseCustom = { picker.launch(arrayOf("image/*")) })
					} else {
						PredictionMangaList(candidates, selected.manga.id, imageLoader) { item ->
							selected = item
							scope.launch { pagerState.animateScrollToPage(0) }
						}
					}
				}
				Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center) {
					repeat(2) { index ->
						Box(Modifier.padding(horizontal = 4.dp).size(7.dp).clip(CircleShape).background(
							if (pagerState.currentPage == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
						))
					}
				}
			}
		}
	}
}

@Composable
private fun PredictionBackground(manga: Manga, mode: PredictionCalendarBackgroundMode, revision: Int, imageLoader: ImageLoader) {
	val context = LocalContext.current
	val model: Any? = when (mode) {
		PredictionCalendarBackgroundMode.DEFAULT -> null
		PredictionCalendarBackgroundMode.MANGA_COVER -> manga.largeCoverUrl ?: manga.coverUrl
		PredictionCalendarBackgroundMode.CUSTOM -> remember(revision) { File(context.filesDir, CUSTOM_BACKGROUND).takeIf(File::exists) }
	}
	if (model != null) {
		AsyncImage(model = model, imageLoader = imageLoader, contentDescription = null, contentScale = ContentScale.Crop,
			modifier = Modifier.matchParentSize().blur(22.dp))
		Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)))
	}
}

@Composable
private fun PredictionCalendar(expectedDate: LocalDate, releases: List<LocalDate>) {
	val month = YearMonth.from(expectedDate)
	val formatter = remember { DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()) }
	Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
		Column(Modifier.padding(16.dp)) {
			Text(expectedDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)), fontWeight = FontWeight.Bold)
			Text(month.format(formatter), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp, bottom = 8.dp))
			Row(Modifier.fillMaxWidth()) {
				DayOfWeek.entries.forEach { day -> Text(day.getDisplayName(java.time.format.TextStyle.NARROW, Locale.getDefault()), Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
			}
			val firstOffset = month.atDay(1).dayOfWeek.value - 1
			val cells = ((firstOffset + month.lengthOfMonth() + 6) / 7) * 7
			for (row in 0 until cells / 7) {
				Row(Modifier.fillMaxWidth()) {
					for (column in 0..6) {
						val day = row * 7 + column - firstOffset + 1
						val date = day.takeIf { it in 1..month.lengthOfMonth() }?.let(month::atDay)
						val predicted = date == expectedDate
						val previous = date != null && date in releases
						Box(Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
							if (predicted) Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(34.dp)) {}
							if (date != null) Text(day.toString(), fontWeight = if (predicted) FontWeight.Bold else FontWeight.Normal)
							if (previous && !predicted) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp).size(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondary))
						}
					}
				}
			}
		}
	}
}

@Composable
private fun PredictionActions(item: PredictionCalendarItem, onChooseCustom: () -> Unit) {
	val context = LocalContext.current
	Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
		Legend("Predicted next chapter", item.prediction.expectedDate.toString(), MaterialTheme.colorScheme.primary)
		Legend("Previous chapter release", "Release dates used by the source history", MaterialTheme.colorScheme.secondary)
		Legend("Other release (from history)", "Older persisted source events", MaterialTheme.colorScheme.tertiary)
		Button(onClick = { addToCalendar(context, item) }, modifier = Modifier.fillMaxWidth()) { Text("Add to calendar") }
		OutlinedButton(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("View prediction details") }
		TextButton(onClick = onChooseCustom, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Choose custom background") }
	}
}

@Composable
private fun Legend(title: String, subtitle: String, color: Color) {
	Row(verticalAlignment = Alignment.CenterVertically) {
		Box(Modifier.size(10.dp).clip(CircleShape).background(color))
		Column(Modifier.padding(start = 10.dp)) { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, style = MaterialTheme.typography.bodySmall) }
	}
}

@Composable
private fun PredictionMangaList(items: List<PredictionCalendarItem>, selectedId: Long, imageLoader: ImageLoader, onSelect: (PredictionCalendarItem) -> Unit) {
	LazyColumn(Modifier.fillMaxWidth().height(300.dp)) {
		item { Text("Manga with upcoming predictions", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp)) }
		items(items, key = { it.manga.id }) { item ->
			Row(Modifier.fillMaxWidth().clickable { onSelect(item) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
				AsyncImage(model = item.manga.coverUrl, imageLoader = imageLoader, contentDescription = null, contentScale = ContentScale.Crop,
					modifier = Modifier.size(42.dp, 58.dp).clip(RoundedCornerShape(8.dp)))
				Column(Modifier.weight(1f).padding(start = 12.dp)) {
					Text(item.manga.title, fontWeight = if (item.manga.id == selectedId) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
					Text("Expected ${item.prediction.expectedDate} · ${item.prediction.daysUntilNext}d", style = MaterialTheme.typography.bodySmall)
				}
			}
			HorizontalDivider()
		}
	}
}

private fun addToCalendar(context: Context, item: PredictionCalendarItem) {
	val zone = ZoneId.systemDefault()
	val start = item.prediction.expectedDate.atStartOfDay(zone).toInstant().toEpochMilli()
	val end = item.prediction.expectedDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
	val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
		.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
		.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
		.putExtra(CalendarContract.Events.ALL_DAY, true)
		.putExtra(CalendarContract.Events.TITLE, "Expected chapter release — ${item.manga.title}")
		.putExtra(CalendarContract.Events.DESCRIPTION, "Estimated from recent source chapter release history. Not an official publication schedule.")
	if (intent.resolveActivity(context.packageManager) != null) context.startActivity(intent)
}
