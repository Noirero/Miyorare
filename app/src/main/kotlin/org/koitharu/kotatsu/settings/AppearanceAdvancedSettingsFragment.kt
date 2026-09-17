package org.koitharu.kotatsu.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.os.AppShortcutManager
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.MiyorareTheme
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref
import javax.inject.Inject

@AndroidEntryPoint
class AppearanceAdvancedSettingsFragment : BaseComposeSettingsFragment(R.string.settings_advanced) {

    @Inject
    lateinit var appShortcutManager: AppShortcutManager

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            MiyorareTheme {
                AppearanceAdvancedScreen(
                    dynamicShortcutsAvailable = appShortcutManager.isDynamicShortcutsAvailable(),
                )
            }
        }
    }
}

@Composable
private fun AppearanceAdvancedScreen(dynamicShortcutsAvailable: Boolean) {
    var hideStatusBar by rememberBooleanPref(AppSettings.KEY_HIDE_STATUS_BAR, false)
    var navPinned by rememberBooleanPref(AppSettings.KEY_NAV_PINNED, false)
    var navLegacy by rememberBooleanPref(AppSettings.KEY_NAV_LEGACY, false)
    var exitConfirm by rememberBooleanPref(AppSettings.KEY_EXIT_CONFIRM, false)
    var dynamicShortcuts by rememberBooleanPref(AppSettings.KEY_SHORTCUTS, true)

    SettingsScaffold {
        item {
            SettingsGroup(title = stringResource(R.string.settings_advanced_system_navigation)) {
                item { pos ->
                    SwitchSettingsItem(
                        title = stringResource(R.string.hide_status_bar),
                        subtitle = stringResource(R.string.hide_status_bar_summary),
                        checked = hideStatusBar,
                        onCheckedChange = { hideStatusBar = it },
                        icon = R.drawable.ic_eye_off,
                        shape = pos.shape,
                    )
                }
                item { pos ->
                    SwitchSettingsItem(
                        title = stringResource(R.string.use_legacy_navigation_bar),
                        subtitle = stringResource(R.string.use_legacy_navigation_bar_summary),
                        checked = navLegacy,
                        onCheckedChange = { navLegacy = it },
                        icon = R.drawable.ic_bottom_navigation,
                        shape = pos.shape,
                    )
                }
                item { pos ->
                    SwitchSettingsItem(
                        title = stringResource(R.string.pin_navigation_ui),
                        subtitle = stringResource(R.string.pin_navigation_ui_summary),
                        checked = navPinned,
                        onCheckedChange = { navPinned = it },
                        icon = R.drawable.ic_pin,
                        shape = pos.shape,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
        item {
            SettingsGroup(title = stringResource(R.string.settings_advanced_app_behavior)) {
                item { pos ->
                    SwitchSettingsItem(
                        title = stringResource(R.string.exit_confirmation),
                        subtitle = stringResource(R.string.exit_confirmation_summary),
                        checked = exitConfirm,
                        onCheckedChange = { exitConfirm = it },
                        icon = R.drawable.ic_alert_outline,
                        shape = pos.shape,
                    )
                }
                if (dynamicShortcutsAvailable) {
                    item { pos ->
                        SwitchSettingsItem(
                            title = stringResource(R.string.history_shortcuts),
                            subtitle = stringResource(R.string.history_shortcuts_summary),
                            checked = dynamicShortcuts,
                            onCheckedChange = { dynamicShortcuts = it },
                            icon = R.drawable.ic_shortcut,
                            shape = pos.shape,
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
    }
}
