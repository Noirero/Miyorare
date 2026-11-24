package org.koitharu.kotatsu.scrobbling.discord.sdk

import android.app.Activity
import android.os.Handler
import android.os.Looper
import androidx.annotation.Keep
import androidx.annotation.MainThread
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.parsers.model.MangaSource
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

private const val BUTTON_LABEL_BYTE_LIMIT = 32

internal data class DiscordReading(
	val mangaId: Long,
	val title: String,
	val chapter: Int,
	val chapterCount: Int,
	val coverUrl: String?,
	val sourceUrl: String,
	val source: MangaSource,
	val nsfw: Boolean,
)

@Keep
internal object DiscordPresenceNative {
	external fun initialize(applicationId: Long)
	external fun publish(
		title: ByteArray,
		chapter: ByteArray,
		cover: ByteArray,
		coverDescription: ByteArray,
		icon: ByteArray,
		iconDescription: ByteArray,
		url: ByteArray,
		button: ByteArray?,
		startedAt: Long,
	)
	external fun pump()
	external fun clear()
}

/** A process-level SDK client, with publication owned by the most recently started Reader. */
@Singleton
class OfficialDiscordPresence @Inject constructor() {

	private val handler = Handler(Looper.getMainLooper())
	private var owner: Any? = null
	private var activity: WeakReference<Activity>? = null
	private var attached: WeakReference<Activity>? = null
	private var initialized = false
	private var currentManga: Long? = null
	private var startedAt = 0L
	private val callbacks = object : Runnable {
		override fun run() {
			DiscordPresenceNative.pump()
			handler.postDelayed(this, 100L)
		}
	}

	@MainThread
	fun open(owner: Any, activity: Activity) {
		this.owner?.let(::clear)
		this.owner = owner
		this.activity = WeakReference(activity)
	}

	@MainThread
	internal fun show(owner: Any, reading: DiscordReading, cover: String, source: String) {
		if (this.owner !== owner) return
		val reader = activity?.get() ?: return
		if (attached?.get() !== reader) {
			DiscordSdkActivity.attach(reader)
			attached = WeakReference(reader)
		}
		if (!initialized) {
			System.loadLibrary("miyorare_discord_sdk")
			DiscordPresenceNative.initialize(BuildConfig.DISCORD_PRESENCE_APP_ID)
			initialized = true
		}
		if (currentManga != reading.mangaId) {
			if (currentManga == null) handler.post(callbacks)
			currentManga = reading.mangaId
			startedAt = System.currentTimeMillis() / 1_000L
		}
		val button = reader.getString(R.string.read_on_s, reading.source.getTitle(reader)).encodeToByteArray()
		DiscordPresenceNative.publish(
			title = reading.title.discordTextBytes(),
			chapter = reader.getString(R.string.discord_chapter_d_of_d, reading.chapter, reading.chapterCount).encodeToByteArray(),
			cover = cover.encodeToByteArray(),
			coverDescription = reader.getString(R.string.reading_s, reading.title).discordTextBytes(),
			icon = reader.getString(R.string.url_miyorare_rpc_icon).encodeToByteArray(),
			iconDescription = reader.getString(R.string.discord_rpc_description).discordTextBytes(),
			url = source.encodeToByteArray(),
			button = button.takeIf { it.size <= BUTTON_LABEL_BYTE_LIMIT },
			startedAt = startedAt,
		)
	}

	@MainThread
	fun clear(owner: Any) {
		if (this.owner !== owner || currentManga == null) return
		currentManga = null
		handler.removeCallbacks(callbacks)
		DiscordPresenceNative.clear()
		DiscordPresenceNative.pump()
	}

	@MainThread
	fun close(owner: Any) {
		if (this.owner !== owner) return
		clear(owner)
		this.owner = null
		activity = null
	}
}

private fun String.discordTextBytes(): ByteArray {
	val bytes = encodeToByteArray()
	if (bytes.size <= 128) return bytes
	var limit = 128
	while ((bytes[limit].toInt() and 0xc0) == 0x80) limit--
	return bytes.copyOf(limit)
}
