package org.koitharu.kotatsu.settings.fonts

import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.fonts.FontPack
import org.koitharu.kotatsu.core.fonts.InstalledFontPack
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.MiyorareTheme

@AndroidEntryPoint
class FontSettingsFragment : BaseComposeSettingsFragment(R.string.font_title) {

	private val viewModel: FontSettingsViewModel by viewModels()

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			MiyorareTheme {
				FontSettingsScreen(viewModel)
			}
		}
	}
}

@Composable
private fun FontSettingsScreen(viewModel: FontSettingsViewModel) {
	val catalog by viewModel.catalog.collectAsState()
	val installed by viewModel.installed.collectAsState()
	val activePack by viewModel.activePack.collectAsState()
	val busy by viewModel.busyPackIds.collectAsState()
	val catalogLoaded by viewModel.catalogLoaded.collectAsState()
	val error by viewModel.errorMessage.collectAsState()
	val snackbar = remember { SnackbarHostState() }

	LaunchedEffect(error) {
		if (error != null) {
			snackbar.showSnackbar(error.orEmpty())
			viewModel.consumeError()
		}
	}

	Column(modifier = Modifier.fillMaxSize()) {
		LazyColumn(
			modifier = Modifier
				.weight(1f)
				.padding(horizontal = 16.dp),
			verticalArrangement = Arrangement.spacedBy(4.dp),
		) {
			item {
				Text(
					text = stringResource(R.string.font_section_in_use),
					style = MaterialTheme.typography.titleSmall,
					color = MaterialTheme.colorScheme.primary,
					modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
				)
			}
			item {
				FontChoiceRow(
					title = stringResource(R.string.font_builtin_title),
					subtitle = stringResource(R.string.font_builtin_summary),
					previewText = null,
					previewFamily = null,
					selected = activePack == null,
					onClick = { viewModel.activate(null) },
				)
			}
			items(installed, key = { "installed-" + it.pack.id }) { pack ->
				FontChoiceRow(
					title = pack.pack.name,
					subtitle = stringResource(R.string.font_installed),
					previewText = pack.pack.previewText,
					previewFamily = remember(pack.pack.id) { viewModel.fontFamily(pack) },
					selected = activePack?.pack?.id == pack.pack.id,
					onClick = { viewModel.activate(pack.pack.id) },
					trailing = {
						IconButton(
							onClick = { viewModel.uninstall(pack.pack.id) },
							enabled = pack.pack.id !in busy,
						) {
							Icon(
								painter = painterResource(R.drawable.ic_delete),
								contentDescription = stringResource(R.string.font_delete),
							)
						}
					},
				)
			}
			item {
				Text(
					text = stringResource(R.string.font_section_catalog),
					style = MaterialTheme.typography.titleSmall,
					color = MaterialTheme.colorScheme.primary,
					modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
				)
			}
			if (catalog.isEmpty()) {
				item {
					if (catalogLoaded) {
						Text(
							text = stringResource(R.string.font_catalog_empty),
							style = MaterialTheme.typography.bodyMedium,
						)
					} else {
						Row(verticalAlignment = Alignment.CenterVertically) {
							CircularProgressIndicator()
							Text(
								text = stringResource(R.string.font_catalog_loading),
								style = MaterialTheme.typography.bodyMedium,
								modifier = Modifier.padding(start = 12.dp),
							)
						}
						TextButton(onClick = { viewModel.refreshCatalog() }) {
							Text(text = stringResource(R.string.font_retry))
						}
					}
				}
			}
			items(catalog, key = { "catalog-" + it.id }) { pack ->
				val installedPack = installed.firstOrNull { it.pack.id == pack.id }
				CatalogPackRow(
					pack = pack,
					installedPack = installedPack,
					family = installedPack?.let { remember(it.pack.id) { viewModel.fontFamily(it) } },
					busy = pack.id in busy,
					onInstall = { viewModel.install(pack) },
				)
			}
			item { Spacer(Modifier.height(24.dp)) }
		}
		SnackbarHost(hostState = snackbar)
	}
}

@Composable
private fun FontChoiceRow(
	title: String,
	subtitle: String,
	previewText: String?,
	previewFamily: FontFamily?,
	selected: Boolean,
	onClick: () -> Unit,
	trailing: (@Composable () -> Unit)? = null,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onClick)
			.padding(vertical = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		RadioButton(selected = selected, onClick = onClick)
		Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
			Text(text = title, style = MaterialTheme.typography.bodyLarge)
			Text(
				text = subtitle,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			if (!previewText.isNullOrEmpty() && previewFamily != null) {
				Text(
					text = previewText,
					style = MaterialTheme.typography.bodyMedium.copy(fontFamily = previewFamily),
					modifier = Modifier.padding(top = 4.dp),
				)
			}
		}
		if (trailing != null) {
			trailing()
		}
	}
}

@Composable
private fun CatalogPackRow(
	pack: FontPack,
	installedPack: InstalledFontPack?,
	family: FontFamily?,
	busy: Boolean,
	onInstall: () -> Unit,
) {
	val ctx = LocalContext.current
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(vertical = 8.dp),
	) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Column(modifier = Modifier.weight(1f)) {
				Text(text = pack.name, style = MaterialTheme.typography.bodyLarge)
				Text(
					text = listOf(pack.author, pack.licenseName)
						.filter { it.isNotEmpty() }
						.joinToString(" · "),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				if (pack.description.isNotEmpty()) {
					Text(
						text = pack.description,
						style = MaterialTheme.typography.bodyMedium,
						modifier = Modifier.padding(top = 2.dp),
					)
				}
				if (pack.previewText.isNotEmpty() && family != null) {
					Text(
						text = pack.previewText,
						style = MaterialTheme.typography.bodyMedium.copy(fontFamily = family),
						modifier = Modifier.padding(top = 4.dp),
					)
				}
			}
			when {
				busy -> CircularProgressIndicator()
				installedPack != null -> Text(
					text = stringResource(R.string.font_installed),
					style = MaterialTheme.typography.labelLarge,
					color = MaterialTheme.colorScheme.primary,
				)
				else -> Button(onClick = onInstall) {
					Text(
						text = stringResource(
							R.string.font_download_size,
							Formatter.formatShortFileSize(ctx, pack.totalSizeBytes),
						),
					)
				}
			}
		}
	}
}
