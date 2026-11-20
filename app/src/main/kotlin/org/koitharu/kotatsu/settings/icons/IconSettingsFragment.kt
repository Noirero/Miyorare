package org.koitharu.kotatsu.settings.icons

import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
private val MODEL_4_ACCENT = Color(0xFFC2487D)
private val MODEL_5_ACCENT = Color(0xFF0E7C9C)

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
	4 -> MODEL_4_ACCENT
	5 -> MODEL_5_ACCENT
	else -> MaterialTheme.colorScheme.primary
}

private const val BUILTIN_PREVIEW_ID = ""

@Composable
private fun IconSettingsScreen(viewModel: IconSettingsViewModel) {
	val catalog by viewModel.catalog.collectAsState()
	val installed by viewModel.installed.collectAsState()
	val activePack by viewModel.activePack.collectAsState()
	val busy by viewModel.busyPackIds.collectAsState()
	val installProgress by viewModel.installProgress.collectAsState()
	val catalogLoaded by viewModel.catalogLoaded.collectAsState()
	val catalogLoading by viewModel.catalogLoading.collectAsState()
	val previewGlyphs by viewModel.previewGlyphs.collectAsState()
	val previewLoading by viewModel.previewLoadingIds.collectAsState()
	val error by viewModel.errorMessage.collectAsState()
	val snackbar = remember { SnackbarHostState() }
	var pendingRemoval by remember { mutableStateOf<InstalledIconPack?>(null) }

	// The pack the pinned preview at the top shows. Until the user taps a
	// card the preview follows the active pack; a tap pins the preview to
	// that card instead (BUILTIN_PREVIEW_ID pins the built-in icons).
	var previewTouched by remember { mutableStateOf(false) }
	var previewId by remember { mutableStateOf<String?>(null) }

	// Collapsed model groups, keyed by model number (-1 for packs whose
	// name carries no model). Starts with only the active pack's group open.
	var collapsedModels by rememberSaveable { mutableStateOf<List<Int>>(emptyList()) }
	var groupsInitialized by rememberSaveable { mutableStateOf(false) }

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

	LaunchedEffect(groups) {
		if (!groupsInitialized && groups.isNotEmpty()) {
			val openKey = (activeEntry?.modelNumber ?: groups.first().key) ?: -1
			collapsedModels = groups.map { (it.key ?: -1) }.filter { it != openKey }
			groupsInitialized = true
		}
	}

	val targetId = if (previewTouched) previewId else activeEntry?.id
	val targetEntry = when {
		targetId == null || targetId == BUILTIN_PREVIEW_ID -> null
		else -> entries.firstOrNull { it.id == targetId } ?: activeEntry
	}
	val targetIsActive = if (targetEntry == null) {
		activePack == null
	} else {
		targetEntry.id == activePack?.pack?.id
	}
	val targetInstalledPack = targetEntry?.installedPack
	val targetInstalledGlyphs = remember(targetInstalledPack?.pack?.id) {
		targetInstalledPack?.let { viewModel.glyphs(it) } ?: emptyMap()
	}
	val targetGlyphs = if (targetEntry?.isInstalled == true) {
		targetInstalledGlyphs
	} else {
		targetEntry?.let { previewGlyphs[it.id] } ?: emptyMap()
	}
	val targetPreviewLoading = targetEntry != null && !targetEntry.isInstalled &&
		targetEntry.id in previewLoading

	// Fetch the sample glyphs as soon as a catalog pack becomes the preview
	// target; the hero shows a spinner until they arrive.
	LaunchedEffect(targetEntry?.id) {
		targetEntry?.catalogPack?.let { viewModel.preview(it) }
	}

	fun showPreview(id: String?) {
		previewTouched = true
		previewId = id
	}

	Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
		Column(modifier = Modifier.fillMaxSize().widthIn(max = 840.dp)) {
			PackPreviewHero(
				entry = targetEntry,
				glyphs = targetGlyphs,
				isActive = targetIsActive,
				isBusy = targetEntry != null && targetEntry.id in busy,
				progress = targetEntry?.let { installProgress[it.id] },
				previewLoading = targetPreviewLoading,
				onDownload = { targetEntry?.catalogPack?.let { viewModel.install(it) } },
				onUse = { viewModel.activate(targetEntry?.id) },
				modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
			)
			LazyColumn(
				modifier = Modifier.weight(1f).fillMaxWidth(),
				contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
				verticalArrangement = Arrangement.spacedBy(8.dp),
			) {
				item {
					BuiltInIconsRow(
						selected = activePack == null,
						onClick = {
							showPreview(BUILTIN_PREVIEW_ID)
							viewModel.activate(null)
						},
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
							val groupKey = modelNumber ?: -1
							val expanded = groupKey !in collapsedModels
							item(key = "model-$groupKey") {
								ModelGroupHeader(
									text = modelNumber?.let { "Model $it" }
										?: stringResource(R.string.icon_model_other),
									color = modelAccent(modelNumber),
									count = groupEntries.size,
									expanded = expanded,
									onClick = {
										collapsedModels = if (expanded) {
											collapsedModels + groupKey
										} else {
											collapsedModels - groupKey
										}
									},
								)
							}
							if (expanded) {
								items(groupEntries, key = { "pack-${it.id}" }) { entry ->
									if (entry.isInstalled) {
										InstalledPackCard(
											entry = entry,
											isActive = activePack?.pack?.id == entry.id,
											isPreviewed = targetEntry?.id == entry.id,
											glyphs = remember(entry.id) { viewModel.glyphs(entry.installedPack!!) },
											onClick = { showPreview(entry.id) },
											onDelete = { pendingRemoval = entry.installedPack },
										)
									} else {
										CatalogPackCard(
											entry = entry,
											isBusy = entry.id in busy,
											progress = installProgress[entry.id],
											isPreviewed = targetEntry?.id == entry.id,
											previewGlyphs = previewGlyphs[entry.id] ?: emptyMap(),
											previewLoading = entry.id in previewLoading,
											onClick = { showPreview(entry.id) },
											onDownload = { entry.catalogPack?.let { viewModel.install(it) } },
										)
									}
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
private fun ModelGroupHeader(
	text: String,
	color: Color,
	count: Int,
	expanded: Boolean,
	onClick: () -> Unit,
) {
	Row(
		modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
			.padding(top = 10.dp, bottom = 2.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			text = text.uppercase(),
			style = MaterialTheme.typography.titleSmall,
			color = color,
		)
		Spacer(Modifier.width(8.dp))
		Text(
			text = count.toString(),
			style = MaterialTheme.typography.labelMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Spacer(Modifier.weight(1f))
		Text(
			text = if (expanded) "▾" else "▸",
			style = MaterialTheme.typography.titleMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
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
private fun PackPreviewHero(
	entry: PackEntry?,
	glyphs: Map<String, ImageBitmap>,
	isActive: Boolean,
	isBusy: Boolean,
	progress: Pair<Int, Int>?,
	previewLoading: Boolean,
	onDownload: () -> Unit,
	onUse: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val context = LocalContext.current
	val accent = entry?.let { modelAccent(it.modelNumber) } ?: MaterialTheme.colorScheme.primary
	Surface(
		shape = RoundedCornerShape(20.dp),
		color = accent.copy(alpha = 0.15f),
		modifier = modifier.fillMaxWidth(),
	) {
		Column(modifier = Modifier.padding(16.dp)) {
			Text(
				text = stringResource(
					if (isActive) R.string.icon_section_in_use else R.string.icon_preview,
				),
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			Row(verticalAlignment = Alignment.CenterVertically) {
				Column(modifier = Modifier.weight(1f)) {
					Text(
						text = entry?.shortName ?: stringResource(R.string.icon_builtin_title),
						style = MaterialTheme.typography.titleLarge,
					)
					Text(
						text = if (entry != null) {
							listOfNotNull(
								entry.modelLabel,
								stringResource(
									R.string.icon_pack_meta,
									entry.metaPack.files.size,
									entry.metaPack.version,
								),
							).joinToString(" · ")
						} else {
							stringResource(R.string.icon_builtin_summary)
						},
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
				when {
					isBusy -> Row(verticalAlignment = Alignment.CenterVertically) {
						CircularProgressIndicator(modifier = Modifier.size(20.dp))
						if (progress != null) {
							Spacer(Modifier.width(8.dp))
							Text(
								text = stringResource(R.string.icon_downloading, progress.first, progress.second),
								style = MaterialTheme.typography.labelMedium,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
					}

					isActive -> StatusBadge(
						text = stringResource(R.string.icon_badge_in_use),
						color = accent,
						filled = true,
					)

					entry == null -> Button(onClick = onUse) {
						Text(stringResource(R.string.icon_use_pack))
					}

					entry.isInstalled -> Button(onClick = onUse) {
						Text(stringResource(R.string.icon_use_pack))
					}

					else -> Button(onClick = onDownload) {
						Text(stringResource(R.string.icon_download))
					}
				}
			}
			Spacer(Modifier.height(12.dp))
			when {
				entry == null -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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

				glyphs.isNotEmpty() -> GlyphRow(glyphs = glyphs, count = 10, size = 26)

				previewLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
					CircularProgressIndicator(modifier = Modifier.size(18.dp))
					Spacer(Modifier.width(10.dp))
					Text(
						text = stringResource(R.string.icon_catalog_loading),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
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
	isPreviewed: Boolean,
	glyphs: Map<String, ImageBitmap>,
	onClick: () -> Unit,
	onDelete: () -> Unit,
) {
	val accent = modelAccent(entry.modelNumber)
	Surface(
		onClick = onClick,
		shape = RoundedCornerShape(16.dp),
		color = MaterialTheme.colorScheme.surfaceContainerLow,
		border = when {
			isActive -> BorderStroke(2.dp, accent)
			isPreviewed -> BorderStroke(1.5.dp, accent.copy(alpha = 0.65f))
			else -> null
		},
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
				IconButton(onClick = onDelete) {
					Icon(
						painter = painterResource(R.drawable.ic_delete),
						contentDescription = stringResource(R.string.icon_delete),
						tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
	isPreviewed: Boolean,
	previewGlyphs: Map<String, ImageBitmap>,
	previewLoading: Boolean,
	onClick: () -> Unit,
	onDownload: () -> Unit,
) {
	val accent = modelAccent(entry.modelNumber)
	Surface(
		onClick = onClick,
		shape = RoundedCornerShape(16.dp),
		color = MaterialTheme.colorScheme.surfaceContainerLow,
		border = if (isPreviewed) BorderStroke(1.5.dp, accent.copy(alpha = 0.65f)) else null,
		modifier = Modifier.fillMaxWidth(),
	) {
		Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
			Row(verticalAlignment = Alignment.CenterVertically) {
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
			if (isPreviewed && (previewGlyphs.isNotEmpty() || previewLoading)) {
				Spacer(Modifier.height(10.dp))
				Box(
					modifier = Modifier.fillMaxWidth()
						.background(accent.copy(alpha = 0.13f), RoundedCornerShape(12.dp))
						.padding(horizontal = 10.dp, vertical = 9.dp),
				) {
					if (previewGlyphs.isNotEmpty()) {
						GlyphRow(glyphs = previewGlyphs, count = 8, size = 24)
					} else {
						Row(verticalAlignment = Alignment.CenterVertically) {
							CircularProgressIndicator(modifier = Modifier.size(16.dp))
							Spacer(Modifier.width(8.dp))
							Text(
								text = stringResource(R.string.icon_catalog_loading),
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
					}
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
