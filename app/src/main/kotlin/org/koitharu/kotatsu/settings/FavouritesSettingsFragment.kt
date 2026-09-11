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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.favourites.domain.FavouriteHeaderScrollMode
import org.koitharu.kotatsu.favourites.domain.FavouriteListLoadingMode
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.ListSettingsItem
import org.koitharu.kotatsu.settings.compose.PlainInfoSettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.settings.compose.rememberStringPref

@AndroidEntryPoint
class FavouritesSettingsFragment : BaseComposeSettingsFragment(R.string.favourites) {

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			DropSauceTheme {
				FavouritesSettingsScreen()
			}
		}
	}
}

@Composable
private fun FavouritesSettingsScreen() {
	val context = LocalContext.current
	val entries = remember {
		FavouriteHeaderScrollMode.entries.map { context.getString(it.titleResId) }
	}
	val values = remember { FavouriteHeaderScrollMode.entries.map { it.name } }
	val loadingEntries = remember { FavouriteListLoadingMode.entries.map { context.getString(it.titleResId) } }
	val loadingValues = remember { FavouriteListLoadingMode.entries.map { it.name } }
	var mode by rememberStringPref(
		FavouriteHeaderScrollMode.KEY_PREFERENCE,
		FavouriteHeaderScrollMode.SCROLL_AWAY.name,
	)
	var loadingMode by rememberStringPref(
		AppSettings.KEY_FAVOURITES_LIST_LOADING_MODE,
		FavouriteListLoadingMode.PAGED.name,
	)

	SettingsScaffold {
		item {
			SettingsGroup(title = stringResource(R.string.favourites)) {
				item { pos ->
					ListSettingsItem(
						title = stringResource(R.string.favourites_scroll_mode),
						entries = entries,
						entryValues = values,
						selectedValue = mode,
						onValueChange = { mode = it },
						icon = R.drawable.ic_list,
						shape = pos.shape,
					)
				}
				item { pos ->
					ListSettingsItem(
						title = stringResource(R.string.favourites_loading_mode),
						entries = loadingEntries,
						entryValues = loadingValues,
						selectedValue = loadingMode,
						onValueChange = { loadingMode = it },
						icon = R.drawable.ic_list,
						shape = pos.shape,
					)
				}
			}
		}
		item { PlainInfoSettingsItem(text = stringResource(R.string.favourites_loading_mode_summary), icon = R.drawable.ic_info_outline) }
		item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
	}
}
