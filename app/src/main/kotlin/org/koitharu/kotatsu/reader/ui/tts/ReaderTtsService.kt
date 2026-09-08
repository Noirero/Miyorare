package org.koitharu.kotatsu.reader.ui.tts

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.view.WindowManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.LifecycleService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.util.ForegroundActivityHolder
import org.koitharu.kotatsu.main.ui.protect.ScreenshotPolicyHelper
import javax.inject.Inject

/**
 * Keeps [ReaderTts] alive and controllable while the reader is in the background. It owns nothing
 * but the notification — every button routes straight into the shared controller.
 */
@AndroidEntryPoint
class ReaderTtsService : LifecycleService() {

	@Inject
	lateinit var tts: ReaderTts

	@Inject
	lateinit var settings: AppSettings

	@Inject
	lateinit var foregroundActivityHolder: ForegroundActivityHolder

	@Inject
	lateinit var database: MangaDatabase

	@Inject
	lateinit var screenshotPolicyHelper: ScreenshotPolicyHelper

	private var title: String = ""
	private var hideSensitiveTitle: Boolean = false
	private var isPrivateOnly: Boolean = true
	private var observedMangaId: Long = Long.MIN_VALUE
	private var privacyJob: Job? = null

	override fun onCreate() {
		super.onCreate()
		createNotificationChannel(this)
		tts.isPlaying.onEach { isPlaying ->
			if (tts.isAttached) {
				notify(isPlaying)
			} else {
				stopSelf()
			}
		}.launchIn(lifecycleScope)
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		super.onStartCommand(intent, flags, startId)
		if (intent?.hasExtra(EXTRA_MANGA_ID) == true) {
			val mangaId = intent.getLongExtra(EXTRA_MANGA_ID, 0L)
			if (mangaId != observedMangaId || privacyJob == null) observePrivateMembership(mangaId)
		}
		intent?.getStringExtra(EXTRA_TITLE)?.let {
			title = it
			// Screenshot permission and notification disclosure are separate boundaries. Remember
			// secure/private state before the Reader backgrounds, while the Room observer below keeps
			// live NORMAL -> PRIVATE membership changes reflected in the media notification.
			hideSensitiveTitle = foregroundActivityHolder.current?.isSensitiveWindow() == true
		}
		when (intent?.action) {
			ACTION_TOGGLE -> tts.toggle()
			ACTION_NEXT -> tts.skip(1)
			ACTION_PREVIOUS -> tts.skip(-1)
			ACTION_STOP -> {
				// The only explicit "stop" the user has: retire the quick-start button with it.
				settings.isReaderTtsFabVisible = false
				tts.stop()
				stopSelf()
				return START_NOT_STICKY
			}
		}
		startForeground()
		return START_NOT_STICKY
	}

	override fun onTaskRemoved(rootIntent: Intent?) {
		// Swiping the app away means done reading, not "keep talking from nowhere".
		tts.stop()
		stopSelf()
		super.onTaskRemoved(rootIntent)
	}


	private fun observePrivateMembership(mangaId: Long) {
		observedMangaId = mangaId
		privacyJob?.cancel()
		// Fail closed until Room emits the atomic dual-membership classification.
		isPrivateOnly = true
		privacyJob = database.getPrivateFavouritesDao()
			.observePrivateOnly(mangaId)
			.distinctUntilChanged()
			.onEach { privateOnly ->
				isPrivateOnly = privateOnly
				if (tts.isAttached) notify(tts.isPlaying.value)
			}
			.launchIn(lifecycleScope)
	}

	private fun startForeground() {
		ServiceCompat.startForeground(
			this,
			NOTIFICATION_ID,
			buildNotification(tts.isPlaying.value),
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
			} else {
				0
			},
		)
	}

	private fun notify(isPlaying: Boolean) {
		try {
			NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(isPlaying))
		} catch (_: SecurityException) {
			// Notification permission may be revoked while the foreground service is alive.
		}
	}

	private fun buildNotification(isPlaying: Boolean): android.app.Notification {
		val currentSensitive = foregroundActivityHolder.current?.isSensitiveWindow() == true
		val sensitive = shouldHideTtsTitle(isPrivateOnly, hideSensitiveTitle, currentSensitive)
		val builder = NotificationCompat.Builder(this, CHANNEL_ID)
			.setSmallIcon(R.drawable.ic_voice_over)
			.setContentTitle(if (sensitive) getString(R.string.text_to_speech) else title.ifEmpty { getString(R.string.text_to_speech) })
			.setContentText(getString(if (isPlaying) R.string.tts_playing else R.string.tts_paused))
			.setOngoing(isPlaying)
			.setSilent(true)
			.setCategory(NotificationCompat.CATEGORY_TRANSPORT)
			.setVisibility(if (sensitive) NotificationCompat.VISIBILITY_SECRET else NotificationCompat.VISIBILITY_PUBLIC)
			.addAction(
				android.R.drawable.ic_media_previous,
				getString(R.string.tts_previous_sentence),
				actionIntent(ACTION_PREVIOUS),
			)
			.addAction(
				if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
				getString(if (isPlaying) R.string.pause else R.string.resume),
				actionIntent(ACTION_TOGGLE),
			)
			.addAction(
				android.R.drawable.ic_media_next,
				getString(R.string.tts_next_sentence),
				actionIntent(ACTION_NEXT),
			)
			.addAction(
				android.R.drawable.ic_menu_close_clear_cancel,
				getString(R.string.stop),
				actionIntent(ACTION_STOP),
			)
		packageManager.getLaunchIntentForPackage(packageName)?.let {
			builder.setContentIntent(PendingIntentCompat.getActivity(this, 0, it, 0, false))
		}
		return builder.build()
	}

	private fun android.app.Activity.isSensitiveWindow(): Boolean =
		window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0 ||
			screenshotPolicyHelper.isPrivateContent(this)

	private fun actionIntent(action: String) = PendingIntentCompat.getService(
		this,
		action.hashCode(),
		Intent(this, ReaderTtsService::class.java).setAction(action),
		0,
		false,
	)

	companion object {

		private const val CHANNEL_ID = "reader_tts"
		private const val NOTIFICATION_ID = 42
		private const val EXTRA_TITLE = "title"
		private const val EXTRA_MANGA_ID = "manga_id"
		private const val ACTION_TOGGLE = "toggle"
		private const val ACTION_NEXT = "next"
		private const val ACTION_PREVIOUS = "previous"
		private const val ACTION_STOP = "stop"

		fun start(context: Context, title: String, mangaId: Long) {
			val intent = Intent(context, ReaderTtsService::class.java)
				.putExtra(EXTRA_TITLE, title)
				.putExtra(EXTRA_MANGA_ID, mangaId)
			ContextCompat.startForegroundService(context, intent)
		}

		fun stop(context: Context) {
			context.stopService(Intent(context, ReaderTtsService::class.java))
		}

		private fun createNotificationChannel(context: Context) {
			val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
				.setName(context.getString(R.string.text_to_speech))
				.setShowBadge(false)
				.setVibrationEnabled(false)
				.setSound(null, null)
				.setLightsEnabled(false)
				.build()
			NotificationManagerCompat.from(context).createNotificationChannel(channel)
		}
	}
}

internal fun shouldHideTtsTitle(
	isPrivateOnly: Boolean,
	rememberedSensitive: Boolean,
	currentSensitive: Boolean,
): Boolean = isPrivateOnly || rememberedSensitive || currentSensitive
