package org.koitharu.kotatsu.favourites.private

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.biometric.AuthenticationRequest
import androidx.biometric.AuthenticationRequest.Biometric
import androidx.biometric.AuthenticationResult
import androidx.biometric.AuthenticationResultCallback
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
import androidx.biometric.registerForAuthenticationResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowInsetsCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.databinding.ActivityProtectBinding
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.ui.FavouritesActivity
import org.koitharu.kotatsu.main.ui.protect.ProtectScreen
import org.koitharu.kotatsu.settings.compose.DropSauceTheme

@AndroidEntryPoint
class PrivateFavouritesUnlockActivity :
	BaseActivity<ActivityProtectBinding>(),
	AuthenticationResultCallback {

	@Inject lateinit var securityStore: PrivateFavouritesSecurityStore
	@Inject lateinit var privateSession: PrivateFavouritesSession

	private val biometricPrompt = registerForAuthenticationResult(resultCallback = this)
	private var isAutoPromptPending = true
	private var isPromptShowing = false
	private var forcePin by mutableStateOf(false)

	private val protection: PrivateFavouritesProtection
		get() = securityStore.protection

	private val isPinMode: Boolean
		get() = protection == PrivateFavouritesProtection.PIN || forcePin

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
		if (privateSession.isUnlocked.value || protection == PrivateFavouritesProtection.NONE) {
			privateSession.unlock()
			openPrivateFavourites()
			return
		}
		setContentView(ActivityProtectBinding.inflate(layoutInflater))
		viewBinding.composeView.setContent {
			DropSauceTheme {
				ProtectScreen(
					isPinMode = isPinMode,
					onVerifyPin = { pin ->
						securityStore.verifyPin(pin).also { if (it) unlockAndOpen() }
					},
					onBiometric = { startUnlockFlow() },
					onCancel = { finish() },
				)
			}
		}
	}

	override fun onStart() {
		super.onStart()
		if (!isPinMode && protection != PrivateFavouritesProtection.NONE && isAutoPromptPending) {
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
			unlockAndOpen()
		} else if (protection == PrivateFavouritesProtection.BIOMETRIC_PIN && securityStore.hasPin) {
			forcePin = true
		}
	}

	private fun startUnlockFlow(): Boolean {
		if (BiometricManager.from(this).canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) != BIOMETRIC_SUCCESS) {
			if (protection == PrivateFavouritesProtection.BIOMETRIC_PIN && securityStore.hasPin) {
				forcePin = true
				return false
			}
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

	private fun unlockAndOpen() {
		privateSession.unlock()
		openPrivateFavourites()
	}

	private fun openPrivateFavourites() {
		startActivity(
			Intent(this, FavouritesActivity::class.java)
				.putExtra(EXTRA_FAVOURITE_SPACE, FavouriteSpace.PRIVATE.dbValue),
		)
		@Suppress("DEPRECATION")
		overridePendingTransition(0, 0)
		finish()
	}
}
