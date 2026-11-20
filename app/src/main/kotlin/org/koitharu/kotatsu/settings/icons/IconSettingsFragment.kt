package org.koitharu.kotatsu.settings.icons

import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.icons.IconPack
import org.koitharu.kotatsu.core.icons.InstalledIconPack
import org.koitharu.kotatsu.main.ui.nav.rememberAnyDrawablePainter
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

private val MODEL_REGEX = Regex("""^Model\s+(\d+)\s*·\s*(.+)$""")

private val MODEL_1_ACCENT = Color(0xFFA34D28)
private val MODEL_2_ACCENT = Color(0xFF3E6B8F)
private val MODEL_3_ACCENT = Color(0xFF7A4F9D)

private val BUILTIN_PREVIEW_KEYS = listOf(
	"ic_action_skip",
	"ic_add",
	"ic_alert_outline",
	"ic_app_update",
	"ic_appearance",
	"ic_arrow_forward",
	"ic_auth_key_large",
	"ic_auto_detect",
	"ic_auto_stories",
	"ic_backup_restore",
)

/** One row of the merged list: a catalog pack and/or its installed copy. */
private class PackEntry(
	val catalogPack: IconPack?,
	val installedPack: InstalledIconPack?,
) {
	val id: String get() = catalogPack?.id ?: installedPack!!.pack.id

	/** Display data comes from the catalog when available (names stay current). */
	val displayPack: IconPack get() = catalogPack ?: installedPack!!.pack

	/** Version and file data describe what is actually on the device. */
	val metaPack: IconPack get() = installedPack?.pack ?: displayPack

	val isInstalled: Boolean get() = installedPack != null

	val modelNumber: Int?
		get() = MODEL_REGEX.matchEntire(displayPack.name)?.groupValues?.get(1)?.toIntOrNull()

	val modelLabel: String?
		get() = modelNumber?.let { "Model $it" }

	val shortName: String
		get() = MODEL_REGEX.matchEntire(displayPack.name)?.groupValues?.get(2) ?: displayPack.name
}

private fun buildEntries(
	catalog: List<IconPack>,
	installed: List<InstalledIconPack>,
): List<PackEntry> {
	val installedById = installed.associateBy { it.pack.id }
	val result = ArrayList<PackEntry>()
	val seen = HashSet<String>()
	for (pack in catalog) {
		result += PackEntry(catalogPack = pack, installedPack = installedById[pack.id])
		seen += pack.id
	}
	for (pack in installed) {
		if (pack.pack.id !in seen) {
			result += PackEntry(catalogPack = null, installedPack = pack)
		}
	}
	return result
}

@Composable
private fun modelAccent(modelNumber: Int?): Color = when (modelNumber) {
	1 -> MODEL_1_ACCENT
	2 -> MODEL_2_ACCENT
	3 -> MODEL_3_ACCENT
	else -> MaterialTheme.colorScheme.primary
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IconSettingsScreen(viewModel: IconSettingsViewModel) {
	val catalog by viewModel.catalog.collectAsState()
	val installed by viewModel.installed.collectAsState()
	val activePack by viewModel.activePack.collectAsState()
	val busy by viewModel.busyPackIds.collectAsState()
	val installProgress by viewModel.installProgress.collectAsState()
	val catalogLoaded by viewModel.catalogLoaded.collectAsState()
	val catalogLoading by viewModel.catalogLoading.collectAsState()
	val error by viewModel.errorMessage.collectAsState()
	val snackbar = remember { SnackbarHostState() }
	var pendingRemoval by remember { mutableStateOf<InstalledIconPack?>(null) }
	var selectedPackId by remember { mutableStateOf<String?>(null) }

	LaunchedEffect(error) {
		if (error != null) {
			snackbar.showSnackbar(error.orEmpty())
			viewModel.consumeError()
		}
	}

	val entries = remember(catalog, installed) { buildEntries(catalog, installed) }
	val groups = remember(entries) {
		entries.groupBy { it.modelNumber }
			.entries.sortedBy { it.key ?: Int.MAX_VALUE }
	}
	val activeEntry = activePack?.let { active ->
		entries.firstOrNull { it.id == active.pack.id }
			?: PackEntry(catalogPack = null, installedPack = active)
	}
	val selectedEntry = selectedPackId?.let { id -> entries.firstOrNull { it.id == id } }

	LaunchedEffect(selectedPackId, selectedEntry) {
		if (selectedPackId != null && selectedEntry == null) {
			selectedPackId = null
		}
	}

	Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
		Column(modifier = Modifier.fillMaxSize().widthIn(max = 840.dp)) {
			LazyColumn(
				modifier = Modifier.fillMaxSize(),
				contentPadding = PaddingValues(16.dp),
				verticalArrangement = Arrangement.spacedBy(8.dp),
			) {
				item {
					if (activeEntry != null) {
						ActivePackHero(
							entry = activeEntry,
							glyphs = remember(activeEntry.id) { viewModel.glyphs(activeEntry.installedPack!!) },
							onClick = { selectedPackId = activeEntry.id },
						)
					} else {
						BuiltInHero()
					}
				}
				item {
					BuiltInIconsRow(
						selected = activePack == null,
						onClick = { viewModel.activate(null) },
					)
				}
				when {
					catalogLoading && entries.isEmpty() -> item {
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

					else -> {
						for ((modelNumber, groupEntries) in groups) {
							item {
								SectionTitle(
									text = modelNumber?.let { "Model $it" }
										?: stringResource(R.string.icon_model_other),
									color = modelAccent(modelNumber),
								)
							}
							items(groupEntries, key = { "pack-${it.id}" }) { entry ->
								if (entry.isInstalled) {
									InstalledPackCard(
										entry = entry,
										isActive = activePack?.pack?.id == entry.id,
										glyphs = remember(entry.id) { viewModel.glyphs(entry.installedPack!!) },
										onClick = { selectedPackId = entry.id },
									)
								} else {
									CatalogPackCard(
										entry = entry,
										isBusy = entry.id in busy,
										progress = installProgress[entry.id],
										onClick = { selectedPackId = entry.id },
										onDownload = { entry.catalogPack?.let { viewModel.install(it) } },
									)
								}
							}
						}
						if (catalogLoaded && catalog.isEmpty() && installed.isEmpty()) {
							item {
								CatalogMessageRow(
									message = stringResource(R.string.icon_catalog_empty),
									onRetry = viewModel::refreshCatalog,
								)
							}
						} else if (!catalogLoaded && !catalogLoading) {
							item {
								CatalogMessageRow(
									message = stringResource(R.string.icon_catalog_load_failed),
									onRetry = viewModel::refreshCatalog,
								)
							}
						}
					}
				}
			}
		}
		SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
	}

	selectedEntry?.let { entry ->
		ModalBottomSheet(
			onDismissRequest = { selectedPackId = null },
			sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
		) {
			PackDetailSheet(
				entry = entry,
				isActive = activePack?.pack?.id == entry.id,
				isBusy = entry.id in busy,
				progress = installProgress[entry.id],
				viewModel = viewModel,
				onDownload = { entry.catalogPack?.let { viewModel.install(it) } },
				onUse = {
					viewModel.activate(entry.id)
					selectedPackId = null
				},
				onDelete = {
					pendingRemoval = entry.installedPack
					selectedPackId = null
				},
			)
		}
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
private fun SectionTitle(text: String, color: Color = MaterialTheme.colorScheme.primary) {
	Text(
		text = text.uppercase(),
		style = MaterialTheme.typography.titleSmall,
		color = color,
		modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
	)
}

@Composable
private fun StatusBadge(text: String, color: Color, filled: Boolean) {
	Surface(
		shape = RoundedCornerShape(999.dp),
		color = if (filled) color else Color.Transparent,
		border = if (filled) null else BorderStroke(1.5.dp, color),
	) {
		Text(
			text = text,
			style = MaterialTheme.typography.labelMedium,
			color = if (filled) Color.White else color,
			modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
		)
	}
}

@Composable
private fun PackMetaText(pack: IconPack, modifier: Modifier = Modifier) {
	val context = LocalContext.current
	Text(
		text = stringResource(R.string.icon_pack_meta, pack.files.size, pack.version) +
			" · " + Formatter.formatShortFileSize(context, pack.totalSizeBytes),
		style = MaterialTheme.typography.bodySmall,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
		modifier = modifier,
	)
}

@Composable
private fun GlyphRow(glyphs: Map<String, ImageBitmap>, count: Int, size: Int) {
	Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
		glyphs.entries.sortedBy { it.key }.take(count).forEach { (_, bitmap) ->
			Image(
				bitmap = bitmap,
				contentDescription = null,
				modifier = Modifier.size(size.dp),
				colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
			)
		}
	}
}

@Composable
private fun ActivePackHero(
	entry: PackEntry,
	glyphs: Map<String, ImageBitmap>,
	onClick: () -> Unit,
) {
	val accent = modelAccent(entry.modelNumber)
	Surface(
		onClick = onClick,
		shape = RoundedCornerShape(20.dp),
		color = accent.copy(alpha = 0.15f),
		modifier = Modifier.fillMaxWidth(),
	) {
		Column(modifier = Modifier.padding(16.dp)) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Column(modifier = Modifier.weight(1f)) {
					Text(
						text = entry.shortName,
						style = MaterialTheme.typography.titleLarge,
					)
					Text(
						text = listOfNotNull(
							entry.modelLabel,
							stringResource(
								R.string.icon_pack_meta,
								entry.metaPack.files.size,
								entry.metaPack.version,
							),
						).joinToString(" · "),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
				StatusBadge(
					text = stringResource(R.string.icon_badge_in_use),
					color = accent,
					filled = true,
				)
			}
			if (glyphs.isNotEmpty()) {
				Spacer(Modifier.height(12.dp))
				GlyphRow(glyphs = glyphs, count = 10, size = 26)
			}
		}
	}
}

@Composable
private fun BuiltInHero() {
	val context = LocalContext.current
	val accent = MaterialTheme.colorScheme.primary
	Surface(
		shape = RoundedCornerShape(20.dp),
		color = accent.copy(alpha = 0.13f),
		modifier = Modifier.fillMaxWidth(),
	) {
		Column(modifier = Modifier.padding(16.dp)) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Column(modifier = Modifier.weight(1f)) {
					Text(
						text = stringResource(R.string.icon_builtin_title),
						style = MaterialTheme.typography.titleLarge,
					)
					Text(
						text = stringResource(R.string.icon_builtin_summary),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
				StatusBadge(
					text = stringResource(R.string.icon_badge_in_use),
					color = accent,
					filled = true,
				)
			}
			Spacer(Modifier.height(12.dp))
			Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
				for (key in BUILTIN_PREVIEW_KEYS) {
					val resId = remember(key) {
						context.resources.getIdentifier(key, "drawable", context.packageName)
					}
					if (resId != 0) {
						Icon(
							painter = rememberAnyDrawablePainter(resId),
							contentDescription = null,
							modifier = Modifier.size(26.dp),
							tint = MaterialTheme.colorScheme.onSurface,
						)
					}
				}
			}
		}
	}
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
private fun InstalledPackCard(
	entry: PackEntry,
	isActive: Boolean,
	glyphs: Map<String, ImageBitmap>,
	onClick: () -> Unit,
) {
	val accent = modelAccent(entry.modelNumber)
	Surface(
		onClick = onClick,
		shape = RoundedCornerShape(16.dp),
		color = MaterialTheme.colorScheme.surfaceContainerLow,
		border = if (isActive) BorderStroke(2.dp, accent) else null,
		modifier = Modifier.fillMaxWidth(),
	) {
		Column(modifier = Modifier.padding(12.dp)) {
			if (glyphs.isNotEmpty()) {
				Box(
					modifier = Modifier.fillMaxWidth()
						.background(accent.copy(alpha = 0.13f), RoundedCornerShape(12.dp))
						.padding(horizontal = 10.dp, vertical = 9.dp),
				) {
					GlyphRow(glyphs = glyphs, count = 8, size = 24)
				}
				Spacer(Modifier.height(10.dp))
			}
			Row(verticalAlignment = Alignment.CenterVertically) {
				Column(modifier = Modifier.weight(1f)) {
					Text(
						text = entry.shortName,
						style = MaterialTheme.typography.titleMedium,
					)
					PackMetaText(entry.metaPack)
				}
				if (isActive) {
					StatusBadge(
						text = stringResource(R.string.icon_badge_in_use),
						color = accent,
						filled = true,
					)
				} else {
					StatusBadge(
						text = stringResource(R.string.icon_installed),
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						filled = false,
					)
				}
			}
		}
	}
}

@Composable
private fun CatalogPackCard(
	entry: PackEntry,
	isBusy: Boolean,
	progress: Pair<Int, Int>?,
	onClick: () -> Unit,
	onDownload: () -> Unit,
) {
	Surface(
		onClick = onClick,
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
					text = entry.shortName,
					style = MaterialTheme.typography.titleMedium,
				)
				PackMetaText(entry.metaPack)
			}
			Spacer(Modifier.width(12.dp))
			if (isBusy) {
				Row(verticalAlignment = Alignment.CenterVertically) {
					CircularProgressIndicator(modifier = Modifier.size(20.dp))
					if (progress != null) {
						Spacer(Modifier.width(8.dp))
						Text(
							text = "${progress.first}/${progress.second}",
							style = MaterialTheme.typography.labelMedium,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
				}
			} else {
				Button(onClick = onDownload) {
					Text(stringResource(R.string.icon_download))
				}
			}
		}
	}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PackDetailSheet(
	entry: PackEntry,
	isActive: Boolean,
	isBusy: Boolean,
	progress: Pair<Int, Int>?,
	viewModel: IconSettingsViewModel,
	onDownload: () -> Unit,
	onUse: () -> Unit,
	onDelete: () -> Unit,
) {
	val accent = modelAccent(entry.modelNumber)
	Column(
		modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
	) {
		Text(
			text = entry.shortName,
			style = MaterialTheme.typography.titleLarge,
		)
		Text(
			text = listOfNotNull(
				entry.modelLabel,
				stringResource(
					R.string.icon_pack_meta,
					entry.metaPack.files.size,
					entry.metaPack.version,
				),
			).joinToString(" · "),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		if (entry.displayPack.description.isNotEmpty()) {
			Spacer(Modifier.height(8.dp))
			Text(
				text = entry.displayPack.description,
				style = MaterialTheme.typography.bodyMedium,
			)
		}
		entry.installedPack?.let { installedPack ->
			val glyphs = remember(installedPack.pack.id) { viewModel.glyphs(installedPack) }
			if (glyphs.isNotEmpty()) {
				Spacer(Modifier.height(14.dp))
				Box(
					modifier = Modifier.fillMaxWidth()
						.background(accent.copy(alpha = 0.13f), RoundedCornerShape(12.dp))
						.padding(horizontal = 10.dp, vertical = 10.dp),
				) {
					FlowRow(
						horizontalArrangement = Arrangement.spacedBy(10.dp),
						verticalArrangement = Arrangement.spacedBy(10.dp),
						maxItemsInEachRow = 8,
					) {
						glyphs.entries.sortedBy { it.key }.take(24).forEach { (_, bitmap) ->
							Image(
								bitmap = bitmap,
								contentDescription = null,
								modifier = Modifier.size(26.dp),
								colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
							)
						}
					}
				}
			}
		}
		Spacer(Modifier.height(18.dp))
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			when {
				isBusy -> Row(verticalAlignment = Alignment.CenterVertically) {
					CircularProgressIndicator(modifier = Modifier.size(20.dp))
					if (progress != null) {
						Spacer(Modifier.width(10.dp))
						Text(
							text = stringResource(R.string.icon_downloading, progress.first, progress.second),
							style = MaterialTheme.typography.labelLarge,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
				}

				isActive -> StatusBadge(
					text = stringResource(R.string.icon_badge_in_use),
					color = accent,
					filled = true,
				)

				entry.isInstalled -> Button(onClick = onUse) {
					Text(stringResource(R.string.icon_use_pack))
				}

				else -> Button(onClick = onDownload) {
					Text(stringResource(R.string.icon_download))
				}
			}
			Spacer(Modifier.weight(1f))
			if (entry.isInstalled) {
				TextButton(onClick = onDelete) {
					Text(
						text = stringResource(R.string.icon_delete),
						color = MaterialTheme.colorScheme.error,
					)
				}
			}
		}
		Spacer(Modifier.height(24.dp))
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
