package org.koitharu.kotatsu.local.ui

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.ui.CoroutineIntentService
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.getParcelableExtraCompat
import org.koitharu.kotatsu.core.util.ext.powerManager
import org.koitharu.kotatsu.core.util.ext.withPartialWakeLock
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.local.data.ExhentaiDownloadedCopy
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.local.domain.DeleteLocalMangaUseCase
import org.koitharu.kotatsu.local.domain.DownloadedMangaResolver
import org.koitharu.kotatsu.local.domain.NormalDeleteApproval
import org.koitharu.kotatsu.local.domain.NormalDownloadDeleteAction
import org.koitharu.kotatsu.sources.compat.EhentaiSourceFamily
import org.koitharu.kotatsu.parsers.model.Manga
import javax.inject.Inject

@AndroidEntryPoint
class LocalChaptersRemoveService : CoroutineIntentService() {

	@Inject
	lateinit var localMangaRepository: LocalMangaRepository

	@Inject
	lateinit var mangaDataRepository: MangaDataRepository

	@Inject
	lateinit var downloadedMangaResolver: DownloadedMangaResolver

	@Inject
	lateinit var deleteLocalManga: DeleteLocalMangaUseCase

	@Inject
	lateinit var normalDelete: NormalDownloadDeleteAction

	override fun onCreate() {
		super.onCreate()
		isRunning = true
	}

	override fun onDestroy() {
		isRunning = false
		super.onDestroy()
	}

	override suspend fun IntentJobContext.processIntent(intent: Intent) {
		startForeground(this)
		val manga = intent.getParcelableExtraCompat<ParcelableManga>(EXTRA_MANGA)?.manga ?: return
		val chaptersIds = intent.getLongArrayExtra(EXTRA_CHAPTERS_IDS)?.toSet() ?: return
		val favouriteSpace = FavouriteSpace.fromArgument(
			intent.getIntExtra(EXTRA_FAVOURITE_SPACE, FavouriteSpace.NORMAL.dbValue),
		)
		val provenance = intent.getIntExtra(EXTRA_PROVENANCE, favouriteSpace.dbValue)
			.takeIf { it >= 0 }?.let { FavouriteSpace.fromArgument(it) }
		@Suppress("DEPRECATION")
		val approved = intent.getSerializableExtra(EXTRA_NORMAL_APPROVAL) as? NormalDeleteApproval
		powerManager.withPartialWakeLock(TAG) {
			if (intent.hasExtra(EXTRA_NORMAL_APPROVAL)) {
				val selection = checkNotNull(approved) { "Selected download approval is missing" }
				val remote = checkNotNull(mangaDataRepository.findMangaById(manga.id, withChapters = true))
				check(selection.selected?.keys == chaptersIds && selection.space == provenance)
				check(normalDelete.execute(remote, provenance, selection))
				return@withPartialWakeLock
			}
			val mangaWithChapters = if (manga.isLocal) {
				localMangaRepository.getDetails(manga)
			} else {
				// ParcelableManga deliberately omits chapters. Reload the canonical remote snapshot,
				// then resolve the physical copy inside the space that launched the action. Without
				// this boundary a Private delete could hit a Normal copy from the global Local index.
				val remote = mangaDataRepository.findMangaById(manga.id, withChapters = true) ?: manga
				check(!normalDelete.appliesTo(remote, provenance)) { "Selected Normal chapter requires exact confirmation" }
				if (ExhentaiDownloadedCopy.isOfficial(remote)) {
					check(deleteLocalManga.deleteExhentaiCopy(remote, favouriteSpace, chapterIds = chaptersIds)) {
						"Downloaded ExHentai gallery is unavailable"
					}
					return@withPartialWakeLock
				}
				val saved = checkNotNull(downloadedMangaResolver.findSavedManga(remote, favouriteSpace)) {
					"Downloaded manga not found in ${favouriteSpace.name.lowercase()} storage"
				}
				if (EhentaiSourceFamily.supportsTitleMatchedDownloads(remote.source.name)) {
					deleteLocalManga.requireSourceOwnedExhentaiDeletion(remote, favouriteSpace, saved.file)
				}
				saved.manga
			}
			localMangaRepository.deleteChapters(mangaWithChapters, chaptersIds)
		}
	}

	override fun IntentJobContext.onError(error: Throwable) {
		val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
			.setContentTitle(getString(R.string.error_occurred))
			.setPriority(NotificationCompat.PRIORITY_DEFAULT)
			.setDefaults(0)
			.setSilent(true)
			.setContentText(error.getDisplayMessage(resources))
			.setSmallIcon(R.drawable.general_notification)
			.setAutoCancel(true)
			.build()
		val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
		nm.notify(NOTIFICATION_ID + startId, notification)
	}

	@SuppressLint("InlinedApi")
	private fun startForeground(jobContext: IntentJobContext) {
		val title = getString(R.string.local_manga_processing)
		val manager = NotificationManagerCompat.from(this)
		val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
			.setName(title)
			.setShowBadge(false)
			.setVibrationEnabled(false)
			.setSound(null, null)
			.setLightsEnabled(false)
			.build()
		manager.createNotificationChannel(channel)

		val notification = NotificationCompat.Builder(this, CHANNEL_ID)
			.setContentTitle(title)
			.setPriority(NotificationCompat.PRIORITY_MIN)
			.setDefaults(0)
			.setSilent(true)
			.setProgress(0, 0, true)
			.setSmallIcon(R.drawable.general_notification)
			.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
			.setOngoing(false)
			.build()
		jobContext.setForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
	}

	companion object {

		var isRunning: Boolean = false
			private set

		private const val CHANNEL_ID = "local_processing"
		private const val NOTIFICATION_ID = 21

		private const val EXTRA_MANGA = "manga"
		private const val EXTRA_CHAPTERS_IDS = "chapters_ids"
		private const val EXTRA_FAVOURITE_SPACE = "favourite_space"
		private const val EXTRA_PROVENANCE = "normal_provenance"
		private const val EXTRA_NORMAL_APPROVAL = "normal_delete_approval"

		private const val TAG = CHANNEL_ID

		fun start(
			context: Context,
			manga: Manga,
			chaptersIds: Collection<Long>,
			favouriteSpace: FavouriteSpace,
			provenance: FavouriteSpace? = favouriteSpace,
		) = startInternal(context, manga, chaptersIds, favouriteSpace, provenance, null)

		internal fun startVerifiedNormal(
			context: Context, manga: Manga, chaptersIds: Collection<Long>, favouriteSpace: FavouriteSpace,
			approval: NormalDeleteApproval,
		) = startInternal(context, manga, chaptersIds, favouriteSpace, approval.space, approval)

		private fun startInternal(
			context: Context, manga: Manga, chaptersIds: Collection<Long>, favouriteSpace: FavouriteSpace,
			provenance: FavouriteSpace?, approval: NormalDeleteApproval?,
		) {
			if (chaptersIds.isEmpty()) {
				return
			}
			val intent = Intent(context, LocalChaptersRemoveService::class.java)
			intent.putExtra(EXTRA_MANGA, ParcelableManga(manga))
			intent.putExtra(EXTRA_CHAPTERS_IDS, chaptersIds.toLongArray())
			intent.putExtra(EXTRA_FAVOURITE_SPACE, favouriteSpace.dbValue)
			intent.putExtra(EXTRA_PROVENANCE, provenance?.dbValue ?: -1)
			if (approval != null) intent.putExtra(EXTRA_NORMAL_APPROVAL, approval)
			ContextCompat.startForegroundService(context, intent)
		}
	}
}
