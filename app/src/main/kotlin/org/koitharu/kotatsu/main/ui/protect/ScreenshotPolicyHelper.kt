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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.core.db.MangaDatabase
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
import org.koitharu.kotatsu.favourites.vault.PrivateFavouritesSession
import org.koitharu.kotatsu.favourites.ui.FavouritesActivity
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.util.WeakHashMap
import javax.inject.Inject

class ScreenshotPolicyHelper @Inject constructor(
	private val settings: AppSettings,
	private val protectHelper: AppProtectHelper,
	private val database: MangaDatabase,
	private val favouritesRepository: FavouritesRepository,
	private val privateSession: PrivateFavouritesSession,
) : DefaultActivityLifecycleCallbacks {

	private val privateContentState = WeakHashMap<Activity, Boolean>()

	override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
		val container = activity as? ContentContainer ?: return
		if (explicitPrivateSpace(activity) || mangaId(activity) != null) {
			activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
		}
		container.setupScreenshotPolicy(activity)
	}

	override fun onActivityResumed(activity: Activity) {
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
			val privateMembershipState = observePrivateContent(activity)
				.stateIn(this, SharingStarted.Eagerly, PrivateMembershipState.UNKNOWN)
			val privateVaultFlow = combine(
				privateMembershipState,
				isPrivateVaultContent().distinctUntilChanged(),
			) { fromIntentOrMembership, fromScreen ->
				fromIntentOrMembership == PrivateMembershipState.PRIVATE || fromScreen
			}.distinctUntilChanged()
			val sensitiveScreenFlow = combine(
				isPrivacySensitiveContent().distinctUntilChanged(),
				privateMembershipState,
			) { fromScreen, membershipState ->
				fromScreen || membershipState == PrivateMembershipState.UNKNOWN
			}.distinctUntilChanged()

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
		activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
		activity.finish()
		activity.startActivity(
			Intent(activity, ProtectActivity::class.java)
				.putExtra(ProtectActivity.EXTRA_PRIVATE_FAVOURITES, true),
		)
	}

	private fun observePrivateContent(activity: Activity): Flow<PrivateMembershipState> {
		if (explicitPrivateSpace(activity)) return flowOf(PrivateMembershipState.PRIVATE)
		val mangaId = mangaId(activity) ?: return flowOf(PrivateMembershipState.NORMAL)
		return merge(
			favouritesRepository.observeFavouritesChanges(FavouriteSpace.NORMAL),
			favouritesRepository.observeFavouritesChanges(FavouriteSpace.PRIVATE),
		).transformLatest {
			emit(PrivateMembershipState.UNKNOWN)
			val privateOnly = runCatchingCancellable { isPrivateOnly(mangaId) }.getOrDefault(true)
			emit(if (privateOnly) PrivateMembershipState.PRIVATE else PrivateMembershipState.NORMAL)
		}.distinctUntilChanged()
	}

	private suspend fun resolvePrivateContent(activity: Activity): Boolean {
		if (explicitPrivateSpace(activity)) return true
		val mangaId = mangaId(activity) ?: return false
		return runCatchingCancellable { isPrivateOnly(mangaId) }.getOrDefault(true)
	}

	/** One Room query classifies dual membership from the same database snapshot. */
	private suspend fun isPrivateOnly(mangaId: Long): Boolean =
		database.getPrivateFavouritesDao().isPrivateOnly(mangaId)

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

		fun isPrivacySensitiveContent(): Flow<Boolean> = flowOf(false)

		fun isPrivateVaultContent(): Flow<Boolean> = flowOf(false)
	}

	private enum class PrivateMembershipState {
		UNKNOWN,
		NORMAL,
		PRIVATE,
	}

	private data class SecurityState(
		val isSecure: Boolean,
		val isPrivateVault: Boolean,
	)
}
