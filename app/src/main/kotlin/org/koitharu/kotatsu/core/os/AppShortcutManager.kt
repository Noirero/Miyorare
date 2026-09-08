package org.koitharu.kotatsu.core.os

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ShortcutManager
import android.os.Build
import androidx.annotation.VisibleForTesting
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.room.InvalidationTracker
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.size.Scale
import coil3.size.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.LocalizedAppContext
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.TABLE_FAVOURITES
import org.koitharu.kotatsu.core.db.TABLE_HISTORY
import org.koitharu.kotatsu.core.db.TABLE_PRIVATE_FAVOURITES
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.ReaderIntent
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.favicon.faviconUri
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.image.ThumbnailTransformation
import org.koitharu.kotatsu.core.util.ext.getDrawableOrThrow
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.processLifecycleScope
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.ifNullOrEmpty
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppShortcutManager @Inject constructor(
	@LocalizedAppContext private val context: Context,
	private val coil: ImageLoader,
	private val historyRepository: HistoryRepository,
	private val mangaRepository: MangaDataRepository,
	private val database: MangaDatabase,
	private val settings: AppSettings,
) : InvalidationTracker.Observer(TABLE_HISTORY, TABLE_FAVOURITES, TABLE_PRIVATE_FAVOURITES),
	SharedPreferences.OnSharedPreferenceChangeListener {

	private val iconSize by lazy {
		Size(ShortcutManagerCompat.getIconMaxWidth(context), ShortcutManagerCompat.getIconMaxHeight(context))
	}
	private var shortcutsUpdateJob: Job? = null

	init {
		settings.subscribe(this)
		shortcutsUpdateJob = processLifecycleScope.launch(Dispatchers.Default) {
			sanitizePinnedMangaShortcuts()
		}
	}

	override fun onInvalidated(tables: Set<String>) {
		val membershipChanged = TABLE_FAVOURITES in tables || TABLE_PRIVATE_FAVOURITES in tables
		if (!membershipChanged && !settings.isDynamicShortcutsEnabled) return
		val prevJob = shortcutsUpdateJob
		shortcutsUpdateJob = processLifecycleScope.launch(Dispatchers.Default) {
			if (membershipChanged) prevJob?.cancelAndJoin() else prevJob?.join()
			if (membershipChanged) sanitizePinnedMangaShortcuts()
			if (settings.isDynamicShortcutsEnabled) updateShortcutsImpl()
		}
	}

	override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
		if (key == AppSettings.KEY_SHORTCUTS) {
			if (settings.isDynamicShortcutsEnabled) onInvalidated(emptySet()) else clearShortcuts()
		}
	}

	suspend fun requestPinShortcut(manga: Manga): Boolean {
		if (isPrivateOnly(manga.id)) return false
		val shortcut = buildShortcutInfo(manga)
		if (isPrivateOnly(manga.id)) return false
		return try {
			ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
		} catch (e: IllegalStateException) {
			e.printStackTraceDebug()
			false
		}
	}

	suspend fun requestPinShortcut(source: MangaSource): Boolean = try {
		ShortcutManagerCompat.requestPinShortcut(context, buildShortcutInfo(source), null)
	} catch (e: IllegalStateException) {
		e.printStackTraceDebug()
		false
	}

	fun getMangaShortcuts(): Set<Long> {
		val shortcuts = ShortcutManagerCompat.getShortcuts(
			context,
			ShortcutManagerCompat.FLAG_MATCH_CACHED or ShortcutManagerCompat.FLAG_MATCH_PINNED or ShortcutManagerCompat.FLAG_MATCH_DYNAMIC,
		)
		return shortcuts.mapNotNullToSet { it.id.toLongOrNull() }
	}

	@VisibleForTesting
	suspend fun await(): Boolean = shortcutsUpdateJob?.join() != null

	suspend fun notifyMangaOpened(mangaId: Long) {
		if (isPrivateOnly(mangaId)) return
		ShortcutManagerCompat.reportShortcutUsed(context, mangaId.toString())
	}

	fun isDynamicShortcutsAvailable(): Boolean {
		return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1 &&
			context.getSystemService(ShortcutManager::class.java).maxShortcutCountPerActivity > 0
	}

	private suspend fun updateShortcutsImpl() = runCatchingCancellable {
		val maxShortcuts = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceAtLeast(5)
		val mangas = historyRepository.getList(0, maxShortcuts).filter { it.title.isNotEmpty() }
		val candidates = ArrayList<Pair<Long, ShortcutInfoCompat>>(mangas.size)
		for (manga in mangas) {
			if (isPrivateOnly(manga.id)) continue
			val shortcut = buildShortcutInfo(manga)
			if (!isPrivateOnly(manga.id)) candidates += manga.id to shortcut
		}
		val shortcuts = ArrayList<ShortcutInfoCompat>(candidates.size)
		for ((mangaId, shortcut) in candidates) {
			if (!isPrivateOnly(mangaId)) shortcuts += shortcut
		}
		ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
	}.onFailure {
		it.printStackTraceDebug()
	}

	private suspend fun sanitizePinnedMangaShortcuts() = runCatchingCancellable {
		val pinnedIds = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
			.mapNotNullToSet { shortcut -> shortcut.id.toLongOrNull() }
		if (pinnedIds.isEmpty()) return@runCatchingCancellable

		val candidates = ArrayList<Pair<Long, ShortcutInfoCompat>>(pinnedIds.size)
		for (mangaId in pinnedIds) {
			if (isPrivateOnly(mangaId)) {
				candidates += mangaId to buildPrivatePlaceholderShortcut(mangaId)
				continue
			}
			val manga = mangaRepository.findMangaById(mangaId, withChapters = false) ?: continue
			if (isPrivateOnly(mangaId)) {
				candidates += mangaId to buildPrivatePlaceholderShortcut(mangaId)
				continue
			}
			val shortcut = buildShortcutInfo(manga)
			candidates += mangaId to if (isPrivateOnly(mangaId)) {
				buildPrivatePlaceholderShortcut(mangaId)
			} else {
				shortcut
			}
		}

		val updates = ArrayList<ShortcutInfoCompat>(candidates.size)
		for ((mangaId, shortcut) in candidates) {
			updates += if (isPrivateOnly(mangaId)) buildPrivatePlaceholderShortcut(mangaId) else shortcut
		}
		if (updates.isNotEmpty()) ShortcutManagerCompat.updateShortcuts(context, updates)
	}.onFailure {
		it.printStackTraceDebug()
	}

	/** Atomic SQL classification; DB failure must hide rather than publish launcher metadata. */
	private suspend fun isPrivateOnly(mangaId: Long): Boolean = runCatchingCancellable {
		database.getPrivateFavouritesDao().isPrivateOnly(mangaId)
	}.getOrDefault(true)

	private fun clearShortcuts() {
		try {
			ShortcutManagerCompat.removeAllDynamicShortcuts(context)
		} catch (_: IllegalStateException) {
		}
	}

	private fun buildPrivatePlaceholderShortcut(mangaId: Long): ShortcutInfoCompat {
		val appName = context.getString(R.string.app_name)
		return ShortcutInfoCompat.Builder(context, mangaId.toString())
			.setShortLabel(appName)
			.setLongLabel(appName)
			.setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_default))
			.setLongLived(true)
			.setIntent(AppRouter.homeIntent(context))
			.build()
	}

	private suspend fun buildShortcutInfo(manga: Manga): ShortcutInfoCompat = withContext(Dispatchers.Default) {
		val icon = runCatchingCancellable {
			coil.execute(
				ImageRequest.Builder(context)
					.data(manga.coverUrl)
					.size(iconSize)
					.mangaSourceExtra(manga.source)
					.scale(Scale.FILL)
					.transformations(ThumbnailTransformation())
					.build(),
			).getDrawableOrThrow().toBitmap()
		}.fold(
			onSuccess = { IconCompat.createWithAdaptiveBitmap(it) },
			onFailure = { IconCompat.createWithResource(context, R.drawable.ic_shortcut_default) },
		)
		mangaRepository.storeManga(manga, replaceExisting = true)
		val title = manga.title.ifEmpty {
			manga.altTitles.firstOrNull()
		}.ifNullOrEmpty {
			context.getString(R.string.unknown)
		}
		ShortcutInfoCompat.Builder(context, manga.id.toString())
			.setShortLabel(title)
			.setLongLabel(title)
			.setIcon(icon)
			.setLongLived(true)
			.setIntent(
				ReaderIntent.Builder(context)
					.mangaId(manga.id)
					.build()
					.intent,
			).build()
	}

	private suspend fun buildShortcutInfo(source: MangaSource): ShortcutInfoCompat = withContext(Dispatchers.Default) {
		val icon = runCatchingCancellable {
			coil.execute(
				ImageRequest.Builder(context)
					.data(source.faviconUri())
					.mangaSourceExtra(source)
					.size(iconSize)
					.scale(Scale.FIT)
					.build(),
			).getDrawableOrThrow().toBitmap()
		}.fold(
			onSuccess = { IconCompat.createWithAdaptiveBitmap(it) },
			onFailure = { IconCompat.createWithResource(context, R.drawable.ic_shortcut_default) },
		)
		val title = source.getTitle(context)
		ShortcutInfoCompat.Builder(context, source.name)
			.setShortLabel(title)
			.setLongLabel(title)
			.setIcon(icon)
			.setLongLived(true)
			.setIntent(AppRouter.listIntent(context, source, null, null))
			.build()
	}
}
