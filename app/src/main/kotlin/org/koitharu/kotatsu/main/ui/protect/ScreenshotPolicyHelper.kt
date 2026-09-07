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
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ScreenshotsPolicy
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.ui.DefaultActivityLifecycleCallbacks
import org.koitharu.kotatsu.core.util.ext.getParcelableExtraCompat
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

	/** Actual Private-vault classification only; weak keys avoid retaining activities. */
	private val privateContentState = WeakHashMap<Activity, Boolean>()

	override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
		val container = activity as? ContentContainer ?: return
		// Details/Reader/Image all carry a stable manga identity in the normal in-app path. Start those
		// windows protected until the first database/content classification arrives so a task-preview or
		// screenshot cannot race it. Details opened from an external URL also protects its own first frame.
		if (explicitPrivateSpace(activity) || mangaId(activity) != null) {
			activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
		}
		container.setupScreenshotPolicy(activity)
	}

	override fun onActivityResumed(activity: Activity) {
		// FavouritesActivity has its own in-place re-auth flow that preserves private search/tab state.
		if (activity is FavouritesActivity) return
		val owner = activity as? LifecycleOwner ?: return
		activity.window.addFlagsIf(privateContentState[activity] == true)
		owner.lifecycleScope.launch(Dispatchers.Main.immediate) {
			val isPrivate = privateContentState[activity] ?: resolvePrivateContent(activity)
			privateContentState[activity] = isPrivate
			enforcePrivateSession(activity, owner, isPrivate)
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

			// Keep "must be secure" separate from "belongs to the Private vault". Details deliberately
			// reports loading/unknown as privacy-sensitive so the first frame stays FLAG_SECURE, but that
			// temporary state must never be allowed to trigger a PIN for an ordinary external deep link.
			val privateVaultFlow = combine(
				observePrivateContent(activity),
				isPrivateVaultContent().distinctUntilChanged(),
			) { fromIntentOrMembership, fromScreen ->
				fromIntentOrMembership || fromScreen
			}.distinctUntilChanged()
			val sensitiveScreenFlow = isPrivacySensitiveContent().distinctUntilChanged()

			combine(
				screenshotPolicyFlow,
				protectAppFlow,
				protectHelper.isUnlockedFlow,
				privateVaultFlow,
				sensitiveScreenFlow,
			) { screenshotSecure, protectEnabled, isUnlocked, privateVault, sensitiveScreen ->
				SecurityState(
					isSecure = screenshotSecure || privateVault || sensitiveScreen || (protectEnabled && !isUnlocked),
					isPrivateVault = privateVault,
				)
			}.collect { state ->
				privateContentState[activity] = state.isPrivateVault
				if (state.isSecure) {
					activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
				} else {
					activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
				}
				// This collector also handles a deep link that resolves to a Private manga while the activity
				// is already RESUMED; waiting for another onResume would leave a secure-but-usable vault screen.
				enforcePrivateSession(activity, this@setupScreenshotPolicy, state.isPrivateVault)
			}
		}

	private fun enforcePrivateSession(activity: Activity, owner: LifecycleOwner, isPrivate: Boolean) {
		if (
			!isPrivate ||
			activity is FavouritesActivity ||
			privateSession.isUnlocked.value ||
			!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
			activity.isFinishing
		) {
			return
		}
		// Never leave a Private-only Details/Reader/Image screen usable after the vault session locks.
		// Finishing first also means cancelling authentication cannot reveal the old screen behind it.
		activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
		activity.finish()
		activity.startActivity(
			Intent(activity, ProtectActivity::class.java)
				.putExtra(ProtectActivity.EXTRA_PRIVATE_FAVOURITES, true),
		)
	}

	/**
	 * Private Favourites itself is explicit in the intent. Manga child screens are classified by the
	 * stable `kotatsu:/manga?id=…` URI used by Details/Reader, or by their ParcelableManga extra
	 * (Image/other child surfaces), then re-evaluated whenever either membership table changes.
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

	private fun mangaId(activity: Activity): Long? {
		val intent = activity.intent ?: return null
		val uriId = intent.data
			?.takeIf { uri -> uri.scheme == "kotatsu" && uri.path?.trim('/') == "manga" }
			?.getQueryParameter("id")
			?.toLongOrNull()
		if (uriId != null) return uriId
		return intent.getParcelableExtraCompat<ParcelableManga>(AppRouter.KEY_MANGA)?.manga?.id
	}

	private fun android.view.Window.addFlagsIf(value: Boolean) {
		if (value) addFlags(WindowManager.LayoutParams.FLAG_SECURE)
	}

	interface ContentContainer : LifecycleOwner {

		@MainThread
		fun isNsfwContent(): Flow<Boolean>

		/** Secure-only state, e.g. a Details screen that is still resolving an external URL. */
		fun isPrivacySensitiveContent(): Flow<Boolean> = flowOf(false)

		/** Actual Private-vault content. Only this state is allowed to trigger vault authentication. */
		fun isPrivateVaultContent(): Flow<Boolean> = flowOf(false)
	}

	private data class SecurityState(
		val isSecure: Boolean,
		val isPrivateVault: Boolean,
	)
}
