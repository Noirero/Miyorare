package org.koitharu.kotatsu.settings.nav

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.NavigationMotion
import org.koitharu.kotatsu.core.prefs.NavigationTransitionPreferences
import org.koitharu.kotatsu.core.prefs.ScreenTransition
import org.koitharu.kotatsu.settings.compose.ActionSettingsItem
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.ListSettingsItem
import org.koitharu.kotatsu.settings.compose.MiyorareSettingsTheme
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref

class NavigationTransitionSettingsFragment : BaseComposeSettingsFragment(R.string.navigation_transition_title) {
	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
		ComposeView(requireContext()).apply {
			setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
			setContent {
				MiyorareSettingsTheme("appearance") {
					val context = LocalContext.current
					val prefs = remember(context) { NavigationTransitionPreferences(context) }
					var motion by remember(prefs) { mutableStateOf(prefs.motion) }
					var transition by remember(prefs) { mutableStateOf(prefs.transition) }
					val legacy by rememberBooleanPref(AppSettings.KEY_NAV_LEGACY, false)
					val labels by rememberBooleanPref(AppSettings.KEY_NAV_LABELS, true)
					DisposableEffect(prefs) {
						val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
							if (key == null || key == NavigationTransitionPreferences.KEY_MOTION || key == NavigationTransitionPreferences.KEY_TRANSITION) {
								motion = prefs.motion
								transition = prefs.transition
							}
						}
						prefs.subscribe(listener)
						motion = prefs.motion
						transition = prefs.transition
						onDispose { prefs.unsubscribe(listener) }
					}
					SettingsScaffold {
						item {
							Text(stringResource(R.string.navigation_transition_preview), style = MaterialTheme.typography.titleMedium,
								modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp))
						}
						item {
							Text(stringResource(R.string.navigation_transition_preview_hint), style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
						}
						item {
							AndroidView(
								factory = { NavigationTransitionPreview(it) },
								update = { it.configure(motion, transition, legacy, labels) },
								modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
							)
						}
						item {
							SettingsGroup {
								item { pos ->
									ListSettingsItem(
										title = stringResource(R.string.navigation_motion_title),
										entries = listOf(stringResource(R.string.navigation_motion_classic), stringResource(R.string.navigation_motion_elastic), stringResource(R.string.navigation_motion_fluid)),
										entryValues = NavigationMotion.entries.map { it.storedValue },
										selectedValue = motion.storedValue,
										onValueChange = { prefs.setMotion(NavigationMotion.parse(it)) },
										shape = pos.shape,
									)
								}
								item { pos ->
									ListSettingsItem(
										title = stringResource(R.string.navigation_screen_transition_title),
										entries = ScreenTransition.entries.map { effect ->
											stringResource(when (effect) {
												ScreenTransition.DIRECTIONAL_SLIDE -> R.string.navigation_transition_directional
												ScreenTransition.FLIP_3D -> R.string.navigation_transition_flip_3d
												ScreenTransition.CUBE_3D -> R.string.navigation_transition_cube_3d
												ScreenTransition.CROSSFADE -> R.string.navigation_transition_crossfade
												ScreenTransition.ZOOM -> R.string.navigation_transition_zoom
												ScreenTransition.CARD_STACK -> R.string.navigation_transition_card_stack
												ScreenTransition.VERTICAL_SLIDE -> R.string.navigation_transition_vertical_slide
												ScreenTransition.CURTAIN_REVEAL -> R.string.navigation_transition_curtain_reveal
											})
										},
										entryValues = ScreenTransition.entries.map { it.storedValue },
										selectedValue = transition.storedValue,
										onValueChange = { prefs.setTransition(ScreenTransition.parse(it)) },
										shape = pos.shape,
									)
								}
								item { pos ->
									ActionSettingsItem(
										title = stringResource(R.string.navigation_transition_reset),
										subtitle = stringResource(R.string.navigation_transition_reset_summary),
										onClick = prefs::reset,
										shape = pos.shape,
									)
								}
							}
						}
					}
				}
			}
		}
}
