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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.LocalizedAppContext
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
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
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
	private val favouritesRepository: FavouritesRepository,
	private val settings: AppSettings,
) : InvalidationTracker.Observer(TABLE_HISTORY, TABLE_FAVOURITES, TABLE_PRIVATE_FAVOURITES),
	SharedPreferences.OnSharedPreferenceChangeListener {

	private val iconSize by lazy {
		Size(ShortcutManagerCompat.getIconMaxWidth(context), ShortcutManagerCompat.getIconMaxHeight(context))
	}
	private var shortcutsUpdateJob: Job? = null

	init {
		settings.subscribe(this)
		// Upgrade safety: a shortcut pinned by an older build may already point at a manga that is now
		// Private-only. Sanitize it as soon as this singleton is initialized; no membership mutation is
		// required before the launcher is corrected.
		shortcutsUpdateJob = processLifecycleScope.launch(Dispatchers.Default) {
			sanitizePinnedMangaShortcuts()
		}
	}

	override fun onInvalidated(tables: Set<String>) {
		val membershipChanged = TABLE_FAVOURITES in tables || TABLE_PRIVATE_FAVOURITES in tables
		if (!membershipChanged && !settings.isDynamicShortcutsEnabled) return
		val prevJob = shortcutsUpdateJob
		shortcutsUpdateJob = processLifecycleScope.launch(Dispatchers.Default) {
			prevJob?.join()
			if (membershipChanged) {
				sanitizePinnedMangaShortcuts()
			}
			if (settings.isDynamicShortcutsEnabled) {
				updateShortcutsImpl()
			}
		}
	}

	override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
		if (key == AppSettings.KEY_SHORTCUTS) {
			if (settings.isDynamicShortcutsEnabled) onInvalidated(emptySet()) else clearShortcuts()
		}
	}

	/** Private-only titles must never escape onto the launcher through a newly pinned shortcut. */
	suspend fun requestPinShortcut(manga: Manga): Boolean {
		val isPrivate = favouritesRepository.isFavorite(manga.id, FavouriteSpace.PRIVATE)
		val isNormal = favouritesRepository.isFavorite(manga.id, FavouriteSpace.NORMAL)
		if (isPrivate && !isNormal) return false
		return try {
			ShortcutManagerCompat.requestPinShortcut(context, buildShortcutInfo(manga), null)
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

	fun notifyMangaOpened(mangaId: Long) {
		ShortcutManagerCompat.reportShortcutUsed(context, mangaId.toString())
	}

	fun isDynamicShortcutsAvailable(): Boolean {
		return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1 &&
			context.getSystemService(ShortcutManager::class.java).maxShortcutCountPerActivity > 0
	}

	private suspend fun updateShortcutsImpl() = runCatchingCancellable {
		val maxShortcuts = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceAtLeast(5)
		val shortcuts = historyRepository.getList(0, maxShortcuts)
			.filter { x -> x.title.isNotEmpty() }
			.map { buildShortcutInfo(it) }
		ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
	}.onFailure {
		it.printStackTraceDebug()
	}

	/**
	 * Android launchers keep pinned shortcuts even after the app removes the corresponding dynamic
	 * shortcut. Rewrite numeric manga pins in place: Private-only pins become an app-generic Home
	 * shortcut, while a title moved back to Normal gets its real metadata/Reader intent restored.
	 */
	private suspend fun sanitizePinnedMangaShortcuts() = runCatchingCancellable {
		val pinnedIds = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
			.mapNotNullToSet { shortcut -> shortcut.id.toLongOrNull() }
		if (pinnedIds.isEmpty()) return@runCatchingCancellable

		val updates = ArrayList<ShortcutInfoCompat>(pinnedIds.size)
		for (mangaId in pinnedIds) {
			val isPrivateOnly = runCatchingCancellable {
				val isPrivate = favouritesRepository.isFavorite(mangaId, FavouriteSpace.PRIVATE)
				isPrivate && !favouritesRepository.isFavorite(mangaId, FavouriteSpace.NORMAL)
			}.getOrDefault(true)
			if (isPrivateOnly) {
				updates += buildPrivatePlaceholderShortcut(mangaId)
				continue
			}
			mangaRepository.findMangaById(mangaId, withChapters = false)?.let { manga ->
				updates += buildShortcutInfo(manga)
			}
		}
		if (updates.isNotEmpty()) {
			ShortcutManagerCompat.updateShortcuts(context, updates)
		}
	}.onFailure {
		it.printStackTraceDebug()
	}

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
