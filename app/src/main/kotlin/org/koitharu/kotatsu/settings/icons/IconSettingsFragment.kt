package org.koitharu.kotatsu.settings.icons

import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.icons.IconPack
import org.koitharu.kotatsu.core.icons.InstalledIconPack
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.MiyorareTheme

@AndroidEntryPoint
class IconSettingsFragment : BaseComposeSettingsFragment(R.string.icon_title) {

	private val viewModel: IconSettingsViewModel by viewModels()

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			MiyorareTheme {
				IconSettingsScreen(viewModel)
			}
		}
	}
}

@Composable
private fun IconSettingsScreen(viewModel: IconSettingsViewModel) {
	val catalog by viewModel.catalog.collectAsState()
	val installed by viewModel.installed.collectAsState()
	val activePack by viewModel.activePack.collectAsState()
	val busy by viewModel.busyPackIds.collectAsState()
	val catalogLoaded by viewModel.catalogLoaded.collectAsState()
	val catalogLoading by viewModel.catalogLoading.collectAsState()
	val error by viewModel.errorMessage.collectAsState()
	val snackbar = remember { SnackbarHostState() }
	var pendingRemoval by remember { mutableStateOf<InstalledIconPack?>(null) }

	LaunchedEffect(error) {
		if (error != null) {
			snackbar.showSnackbar(error.orEmpty())
			viewModel.consumeError()
		}
	}

	val installedIds = installed.map { it.pack.id }.toSet()

	Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
		Column(modifier = Modifier.fillMaxSize().widthIn(max = 840.dp)) {
			LazyColumn(
				modifier = Modifier.fillMaxSize(),
				contentPadding = PaddingValues(16.dp),
				verticalArrangement = Arrangement.spacedBy(8.dp),
			) {
				item {
					SectionTitle(stringResource(R.string.icon_section_in_use))
				}
				item {
					BuiltInIconsRow(
						selected = activePack == null,
						onClick = { viewModel.activate(null) },
					)
				}
				items(installed, key = { "installed-${it.pack.id}" }) { pack ->
					InstalledPackRow(
						pack = pack,
						selected = activePack?.pack?.id == pack.pack.id,
						glyphs = remember(pack.pack.id) { viewModel.glyphs(pack) },
						onClick = { viewModel.activate(pack.pack.id) },
						onRemove = { pendingRemoval = pack },
					)
				}
				item {
					Spacer(Modifier.height(8.dp))
					SectionTitle(stringResource(R.string.icon_section_catalog))
				}
				when {
					catalogLoading && catalog.isEmpty() -> item {
						Row(
							modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
							verticalAlignment = Alignment.CenterVertically,
						) {
							CircularProgressIndicator(modifier = Modifier.size(20.dp))
							Spacer(Modifier.width(12.dp))
							Text(
								text = stringResource(R.string.icon_catalog_loading),
								style = MaterialTheme.typography.bodyMedium,
							)
						}
					}

					catalogLoaded && catalog.isEmpty() -> item {
						CatalogMessageRow(
							message = stringResource(R.string.icon_catalog_empty),
							onRetry = viewModel::refreshCatalog,
						)
					}

					!catalogLoaded && !catalogLoading -> item {
						CatalogMessageRow(
							message = stringResource(R.string.icon_catalog_load_failed),
							onRetry = viewModel::refreshCatalog,
						)
					}

					else -> items(catalog, key = { "catalog-${it.id}" }) { pack ->
						CatalogPackRow(
							pack = pack,
							isInstalled = pack.id in installedIds,
							isBusy = pack.id in busy,
							onDownload = { viewModel.install(pack) },
						)
					}
				}
			}
		}
		SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
	}

	pendingRemoval?.let { pack ->
		AlertDialog(
			onDismissRequest = { pendingRemoval = null },
			title = { Text(stringResource(R.string.icon_remove_title)) },
			text = { Text(stringResource(R.string.icon_remove_confirmation, pack.pack.name)) },
			confirmButton = {
				TextButton(
					onClick = {
						viewModel.uninstall(pack.pack.id)
						pendingRemoval = null
					},
				) {
					Text(stringResource(R.string.icon_delete))
				}
			},
			dismissButton = {
				TextButton(onClick = { pendingRemoval = null }) {
					Text(stringResource(android.R.string.cancel))
				}
			},
		)
	}
}

@Composable
private fun SectionTitle(text: String) {
	Text(
		text = text,
		style = MaterialTheme.typography.titleSmall,
		color = MaterialTheme.colorScheme.primary,
		modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
	)
}

@Composable
private fun BuiltInIconsRow(selected: Boolean, onClick: () -> Unit) {
	Surface(
		shape = RoundedCornerShape(16.dp),
		color = MaterialTheme.colorScheme.surfaceContainerLow,
		modifier = Modifier.fillMaxWidth().selectable(
			selected = selected,
			role = Role.RadioButton,
			onClick = onClick,
		),
	) {
		Row(
			modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			RadioButton(selected = selected, onClick = null)
			Spacer(Modifier.width(8.dp))
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = stringResource(R.string.icon_builtin_title),
					style = MaterialTheme.typography.titleMedium,
				)
				Text(
					text = stringResource(R.string.icon_builtin_summary),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
	}
}

@Composable
private fun InstalledPackRow(
	pack: InstalledIconPack,
	selected: Boolean,
	glyphs: Map<String, ImageBitmap>,
	onClick: () -> Unit,
	onRemove: () -> Unit,
) {
	Surface(
		shape = RoundedCornerShape(16.dp),
		color = MaterialTheme.colorScheme.surfaceContainerLow,
		modifier = Modifier.fillMaxWidth().selectable(
			selected = selected,
			role = Role.RadioButton,
			onClick = onClick,
		),
	) {
		Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				RadioButton(selected = selected, onClick = null)
				Spacer(Modifier.width(8.dp))
				Column(modifier = Modifier.weight(1f)) {
					Text(
						text = pack.pack.name,
						style = MaterialTheme.typography.titleMedium,
					)
					Text(
						text = stringResource(
							R.string.icon_pack_meta,
							pack.pack.files.size,
							pack.pack.version,
						),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
				IconButton(onClick = onRemove) {
					Icon(
						painter = painterResource(R.drawable.ic_delete),
						contentDescription = stringResource(R.string.icon_delete),
					)
				}
			}
			if (glyphs.isNotEmpty()) {
				Row(
					modifier = Modifier.padding(start = 48.dp, top = 6.dp, bottom = 2.dp),
					horizontalArrangement = Arrangement.spacedBy(10.dp),
				) {
					glyphs.entries.sortedBy { it.key }.take(8).forEach { (_, bitmap) ->
						Image(
							bitmap = bitmap,
							contentDescription = null,
							modifier = Modifier.size(22.dp),
							colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant),
						)
					}
				}
			}
		}
	}
}

@Composable
private fun CatalogPackRow(
	pack: IconPack,
	isInstalled: Boolean,
	isBusy: Boolean,
	onDownload: () -> Unit,
) {
	val context = LocalContext.current
	Surface(
		shape = RoundedCornerShape(16.dp),
		color = MaterialTheme.colorScheme.surfaceContainerLow,
		modifier = Modifier.fillMaxWidth(),
	) {
		Row(
			modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = pack.name,
					style = MaterialTheme.typography.titleMedium,
				)
				if (pack.description.isNotEmpty()) {
					Text(
						text = pack.description,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
				Text(
					text = stringResource(
						R.string.icon_pack_meta,
						pack.files.size,
						pack.version,
					) + " · " + Formatter.formatShortFileSize(context, pack.totalSizeBytes),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
			Spacer(Modifier.width(12.dp))
			when {
				isBusy -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
				isInstalled -> Text(
					text = stringResource(R.string.icon_installed),
					style = MaterialTheme.typography.labelLarge,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)

				else -> Button(onClick = onDownload) {
					Text(stringResource(R.string.icon_download))
				}
			}
		}
	}
}

@Composable
private fun CatalogMessageRow(message: String, onRetry: () -> Unit) {
	Row(
		modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			text = message,
			style = MaterialTheme.typography.bodyMedium,
			modifier = Modifier.weight(1f),
		)
		TextButton(onClick = onRetry) {
			Text(stringResource(R.string.icon_retry))
		}
	}
}
