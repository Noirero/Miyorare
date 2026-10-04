package org.koitharu.kotatsu.sync.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.settings.SettingsActivity
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.CategoryPalette
import org.koitharu.kotatsu.settings.compose.MiyorareTheme
import org.koitharu.kotatsu.settings.compose.NavigationSettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.sync.library.LibrarySyncConnectionStatus
import org.koitharu.kotatsu.sync.library.LibrarySyncServiceId

@AndroidEntryPoint
class LibrarySyncHubFragment : BaseComposeSettingsFragment(R.string.library_sync) {

	private val viewModel: LibrarySyncViewModel by viewModels()

	override fun onResume() {
		super.onResume()
		viewModel.refresh()
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View =
		ComposeView(requireContext()).apply {
			setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
			setContent {
				MiyorareTheme {
					val services by viewModel.services.collectAsState()
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
								serviceItem(
									LibrarySyncServiceId.MANGADEX,
									services.firstOrNull { it.id == LibrarySyncServiceId.MANGADEX },
								) { id ->
									(activity as? SettingsActivity)?.openFragment(
										LibrarySyncDetailFragment::class.java,
										Bundle().apply { putString("service", id.name) },
										isFromRoot = false,
									)
								}
								serviceItem(
									LibrarySyncServiceId.MANGAUPDATES,
									services.firstOrNull {
										it.id == LibrarySyncServiceId.MANGAUPDATES
									},
								) { id ->
									(activity as? SettingsActivity)?.openFragment(
										LibrarySyncDetailFragment::class.java,
										Bundle().apply { putString("service", id.name) },
										isFromRoot = false,
									)
								}
								serviceItem(
									LibrarySyncServiceId.ANILIST,
									services.firstOrNull { it.id == LibrarySyncServiceId.ANILIST },
								) { id ->
									(activity as? SettingsActivity)?.openFragment(
										LibrarySyncDetailFragment::class.java,
										Bundle().apply { putString("service", id.name) },
										isFromRoot = false,
									)
								}
								serviceItem(
									LibrarySyncServiceId.KITSU,
									services.firstOrNull { it.id == LibrarySyncServiceId.KITSU },
								) { id ->
									(activity as? SettingsActivity)?.openFragment(
										LibrarySyncDetailFragment::class.java,
										Bundle().apply { putString("service", id.name) },
										isFromRoot = false,
									)
								}
							}
						}

						item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
						item {
							SettingsGroup(title = stringResource(R.string.library_sync_novel)) {
								serviceItem(
									LibrarySyncServiceId.NOVELUPDATES,
									services.firstOrNull {
										it.id == LibrarySyncServiceId.NOVELUPDATES
									},
								) { id ->
									(activity as? SettingsActivity)?.openFragment(
										LibrarySyncDetailFragment::class.java,
										Bundle().apply { putString("service", id.name) },
										isFromRoot = false,
									)
								}
								serviceItem(
									LibrarySyncServiceId.RANOBEDB,
									services.firstOrNull { it.id == LibrarySyncServiceId.RANOBEDB },
								) { id ->
									(activity as? SettingsActivity)?.openFragment(
										LibrarySyncDetailFragment::class.java,
										Bundle().apply { putString("service", id.name) },
										isFromRoot = false,
									)
								}
							}
						}
						item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
					}
				}
			}
		}
}

private fun org.koitharu.kotatsu.settings.compose.SettingsGroupScope.serviceItem(
	id: LibrarySyncServiceId,
	state: LibraryServiceUi?,
	open: (LibrarySyncServiceId) -> Unit,
) {
	item { pos ->
		val blocked = state?.status == LibrarySyncConnectionStatus.BLOCKED
		NavigationSettingsItem(
			title = stringResource(serviceTitle(id)),
			subtitle =
				if (blocked)
					stringResource(R.string.library_sync_blocked)
				else
					state?.let {
						statusText(it.status, it.running) +
							(it.lastSync?.let { at -> " · " + formatSyncTime(at) } ?: "")
					} ?: stringResource(R.string.library_sync_loading),
			onClick = { open(id) },
			shape = pos.shape,
			enabled = state != null,
		)
	}
}

internal fun serviceTitle(id: LibrarySyncServiceId): Int =
	when (id) {
		LibrarySyncServiceId.MANGADEX -> R.string.library_sync_mangadex
		LibrarySyncServiceId.MANGAUPDATES -> R.string.library_sync_mangaupdates
		LibrarySyncServiceId.ANILIST -> R.string.library_sync_anilist
		LibrarySyncServiceId.KITSU -> R.string.library_sync_kitsu
		LibrarySyncServiceId.NOVELUPDATES -> R.string.library_sync_novelupdates
		LibrarySyncServiceId.RANOBEDB -> R.string.library_sync_ranobedb
	}

@androidx.compose.runtime.Composable
internal fun statusText(status: LibrarySyncConnectionStatus, running: Boolean): String =
	stringResource(
		if (running) R.string.library_sync_running
		else
			when (status) {
				LibrarySyncConnectionStatus.CONNECTED -> R.string.library_sync_connected
				LibrarySyncConnectionStatus.DISCONNECTED -> R.string.library_sync_disconnected
				LibrarySyncConnectionStatus.SYNCING -> R.string.library_sync_running
				LibrarySyncConnectionStatus.ERROR -> R.string.library_sync_error
				LibrarySyncConnectionStatus.BLOCKED -> R.string.library_sync_blocked
			}
	)

internal fun formatSyncTime(at: java.time.Instant): String =
	java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.SHORT)
		.withZone(java.time.ZoneId.systemDefault())
		.format(at)
