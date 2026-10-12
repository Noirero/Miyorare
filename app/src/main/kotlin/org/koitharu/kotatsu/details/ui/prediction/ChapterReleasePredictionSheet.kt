package org.koitharu.kotatsu.details.ui.prediction

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.core.util.ext.tryLaunch
import org.koitharu.kotatsu.details.domain.ChapterReleasePredictionItem
import org.koitharu.kotatsu.details.domain.FavouriteReleasePredictionsUseCase
import org.koitharu.kotatsu.details.domain.releasePredictionOrder
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

// A dark image treatment is independent of the host theme; retain the image's character.
internal const val PREDICTION_BACKGROUND_SCRIM_ALPHA = 0.68f

internal fun predictionCalendarColorScheme(base: ColorScheme, imageBackground: Boolean): ColorScheme {
	if (!imageBackground) return base
	val dark = darkColorScheme()
	val brightestBackdrop = dark.scrim.copy(alpha = PREDICTION_BACKGROUND_SCRIM_ALPHA).compositeOver(Color.White)
	fun accent(color: Color): Color {
		var candidate = color.copy(alpha = 1f)
		repeat(12) {
			val contrast = (candidate.luminance() + 0.05f) / (brightestBackdrop.luminance() + 0.05f)
			if (contrast >= 4.5f) return candidate
			candidate = lerp(candidate, dark.onSurface, 0.16f)
		}
		return candidate
	}
	val primary = accent(base.primary)
	return dark.copy(
		primary = primary, onPrimary = dark.surface,
		primaryContainer = lerp(dark.surface, primary, 0.2f), onPrimaryContainer = dark.onSurface,
		secondary = accent(base.secondary), tertiary = accent(base.tertiary),
		outline = dark.onSurfaceVariant.copy(alpha = 0.8f),
		outlineVariant = dark.onSurfaceVariant.copy(alpha = 0.7f),
	)
}

@Composable
private fun predictionRainbowBrush(): Brush = remember {
	Brush.sweepGradient(listOf(Color(0xFFFF4D6D), Color(0xFFFFB84D), Color(0xFFFFF15C), Color(0xFF62E36D), Color(0xFF4DD8FF), Color(0xFF6C63FF), Color(0xFFD65CFF), Color(0xFFFF4D6D)))
}

internal data class UpcomingPredictions(
	val items: List<ChapterReleasePredictionItem> = emptyList(),
	val loading: Boolean = true,
	val failed: Boolean = false,
	val locked: Boolean = false,
)

@Composable
internal fun ChapterReleasePredictionRoute(
	originMangaId: Long,
	origin: ChapterReleasePredictionItem?,
	space: FavouriteSpace,
	canReadFavourites: Boolean,
	useCase: FavouriteReleasePredictionsUseCase,
	imageLoader: ImageLoader,
	onDismiss: () -> Unit,
) {
	val lifecycle = LocalLifecycleOwner.current.lifecycle
	val upcoming by produceState(UpcomingPredictions(), space, lifecycle, useCase, canReadFavourites) {
		if (!canReadFavourites) {
			value = UpcomingPredictions(loading = false, locked = true)
			return@produceState
		}
		lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
			value = UpcomingPredictions()
			useCase.observe(space).catch { value = UpcomingPredictions(loading = false, failed = true) }
				.collect { value = UpcomingPredictions(it, loading = false) }
		}
	}
	ChapterReleasePredictionSheet(originMangaId, origin.takeIf { canReadFavourites },
		if (canReadFavourites) upcoming else UpcomingPredictions(loading = false, locked = true), imageLoader, onDismiss)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChapterReleasePredictionSheet(
	originMangaId: Long,
	origin: ChapterReleasePredictionItem?,
	upcoming: UpcomingPredictions,
	imageLoader: ImageLoader,
	onDismiss: () -> Unit,
) {
	val context = LocalContext.current
	val prefs = remember(context) { context.getSharedPreferences(PREDICTION_PREFS, Context.MODE_PRIVATE) }
	var backgroundMode by remember { mutableStateOf(PredictionCalendarBackgroundStore.readMode(context)) }
	var blurCover by remember { mutableStateOf(PredictionCalendarBackgroundStore.readCoverBlur(context)) }
	var customRevision by remember { mutableIntStateOf(prefs.getInt(KEY_BACKGROUND_REVISION, 0)) }
	var selectedId by rememberSaveable(originMangaId) { mutableLongStateOf(originMangaId) }
	var selectedFromOrigin by rememberSaveable(originMangaId) { mutableStateOf(true) }
	val useOriginRow = selectedId != originMangaId || selectedFromOrigin
	val candidates by produceState(emptyList<ChapterReleasePredictionItem>(), originMangaId, origin, upcoming.items, useOriginRow) {
		value = withContext(Dispatchers.Default) {
			upcoming.items.map { if (useOriginRow && it.manga.id == originMangaId && origin != null) origin else it }.sortedWith(releasePredictionOrder)
		}
	}
	val selected = remember(selectedId, selectedFromOrigin, origin, upcoming.items) {
		if (selectedFromOrigin) origin else upcoming.items.find { it.manga.id == selectedId }
	}
	val pagerState = rememberPagerState(pageCount = { 2 })
	val scope = rememberCoroutineScope()
	val unavailable = stringResource(if (upcoming.locked) R.string.release_calendar_locked else R.string.release_calendar_unavailable)
	var showBackground by remember { mutableStateOf(false) }
	var showDetails by remember { mutableStateOf(false) }
	var importing by remember { mutableStateOf(false) }
	val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
		if (uri != null && !importing) scope.launch {
			importing = true
			try {
				val result = runCatchingCancellable { withContext(Dispatchers.IO) { PredictionCalendarBackgroundStore.import(context, uri) } }
				if (result.isSuccess) {
					customRevision++
					prefs.edit().putInt(KEY_BACKGROUND_REVISION, customRevision).apply()
					backgroundMode = PredictionCalendarBackgroundMode.CUSTOM
					PredictionCalendarBackgroundStore.writeMode(context, backgroundMode)
				} else Toast.makeText(context, R.string.release_calendar_import_error, Toast.LENGTH_SHORT).show()
			} finally { importing = false }
		}
	}
	val chooseImage = {
		if (!importing && !picker.tryLaunch(arrayOf("image/*"))) {
			Toast.makeText(context, R.string.release_calendar_import_error, Toast.LENGTH_SHORT).show()
		}
	}
	val imageBackground = backgroundMode != PredictionCalendarBackgroundMode.DEFAULT
	val baseScheme = MaterialTheme.colorScheme
	val calendarScheme = remember(baseScheme, imageBackground) { predictionCalendarColorScheme(baseScheme, imageBackground) }
	val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
	val noInsets = WindowInsets(0, 0, 0, 0)
	val dismissLabel = stringResource(R.string.close)
	MaterialTheme(colorScheme = calendarScheme) {
		ModalBottomSheet(
			modifier = Modifier.testTag("release-prediction-sheet"),
			onDismissRequest = onDismiss,
			sheetState = sheetState,
			dragHandle = if (imageBackground) null else ({ BottomSheetDefaults.DragHandle() }),
			containerColor = if (imageBackground) Color.Transparent else MaterialTheme.colorScheme.surface,
			// Transparent containers cannot resolve a themed content color automatically.
			contentColor = MaterialTheme.colorScheme.onSurface,
			contentWindowInsets = { if (imageBackground) noInsets else BottomSheetDefaults.windowInsets },
		) {
			Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).testTag("release-prediction-backdrop")) {
				PredictionBackground(selected, backgroundMode, blurCover, customRevision, imageLoader, Modifier.matchParentSize())
				Column(Modifier.fillMaxWidth().windowInsetsPadding(if (imageBackground) BottomSheetDefaults.windowInsets else noInsets)) {
					if (imageBackground) BottomSheetDefaults.DragHandle(
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.align(Alignment.CenterHorizontally).testTag("release-prediction-handle").semantics(mergeDescendants = true) {
							dismiss(dismissLabel) {
								scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) onDismiss() }
								true
							}
						},
					)
					Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
						Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
							Text(stringResource(R.string.release_calendar_title), style = MaterialTheme.typography.titleLarge,
								fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
							IconButton(onClick = { showBackground = true }) {
								Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_settings), stringResource(R.string.release_calendar_background))
							}
						}
						if (selected != null) {
							Text(selected.manga.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
								modifier = Modifier.testTag("release-prediction-active-title"))
							Text(selected.prediction.expectedDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)), fontWeight = FontWeight.SemiBold,
								modifier = Modifier.testTag("release-prediction-date").padding(top = 6.dp))
							Text(expectedLabel(selected), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
							Spacer(Modifier.height(8.dp))
							PredictionCalendar(selected, imageBackground)
						} else Text(unavailable, modifier = Modifier.padding(vertical = 16.dp))
						Spacer(Modifier.height(12.dp))
						HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth().height(280.dp).testTag("release-prediction-pager")) { page ->
							if (page == 0) {
								if (selected != null) PredictionActions(selected, onDetails = { showDetails = true })
								else Text(unavailable)
							} else PredictionMangaList(candidates, selectedId, upcoming, imageLoader) { item ->
								selectedId = item.manga.id
								selectedFromOrigin = item == origin
								scope.launch { pagerState.animateScrollToPage(0) }
							}
						}
						Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center) {
							repeat(2) { index ->
								val label = stringResource(R.string.release_calendar_page, index + 1)
								Box(Modifier.padding(horizontal = 4.dp).size(8.dp).clip(CircleShape)
									.background(if (pagerState.currentPage == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
									.semantics { contentDescription = label; this.selected = pagerState.currentPage == index })
							}
						}
					}
				}
			}
		}
		if (showBackground) AlertDialog(
			onDismissRequest = { showBackground = false },
			title = { Text(stringResource(R.string.release_calendar_background)) },
			text = { Column {
				PredictionCalendarBackgroundMode.entries.forEach { mode ->
					val label = when (mode) {
						PredictionCalendarBackgroundMode.DEFAULT -> R.string.release_calendar_background_default
						PredictionCalendarBackgroundMode.MANGA_COVER -> R.string.release_calendar_background_cover
						PredictionCalendarBackgroundMode.CUSTOM -> R.string.release_calendar_background_custom
					}
					Row(Modifier.fillMaxWidth().clickable(enabled = !importing) {
						if (mode == PredictionCalendarBackgroundMode.CUSTOM && !PredictionCalendarBackgroundStore.file(context).isFile) chooseImage()
						else { backgroundMode = mode; PredictionCalendarBackgroundStore.writeMode(context, mode) }
						if (mode != PredictionCalendarBackgroundMode.MANGA_COVER) showBackground = false
					}, verticalAlignment = Alignment.CenterVertically) {
						RadioButton(selected = mode == backgroundMode, onClick = null, enabled = !importing)
						Text(stringResource(label))
					}
					if (mode == PredictionCalendarBackgroundMode.MANGA_COVER && backgroundMode == mode) {
						Row(Modifier.fillMaxWidth().padding(start = 16.dp).toggleable(
							value = blurCover, role = Role.Switch, enabled = !importing,
							onValueChange = { blurCover = it; PredictionCalendarBackgroundStore.writeCoverBlur(context, it) },
						), verticalAlignment = Alignment.CenterVertically) {
							Text(stringResource(R.string.release_calendar_cover_blur), modifier = Modifier.weight(1f))
							Switch(checked = blurCover, onCheckedChange = null, enabled = !importing)
						}
					}
				}
				TextButton(onClick = { showBackground = false; chooseImage() }, enabled = !importing) { Text(stringResource(R.string.release_calendar_choose_image)) }
			} },
			confirmButton = { TextButton(onClick = { showBackground = false }) { Text(stringResource(R.string.close)) } },
		)
		if (showDetails && selected != null) AlertDialog(
			onDismissRequest = { showDetails = false },
			title = { Text(stringResource(R.string.release_calendar_details)) },
			text = {
				Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
					Text(selected.manga.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
					Text(stringResource(R.string.release_calendar_source, selected.manga.source.getTitle(context)), style = MaterialTheme.typography.bodySmall)
					selected.branch?.let { Text(stringResource(R.string.release_calendar_branch, it), style = MaterialTheme.typography.bodySmall) }
					Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
						contentColor = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.fillMaxWidth()) {
						Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
							Text(formatDate(selected.prediction.expectedDate), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
							Text(expectedLabel(selected), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
							Text(stringResource(R.string.release_calendar_predicted), style = MaterialTheme.typography.labelSmall)
						}
					}
					Text(stringResource(R.string.release_calendar_recent_history), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
					selected.releaseDates.forEach { Text(formatDate(it), style = MaterialTheme.typography.bodyMedium) }
					HorizontalDivider()
					Text(stringResource(R.string.release_calendar_estimate), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
				}
			},
			confirmButton = { TextButton(onClick = { showDetails = false }) { Text(stringResource(R.string.close)) } },
		)
	}
}

@Composable
private fun PredictionBackground(item: ChapterReleasePredictionItem?, mode: PredictionCalendarBackgroundMode, blurCover: Boolean, revision: Int, imageLoader: ImageLoader, modifier: Modifier) {
	val context = LocalContext.current
	val model: Any? = when (mode) {
		PredictionCalendarBackgroundMode.DEFAULT -> null
		PredictionCalendarBackgroundMode.MANGA_COVER -> item?.manga?.largeCoverUrl ?: item?.manga?.coverUrl
		PredictionCalendarBackgroundMode.CUSTOM -> remember(revision) { PredictionCalendarBackgroundStore.file(context).takeIf { it.isFile && it.length() > 0 } }
	}
	if (model != null) Box(modifier) {
		val source = if (mode == PredictionCalendarBackgroundMode.MANGA_COVER) item?.manga?.source else null
		val request = remember(model, revision, source) {
			ImageRequest.Builder(context).data(model).mangaSourceExtra(source).memoryCacheKey("prediction:${source?.name}:$model:$revision").build()
		}
		AsyncImage(model = request, imageLoader = imageLoader, contentDescription = null, contentScale = ContentScale.Crop,
			modifier = Modifier.fillMaxSize().then(if (mode.shouldBlur(blurCover)) Modifier.blur(22.dp) else Modifier), onError = {
				if (mode == PredictionCalendarBackgroundMode.CUSTOM) Toast.makeText(context, R.string.release_calendar_image_error, Toast.LENGTH_SHORT).show()
			})
		Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = PREDICTION_BACKGROUND_SCRIM_ALPHA)))
	}
}

@Composable
private fun PredictionCalendar(item: ChapterReleasePredictionItem, imageBackground: Boolean) {
	val expectedDate = item.prediction.expectedDate
	var monthValue by rememberSaveable(item.manga.id, expectedDate.toString()) { mutableStateOf(YearMonth.from(expectedDate).toString()) }
	val month = YearMonth.parse(monthValue)
	val formatter = remember { DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()) }
	val previousDate = item.releaseDates.maxOrNull()
	val history = remember(item.releaseDates) { item.releaseDates.toSet() }
	val density = LocalDensity.current
	val dayHeight = maxOf(34.dp, with(density) { MaterialTheme.typography.bodyMedium.lineHeight.toDp() + 6.dp })
	val previousMonthLabel = stringResource(R.string.release_calendar_previous_month)
	val nextMonthLabel = stringResource(R.string.release_calendar_next_month)
	val rainbow = predictionRainbowBrush()
	Surface(
		shape = RoundedCornerShape(20.dp),
		color = if (imageBackground) MaterialTheme.colorScheme.surface.copy(alpha = 0.76f) else MaterialTheme.colorScheme.surface,
		contentColor = MaterialTheme.colorScheme.onSurface,
		tonalElevation = if (imageBackground) 0.dp else 2.dp,
		modifier = Modifier.fillMaxWidth().testTag("release-prediction-calendar"),
	) {
		Column(Modifier.padding(12.dp)) {
			Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
				IconButton(onClick = { monthValue = month.minusMonths(1).toString() }) { Text("‹", modifier = Modifier.semantics { contentDescription = previousMonthLabel }) }
				Text(month.format(formatter), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
				IconButton(onClick = { monthValue = month.plusMonths(1).toString() }) { Text("›", modifier = Modifier.semantics { contentDescription = nextMonthLabel }) }
			}
			Row(Modifier.fillMaxWidth()) { DayOfWeek.entries.forEach { day ->
				Text(day.getDisplayName(TextStyle.SHORT, Locale.getDefault()), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
			} }
			val firstOffset = month.atDay(1).dayOfWeek.value - 1
			val cells = ((firstOffset + month.lengthOfMonth() + 6) / 7) * 7
			for (row in 0 until cells / 7) Row(Modifier.fillMaxWidth()) {
				for (column in 0..6) {
					val day = row * 7 + column - firstOffset + 1
					val date = day.takeIf { it in 1..month.lengthOfMonth() }?.let(month::atDay)
					val predicted = date == expectedDate
					val previous = date != null && date == previousDate
					val historical = date != null && date in history
					val markerLabel = when {
						predicted -> stringResource(R.string.release_calendar_predicted)
						previous -> stringResource(R.string.release_calendar_previous)
						historical -> stringResource(R.string.release_calendar_history)
						else -> ""
					}
					Box(Modifier.weight(1f).height(dayHeight).semantics { if (date != null) contentDescription = "${formatDate(date)} $markerLabel" }, contentAlignment = Alignment.Center) {
						if (predicted) Box(Modifier.size(dayHeight - 4.dp).border(BorderStroke(2.dp, rainbow), CircleShape))
						if (date != null) Text(day.toString(), style = MaterialTheme.typography.bodyMedium, fontWeight = if (predicted) FontWeight.Bold else FontWeight.Normal,
							color = MaterialTheme.colorScheme.onSurface)
						if (historical) Box(Modifier.align(Alignment.BottomCenter).size(if (previous) 6.dp else 4.dp).clip(if (previous) CircleShape else RoundedCornerShape(1.dp))
							.background(if (previous) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary))
					}
				}
			}
		}
	}
}

@Composable
private fun PredictionActions(item: ChapterReleasePredictionItem, onDetails: () -> Unit) {
	val context = LocalContext.current
	Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
		Legend(stringResource(R.string.release_calendar_predicted), formatDate(item.prediction.expectedDate), predictionRainbowBrush(), true)
		Legend(stringResource(R.string.release_calendar_previous), item.releaseDates.maxOrNull()?.let(::formatDate).orEmpty(), SolidColor(MaterialTheme.colorScheme.secondary), true)
		Legend(stringResource(R.string.release_calendar_history), stringResource(R.string.release_calendar_recent_history), SolidColor(MaterialTheme.colorScheme.tertiary), false)
		Button(onClick = { addToCalendar(context, item) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.release_calendar_add)) }
		OutlinedButton(onClick = onDetails, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.release_calendar_details)) }
	}
}

@Composable
private fun Legend(title: String, subtitle: String, brush: Brush, round: Boolean) {
	Row(verticalAlignment = Alignment.CenterVertically) {
		Box(Modifier.size(10.dp).clip(if (round) CircleShape else RoundedCornerShape(1.dp)).background(brush))
		Column(Modifier.padding(start = 10.dp)) { Text(title, fontWeight = FontWeight.SemiBold)
			Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
	}
}

@Composable
private fun PredictionMangaList(items: List<ChapterReleasePredictionItem>, selectedId: Long, state: UpcomingPredictions, imageLoader: ImageLoader, onSelect: (ChapterReleasePredictionItem) -> Unit) {
	val context = LocalContext.current
	Column(Modifier.fillMaxSize()) {
		Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
			Text(stringResource(R.string.release_calendar_upcoming), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
			Text(items.size.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
		}
		when {
			state.locked -> Text(stringResource(R.string.release_calendar_locked), modifier = Modifier.padding(vertical = 12.dp))
			state.loading -> Text(stringResource(R.string.release_calendar_loading), modifier = Modifier.padding(vertical = 12.dp))
			state.failed -> Text(stringResource(R.string.release_calendar_error), modifier = Modifier.padding(vertical = 12.dp))
			items.isEmpty() -> Text(stringResource(R.string.release_calendar_empty), modifier = Modifier.padding(vertical = 12.dp))
			else -> LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
				items(items, key = { it.manga.id }) { item ->
					val coverRequest = remember(item.manga.coverUrl, item.manga.source) {
						ImageRequest.Builder(context).data(item.manga.coverUrl).mangaSourceExtra(item.manga.source).build()
					}
					Row(Modifier.fillMaxWidth().testTag("release-prediction-manga:${item.manga.id}").clickable { onSelect(item) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
						AsyncImage(model = coverRequest, imageLoader = imageLoader, contentDescription = null, contentScale = ContentScale.Crop,
							modifier = Modifier.size(42.dp, 58.dp).clip(RoundedCornerShape(8.dp)))
						Column(Modifier.weight(1f).padding(start = 12.dp)) {
							Text(item.manga.title, fontWeight = if (item.manga.id == selectedId) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
							Text("${formatDate(item.prediction.expectedDate)} · ${expectedLabel(item)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
							if (item.manga.id == selectedId) Text(stringResource(R.string.release_calendar_selected), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
						}
					}
					HorizontalDivider()
				}
			}
		}
	}
}

private fun formatDate(date: LocalDate) = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

@Composable
private fun expectedLabel(item: ChapterReleasePredictionItem): String = if (item.prediction.daysUntilNext == 0L) stringResource(R.string.release_calendar_today)
	else pluralStringResource(R.plurals.release_calendar_expected_days, item.prediction.daysUntilNext.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), item.prediction.daysUntilNext)

internal fun releasePredictionCalendarIntent(context: Context, item: ChapterReleasePredictionItem): Intent {
	val start = item.prediction.expectedDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
	val end = item.prediction.expectedDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
	return Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
		.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
		.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
		.putExtra(CalendarContract.Events.ALL_DAY, true)
		.putExtra(CalendarContract.Events.TITLE, context.getString(R.string.release_calendar_event_title, item.manga.title))
		.putExtra(CalendarContract.Events.DESCRIPTION, context.getString(R.string.release_calendar_estimate))
}

private fun addToCalendar(context: Context, item: ChapterReleasePredictionItem) {
	try {
		context.startActivity(releasePredictionCalendarIntent(context, item))
	} catch (_: ActivityNotFoundException) {
		Toast.makeText(context, R.string.release_calendar_no_handler, Toast.LENGTH_SHORT).show()
	} catch (_: SecurityException) {
		Toast.makeText(context, R.string.release_calendar_no_handler, Toast.LENGTH_SHORT).show()
	}
}
