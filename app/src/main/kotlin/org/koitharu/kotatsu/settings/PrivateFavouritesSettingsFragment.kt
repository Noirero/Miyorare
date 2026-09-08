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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import org.koitharu.kotatsu.core.prefs.PrivateFavouritesThemePreset
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.favourites.vault.PrivateFavouritesAppearanceStore
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
	@Inject lateinit var appearance: PrivateFavouritesAppearanceStore

	private val protectionState = MutableStateFlow(PrivateFavouritesProtection.BIOMETRIC)
	private val hasPinState = MutableStateFlow(false)
	private val backupState = MutableStateFlow(false)
	private val screenshotsState = MutableStateFlow(false)
	private val themeState = MutableStateFlow(PrivateFavouritesThemePreset.FOLLOW_NORMAL)

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
				val allowScreenshots by screenshotsState.collectAsState()
				val privateTheme by themeState.collectAsState()
				PrivateFavouritesSettingsScreen(
					protection = protection,
					hasPin = hasPin,
					includeBackup = includeBackup,
					allowScreenshots = allowScreenshots,
					privateTheme = privateTheme,
					onThemeClick = ::showThemeChooser,
					onThemeSelect = ::changePrivateTheme,
					onProtectionClick = ::showProtectionChooser,
					onChangePin = ::changePin,
					onLockNow = {
						session.lock()
						Toast.makeText(requireContext(), R.string.private_favourites_lock_now, Toast.LENGTH_SHORT).show()
					},
					onIncludeBackupChange = ::changeBackupInclusion,
					onAllowScreenshotsChange = ::changePrivateScreenshots,
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
		screenshotsState.value = security.allowPrivateScreenshots
		themeState.value = appearance.themePreset
	}

	private fun showThemeChooser() {
		val values = PrivateFavouritesThemePreset.entries.toTypedArray()
		val labels = values.map { getString(it.titleResId) }.toTypedArray()
		val checked = values.indexOf(appearance.themePreset).coerceAtLeast(0)
		buildAlertDialog(requireContext(), isCentered = true) {
			setTitle(R.string.private_favourites_theme_title)
			setSingleChoiceItems(labels, checked) { dialog, which ->
				dialog.dismiss()
				changePrivateTheme(values[which])
			}
			setNegativeButton(android.R.string.cancel, null)
		}.show()
	}

	private fun changePrivateTheme(theme: PrivateFavouritesThemePreset) {
		appearance.themePreset = theme
		themeState.value = theme
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

	private fun changePrivateScreenshots(allow: Boolean) {
		if (!allow) {
			security.allowPrivateScreenshots = false
			refreshState()
			return
		}
		if (security.privateScreenshotWarningAcknowledged) {
			security.allowPrivateScreenshots = true
			refreshState()
			return
		}
		buildAlertDialog(requireContext(), isCentered = true) {
			setTitle(R.string.private_favourites_screenshot_warning_title)
			setMessage(R.string.private_favourites_screenshot_warning_message)
			setPositiveButton(R.string.private_favourites_screenshot_allow_button) { _, _ ->
				security.privateScreenshotWarningAcknowledged = true
				security.allowPrivateScreenshots = true
				refreshState()
			}
			setNegativeButton(android.R.string.cancel, null)
		}.show()
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
	allowScreenshots: Boolean,
	privateTheme: PrivateFavouritesThemePreset,
	onThemeClick: () -> Unit,
	onThemeSelect: (PrivateFavouritesThemePreset) -> Unit,
	onProtectionClick: () -> Unit,
	onChangePin: () -> Unit,
	onLockNow: () -> Unit,
	onIncludeBackupChange: (Boolean) -> Unit,
	onAllowScreenshotsChange: (Boolean) -> Unit,
) {
	val protectionTitle = when (protection) {
		PrivateFavouritesProtection.NONE -> stringResource(R.string.private_favourites_security_none)
		PrivateFavouritesProtection.PIN -> stringResource(R.string.private_favourites_security_pin)
		PrivateFavouritesProtection.BIOMETRIC -> stringResource(R.string.private_favourites_security_biometric)
		PrivateFavouritesProtection.BIOMETRIC_PIN -> stringResource(R.string.private_favourites_security_biometric_pin)
	}
	SettingsScaffold {
		item {
			SettingsGroup(title = stringResource(R.string.private_favourites_appearance_group)) {
				item { pos ->
					SettingsItem(
						title = stringResource(R.string.private_favourites_theme_title),
						subtitle = stringResource(privateTheme.titleResId),
						icon = R.drawable.ic_palette,
						shape = pos.shape,
						onClick = onThemeClick,
					)
				}
				item {
					PrivateThemePreviewStrip(
						selected = privateTheme,
						onSelect = onThemeSelect,
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
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
			SettingsGroup(title = stringResource(R.string.private_favourites_privacy_group)) {
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.private_favourites_allow_screenshots),
						subtitle = stringResource(R.string.private_favourites_allow_screenshots_summary),
						checked = allowScreenshots,
						onCheckedChange = onAllowScreenshotsChange,
						icon = R.drawable.ic_lock,
						shape = pos.shape,
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

@Composable
private fun PrivateThemePreviewStrip(
	selected: PrivateFavouritesThemePreset,
	onSelect: (PrivateFavouritesThemePreset) -> Unit,
) {
	Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
		Text(
			text = stringResource(R.string.private_favourites_theme_preview),
			style = MaterialTheme.typography.labelLarge,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
		)
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.horizontalScroll(rememberScrollState())
				.padding(horizontal = 12.dp, vertical = 6.dp),
		) {
			PrivateFavouritesThemePreset.entries.forEach { theme ->
				val preset = theme.preset ?: MiyorareThemePreset.MIYORARE
				val shape = RoundedCornerShape(16.dp)
				Column(
					horizontalAlignment = Alignment.CenterHorizontally,
					modifier = Modifier
						.width(100.dp)
						.padding(horizontal = 4.dp)
						.clickable { onSelect(theme) },
				) {
					Box(
						modifier = Modifier
							.size(width = 92.dp, height = 56.dp)
							.background(
								brush = Brush.linearGradient(
									listOf(
										Color(preset.accentArgb).copy(alpha = 0.88f),
										Color(preset.secondaryArgb).copy(alpha = 0.52f),
										Color(0xFF080A12),
									),
								),
								shape = shape,
							)
							.border(
								width = if (theme == selected) 2.dp else 1.dp,
								color = if (theme == selected) Color(preset.accentArgb) else Color.White.copy(alpha = 0.18f),
								shape = shape,
							),
					)
					Text(
						text = stringResource(theme.titleResId),
						style = MaterialTheme.typography.labelSmall,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
						modifier = Modifier.padding(top = 5.dp),
					)
				}
			}
		}
	}
}
