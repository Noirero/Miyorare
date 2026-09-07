package org.koitharu.kotatsu.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.favourites.vault.PrivateFavouritesProtection
import org.koitharu.kotatsu.favourites.vault.PrivateFavouritesSecurityStore
import org.koitharu.kotatsu.favourites.vault.PrivateFavouritesSession
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import org.koitharu.kotatsu.settings.protect.showPinSetupDialog
import javax.inject.Inject

@AndroidEntryPoint
class PrivateFavouritesSettingsFragment : BaseComposeSettingsFragment(R.string.private_favourites) {

	@Inject lateinit var security: PrivateFavouritesSecurityStore
	@Inject lateinit var session: PrivateFavouritesSession

	private val protectionState = MutableStateFlow(PrivateFavouritesProtection.BIOMETRIC)
	private val hasPinState = MutableStateFlow(false)
	private val backupState = MutableStateFlow(false)

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			DropSauceTheme {
				val protection by protectionState.collectAsState()
				val hasPin by hasPinState.collectAsState()
				val includeBackup by backupState.collectAsState()
				PrivateFavouritesSettingsScreen(
					protection = protection,
					hasPin = hasPin,
					includeBackup = includeBackup,
					onProtectionClick = ::showProtectionChooser,
					onChangePin = ::changePin,
					onLockNow = {
						session.lock()
						Toast.makeText(requireContext(), R.string.private_favourites_lock_now, Toast.LENGTH_SHORT).show()
					},
					onIncludeBackupChange = ::changeBackupInclusion,
				)
			}
		}
	}

	override fun onResume() {
		super.onResume()
		refreshState()
	}

	private fun refreshState() {
		protectionState.value = security.protection
		hasPinState.value = security.hasPin
		backupState.value = security.includePrivateInBackup
	}

	private fun showProtectionChooser() {
		val modes = arrayOf(
			PrivateFavouritesProtection.BIOMETRIC,
			PrivateFavouritesProtection.BIOMETRIC_PIN,
			PrivateFavouritesProtection.PIN,
			PrivateFavouritesProtection.NONE,
		)
		val labels = arrayOf(
			getString(R.string.private_favourites_security_biometric),
			getString(R.string.private_favourites_security_biometric_pin),
			getString(R.string.private_favourites_security_pin),
			getString(R.string.private_favourites_security_none),
		)
		val checked = modes.indexOf(security.protection).coerceAtLeast(0)
		buildAlertDialog(requireContext(), isCentered = true) {
			setTitle(R.string.private_favourites_security)
			setSingleChoiceItems(labels, checked) { dialog, which ->
				dialog.dismiss()
				applyProtection(modes[which])
			}
			setNegativeButton(android.R.string.cancel, null)
		}.show()
	}

	private fun applyProtection(mode: PrivateFavouritesProtection) {
		when (mode) {
			PrivateFavouritesProtection.NONE -> confirmDisableProtection()
			PrivateFavouritesProtection.PIN -> setupPinFor(PrivateFavouritesProtection.PIN)
			PrivateFavouritesProtection.BIOMETRIC -> {
				authenticateForSecurityChange {
					security.clearPin()
					security.protection = PrivateFavouritesProtection.BIOMETRIC
					session.lock()
					refreshState()
				}
			}
			PrivateFavouritesProtection.BIOMETRIC_PIN -> {
				authenticateForSecurityChange {
					setupPinFor(PrivateFavouritesProtection.BIOMETRIC_PIN)
				}
			}
		}
	}

	private fun confirmDisableProtection() {
		buildAlertDialog(requireContext(), isCentered = true) {
			setTitle(R.string.private_favourites_security_disable_title)
			setMessage(R.string.private_favourites_security_disable_message)
			setPositiveButton(android.R.string.ok) { _, _ ->
				security.protection = PrivateFavouritesProtection.NONE
				session.lock()
				refreshState()
			}
			setNegativeButton(android.R.string.cancel, null)
		}.show()
	}

	private fun changePin() {
		val mode = when (security.protection) {
			PrivateFavouritesProtection.BIOMETRIC_PIN -> PrivateFavouritesProtection.BIOMETRIC_PIN
			else -> PrivateFavouritesProtection.PIN
		}
		setupPinFor(mode)
	}

	private fun setupPinFor(mode: PrivateFavouritesProtection) {
		showPinSetupDialog(
			activity = requireActivity(),
			onPinConfirmed = { pin ->
				security.setPin(pin)
				security.protection = mode
				session.lock()
				refreshState()
			},
		)
	}

	private fun changeBackupInclusion(include: Boolean) {
		if (!include) {
			security.includePrivateInBackup = false
			refreshState()
			return
		}
		buildAlertDialog(requireContext(), isCentered = true) {
			setTitle(R.string.private_favourites_backup_title)
			setMessage(R.string.private_favourites_include_backup_warning)
			setPositiveButton(android.R.string.ok) { _, _ ->
				security.includePrivateInBackup = true
				refreshState()
			}
			setNegativeButton(android.R.string.cancel, null)
		}.show()
	}

	private fun authenticateForSecurityChange(onSuccess: () -> Unit) {
		if (!isAuthenticationSupported()) {
			Toast.makeText(requireContext(), R.string.private_favourites_security_unavailable, Toast.LENGTH_LONG).show()
			return
		}
		val executor = ContextCompat.getMainExecutor(requireContext())
		val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
			override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
				onSuccess()
			}
		})
		val info = BiometricPrompt.PromptInfo.Builder()
			.setTitle(getString(R.string.private_favourites))
			.setSubtitle(getString(R.string.private_favourites_security_summary))
			.setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
			.setConfirmationRequired(false)
			.build()
		prompt.authenticate(info)
	}

	private fun isAuthenticationSupported(): Boolean =
		BiometricManager.from(requireContext()).canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) ==
			BiometricManager.BIOMETRIC_SUCCESS
}

@Composable
private fun PrivateFavouritesSettingsScreen(
	protection: PrivateFavouritesProtection,
	hasPin: Boolean,
	includeBackup: Boolean,
	onProtectionClick: () -> Unit,
	onChangePin: () -> Unit,
	onLockNow: () -> Unit,
	onIncludeBackupChange: (Boolean) -> Unit,
) {
	val protectionTitle = when (protection) {
		PrivateFavouritesProtection.NONE -> stringResource(R.string.private_favourites_security_none)
		PrivateFavouritesProtection.PIN -> stringResource(R.string.private_favourites_security_pin)
		PrivateFavouritesProtection.BIOMETRIC -> stringResource(R.string.private_favourites_security_biometric)
		PrivateFavouritesProtection.BIOMETRIC_PIN -> stringResource(R.string.private_favourites_security_biometric_pin)
	}
	SettingsScaffold {
		item {
			SettingsGroup(title = stringResource(R.string.private_favourites_security_group)) {
				item { pos ->
					SettingsItem(
						title = stringResource(R.string.private_favourites_security),
						subtitle = protectionTitle,
						icon = R.drawable.ic_lock,
						shape = pos.shape,
						onClick = onProtectionClick,
					)
				}
				if (protection == PrivateFavouritesProtection.PIN || protection == PrivateFavouritesProtection.BIOMETRIC_PIN) {
					item { pos ->
						SettingsItem(
							title = stringResource(R.string.private_favourites_change_pin),
							subtitle = stringResource(R.string.private_favourites_change_pin_summary),
							icon = R.drawable.ic_lock,
							shape = pos.shape,
							enabled = hasPin,
							onClick = onChangePin,
						)
					}
				}
				item { pos ->
					SettingsItem(
						title = stringResource(R.string.private_favourites_lock_now),
						subtitle = stringResource(R.string.private_favourites_lock_now_summary),
						icon = R.drawable.ic_lock,
						shape = pos.shape,
						onClick = onLockNow,
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = stringResource(R.string.private_favourites_backup_group)) {
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.private_favourites_include_backup),
						subtitle = stringResource(R.string.private_favourites_include_backup_summary),
						checked = includeBackup,
						onCheckedChange = onIncludeBackupChange,
						icon = R.drawable.ic_backup_restore,
						shape = pos.shape,
					)
				}
			}
		}
	}
}
