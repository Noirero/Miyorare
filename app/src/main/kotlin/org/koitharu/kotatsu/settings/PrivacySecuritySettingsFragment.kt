package org.koitharu.kotatsu.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppProtectionTimeout
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ScreenshotsPolicy
import org.koitharu.kotatsu.core.util.ext.toList
import org.koitharu.kotatsu.parsers.util.names
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.ListSettingsItem
import org.koitharu.kotatsu.settings.compose.NavigationSettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref
import org.koitharu.kotatsu.settings.compose.rememberStringPref
import org.koitharu.kotatsu.settings.protect.showProtectMethodDialog
import javax.inject.Inject

@AndroidEntryPoint
class PrivacySecuritySettingsFragment : BaseComposeSettingsFragment(R.string.settings_privacy_security) {

    @Inject
    lateinit var settings: AppSettings

    private var pendingProtectState: Boolean? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            DropSauceTheme {
                PrivacySecurityScreen(
                    onProtectToggle = ::onProtectToggle,
                    onOpenPrivateFavourites = ::openPrivateFavourites,
                )
            }
        }
    }

    override fun onDestroyView() {
        pendingProtectState = null
        super.onDestroyView()
    }

    private fun openPrivateFavourites() {
        (activity as? SettingsActivity)?.openFragment(
            PrivateFavouritesSettingsFragment::class.java,
            null,
            isFromRoot = false,
        )
    }

    private fun isAuthenticationSupported(): Boolean {
        val manager = context?.let { BiometricManager.from(it) } ?: return false
        return manager.canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) == BIOMETRIC_SUCCESS
    }

    private fun onProtectToggle(enable: Boolean) {
        if (!enable) {
            settings.isAppProtectionEnabled = false
            settings.clearAppPassword()
            return
        }
        showProtectMethodDialog(
            activity = requireActivity(),
            deviceAuthSupported = isAuthenticationSupported(),
            onSelectDevice = { startProtectionAuthentication(true) },
            onPinConfirmed = { pin ->
                settings.setAppPassword(pin)
                settings.isAppProtectionEnabled = true
            },
        )
    }

    private fun startProtectionAuthentication(requestedState: Boolean): Boolean {
        if (!isAuthenticationSupported() || !isAdded) return false
        val executor = context?.let { ContextCompat.getMainExecutor(it) } ?: return false
        pendingProtectState = requestedState
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val state = pendingProtectState ?: return
                settings.clearAppPassword()
                settings.isAppProtectionEnabled = state
                pendingProtectState = null
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                pendingProtectState = null
            }
        })
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.app_name))
            .setSubtitle(getString(R.string.require_unlock))
            .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
            .setConfirmationRequired(false)
            .build()
        prompt.authenticate(promptInfo)
        return true
    }
}

@Composable
private fun PrivacySecurityScreen(
    onProtectToggle: (Boolean) -> Unit,
    onOpenPrivateFavourites: () -> Unit,
) {
    val context = LocalContext.current
    val protectTimeoutEntries = remember {
        AppProtectionTimeout.entries.map { context.getString(it.titleResId) }
    }
    val protectTimeoutValues = remember { AppProtectionTimeout.entries.names().toList() }
    val screenshotsPolicyEntries = remember {
        context.resources.getStringArray(R.array.screenshots_policy).toList()
    }
    val screenshotsPolicyValues = remember { ScreenshotsPolicy.entries.names().toList() }

    var protectApp by rememberBooleanPref(AppSettings.KEY_PROTECT_APP, false)
    var protectAppTimeout by rememberStringPref(
        AppSettings.KEY_PROTECT_APP_TIMEOUT,
        AppProtectionTimeout.INSTANT.name,
    )
    var screenshotsPolicy by rememberStringPref(
        AppSettings.KEY_SCREENSHOTS_POLICY,
        ScreenshotsPolicy.ALLOW.name,
    )

    SettingsScaffold {
        item {
            SettingsGroup(title = stringResource(R.string.settings_app_security)) {
                item { pos ->
                    SwitchSettingsItem(
                        title = stringResource(R.string.require_unlock),
                        subtitle = stringResource(R.string.require_unlock_summary),
                        checked = protectApp,
                        onCheckedChange = onProtectToggle,
                        icon = R.drawable.ic_lock,
                        shape = pos.shape,
                    )
                }
                item { pos ->
                    ListSettingsItem(
                        title = stringResource(R.string.require_unlock_after),
                        entries = protectTimeoutEntries,
                        entryValues = protectTimeoutValues,
                        selectedValue = protectAppTimeout,
                        onValueChange = { protectAppTimeout = it },
                        icon = R.drawable.ic_timer,
                        shape = pos.shape,
                        enabled = protectApp,
                    )
                }
                item { pos ->
                    ListSettingsItem(
                        title = stringResource(R.string.screenshots_policy),
                        entries = screenshotsPolicyEntries,
                        entryValues = screenshotsPolicyValues,
                        selectedValue = screenshotsPolicy,
                        onValueChange = { screenshotsPolicy = it },
                        icon = R.drawable.ic_eye,
                        shape = pos.shape,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
        item {
            SettingsGroup(title = stringResource(R.string.settings_private_collection)) {
                item { pos ->
                    NavigationSettingsItem(
                        title = stringResource(R.string.private_favourites),
                        subtitle = stringResource(R.string.settings_private_favourites_entry_summary),
                        icon = R.drawable.ic_lock,
                        shape = pos.shape,
                        onClick = onOpenPrivateFavourites,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
    }
}
