package org.koitharu.kotatsu.sync.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.settings.SettingsActivity
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.CategoryPalette
import org.koitharu.kotatsu.settings.compose.MiyorareTheme
import org.koitharu.kotatsu.settings.compose.NavigationSettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsScaffold

class LibrarySyncHubFragment : BaseComposeSettingsFragment(R.string.library_sync) {

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			MiyorareTheme {
				SettingsScaffold {
					item {
						SettingsGroup {
							item { pos ->
								NavigationSettingsItem(
									title = stringResource(R.string.google_drive_sync),
									subtitle = stringResource(R.string.sync_sign_in_summary),
									icon = R.drawable.ic_cloud_sync,
									iconColors = CategoryPalette.forKey("sync"),
									shape = pos.shape,
									onClick = {
										(activity as? SettingsActivity)?.openFragment(
											SyncSettingsFragment::class.java,
											null,
											isFromRoot = false,
										)
									},
								)
							}
						}
					}

					item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
					item {
						SettingsGroup(title = stringResource(R.string.library_sync_manga)) {
							comingSoonItem(R.string.library_sync_mangadex)
							comingSoonItem(R.string.library_sync_mangaupdates)
							comingSoonItem(R.string.library_sync_anilist)
							comingSoonItem(R.string.library_sync_kitsu)
						}
					}

					item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
					item {
						SettingsGroup(title = stringResource(R.string.library_sync_novel)) {
							comingSoonItem(R.string.library_sync_novelupdates)
							comingSoonItem(R.string.library_sync_ranobedb)
						}
					}
					item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
				}
			}
		}
	}
}

private fun org.koitharu.kotatsu.settings.compose.SettingsGroupScope.comingSoonItem(titleRes: Int) {
	item { pos ->
		NavigationSettingsItem(
			title = stringResource(titleRes),
			subtitle = stringResource(R.string.library_sync_coming_soon),
			onClick = {},
			shape = pos.shape,
			enabled = false,
		)
	}
}
