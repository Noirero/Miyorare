package org.koitharu.kotatsu.settings.sources.migration

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchAction
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchDiscoveryMode
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchPolicy
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchStatus
import org.koitharu.kotatsu.alternatives.domain.MigrationSourceStrategy
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.extensions.runtime.getExternalExtensionLangCode
import org.koitharu.kotatsu.extensions.runtime.getExternalExtensionLanguageDisplayName
import org.koitharu.kotatsu.extensions.runtime.getExternalExtensionLanguageFlag
import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.explore.ui.adapter.ExploreSourceOrigin
import org.koitharu.kotatsu.explore.ui.adapter.exploreSourceOrigin
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.tsuki.model.TsukiMangaSource
import org.koitharu.kotatsu.lnreader.model.LnMangaSource
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.sources.compat.EhentaiSourceFamily
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra

/**
 * The existing ViewModel and WorkManager remain the only execution authorities.
 * This screen only projects their states into Search -> Review -> Results.
 */
@Composable
internal fun MigrationFlowScreen(
	state: SourceMangaListState,
	imageLoader: ImageLoader,
	onRetry: () -> Unit,
	onSearch: () -> Unit,
	onMigrateReady: () -> Unit,
	onCopyReady: () -> Unit,
	onCancel: () -> Unit,
	onFilter: (MigrationBatchStatus?) -> Unit,
	onToggleSource: (String) -> Unit,
	onSelectAllSources: () -> Unit,
	onClearSources: () -> Unit,
	onMoveSource: (String, Int) -> Unit,
	onStrategy: (MigrationSourceStrategy) -> Unit,
	onExtraSearch: (String) -> Unit,
	onSkipSetup: (Boolean) -> Unit,
	onRetrySources: () -> Unit,
	onMangaClick: (Manga) -> Unit,
	onMangaLongClick: (Manga) -> Unit,
	onMigrateOne: (Long) -> Unit = {},
	onCopyOne: (Long) -> Unit = {},
	onSkipOne: (Long) -> Unit = {},
	onOpenTarget: (Manga) -> Unit = {},
) {
	var step by rememberSaveable { mutableIntStateOf(0) }
	var pendingAction by remember { mutableStateOf<MigrationBatchAction?>(null) }
	var pendingCancellation by remember { mutableStateOf(false) }
	val busy = state.batchBusy || state.scheduling
	BackHandler(enabled = step > 0 && !busy) { step = 0 }

	if (state.isLoading) {
		Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
		return
	}
	if (state.failed && state.manga.isEmpty()) {
		Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
			horizontalAlignment = Alignment.CenterHorizontally) {
			Text("Sesi migrasi tidak dapat dimuat")
			TextButton(onClick = onRetry) { Text(stringResource(R.string.migration_retry)) }
		}
		return
	}

	// Source setup and the live results are the only two navigation surfaces.
	// The same result list holds discovery, manual intervention, progress and completed outcomes.
	when (step) {
		0 -> MigrationSearchSetup(
			state = state,
			enabled = !busy,
			onToggleSource = onToggleSource,
			onSelectAllSources = onSelectAllSources,
			onClearSources = onClearSources,
			onMoveSource = onMoveSource,
			onStrategy = onStrategy,
			onExtraSearch = onExtraSearch,
			onSkipSetup = onSkipSetup,
			onRetrySources = onRetrySources,
			onSearch = { onSearch(); step = 1 },
			onViewProgress = { step = 1 },
		)
		else -> MigrationReviewStep(
			state = state,
			imageLoader = imageLoader,
			onMangaClick = onMangaClick,
			onOpenTarget = onOpenTarget,
			onRetry = { step = 0 },
			onContinue = { pendingAction = MigrationBatchAction.MIGRATE },
			onCopy = { pendingAction = MigrationBatchAction.COPY },
			onCancel = { pendingCancellation = true },
			onMigrateOne = onMigrateOne,
			onCopyOne = onCopyOne,
			onSkipOne = onSkipOne,
		)
	}

	pendingAction?.let { action ->
		val readyIds = MigrationBatchPolicy.readyRequests(
			manga = state.manga, snapshots = state.batchItems,
			discoveryMode = MigrationBatchDiscoveryMode.YOKAI, configKey = state.configKey,
		).mapTo(hashSetOf()) { it.mangaId }
		val ready = state.manga.filter { it.id in readyIds }
		AlertDialog(
			onDismissRequest = { pendingAction = null },
			title = { Text(if (action == MigrationBatchAction.COPY) "Konfirmasi salin" else "Konfirmasi migrasi") },
			text = { Text(
				(if (action == MigrationBatchAction.COPY) "Salin " else "Migrasikan ") +
				ready.size + " manga yang ditemukan?"
			) },
			confirmButton = {
				TextButton(
					enabled = ready.isNotEmpty() && !busy,
					onClick = {
						pendingAction = null
						if (action == MigrationBatchAction.COPY) onCopyReady() else onMigrateReady()
						step = 1
					},
					modifier = Modifier.testTag("migration-batch-confirm"),
				) { Text(if (action == MigrationBatchAction.COPY) "Salin" else "Migrasikan") }
			},
			dismissButton = { TextButton(onClick = { pendingAction = null }) { Text("Batal") } },
		)
	}
	if (pendingCancellation) AlertDialog(
		onDismissRequest = { pendingCancellation = false },
		title = { Text("Hentikan pencarian?") },
		text = { Text("Pekerjaan yang belum selesai akan dibatalkan.") },
		confirmButton = { TextButton(onClick = {
			pendingCancellation = false
			onCancel()
		}) { Text("Hentikan") } },
		dismissButton = { TextButton(onClick = { pendingCancellation = false }) { Text("Batal") } },
	)
}

/**
 * Group by installed package/plugin identity, never by display title or source-language count.
 * The input pool is already resolved from installed runtime providers by the ViewModel.
 */
internal data class MigrationDestinationGroup(
	val key: String,
	val label: String,
	val origin: ExploreSourceOrigin?,
	val sources: List<org.koitharu.kotatsu.parsers.model.MangaSource>,
	/** Non-null for per-language groups of Tsuki/UMA/Gekkoushi providers. */
	val languageCode: String? = null,
)

internal fun destinationGroups(
	sources: List<org.koitharu.kotatsu.parsers.model.MangaSource>,
	context: android.content.Context,
): List<MigrationDestinationGroup> = sources
	.groupBy { source ->
		when (val raw = source.unwrap()) {
			is MihonMangaSource -> "store:" + raw.pkgName
			is TsukiMangaSource -> "language:" + raw.plugin.provider.name + ":" + migrationLanguageCode(raw.language)
			is LnMangaSource -> "novel:" + raw.pluginId
			else -> "source:" + source.name
		}
	}
	.map { (key, variants) ->
		val first = variants.first().unwrap()
		val lang = (first as? TsukiMangaSource)?.let { migrationLanguageCode(it.language) }
		MigrationDestinationGroup(
			key = key,
			label = when {
				lang != null -> migrationLanguageLabel(lang)
				first is MihonMangaSource -> first.displayName
				else -> variants.first().getTitle(context).toString()
			},
			origin = variants.first().exploreSourceOrigin(),
			sources = variants.sortedBy { source ->
				val raw = source.unwrap()
				if (raw is MihonMangaSource) raw.language else source.getTitle(context).toString().lowercase()
			},
			languageCode = lang,
		)
	}

/** Presentation-only language identity; migration selection continues to use source.name. */
internal fun migrationLanguageCode(raw: String): String {
	val normalized = getExternalExtensionLangCode(raw).substringBefore('-').substringBefore('_').lowercase()
	return normalized.takeUnless { it.isBlank() || it in setOf("all", "mul", "und", "other", "global") } ?: "other"
}

internal fun migrationLanguageLabel(code: String): String = when (code) {
	"id" -> "🇮🇩 Indonesia"
	"en" -> "🇬🇧 English"
	"other" -> "🏳️ Other / Global"
	else -> getExternalExtensionLanguageFlag(code) + " " + getExternalExtensionLanguageDisplayName(code)
}

internal fun migrationLanguageRank(code: String?): Int = when (code) {
	"id" -> 0
	"en" -> 1
	"other" -> 999
	else -> 2
}

private fun ExploreSourceOrigin?.migrationTabTitle(): String = when (this) {
	ExploreSourceOrigin.MIYORARE_PACK -> "Miyorare"
	ExploreSourceOrigin.EXTENSION_STORE -> "Store"
	ExploreSourceOrigin.UMA -> "UMA"
	ExploreSourceOrigin.GEKKOUSHI -> "Gekkoushi"
	else -> "Other"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MigrationSearchSetup(
	state: SourceMangaListState,
	enabled: Boolean,
	onToggleSource: (String) -> Unit,
	onSelectAllSources: () -> Unit,
	onClearSources: () -> Unit,
	onMoveSource: (String, Int) -> Unit,
	onStrategy: (MigrationSourceStrategy) -> Unit,
	onExtraSearch: (String) -> Unit,
	onSkipSetup: (Boolean) -> Unit,
	onRetrySources: () -> Unit,
	onSearch: () -> Unit,
	onViewProgress: () -> Unit,
) {
	val context = LocalContext.current
	var showOptions by rememberSaveable { mutableStateOf(false) }
	var activeTab by rememberSaveable { mutableStateOf("Semua") }
	var sortAscending by rememberSaveable { mutableStateOf(true) }
	var expandedKeys by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
	val installed = state.installedSources
	val groups = remember(installed, context) { destinationGroups(installed, context) }
	val eligibleNames = state.availableSources.mapTo(hashSetOf(), MangaSource::name)
	val order = listOf("Miyorare", "Store", "UMA", "Gekkoushi", "Other")
	val tabs = listOf("Semua") + order.filter { name -> groups.any { it.origin.migrationTabTitle() == name } }
	val selectedTab = activeTab.takeIf { it in tabs } ?: "Semua"
	val visibleGroups = groups.filter { selectedTab == "Semua" || it.origin.migrationTabTitle() == selectedTab }
		.let { filtered ->
			if (selectedTab in setOf("Miyorare", "UMA", "Gekkoushi"))
				filtered.sortedWith(compareBy<MigrationDestinationGroup> { migrationLanguageRank(it.languageCode) }
					.thenBy { it.label.lowercase() })
			else if (sortAscending) filtered.sortedBy { it.label.lowercase() }
			else filtered.sortedByDescending { it.label.lowercase() }
		}
	val selectedSet = state.selectedSourceNames.toSet()
	Column(Modifier.fillMaxSize()) {
		Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
			verticalAlignment = Alignment.CenterVertically) {
			Text("Pilih source tujuan", style = MaterialTheme.typography.titleLarge,
				modifier = Modifier.weight(1f))
			TextButton(onClick = { sortAscending = !sortAscending }) {
				Text(if (sortAscending) "A–Z" else "Z–A")
			}
			TextButton(onClick = { showOptions = true }) { Text("Opsi") }
		}
		when {
			state.sourceSelectionLoading -> LinearProgressIndicator(Modifier.fillMaxWidth())
			state.sourceSelectionFailed -> Column(Modifier.padding(16.dp)) {
				Text(stringResource(R.string.migration_source_list_failed))
				TextButton(onClick = onRetrySources) { Text(stringResource(R.string.migration_retry)) }
			}
			groups.isEmpty() -> Text("Tidak ada extension atau plugin terinstal.", modifier = Modifier.padding(16.dp))
			else -> {
				ScrollableTabRow(
					selectedTabIndex = tabs.indexOf(selectedTab),
					edgePadding = 12.dp,
					divider = {},
				) {
					tabs.forEach { tab ->
						val count = groups
							.filter { tab == "Semua" || it.origin.migrationTabTitle() == tab }
							.sumOf { if (it.languageCode == null) 1 else it.sources.size }
						Tab(selected = selectedTab == tab, onClick = { activeTab = tab },
							text = { Text(tab + " (" + count + ")") })
					}
				}
				Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
					horizontalArrangement = Arrangement.spacedBy(8.dp),
					verticalAlignment = Alignment.CenterVertically) {
					TextButton(onClick = onSelectAllSources, enabled = enabled) { Text("Semua") }
					TextButton(onClick = onClearSources, enabled = enabled) { Text("Kosongkan") }
					Text(state.selectedSourceNames.size.toString() + " source dipilih",
						style = MaterialTheme.typography.bodySmall)
				}
				if (eligibleNames.isEmpty()) {
					Text("Tidak ada source kompatibel untuk judul yang dipilih.",
						modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
						color = MaterialTheme.colorScheme.onSurfaceVariant)
				}
				if (state.sourceStrategy == MigrationSourceStrategy.FIRST_MATCH && selectedSet.isNotEmpty()) {
					Text("Angka prioritas menentukan urutan pencarian, terlepas dari urutan A–Z/Z–A.",
						modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant)
				}
				LazyColumn(Modifier.weight(1f),
					contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
					verticalArrangement = Arrangement.spacedBy(4.dp)) {
					items(visibleGroups, key = MigrationDestinationGroup::key) { group ->
						val isExpanded = group.key in expandedKeys
						val eligibleGroup = group.sources.filter { it.name in eligibleNames }
						val allChecked = eligibleGroup.isNotEmpty() && eligibleGroup.all { it.name in selectedSet }
						if (group.sources.size > 1 || group.languageCode != null) {
							Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
								verticalAlignment = Alignment.CenterVertically) {
								Checkbox(checked = allChecked, enabled = enabled && eligibleGroup.isNotEmpty(), onCheckedChange = {
									eligibleGroup.forEach { source ->
										if ((source.name in selectedSet) == allChecked) onToggleSource(source.name)
									}
								})
								Row(Modifier.weight(1f).clickable {
									expandedKeys = ArrayList(expandedKeys.toMutableSet().apply {
										if (!add(group.key)) remove(group.key)
									})
								}.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
									Column(Modifier.weight(1f)) {
										Text(group.label + " (" + group.sources.size + ")",
											maxLines = 2, overflow = TextOverflow.Ellipsis)
										val groupPriorities = eligibleGroup.mapNotNull { source ->
											state.selectedSourceNames.indexOf(source.name).takeIf { it >= 0 }?.plus(1)
										}.sorted()
										if (groupPriorities.isNotEmpty()) Text(
											"Prioritas: " + groupPriorities.joinToString(", "),
											style = MaterialTheme.typography.labelSmall,
											color = MaterialTheme.colorScheme.primary,
											maxLines = 1, overflow = TextOverflow.Ellipsis,
										)
									}
									Text(if (isExpanded) "⌄" else "›", style = MaterialTheme.typography.titleMedium)
								}
							}
						}
						if ((group.languageCode == null && group.sources.size == 1) || isExpanded) {
							val orderedSources = if (sortAscending) group.sources else group.sources.reversed()
							orderedSources.forEach { source ->
								val index = state.selectedSourceNames.indexOf(source.name)
								val sourceEnabled = enabled && source.name in eligibleNames
								val raw = source.unwrap()
								val label = if (raw is MihonMangaSource && group.sources.size > 1)
									raw.displayName + " (" + raw.language + ")"
								else source.getTitle(context).toString()
								Row(Modifier.fillMaxWidth().clickable(enabled = sourceEnabled) {
									onToggleSource(source.name)
								}.padding(start = if (group.sources.size > 1 || group.languageCode != null) 28.dp else 0.dp, top = 4.dp, bottom = 4.dp),
									verticalAlignment = Alignment.CenterVertically) {
									Checkbox(checked = index >= 0, enabled = sourceEnabled,
										onCheckedChange = { onToggleSource(source.name) })
									Column(modifier = Modifier.weight(1f)) {
										Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis)
										if (!sourceEnabled && source.name !in eligibleNames) Text(
											"Tidak kompatibel dengan judul terpilih",
											style = MaterialTheme.typography.labelSmall,
											color = MaterialTheme.colorScheme.onSurfaceVariant,
										)
									}
									if (index >= 0) {
										Text("Prioritas " + (index + 1),
											modifier = Modifier.testTag("migration-priority-" + source.name),
											style = MaterialTheme.typography.labelSmall,
											color = MaterialTheme.colorScheme.primary)
										TextButton(onClick = { onMoveSource(source.name, -1) },
											enabled = enabled && index > 0) { Text("↑") }
										TextButton(onClick = { onMoveSource(source.name, 1) },
											enabled = enabled && index < state.selectedSourceNames.lastIndex) { Text("↓") }
									}
								}
							}
						}
					}
				}
			}
		}
		Surface(tonalElevation = 3.dp) {
			Column(Modifier.fillMaxWidth().padding(12.dp)) {
				if (state.batchBusy) OutlinedButton(onClick = onViewProgress,
					modifier = Modifier.fillMaxWidth()) { Text("Lihat pencarian") }
				Button(onClick = onSearch,
					enabled = enabled && state.manga.isNotEmpty() &&
						state.selectedSourceNames.any { it in eligibleNames } &&
						!state.sourceSelectionLoading && !state.sourceSelectionFailed,
					modifier = Modifier.fillMaxWidth().height(52.dp).testTag("migration-batch-search")) {
					Text("Mulai mencari (" + state.manga.size + ")")
				}
			}
		}
	}
	if (showOptions) {
		ModalBottomSheet(onDismissRequest = { showOptions = false }) {
			Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
				verticalArrangement = Arrangement.spacedBy(10.dp)) {
				Text("Opsi pencarian", style = MaterialTheme.typography.titleLarge)
				for (strategy in MigrationSourceStrategy.entries) {
					Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onStrategy(strategy) },
						verticalAlignment = Alignment.CenterVertically) {
						RadioButton(selected = state.sourceStrategy == strategy,
							onClick = { onStrategy(strategy) }, enabled = enabled)
						Text(if (strategy == MigrationSourceStrategy.FIRST_MATCH) "Gunakan source pertama"
							else "Gunakan yang memiliki chapter terbanyak")
					}
				}
				OutlinedTextField(value = state.extraSearchParams, onValueChange = onExtraSearch,
					enabled = enabled, label = { Text("Parameter pencarian tambahan") },
					modifier = Modifier.fillMaxWidth())
				Row(verticalAlignment = Alignment.CenterVertically) {
					Checkbox(checked = state.skipSetup, onCheckedChange = onSkipSetup, enabled = enabled)
					Text("Gunakan pengaturan ini berikutnya")
				}
				Button(onClick = { showOptions = false }, modifier = Modifier.fillMaxWidth()) { Text("Selesai") }
			}
		}
	}
}

@Composable
private fun MigrationReviewStep(
	state: SourceMangaListState,
	imageLoader: ImageLoader,
	onMangaClick: (Manga) -> Unit,
	onOpenTarget: (Manga) -> Unit,
	onRetry: () -> Unit,
	onContinue: () -> Unit,
	onCopy: () -> Unit,
	onCancel: () -> Unit,
	onMigrateOne: (Long) -> Unit,
	onCopyOne: (Long) -> Unit,
	onSkipOne: (Long) -> Unit,
) {
	val readyIds = MigrationBatchPolicy.readyRequests(
		manga = state.manga, snapshots = state.batchItems,
		discoveryMode = MigrationBatchDiscoveryMode.YOKAI, configKey = state.configKey,
	).mapTo(hashSetOf()) { it.mangaId }
	val busy = state.batchBusy || state.scheduling
	Column(Modifier.fillMaxSize()) {
		Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
			verticalAlignment = Alignment.CenterVertically) {
			Text("Hasil pencarian", style = MaterialTheme.typography.titleLarge,
				modifier = Modifier.weight(1f))
			TextButton(onClick = onRetry, enabled = !busy) { Text("Source") }
		}
		if (busy) {
			LinearProgressIndicator(Modifier.fillMaxWidth())
			Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
				Text("Memproses " + state.batchCompleted + "/" + state.batchTotal,
					modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
				TextButton(onClick = onCancel, enabled = state.ownedBatchName != null) { Text("Batalkan") }
			}
		}
		if (state.failed) {
			Text("Sebagian pekerjaan tidak dapat dijalankan. Periksa hasil atau coba lagi.",
				modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
		}
		LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp)) {
			items(state.manga, key = Manga::id) { manga ->
				val status = state.status(manga.id)
				val outcome = state.batchItems[manga.id]?.outcome
				MigrationMangaReviewItem(manga, imageLoader, status,
					outcome?.destinationTitle, outcome?.destinationSource,
					onClick = { onMangaClick(manga) },
					targetManga = state.readyTarget(manga.id), onOpenTarget = onOpenTarget,
					onMigrate = if (!busy && manga.id in readyIds) ({ onMigrateOne(manga.id) }) else null,
					onCopy = if (!busy && manga.id in readyIds) ({ onCopyOne(manga.id) }) else null,
					onSkip = if (!busy) ({ onSkipOne(manga.id) }) else null)
			}
			if (state.manga.isEmpty()) item { Text("Tidak ada manga dalam sesi ini.") }
			if (state.completedResults.isNotEmpty()) {
				item { Text("Hasil sebelumnya", style = MaterialTheme.typography.titleSmall) }
				items(state.completedResults, key = { "done:" + it.mangaId + ":" + it.generation }) { snapshot ->
					Text((snapshot.outcome.destinationTitle ?: "Manga") + " · " +
						stringResource(snapshot.outcome.status.labelRes()), style = MaterialTheme.typography.bodySmall)
				}
			}
		}
		Surface(tonalElevation = 3.dp) {
			Column(Modifier.fillMaxWidth().padding(12.dp)) {
				if (readyIds.isNotEmpty() && !busy) {
					if (state.preferredAction == MigrationBatchAction.COPY) {
						Button(onClick = onCopy, modifier = Modifier.fillMaxWidth()
							.testTag("migration-batch-copy")) { Text("Salin (" + readyIds.size + ")") }
						OutlinedButton(onClick = onContinue, modifier = Modifier.fillMaxWidth()
							.testTag("migration-batch-migrate")) { Text("Migrasikan (" + readyIds.size + ")") }
					} else {
						Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()
							.testTag("migration-batch-migrate")) { Text("Migrasikan (" + readyIds.size + ")") }
						OutlinedButton(onClick = onCopy, modifier = Modifier.fillMaxWidth()
							.testTag("migration-batch-copy")) { Text("Salin (" + readyIds.size + ")") }
					}
				} else {
					Text(if (busy) "Pencarian / migrasi sedang berjalan"
						else "Pilih kandidat secara manual jika belum ditemukan.",
						style = MaterialTheme.typography.bodySmall)
				}
			}
		}
	}
}

@Composable
private fun ReviewCount(title: String, count: Int, modifier: Modifier = Modifier) {
	Surface(modifier = modifier, shape = MaterialTheme.shapes.medium,
		color = MaterialTheme.colorScheme.surfaceContainerLow) {
		Column(Modifier.padding(9.dp)) {
			Text(count.toString(), style = MaterialTheme.typography.titleLarge)
			Text(title, style = MaterialTheme.typography.labelSmall)
		}
	}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MigrationMangaReviewItem(
	manga: Manga, imageLoader: ImageLoader, status: MigrationBatchStatus,
	targetTitle: String?, targetSource: String?, onClick: () -> Unit,
	targetManga: Manga? = null,
	onOpenTarget: (Manga) -> Unit = {},
	onMigrate: (() -> Unit)? = null,
	onCopy: (() -> Unit)? = null,
	onSkip: (() -> Unit)? = null,
) {
	val context = LocalContext.current
	var menuExpanded by remember { mutableStateOf(false) }
	Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium) {
		Row(Modifier.fillMaxWidth().padding(9.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(7.dp)) {
			Column(Modifier.weight(1f).clickable(onClick = onClick)) {
				val request = remember(manga.id, manga.coverUrl, manga.source) {
					manga.coverUrl?.let {
						ImageRequest.Builder(context).data(it).mangaSourceExtra(manga.source).build()
					}
				}
				if (request != null) AsyncImage(model = request, imageLoader = imageLoader,
					contentDescription = null, modifier = Modifier.width(58.dp).height(76.dp))
				Text(manga.title, style = MaterialTheme.typography.bodySmall,
					maxLines = 2, overflow = TextOverflow.Ellipsis)
				Text(manga.source.getTitle(context).toString(), style = MaterialTheme.typography.labelSmall,
					maxLines = 1, overflow = TextOverflow.Ellipsis)
			}
			Text("→", style = MaterialTheme.typography.titleMedium)
			Column(Modifier.weight(1f)) {
				if (targetManga != null) {
					val targetRequest = remember(targetManga.id, targetManga.coverUrl, targetManga.source) {
						targetManga.coverUrl?.let {
							ImageRequest.Builder(context).data(it).mangaSourceExtra(targetManga.source).build()
						}
					}
					if (targetRequest != null) AsyncImage(
						model = targetRequest, imageLoader = imageLoader,
						contentDescription = targetManga.title,
						modifier = Modifier.width(58.dp).height(76.dp)
							.combinedClickable(
								onClick = { onOpenTarget(targetManga) },
								onLongClick = onClick,
							),
					)
				}
				Column(
					if (targetManga == null) Modifier.clickable(onClick = onClick)
					else Modifier.combinedClickable(
						onClick = { onOpenTarget(targetManga) },
						onLongClick = onClick,
					)
				) {
				Text(targetTitle ?: if (status == MigrationBatchStatus.NO_REPLACEMENT)
					"Tidak ditemukan" else "Mencari...",
					style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
				val publicSource = targetManga?.source?.getTitle(context)?.takeIf(String::isNotBlank)
					?: targetSource?.takeUnless { it.startsWith("MIHON_") || it.startsWith("TSUKI:")
						|| it.startsWith("LN_") }
				if (publicSource != null) Text(publicSource,
					style = MaterialTheme.typography.labelSmall, maxLines = 1)
				Text(stringResource(status.labelRes()), style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.primary)
				}
			}
			Box {
				TextButton(onClick = { menuExpanded = true }) { Text("⋮") }
				DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
				DropdownMenuItem(text = { Text("Cari manual") }, onClick = {
					menuExpanded = false; onClick()
				})
				if (onMigrate != null) DropdownMenuItem(text = { Text("Migrasikan") }, onClick = {
					menuExpanded = false; onMigrate()
				})
				if (onCopy != null) DropdownMenuItem(text = { Text("Salin") }, onClick = {
					menuExpanded = false; onCopy()
				})
				if (onSkip != null) DropdownMenuItem(text = { Text("Lewati") }, onClick = {
					menuExpanded = false; onSkip()
				})
			}
			}
		}
	}
}

@Composable
private fun MigrationResultsStep(
	state: SourceMangaListState,
	imageLoader: ImageLoader,
	onCancel: () -> Unit,
	onReview: (Manga) -> Unit,
	onSearchAgain: () -> Unit,
) {
	val inProgress = state.ownedBatchName != null && state.batchBusy
	val completed = state.manga.filter { state.status(it.id) in setOf(
		MigrationBatchStatus.MIGRATED, MigrationBatchStatus.COPIED) }
	val failures = state.manga.filter { state.status(it.id) == MigrationBatchStatus.ERROR }
	val unresolved = state.manga.filter { state.status(it.id) in setOf(
		MigrationBatchStatus.INTERRUPTED, MigrationBatchStatus.NEEDS_REVIEW,
		MigrationBatchStatus.INCOMPLETE, MigrationBatchStatus.NO_REPLACEMENT) }
	val skipped = state.manga.filter { state.status(it.id) == MigrationBatchStatus.SKIPPED }
	Column(Modifier.fillMaxSize()) {
		LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp)) {
			item {
				Text("Progress dan hasil", style = MaterialTheme.typography.titleLarge)
				if (state.failed) {
					Text("Pekerjaan tidak dapat dimulai atau statusnya gagal dimuat. Periksa hasil lalu coba lagi.",
						color = MaterialTheme.colorScheme.error,
						style = MaterialTheme.typography.bodySmall)
				}
				if (inProgress) {
					Text("Batch sedang berjalan · " + state.batchCompleted + " / " + state.batchTotal)
					LinearProgressIndicator(progress = {
						if (state.batchTotal == 0) 0f else
							state.batchCompleted.toFloat() / state.batchTotal.toFloat()
					}, modifier = Modifier.fillMaxWidth())
					TextButton(onClick = onCancel) { Text("Batalkan batch berjalan") }
				} else if (state.batchBusy) {
					Text(stringResource(R.string.migration_batch_other_active))
				} else if (state.scheduling) {
					LinearProgressIndicator(Modifier.fillMaxWidth())
					Text("Menyiapkan pekerjaan migrasi")
				} else {
					Text("Tidak ada batch aktif pada sesi ini.",
						style = MaterialTheme.typography.bodySmall)
				}
			}
			if (completed.isNotEmpty() || state.completedResults.isNotEmpty()) item {
				Text("Berhasil", style = MaterialTheme.typography.titleMedium)
			}
			items(completed, key = Manga::id) { manga ->
				MigrationMangaReviewItem(manga, imageLoader, state.status(manga.id),
					state.batchItems[manga.id]?.outcome?.destinationTitle,
					state.batchItems[manga.id]?.outcome?.destinationSource,
					onClick = { onReview(manga) })
			}
			items(state.completedResults, key = { "complete:" + it.mangaId + ":" + it.generation }) { result ->
				Column {
					Text(result.outcome.destinationTitle ?: "Manga selesai",
						style = MaterialTheme.typography.bodyMedium)
					Text(stringResource(result.outcome.status.labelRes()),
						style = MaterialTheme.typography.bodySmall)
				}
			}
			if (failures.isNotEmpty()) item { Text("Gagal", style = MaterialTheme.typography.titleMedium) }
			items(failures, key = Manga::id) { manga ->
				MigrationMangaReviewItem(manga, imageLoader, state.status(manga.id),
					null, null, onClick = { onReview(manga) })
			}
			if (unresolved.isNotEmpty() || state.unconfirmedResults.isNotEmpty()) item {
				Text("Perlu diperiksa / belum selesai", style = MaterialTheme.typography.titleMedium)
			}
			items(unresolved, key = Manga::id) { manga ->
				MigrationMangaReviewItem(manga, imageLoader, state.status(manga.id),
					state.batchItems[manga.id]?.outcome?.destinationTitle,
					state.batchItems[manga.id]?.outcome?.destinationSource,
					onClick = { onReview(manga) })
			}
			items(state.unconfirmedResults, key = { "unconfirmed:" + it.mangaId + ":" + it.generation }) { result ->
				Column {
					Text(result.outcome.destinationTitle ?: "Hasil belum terkonfirmasi")
					Text(stringResource(R.string.migration_batch_check_library),
						style = MaterialTheme.typography.bodySmall)
				}
			}
			if (skipped.isNotEmpty()) item { Text("Dilewati", style = MaterialTheme.typography.titleMedium) }
			items(skipped, key = Manga::id) { manga ->
				MigrationMangaReviewItem(manga, imageLoader, state.status(manga.id),
					null, null, onClick = { onReview(manga) })
			}
			if (!inProgress && completed.isEmpty() && failures.isEmpty() && unresolved.isEmpty() &&
				skipped.isEmpty() && state.completedResults.isEmpty() &&
				state.unconfirmedResults.isEmpty()) {
				item { Text("Belum ada hasil migrasi untuk ditampilkan.") }
			}
		}
		Surface(tonalElevation = 3.dp) {
			Column(Modifier.fillMaxWidth().padding(12.dp)) {
				Button(onClick = onSearchAgain, modifier = Modifier.fillMaxWidth()) {
					Text("Kembali ke pencarian")
				}
			}
		}
	}
}
