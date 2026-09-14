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
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref

@AndroidEntryPoint
class FeedAppearanceSettingsFragment : BaseComposeSettingsFragment(R.string.settings_feed) {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            DropSauceTheme {
                FeedAppearanceScreen()
            }
        }
    }
}

@Composable
private fun FeedAppearanceScreen() {
    var feedSwipeGestures by rememberBooleanPref(AppSettings.KEY_FEED_SWIPE_GESTURES, true)
    var feedCounterAsDot by rememberBooleanPref(AppSettings.KEY_FEED_COUNTER_DOT, false)

    SettingsScaffold {
        item {
            SettingsGroup(title = stringResource(R.string.settings_feed)) {
                item { pos ->
                    SwitchSettingsItem(
                        title = stringResource(R.string.feed_swipe_gestures),
                        subtitle = stringResource(R.string.feed_swipe_gestures_summary),
                        checked = feedSwipeGestures,
                        onCheckedChange = { feedSwipeGestures = it },
                        icon = R.drawable.ic_gesture_horizontal,
                        shape = pos.shape,
                    )
                }
                item { pos ->
                    SwitchSettingsItem(
                        title = stringResource(R.string.feed_counter_dot),
                        subtitle = stringResource(R.string.feed_counter_dot_summary),
                        checked = feedCounterAsDot,
                        onCheckedChange = { feedCounterAsDot = it },
                        icon = R.drawable.ic_dot_indicator,
                        shape = pos.shape,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
    }
}
