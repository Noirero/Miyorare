package org.koitharu.kotatsu.readerjourney.ui

import android.app.Dialog
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.suspendCancellableCoroutine
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.core.util.ext.hapticFeedback
import org.koitharu.kotatsu.readerjourney.domain.CelebrationPresentation
import org.koitharu.kotatsu.readerjourney.domain.CelebrationQueueItem
import org.koitharu.kotatsu.readerjourney.domain.ReaderAchievementId
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyCosmeticUnlock
import org.koitharu.kotatsu.readerjourney.domain.presentation
import org.koitharu.kotatsu.readerjourney.domain.presentations
import org.koitharu.kotatsu.readerjourney.theme.RankThemeRegistry
import org.koitharu.kotatsu.readerjourney.theme.RankThemeVariant
import org.koitharu.kotatsu.settings.compose.MiyorareTheme
import kotlin.coroutines.resume

enum class CelebrationDialogResult { DISMISSED, OPEN_COLLECTION }

suspend fun ComponentActivity.presentCelebration(
	item: CelebrationQueueItem,
	onOpenCollection: () -> Unit,
) {
	for (presentation in item.presentations()) {
		if (presentation == CelebrationPresentation.SNACKBAR) return
		val result = showReaderJourneyCelebrationDialog(
			item.copy(presentationOverride = presentation),
		)
		if (result == CelebrationDialogResult.OPEN_COLLECTION) {
			onOpenCollection()
			break
		}
	}
}

suspend fun ComponentActivity.showReaderJourneyCelebrationDialog(
	item: CelebrationQueueItem,
): CelebrationDialogResult = suspendCancellableCoroutine { continuation ->
	var result = CelebrationDialogResult.DISMISSED
	val dialog = Dialog(this)
	dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
	dialog.setCancelable(true)
	dialog.setCanceledOnTouchOutside(true)
	val compose = ComposeView(this).apply {
		setViewTreeLifecycleOwner(this@showReaderJourneyCelebrationDialog)
		setViewTreeSavedStateRegistryOwner(this@showReaderJourneyCelebrationDialog)
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
		setContent {
			MiyorareTheme {
				CelebrationDialogContent(
					item = item,
					onDismiss = { dialog.dismiss() },
					onOpenCollection = {
						result = CelebrationDialogResult.OPEN_COLLECTION
						dialog.dismiss()
					},
				)
			}
		}
	}
	dialog.setContentView(compose)
	dialog.setOnShowListener {
		dialog.window?.apply {
			setBackgroundDrawableResource(android.R.color.transparent)
			setDimAmount(0.48f)
			setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
		}
		compose.hapticFeedback(HapticEffect.CONFIRM)
	}
	dialog.setOnDismissListener {
		if (continuation.isActive) continuation.resume(result)
	}
	continuation.invokeOnCancellation { dialog.dismiss() }
	dialog.show()
}

@Composable
private fun CelebrationDialogContent(item: CelebrationQueueItem, onDismiss: () -> Unit, onOpenCollection: () -> Unit) {
	Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
		if (!item.reduceMotion) ConfettiCanvas(Modifier.fillMaxSize())
		Surface(shape = RoundedCornerShape(28.dp), tonalElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
			when (item.presentation()) {
				CelebrationPresentation.ACHIEVEMENT -> AchievementCard(item.event.unlockedAchievements.first(), onDismiss)
				CelebrationPresentation.COSMETIC -> CosmeticCard(item.event.unlockedCosmetics.first(), !item.reduceMotion, onOpenCollection, onDismiss)
				CelebrationPresentation.RANK_UP -> RankUpCard(item, !item.reduceMotion, onOpenCollection, onDismiss)
				CelebrationPresentation.SNACKBAR -> Unit
			}
		}
	}
}

@Composable
private fun AchievementCard(achievement: ReaderAchievementId, onDismiss: () -> Unit) {
	Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
		Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
			Icon(painterResource(R.drawable.ic_check), null, Modifier.padding(14.dp).size(32.dp))
		}
		Text(stringResource(R.string.reader_journey_celebration_achievement), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
		Text(stringResource(achievement.titleRes), style = MaterialTheme.typography.headlineSmall)
		Text(stringResource(achievement.descriptionRes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
		Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
			Text("+" + achievement.xpReward + " XP", Modifier.padding(horizontal = 14.dp, vertical = 7.dp), style = MaterialTheme.typography.labelLarge)
		}
		Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.reader_journey_celebration_continue)) }
	}
}

@Composable
private fun CosmeticCard(cosmetic: ReaderJourneyCosmeticUnlock, animate: Boolean, onOpenCollection: () -> Unit, onDismiss: () -> Unit) {
	val tokens = RankThemeRegistry.resolveOrDefault(cosmetic.theme.stableId).tokens(RankThemeVariant.DARK)
	Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
		Text(stringResource(R.string.reader_journey_celebration_cosmetic), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
		Text(cosmetic.name, style = MaterialTheme.typography.headlineSmall)
		ReferenceRankThemeNameplate(spec = cosmetic.previewInfo, tokens = tokens, modifier = Modifier.fillMaxWidth().height(92.dp), animate = animate)
		Text(cosmetic.type.name.replace('_', ' '), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
		Button(onClick = onOpenCollection, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.reader_journey_celebration_view_collection)) }
		TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
	}
}

@Composable
private fun RankUpCard(item: CelebrationQueueItem, animate: Boolean, onOpenCollection: () -> Unit, onDismiss: () -> Unit) {
	val cosmetic = item.event.unlockedCosmetics.firstOrNull()
	Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
		Text(stringResource(R.string.reader_journey_celebration_rank_up), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
		Text(cosmetic?.theme?.displayName ?: item.event.toRank.name.replace('_', ' '), style = MaterialTheme.typography.headlineSmall)
		if (cosmetic != null) {
			val tokens = RankThemeRegistry.resolveOrDefault(cosmetic.theme.stableId).tokens(RankThemeVariant.DARK)
			ReferenceRankThemeNameplate(cosmetic.previewInfo, tokens, Modifier.fillMaxWidth().height(92.dp), animate = animate)
		}
		Button(onClick = onOpenCollection, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.reader_journey_celebration_view_collection)) }
		TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
	}
}

@Composable
private fun ConfettiCanvas(modifier: Modifier = Modifier) {
	val transition = rememberInfiniteTransition(label = "journey-confetti")
	val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart), label = "journey-confetti-phase")
	val colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.primaryContainer)
	Canvas(modifier) {
		repeat(28) { index ->
			val x = size.width * (((index * 37) % 100) / 100f)
			val y = size.height * ((phase + ((index * 19) % 100) / 100f) % 1f)
			val w = 5.dp.toPx() + (index % 3) * 2.dp.toPx()
			val h = 10.dp.toPx() + (index % 4) * 2.dp.toPx()
			rotate(index * 31f + phase * 180f, pivot = androidx.compose.ui.geometry.Offset(x, y)) {
				drawRect(colors[index % colors.size], topLeft = androidx.compose.ui.geometry.Offset(x, y), size = androidx.compose.ui.geometry.Size(w, h))
			}
		}
	}
}

internal val ReaderAchievementId.titleRes: Int get() = when (this) {
	ReaderAchievementId.FIRST_CHAPTER -> R.string.reader_journey_achievement_first_chapter
	ReaderAchievementId.CHAPTERS_100 -> R.string.reader_journey_achievement_chapters_100
	ReaderAchievementId.CHAPTERS_1000 -> R.string.reader_journey_achievement_chapters_1000
	ReaderAchievementId.FIRST_NOVEL -> R.string.reader_journey_achievement_first_novel
	ReaderAchievementId.TITLES_10 -> R.string.reader_journey_achievement_titles_10
	ReaderAchievementId.TITLES_50 -> R.string.reader_journey_achievement_titles_50
	ReaderAchievementId.STREAK_7 -> R.string.reader_journey_achievement_streak_7
	ReaderAchievementId.STREAK_30 -> R.string.reader_journey_achievement_streak_30
	ReaderAchievementId.STREAK_100 -> R.string.reader_journey_achievement_streak_100
}

internal val ReaderAchievementId.descriptionRes: Int get() = when (this) {
	ReaderAchievementId.FIRST_CHAPTER -> R.string.reader_journey_achievement_first_chapter_desc
	ReaderAchievementId.CHAPTERS_100 -> R.string.reader_journey_achievement_chapters_100_desc
	ReaderAchievementId.CHAPTERS_1000 -> R.string.reader_journey_achievement_chapters_1000_desc
	ReaderAchievementId.FIRST_NOVEL -> R.string.reader_journey_achievement_first_novel_desc
	ReaderAchievementId.TITLES_10 -> R.string.reader_journey_achievement_titles_10_desc
	ReaderAchievementId.TITLES_50 -> R.string.reader_journey_achievement_titles_50_desc
	ReaderAchievementId.STREAK_7 -> R.string.reader_journey_achievement_streak_7_desc
	ReaderAchievementId.STREAK_30 -> R.string.reader_journey_achievement_streak_30_desc
	ReaderAchievementId.STREAK_100 -> R.string.reader_journey_achievement_streak_100_desc
}
