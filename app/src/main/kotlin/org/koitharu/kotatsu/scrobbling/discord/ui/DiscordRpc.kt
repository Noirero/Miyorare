package org.koitharu.kotatsu.scrobbling.discord.ui

import android.content.Context
import android.os.SystemClock
import androidx.annotation.AnyThread
import androidx.collection.ArrayMap
import com.my.kizzyrpc.KizzyRPC
import com.my.kizzyrpc.entities.presence.Activity
import com.my.kizzyrpc.entities.presence.Assets
import com.my.kizzyrpc.entities.presence.Metadata
import com.my.kizzyrpc.entities.presence.Timestamps
import dagger.hilt.android.ViewModelLifecycle
import dagger.hilt.android.lifecycle.RetainedLifecycle
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import okio.utf8Size
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.LocalizedAppContext
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.lifecycleScope
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.reader.ui.pager.ReaderUiState
import org.koitharu.kotatsu.scrobbling.discord.data.DiscordRepository
import java.util.Collections
import javax.inject.Inject

private const val STATUS_ONLINE = "online"
private const val STATUS_IDLE = "idle"
private const val BUTTON_TEXT_LIMIT = 32
private const val DEBOUNCE_TIMEOUT = 16_000L

@ViewModelScoped
class DiscordRpc @Inject constructor(
	@LocalizedAppContext private val context: Context,
	private val settings: AppSettings,
	private val database: MangaDatabase,
	private val repository: DiscordRepository,
	private val favouritesRepository: FavouritesRepository,
	lifecycle: ViewModelLifecycle,
) : RetainedLifecycle.OnClearedListener {

	private val coroutineScope = lifecycle.lifecycleScope + Dispatchers.Default
	private val appId = context.getString(R.string.discord_app_id)
	private val appName = context.getString(R.string.app_name)
	private val appIcon = context.getString(R.string.url_miyorare_rpc_icon)
	private val mpCache = Collections.synchronizedMap(ArrayMap<String, String>())
	private var lastUpdate = 0L

	private var rpc: KizzyRPC? = null
	private var rpcRequestJob: Job? = null
	private var rpcUpdateJob: Job? = null

	@Volatile private var lastActivity: Activity? = null
	@Volatile private var lastMangaId: Long? = null

	init {
		lifecycle.addOnClearedListener(this)
		coroutineScope.launch {
			merge(
				favouritesRepository.observeFavouritesChanges(FavouriteSpace.NORMAL),
				favouritesRepository.observeFavouritesChanges(FavouriteSpace.PRIVATE),
			).collect {
				val mangaId = lastMangaId ?: return@collect
				if (isPrivateOnly(mangaId)) clearRpc()
			}
		}
	}

	override fun onCleared() {
		clearRpc()
	}

	fun clearRpc() = synchronized(this) {
		rpcUpdateJob?.cancel()
		rpcUpdateJob = null
		rpc?.closeRPC()
		rpc = null
		lastActivity = null
		lastUpdate = 0L
	}

	fun setIdle() {
		val activity = lastActivity ?: return
		val mangaId = lastMangaId ?: return
		launchRpcRequest {
			if (isPrivateOnly(mangaId)) {
				clearRpc()
				return@launchRpcRequest
			}
			getRpc()?.updateRpcAsync(activity, idle = true)
		}
	}

	@AnyThread
	fun updateRpc(manga: Manga, state: ReaderUiState, coverUrl: String?) {
		val previousMangaId = lastMangaId
		lastMangaId = manga.id
		if (previousMangaId != null && previousMangaId != manga.id) clearRpc()
		launchRpcRequest {
			if (isPrivateOnly(manga.id)) {
				clearRpc()
				return@launchRpcRequest
			}
			if (settings.isDiscordRpcSkipNsfw && manga.isNsfw()) {
				clearRpc()
				return@launchRpcRequest
			}
			getRpc()?.updateRpcAsync(
				activity = Activity(
					applicationId = appId,
					name = appName,
					details = manga.title,
					state = context.getString(R.string.chapter_d_of_d, state.chapterNumber, state.chaptersTotal),
					type = 3,
					timestamps = Timestamps(
						start = lastActivity?.timestamps?.start ?: System.currentTimeMillis(),
					),
					assets = Assets(
						largeImage = coverUrl,
						largeText = context.getString(R.string.reading_s, manga.title),
						smallText = context.getString(R.string.discord_rpc_description),
						smallImage = appIcon,
					),
					buttons = listOf(context.getString(R.string.read_on_s, manga.source.getTitle(context))),
					metadata = Metadata(listOf(manga.publicUrl)),
				),
				idle = false,
			)
		}
	}

	private fun launchRpcRequest(block: suspend () -> Unit) = synchronized(this) {
		val previous = rpcRequestJob
		rpcRequestJob = coroutineScope.launch {
			previous?.cancelAndJoin()
			block()
		}
	}

	/** Atomic SQL classification plus fail-closed DB errors at the Discord disclosure boundary. */
	private suspend fun isPrivateOnly(mangaId: Long): Boolean = runCatchingCancellable {
		database.getPrivateFavouritesDao().isPrivateOnly(mangaId)
	}.getOrDefault(true)

	private fun KizzyRPC.updateRpcAsync(activity: Activity, idle: Boolean) {
		val prevJob = rpcUpdateJob
		rpcUpdateJob = coroutineScope.launch {
			prevJob?.cancelAndJoin()
			val debounceTime = lastUpdate + DEBOUNCE_TIMEOUT - SystemClock.elapsedRealtime()
			if (debounceTime > 0) delay(debounceTime)
			val mangaId = lastMangaId
			if (mangaId == null || isPrivateOnly(mangaId)) {
				clearRpc()
				return@launch
			}
			val hideButtons = activity.buttons?.any { it != null && it.utf8Size() > BUTTON_TEXT_LIMIT } ?: false
			val mappedActivity = activity.copy(
				assets = activity.assets?.let {
					it.copy(
						largeImage = it.largeImage?.toMediaProxyUrl(),
						smallImage = it.smallImage?.toMediaProxyUrl(),
					)
				},
				buttons = activity.buttons.takeUnless { hideButtons },
				metadata = activity.metadata.takeUnless { hideButtons },
			)
			// Media-proxy conversion above can suspend too. One last classification immediately before
			// updateRPC prevents a membership change during proxy I/O from publishing stale metadata.
			if (isPrivateOnly(mangaId)) {
				clearRpc()
				return@launch
			}
			lastActivity = mappedActivity
			updateRPC(
				activity = mappedActivity,
				status = if (idle) STATUS_IDLE else STATUS_ONLINE,
				since = activity.timestamps?.start ?: System.currentTimeMillis(),
			)
			lastUpdate = SystemClock.elapsedRealtime()
		}
	}

	suspend fun String.toMediaProxyUrl(): String? {
		if (repository.isMediaProxyUrl(this)) return this
		mpCache[this]?.let { return it }
		return runCatchingCancellable {
			repository.getMediaProxyUrl(this)
		}.onSuccess { url ->
			mpCache[this] = url
		}.onFailure {
			it.printStackTraceDebug()
		}.getOrNull()
	}

	private fun getRpc(): KizzyRPC? {
		if (!settings.isDiscordRpcEnabled) {
			clearRpc()
			return null
		}
		rpc?.let { return it }
		return synchronized(this) {
			rpc?.let { return@synchronized it }
			settings.discordToken?.let { KizzyRPC(it) }.also { rpc = it }
		}
	}
}
