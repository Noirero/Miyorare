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
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.MiyorareTheme
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref

@AndroidEntryPoint
class ReaderAdvancedSettingsFragment : BaseComposeSettingsFragment(R.string.settings_advanced) {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            MiyorareTheme {
                ReaderAdvancedScreen()
            }
        }
    }
}

@Composable
private fun ReaderAdvancedScreen() {
    var readerVolumeButtons by rememberBooleanPref(AppSettings.KEY_READER_VOLUME_BUTTONS, false)
    var readerTapsLtr by rememberBooleanPref(AppSettings.KEY_READER_CONTROL_LTR, false)
    var readerNavigationInverted by rememberBooleanPref(AppSettings.KEY_READER_NAVIGATION_INVERTED, false)

    SettingsScaffold {
        item {
            SettingsGroup(title = stringResource(R.string.settings_advanced_reader_hardware)) {
                item { pos ->
                    SwitchSettingsItem(
                        title = stringResource(R.string.switch_pages_volume_buttons),
                        subtitle = stringResource(R.string.switch_pages_volume_buttons_summary),
                        checked = readerVolumeButtons,
                        onCheckedChange = { readerVolumeButtons = it },
                        icon = R.drawable.ic_action_skip,
                        shape = pos.shape,
                    )
                }
                item { pos ->
                    SwitchSettingsItem(
                        title = stringResource(R.string.reader_control_ltr),
                        subtitle = stringResource(R.string.reader_control_ltr_summary),
                        checked = readerTapsLtr,
                        onCheckedChange = { readerTapsLtr = it },
                        icon = R.drawable.ic_reader_ltr,
                        shape = pos.shape,
                    )
                }
                item { pos ->
                    SwitchSettingsItem(
                        title = stringResource(R.string.reader_navigation_inverted),
                        subtitle = stringResource(R.string.reader_navigation_inverted_summary),
                        checked = readerNavigationInverted,
                        onCheckedChange = { readerNavigationInverted = it },
                        icon = R.drawable.ic_revert,
                        shape = pos.shape,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
    }
}
