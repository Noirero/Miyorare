package org.koitharu.kotatsu.download.ui.worker

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.text.format.DateUtils
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.work.WorkManager
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.size.Scale
import coil3.toBitmap
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.LocalizedAppContext
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.getNotificationIconSize
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.download.domain.DownloadState
import org.koitharu.kotatsu.download.ui.list.DownloadsActivity
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.format
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.util.UUID
import androidx.appcompat.R as appcompatR

private const val CHANNEL_ID_DEFAULT = "download"
private const val CHANNEL_ID_SILENT = "download_bg"
private const val GROUP_ID = "downloads"
private const val PRIVATE_STATUS_CACHE_MS = 1_000L

class DownloadNotificationFactory @AssistedInject constructor(
	@LocalizedAppContext private val context: Context,
	private val workManager: WorkManager,
	private val coil: ImageLoader,
	private val database: MangaDatabase,
	private val settings: AppSettings,
	@Assisted private val uuid: UUID,
	@Assisted val isSilent: Boolean,
) {

	private val covers = HashMap<Manga, Drawable>()
	private val builder = NotificationCompat.Builder(context, if (isSilent) CHANNEL_ID_SILENT else CHANNEL_ID_DEFAULT)
	private val mutex = Mutex()
	private var privateStatusMangaId: Long = 0L
	private var privateStatusValue = true
	private var privateStatusCheckedAt = 0L

	private val queueIntent = PendingIntentCompat.getActivity(
		context,
		0,
		Intent(context, DownloadsActivity::class.java),
		0,
		false,
	)

	private val actionCancel by lazy {
		NotificationCompat.Action(
			appcompatR.drawable.abc_ic_clear_material,
			context.getString(android.R.string.cancel),
			workManager.createCancelPendingIntent(uuid),
		)
	}

	private val actionPause by lazy {
		NotificationCompat.Action(R.drawable.ic_action_pause, context.getString(R.string.pause), PausingReceiver.createPausePendingIntent(context, uuid))
	}
	private val actionResume by lazy {
		NotificationCompat.Action(R.drawable.ic_action_resume, context.getString(R.string.resume), PausingReceiver.createResumePendingIntent(context, uuid))
	}
	private val actionRetry by lazy {
		NotificationCompat.Action(R.drawable.ic_retry, context.getString(R.string.retry), actionResume.actionIntent)
	}
	private val actionSkip by lazy {
		NotificationCompat.Action(R.drawable.ic_action_skip, context.getString(R.string.skip), PausingReceiver.createSkipPendingIntent(context, uuid))
	}

	init {
		createChannels()
		builder.setOnlyAlertOnce(true)
		builder.setDefaults(0)
		builder.foregroundServiceBehavior = if (isSilent) NotificationCompat.FOREGROUND_SERVICE_DEFERRED else NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
		builder.setSilent(true)
		builder.setGroup(GROUP_ID)
		builder.setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
		builder.priority = if (isSilent) NotificationCompat.PRIORITY_MIN else NotificationCompat.PRIORITY_DEFAULT
	}

	suspend fun create(state: DownloadState?): Notification = mutex.withLock {
		val isPrivateOnly = state?.let { isPrivateOnly(it.manga.id) } == true
		val redactPrivateDetails = isPrivateOnly && !settings.isPrivateDownloadNotificationDetailsEnabled
		if (state == null || redactPrivateDetails) {
			builder.setContentTitle(context.getString(R.string.manga_downloading_))
			builder.setContentText(context.getString(if (state == null) R.string.preparing_ else R.string.manga_downloading_))
		} else {
			builder.setContentTitle(state.manga.title)
			builder.setContentText(context.getString(R.string.manga_downloading_))
		}
		builder.setProgress(1, 0, true)
		builder.setSmallIcon(R.drawable.general_notification)
		builder.setContentIntent(queueIntent)
		builder.setStyle(null)
		builder.setLargeIcon(if (state != null && !redactPrivateDetails) getCover(state.manga)?.toBitmap() else null)
		builder.clearActions()
		builder.setSubText(null)
		builder.setShowWhen(false)
		builder.setAutoCancel(false)
		builder.setVisibility(if (redactPrivateDetails || (state != null && state.manga.isNsfw())) NotificationCompat.VISIBILITY_SECRET else NotificationCompat.VISIBILITY_PRIVATE)
		when {
			state == null -> Unit
			state.localManga != null -> {
				builder.setProgress(0, 0, false)
				builder.setContentText(context.getString(R.string.download_complete))
				builder.setContentIntent(if (redactPrivateDetails) queueIntent else createMangaIntent(context, state.localManga.manga))
				builder.setAutoCancel(true)
				builder.setSmallIcon(R.drawable.general_notification)
				builder.setCategory(null)
				builder.setStyle(null)
				builder.setOngoing(false)
				builder.setShowWhen(true)
				builder.setWhen(System.currentTimeMillis())
			}
			state.isStopped -> {
				builder.setProgress(0, 0, false)
				builder.setContentText(context.getString(R.string.queued))
				builder.setCategory(NotificationCompat.CATEGORY_PROGRESS)
				builder.setStyle(null)
				builder.setOngoing(true)
				builder.setSmallIcon(R.drawable.ic_stat_paused)
				builder.addAction(actionCancel)
			}
			state.isPaused -> {
				builder.setProgress(state.max, state.progress, false)
				val progressText = getProgressString(state.copy(eta = -1L, isStuck = false))
				if (state.errorMessage != null) {
					builder.setContentText(if (redactPrivateDetails) context.getString(R.string.error) else if (progressText != null) context.getString(R.string.download_summary_pattern, progressText, state.errorMessage) else state.errorMessage)
				} else builder.setContentText(progressText)
				builder.setCategory(NotificationCompat.CATEGORY_PROGRESS)
				builder.setStyle(null)
				builder.setOngoing(true)
				builder.setSmallIcon(R.drawable.ic_stat_paused)
				builder.addAction(actionCancel)
				if (state.errorMessage != null) {
					builder.addAction(actionRetry)
					builder.addAction(actionSkip)
				} else builder.addAction(actionResume)
			}
			state.isIndeterminate -> {
				builder.setProgress(1, 0, true)
				builder.setContentText(context.getString(if (redactPrivateDetails) R.string.manga_downloading_ else R.string.preparing_))
				builder.setCategory(NotificationCompat.CATEGORY_PROGRESS)
				builder.setStyle(null)
				builder.setOngoing(true)
				builder.addAction(actionCancel)
				builder.addAction(actionPause)
			}
			state.error != null -> {
				val errorText = if (redactPrivateDetails) context.getString(R.string.error) else state.errorMessage
				builder.setProgress(0, 0, false)
				builder.setSmallIcon(R.drawable.general_notification)
				builder.setSubText(context.getString(R.string.error))
				builder.setContentText(errorText)
				builder.setAutoCancel(true)
				builder.setOngoing(false)
				builder.setCategory(NotificationCompat.CATEGORY_ERROR)
				builder.setShowWhen(true)
				builder.setWhen(System.currentTimeMillis())
				builder.setStyle(NotificationCompat.BigTextStyle().bigText(errorText))
			}
			else -> {
				builder.setProgress(state.max, state.progress, false)
				builder.setContentText(getProgressString(state))
				builder.setCategory(NotificationCompat.CATEGORY_PROGRESS)
				builder.setStyle(null)
				builder.setOngoing(true)
				builder.addAction(actionCancel)
				builder.addAction(actionPause)
			}
		}
		if (state != null && redactPrivateDetails) {
			builder.setContentTitle(context.getString(R.string.manga_downloading_))
			builder.setContentText(context.getString(if (state.localManga != null) R.string.download_complete else R.string.manga_downloading_))
			builder.setLargeIcon(null as android.graphics.Bitmap?)
			builder.setContentIntent(queueIntent)
			builder.setStyle(null)
			builder.setSubText(null)
			builder.setVisibility(NotificationCompat.VISIBILITY_SECRET)
		}
		builder.build()
	}

	private suspend fun isPrivateOnly(mangaId: Long): Boolean {
		val now = android.os.SystemClock.elapsedRealtime()
		if (privateStatusMangaId == mangaId && now - privateStatusCheckedAt < PRIVATE_STATUS_CACHE_MS) return privateStatusValue
		val resolved = runCatchingCancellable { database.getPrivateFavouritesDao().isPrivateOnly(mangaId) }.getOrDefault(true)
		privateStatusMangaId = mangaId
		privateStatusValue = resolved
		privateStatusCheckedAt = now
		return resolved
	}

	private fun getProgressString(state: DownloadState): CharSequence? {
		val parts = ArrayList<CharSequence>(4)
		if (state.totalChapters > 0) parts += context.getString(R.string.download_chapter_progress, (state.currentChapter + 1).coerceIn(1, state.totalChapters), state.totalChapters)
		if (state.totalPages > 0) parts += context.getString(R.string.download_page_progress, (state.currentPage + 1).coerceIn(1, state.totalPages), state.totalPages)
		if (state.percent >= 0f) parts += context.getString(R.string.percent_string_pattern, (state.percent * 100).format())
		val etaString = when {
			state.eta <= 0L -> null
			state.isStuck -> context.getString(R.string.stuck)
			else -> DateUtils.getRelativeTimeSpanString(state.eta, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS)
		}
		if (etaString != null) parts += etaString
		return parts.takeIf { it.isNotEmpty() }?.joinToString(" • ")
	}

	private fun createMangaIntent(context: Context, manga: Manga?) = PendingIntentCompat.getActivity(
		context,
		manga.hashCode(),
		if (manga != null) AppRouter.detailsIntent(context, manga) else Intent(context, DownloadsActivity::class.java),
		PendingIntent.FLAG_UPDATE_CURRENT,
		false,
	)

	private suspend fun getCover(manga: Manga): Drawable? {
		covers[manga]?.let { return it }
		return runCatchingCancellable {
			val request = ImageRequest.Builder(context)
				.data(manga.coverUrl)
				.mangaSourceExtra(manga.source)
				.size(context.getNotificationIconSize())
				.scale(Scale.FILL)
				.allowHardware(false)
				.build()
			coil.execute(request).image?.toBitmap()?.let { android.graphics.drawable.BitmapDrawable(context.resources, it) }
				.also { if (it != null) covers[manga] = it }
		}.onFailure { it.printStackTraceDebug() }.getOrNull()
	}

	private fun createChannels() {
		NotificationManagerCompat.from(context).apply {
			createNotificationChannel(NotificationChannelCompat.Builder(CHANNEL_ID_DEFAULT, NotificationManagerCompat.IMPORTANCE_LOW).setName(context.getString(R.string.manga_downloading_)).setSound(null, null).setVibrationEnabled(false).setLightsEnabled(false).build())
			createNotificationChannel(NotificationChannelCompat.Builder(CHANNEL_ID_SILENT, NotificationManagerCompat.IMPORTANCE_MIN).setName(context.getString(R.string.background_downloads)).setSound(null, null).setVibrationEnabled(false).setLightsEnabled(false).build())
		}
	}

	@AssistedFactory
	interface Factory {
		fun create(uuid: UUID, isSilent: Boolean): DownloadNotificationFactory
	}
}
