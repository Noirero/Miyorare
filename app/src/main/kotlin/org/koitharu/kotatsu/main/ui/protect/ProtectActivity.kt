package org.koitharu.kotatsu.main.ui.protect

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.biometric.AuthenticationRequest
import androidx.biometric.AuthenticationRequest.Biometric
import androidx.biometric.AuthenticationResult
import androidx.biometric.AuthenticationResultCallback
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
import androidx.biometric.registerForAuthenticationResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowInsetsCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.databinding.ActivityProtectBinding
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.private.PrivateFavouritesProtection
import org.koitharu.kotatsu.favourites.private.PrivateFavouritesSecurityStore
import org.koitharu.kotatsu.favourites.private.PrivateFavouritesSession
import org.koitharu.kotatsu.favourites.ui.FavouritesActivity
import org.koitharu.kotatsu.settings.compose.DropSauceTheme

@AndroidEntryPoint
class ProtectActivity :
	BaseActivity<ActivityProtectBinding>(),
	AuthenticationResultCallback {

	@Inject lateinit var protectHelper: AppProtectHelper
	@Inject lateinit var settings: AppSettings
	@Inject lateinit var privateSecurity: PrivateFavouritesSecurityStore
	@Inject lateinit var privateSession: PrivateFavouritesSession

	private val biometricPrompt = registerForAuthenticationResult(resultCallback = this)
	private var isAutoPromptPending = true
	private var isPromptShowing = false
	private var forcePrivatePin by mutableStateOf(false)

	private val isPrivateMode: Boolean
		get() = intent.getBooleanExtra(EXTRA_PRIVATE_FAVOURITES, false)

	private val openPrivateOnSuccess: Boolean
		get() = intent.getBooleanExtra(EXTRA_OPEN_PRIVATE_ON_SUCCESS, true)

	private val privateProtection: PrivateFavouritesProtection
		get() = privateSecurity.protection

	private val isPinMode: Boolean
		get() = if (isPrivateMode) {
			privateProtection == PrivateFavouritesProtection.PIN || forcePrivatePin
		} else {
			settings.isAppPasswordSet
		}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
		if (isPrivateMode && (privateSession.isUnlocked.value || privateProtection == PrivateFavouritesProtection.NONE)) {
			privateSession.unlock()
			finishPrivateUnlock()
			return
		}
		setContentView(ActivityProtectBinding.inflate(layoutInflater))
		viewBinding.composeView.setContent {
			DropSauceTheme {
				ProtectScreen(
					isPinMode = isPinMode,
					onVerifyPin = { pin ->
						val valid = if (isPrivateMode) privateSecurity.verifyPin(pin) else settings.verifyAppPassword(pin)
						valid.also { if (it) unlockAndFinish() }
					},
					onBiometric = { startUnlockFlow() },
					onCancel = { if (isPrivateMode) finish() else finishAffinity() },
				)
			}
		}
	}

	override fun onStart() {
		super.onStart()
		if (!isPinMode && isAutoPromptPending) {
			isAutoPromptPending = false
			viewBinding.root.post { startUnlockFlow() }
		}
	}

	override fun onStop() {
		super.onStop()
		if (!isPromptShowing) isAutoPromptPending = true
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat = insets

	override fun onAuthResult(result: AuthenticationResult) {
		isPromptShowing = false
		if (result.isSuccess()) {
			unlockAndFinish()
		} else if (
			isPrivateMode &&
			privateProtection == PrivateFavouritesProtection.BIOMETRIC_PIN &&
			privateSecurity.hasPin
		) {
			forcePrivatePin = true
		}
	}

	private fun unlockAndFinish() {
		if (isPrivateMode) {
			privateSession.unlock()
			finishPrivateUnlock()
			return
		}
		protectHelper.unlock()
		@Suppress("DEPRECATION")
		overridePendingTransition(0, 0)
		finish()
	}

	private fun finishPrivateUnlock() {
		if (openPrivateOnSuccess) {
			startActivity(
				Intent(this, FavouritesActivity::class.java)
					.putExtra(EXTRA_FAVOURITE_SPACE, FavouriteSpace.PRIVATE.dbValue),
			)
		} else {
			setResult(Activity.RESULT_OK)
		}
		@Suppress("DEPRECATION")
		overridePendingTransition(0, 0)
		finish()
	}

	private fun startUnlockFlow(): Boolean {
		if (BiometricManager.from(this).canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) != BIOMETRIC_SUCCESS) {
			if (
				isPrivateMode &&
				privateProtection == PrivateFavouritesProtection.BIOMETRIC_PIN &&
				privateSecurity.hasPin
			) {
				forcePrivatePin = true
				return false
			}
			if (isPrivateMode) return false
			finishAffinity()
			return false
		}
		val request = AuthenticationRequest.biometricRequest(
			getString(R.string.app_name),
			Biometric.Fallback.DeviceCredential,
		) {
			setMinStrength(Biometric.Strength.Class2)
			setIsConfirmationRequired(false)
		}
		isPromptShowing = true
		biometricPrompt.launch(request)
		return true
	}

	companion object {
		const val EXTRA_PRIVATE_FAVOURITES = "private_favourites_unlock"
		const val EXTRA_OPEN_PRIVATE_ON_SUCCESS = "open_private_on_success"
	}
}
