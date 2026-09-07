package org.koitharu.kotatsu.main.ui.protect

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.annotation.MainThread
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ScreenshotsPolicy
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.ui.DefaultActivityLifecycleCallbacks
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.favourites.private.PrivateFavouritesSession
import org.koitharu.kotatsu.favourites.ui.FavouritesActivity
import java.util.WeakHashMap
import javax.inject.Inject

class ScreenshotPolicyHelper @Inject constructor(
	private val settings: AppSettings,
	private val protectHelper: AppProtectHelper,
	private val favouritesRepository: FavouritesRepository,
	private val privateSession: PrivateFavouritesSession,
) : DefaultActivityLifecycleCallbacks {

	/** Latest privacy classification for already-created screens; weak keys avoid retaining activities. */
	private val privateContentState = WeakHashMap<Activity, Boolean>()

	override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
		(activity as? ContentContainer)?.setupScreenshotPolicy(activity)
	}

	override fun onActivityResumed(activity: Activity) {
		// FavouritesActivity already has an in-place re-auth flow that preserves its private search/tab
		// state. Child manga screens instead close back to the vault when the process was backgrounded.
		if (activity is FavouritesActivity) return
		val owner = activity as? LifecycleOwner ?: return
		activity.window.addFlagsIf(privateContentState[activity] == true)
		owner.lifecycleScope.launch(Dispatchers.Main.immediate) {
			val isPrivate = privateContentState[activity] ?: resolvePrivateContent(activity)
			privateContentState[activity] = isPrivate
			if (
				isPrivate &&
				!privateSession.isUnlocked.value &&
				owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
				!activity.isFinishing
			) {
				// Never leave a Private-only Details/Reader screen usable after the vault session locks.
				// Finishing first also means cancelling authentication cannot reveal the old screen behind it.
				activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
				activity.finish()
				activity.startActivity(
					Intent(activity, ProtectActivity::class.java)
						.putExtra(ProtectActivity.EXTRA_PRIVATE_FAVOURITES, true),
				)
			}
		}
	}

	private fun ContentContainer.setupScreenshotPolicy(activity: Activity) =
		lifecycleScope.launch(Dispatchers.Main.immediate) {
			val screenshotPolicyFlow = settings.observeAsFlow(AppSettings.KEY_SCREENSHOTS_POLICY) { screenshotsPolicy }
				.flatMapLatest { policy ->
					when (policy) {
						ScreenshotsPolicy.ALLOW -> flowOf(false)
						ScreenshotsPolicy.BLOCK_NSFW -> isNsfwContent().distinctUntilChanged()
						ScreenshotsPolicy.BLOCK_ALL -> flowOf(true)
						ScreenshotsPolicy.BLOCK_INCOGNITO -> settings.observeAsFlow(AppSettings.KEY_INCOGNITO_MODE) {
							isIncognitoModeEnabled
						}
					}
				}

			val protectAppFlow = settings.observeAsFlow(AppSettings.KEY_PROTECT_APP) { isAppProtectionEnabled }
			val privateContentFlow = observePrivateContent(activity)

			combine(
				screenshotPolicyFlow,
				protectAppFlow,
				protectHelper.isUnlockedFlow,
				privateContentFlow,
			) { screenshotSecure, protectEnabled, isUnlocked, privateContent ->
				privateContentState[activity] = privateContent
				screenshotSecure || privateContent || (protectEnabled && !isUnlocked)
			}.collect { isSecure ->
				if (isSecure) {
					activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
				} else {
					activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
				}
			}
		}

	/**
	 * Private Favourites itself is explicit in the intent. Manga child screens are classified by the
	 * stable `kotatsu:manga?id=…` URI used by Details and Reader, then re-evaluated whenever either
	 * membership table changes so moving a title to/from Private takes effect immediately.
	 */
	private fun observePrivateContent(activity: Activity): Flow<Boolean> {
		if (explicitPrivateSpace(activity)) return flowOf(true)
		val mangaId = mangaId(activity) ?: return flowOf(false)
		return merge(
			favouritesRepository.observeFavouritesChanges(FavouriteSpace.NORMAL),
			favouritesRepository.observeFavouritesChanges(FavouriteSpace.PRIVATE),
		).mapLatest {
			isPrivateOnly(mangaId)
		}.distinctUntilChanged()
	}

	private suspend fun resolvePrivateContent(activity: Activity): Boolean {
		if (explicitPrivateSpace(activity)) return true
		return mangaId(activity)?.let { isPrivateOnly(it) } == true
	}

	private suspend fun isPrivateOnly(mangaId: Long): Boolean {
		val isPrivate = favouritesRepository.isFavorite(mangaId, FavouriteSpace.PRIVATE)
		if (!isPrivate) return false
		return !favouritesRepository.isFavorite(mangaId, FavouriteSpace.NORMAL)
	}

	private fun explicitPrivateSpace(activity: Activity): Boolean =
		activity.intent?.getIntExtra(EXTRA_FAVOURITE_SPACE, FavouriteSpace.NORMAL.dbValue) ==
			FavouriteSpace.PRIVATE.dbValue

	private fun mangaId(activity: Activity): Long? = activity.intent?.data
		?.takeIf { it.scheme == "kotatsu" && it.path == "manga" }
		?.getQueryParameter("id")
		?.toLongOrNull()

	private fun android.view.Window.addFlagsIf(value: Boolean) {
		if (value) addFlags(WindowManager.LayoutParams.FLAG_SECURE)
	}

	interface ContentContainer : LifecycleOwner {

		@MainThread
		fun isNsfwContent(): Flow<Boolean>
	}
}
