package org.koitharu.kotatsu.settings.sources.migration

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import org.koitharu.kotatsu.explore.ui.adapter.ExploreSourceOrigin
import org.koitharu.kotatsu.explore.ui.adapter.exploreSourceOrigin
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
) {
	var step by rememberSaveable { mutableIntStateOf(0) }
	var pendingAction by remember { mutableStateOf<MigrationBatchAction?>(null) }
	val busy = state.batchBusy || state.scheduling
	BackHandler(enabled = step > 0 && !state.scheduling) { step-- }

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

	Column(Modifier.fillMaxSize()) {
		Row(
			Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp),
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			listOf("Cari", "Review", "Hasil").forEachIndexed { index, label ->
				FilterChip(
					selected = step == index,
					onClick = { step = index },
					label = { Text(label) },
				)
			}
		}
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
				onViewProgress = { step = 2 },
			)
			1 -> MigrationReviewStep(
				state = state,
				imageLoader = imageLoader,
				onMangaClick = onMangaClick,
				onRetry = { step = 0 },
				onContinue = { pendingAction = MigrationBatchAction.MIGRATE },
				onCopy = { pendingAction = MigrationBatchAction.COPY },
				onResults = { step = 2 },
			)
			else -> MigrationResultsStep(
				state = state,
				imageLoader = imageLoader,
				onCancel = onCancel,
				onReview = onMangaClick,
				onSearchAgain = { step = 0 },
			)
		}
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
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
					Text(ready.size.toString() + " manga siap", fontWeight = FontWeight.SemiBold)
					Text(
						if (action == MigrationBatchAction.COPY)
							"Salin membuat entri pengganti tanpa menghapus manga asal."
						else
							"Migrasi mengganti source manga terpilih. Data pembaca dipertahankan; file unduhan tidak dipindahkan atau dihapus.",
						style = MaterialTheme.typography.bodySmall,
					)
					ready.take(6).forEach { manga ->
						val destination = state.batchItems[manga.id]?.outcome
						Text(manga.title + "\n" + manga.source.getTitle(LocalContext.current) +
							" → " + (destination?.destinationTitle ?: "Belum diketahui") +
							" · " + (destination?.destinationSource ?: ""),
							style = MaterialTheme.typography.bodySmall,
						)
					}
					if (ready.size > 6) Text("Dan " + (ready.size - 6) + " manga lainnya",
						style = MaterialTheme.typography.bodySmall)
				}
			},
			confirmButton = {
				TextButton(
					enabled = ready.isNotEmpty() && !busy,
					onClick = {
						pendingAction = null
						if (action == MigrationBatchAction.COPY) onCopyReady() else onMigrateReady()
						step = 2
					},
					modifier = Modifier.testTag("migration-batch-confirm"),
				) { Text(if (action == MigrationBatchAction.COPY) "Salin" else "Migrasikan") }
			},
			dismissButton = { TextButton(onClick = { pendingAction = null }) { Text("Batal") } },
		)
	}
}

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
	var origin by rememberSaveable { mutableStateOf("All") }
	var advanced by rememberSaveable { mutableStateOf(false) }
	var showSources by rememberSaveable { mutableStateOf(false) }
	var expandedFamily by rememberSaveable { mutableStateOf<String?>(null) }
	val sourceByName = state.availableSources.associateBy(MangaSource::name)
	val selected = state.selectedSourceNames.mapNotNull(sourceByName::get)
	val visible = state.availableSources.filter { source ->
		when (origin) {
			"Miyorare" -> source.exploreSourceOrigin() == ExploreSourceOrigin.MIYORARE_PACK
			"Store" -> source.exploreSourceOrigin() == ExploreSourceOrigin.EXTENSION_STORE
			else -> true
		}
	}
	val grouped = visible.groupBy { source ->
		if (EhentaiSourceFamily.isKnownStoredSource(source.name)) "family:ehentai"
		else "source:" + source.name
	}
	Column(Modifier.fillMaxSize()) {
		Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
			verticalArrangement = Arrangement.spacedBy(11.dp)) {
			Text("Cari pengganti", style = MaterialTheme.typography.titleLarge)
			Text(state.manga.size.toString() + " manga dipilih. Cari pada source tujuan yang kompatibel.",
				style = MaterialTheme.typography.bodyMedium)
			if (state.sourceSelectionLoading) {
				LinearProgressIndicator(Modifier.fillMaxWidth())
				Text(stringResource(R.string.migration_loading_sources))
			} else if (state.sourceSelectionFailed) {
				Text(stringResource(R.string.migration_source_list_failed), color = MaterialTheme.colorScheme.error)
				TextButton(onClick = onRetrySources) { Text(stringResource(R.string.migration_retry)) }
			} else if (state.availableSources.isEmpty()) {
				Text(stringResource(R.string.migration_no_sources_available))
			} else {
				Surface(color = MaterialTheme.colorScheme.surfaceContainerLow,
					shape = MaterialTheme.shapes.medium) {
					Column(Modifier.fillMaxWidth().padding(12.dp)) {
						Text("Source tujuan terpilih: " + selected.size, fontWeight = FontWeight.SemiBold)
						Text(
							if (selected.isEmpty()) "Belum ada source tujuan."
							else selected.take(3).joinToString(" · ") { it.getTitle(context).toString() } +
								if (selected.size > 3) " · +" + (selected.size - 3) + " lainnya" else "",
							style = MaterialTheme.typography.bodySmall,
						)
						Text("Filter di bawah hanya mengubah daftar yang terlihat, bukan pilihan pencarian.",
							style = MaterialTheme.typography.labelSmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant)
					}
				}
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					listOf("All", "Miyorare", "Store").forEach { category ->
						FilterChip(selected = origin == category, onClick = { origin = category },
							label = { Text(category) })
					}
				}
				Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
					TextButton(onClick = { showSources = !showSources },
						modifier = Modifier.weight(1f)) {
						Text(if (showSources) "Sembunyikan source tujuan" else "Lihat dan atur source tujuan")
					}
					if (showSources) {
						TextButton(enabled = enabled, onClick = onSelectAllSources) { Text("Semua") }
						TextButton(enabled = enabled, onClick = onClearSources) { Text("Kosongkan") }
					}
				}
				if (showSources) for ((key, group) in grouped) {
					val family = group.size > 1
					if (family) {
						Row(Modifier.fillMaxWidth().clickable {
							expandedFamily = if (expandedFamily == key) null else key
						}.padding(vertical = 8.dp)) {
							Text("E-Hentai (" + group.size + ")", modifier = Modifier.weight(1f),
								style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
							Text(if (expandedFamily == key) "⌃" else "⌄")
						}
					}
					if (!family || expandedFamily == key) {
						for (source in group) {
							Row(Modifier.fillMaxWidth().clickable(enabled = enabled) {
								onToggleSource(source.name)
							}.padding(start = if (family) 14.dp else 0.dp, top = 5.dp, bottom = 5.dp),
								verticalAlignment = Alignment.CenterVertically) {
								Checkbox(
									checked = source.name in state.selectedSourceNames,
									onCheckedChange = { onToggleSource(source.name) },
									enabled = enabled,
								)
								Text(source.getTitle(context).toString(), style = MaterialTheme.typography.bodyMedium)
							}
						}
					}
				}
				TextButton(onClick = { advanced = !advanced }) {
					Text(if (advanced) "Sembunyikan pengaturan lanjutan" else "Pengaturan lanjutan")
				}
				if (advanced) {
					Text("Prioritas pencarian", style = MaterialTheme.typography.titleSmall)
					selected.forEachIndexed { index, source ->
						Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
							Text((index + 1).toString() + ". " + source.getTitle(context),
								modifier = Modifier.weight(1f), maxLines = 1,
								overflow = TextOverflow.Ellipsis)
							TextButton(enabled = enabled && index > 0,
								onClick = { onMoveSource(source.name, -1) }) { Text("↑") }
							TextButton(enabled = enabled && index < selected.lastIndex,
								onClick = { onMoveSource(source.name, 1) }) { Text("↓") }
						}
					}
					Text("Strategi", style = MaterialTheme.typography.titleSmall)
					for (strategy in MigrationSourceStrategy.entries) {
						Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
							RadioButton(selected = state.sourceStrategy == strategy,
								onClick = { onStrategy(strategy) }, enabled = enabled)
							Text(if (strategy == MigrationSourceStrategy.FIRST_MATCH)
								"Gunakan source pertama yang cocok" else "Bandingkan jumlah chapter")
						}
					}
					OutlinedTextField(value = state.extraSearchParams,
						onValueChange = onExtraSearch, enabled = enabled, singleLine = true,
						label = { Text("Parameter pencarian tambahan") },
						modifier = Modifier.fillMaxWidth())
					Row(verticalAlignment = Alignment.CenterVertically) {
						Checkbox(checked = state.skipSetup, onCheckedChange = onSkipSetup, enabled = enabled)
						Text("Gunakan pengaturan ini lain kali")
					}
				}
				if (state.sourcePoolFailureCount > 0) {
					Text(stringResource(R.string.migration_partial_provider_pool),
						style = MaterialTheme.typography.bodySmall)
				}
			}
		}
		Surface(tonalElevation = 3.dp) {
			Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)) {
				if (state.batchBusy) {
					Text("Pekerjaan migrasi masih berlangsung", style = MaterialTheme.typography.bodySmall)
					OutlinedButton(onClick = onViewProgress, modifier = Modifier.fillMaxWidth()) { Text("Lihat progres") }
				}
				Button(
					onClick = onSearch,
					enabled = enabled && state.manga.isNotEmpty() && state.selectedSourceNames.isNotEmpty()
						&& !state.sourceSelectionLoading && !state.sourceSelectionFailed,
					modifier = Modifier.fillMaxWidth().height(52.dp).testTag("migration-batch-search"),
				) { Text("Cari pengganti untuk " + state.manga.size + " manga") }
			}
		}
	}
}

@Composable
private fun MigrationReviewStep(
	state: SourceMangaListState,
	imageLoader: ImageLoader,
	onMangaClick: (Manga) -> Unit,
	onRetry: () -> Unit,
	onContinue: () -> Unit,
	onCopy: () -> Unit,
	onResults: () -> Unit,
) {
	var filter by rememberSaveable { mutableStateOf("Semua") }
	val readyIds = MigrationBatchPolicy.readyRequests(
			manga = state.manga, snapshots = state.batchItems,
			discoveryMode = MigrationBatchDiscoveryMode.YOKAI, configKey = state.configKey,
		).mapTo(hashSetOf()) { it.mangaId }
		val ready = state.manga.filter { it.id in readyIds }
	val review = state.manga.filter {
		state.status(it.id) in setOf(MigrationBatchStatus.NEEDS_REVIEW, MigrationBatchStatus.INCOMPLETE,
			MigrationBatchStatus.ERROR, MigrationBatchStatus.INTERRUPTED,
			MigrationBatchStatus.UNCHECKED, MigrationBatchStatus.SKIPPED, MigrationBatchStatus.HEALTHY)
	}
	val missing = state.manga.filter { state.status(it.id) == MigrationBatchStatus.NO_REPLACEMENT }
	val queued = state.manga.filter { state.status(it.id) in setOf(
		MigrationBatchStatus.QUEUED, MigrationBatchStatus.CHECKING, MigrationBatchStatus.MIGRATING) }
	val display = when (filter) {
		"Siap" -> ready
		"Perlu review" -> review
		"Tidak ditemukan" -> missing
		else -> state.manga
	}
	Column(Modifier.fillMaxSize()) {
		LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp)) {
			item {
				Text("Review kandidat", style = MaterialTheme.typography.titleLarge)
				Text("Periksa pengganti sebelum migrasi. Kandidat meragukan tidak akan dipindahkan otomatis.",
					style = MaterialTheme.typography.bodySmall)
				if (state.batchBusy || state.scheduling) {
					LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
					Text("Pencarian atau migrasi masih berjalan", style = MaterialTheme.typography.bodySmall)
				}
			}
			item {
				Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
					ReviewCount("Siap", ready.size, Modifier.weight(1f))
					ReviewCount("Perlu review", review.size, Modifier.weight(1f))
					ReviewCount("Tidak ada", missing.size, Modifier.weight(1f))
				}
			}
			item {
				Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
					horizontalArrangement = Arrangement.spacedBy(7.dp)) {
					listOf("Semua", "Siap", "Perlu review", "Tidak ditemukan").forEach { category ->
						FilterChip(selected = filter == category, onClick = { filter = category },
							label = { Text(category) })
					}
				}
			}
			items(display, key = Manga::id) { manga ->
				val status = state.status(manga.id)
				val outcome = state.batchItems[manga.id]?.outcome
				MigrationMangaReviewItem(manga, imageLoader, status,
					outcome?.destinationTitle, outcome?.destinationSource,
					onClick = { onMangaClick(manga) })
			}
			if (queued.isNotEmpty()) item {
				Text(queued.size.toString() + " manga masih dalam antrean/proses.",
					style = MaterialTheme.typography.bodySmall)
			}
			if (display.isEmpty()) item {
				Text("Tidak ada manga dalam kategori ini.", style = MaterialTheme.typography.bodySmall)
			}
			if (state.completedResults.isNotEmpty()) item {
				Text("Riwayat hasil selesai (" + state.completedResults.size + ")",
					style = MaterialTheme.typography.titleSmall)
			}
			items(state.completedResults, key = { "done:" + it.mangaId + ":" + it.generation }) { snapshot ->
				Text((snapshot.outcome.destinationTitle ?: "Manga") + " · " +
					stringResource(snapshot.outcome.status.labelRes()), style = MaterialTheme.typography.bodySmall)
			}
		}
		Surface(tonalElevation = 3.dp) {
			Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)) {
				if (ready.isNotEmpty() && !state.batchBusy && !state.scheduling) {
					Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()
						.testTag("migration-batch-migrate")) {
						Text("Tinjau migrasi " + ready.size + " manga siap")
					}
					OutlinedButton(onClick = onCopy, modifier = Modifier.fillMaxWidth()
						.testTag("migration-batch-copy")) {
						Text("Salin " + ready.size + " manga siap")
					}
				} else {
					Text(if (state.batchBusy) "Menunggu proses selesai" else "Belum ada kandidat siap.",
						style = MaterialTheme.typography.bodySmall)
				}
				Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
					TextButton(onClick = onRetry) { Text("Ubah pencarian") }
					TextButton(onClick = onResults) { Text("Lihat hasil") }
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

@Composable
private fun MigrationMangaReviewItem(
	manga: Manga, imageLoader: ImageLoader, status: MigrationBatchStatus,
	targetTitle: String?, targetSource: String?, onClick: () -> Unit,
) {
	val context = LocalContext.current
	Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium) {
		Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(10.dp),
			horizontalArrangement = Arrangement.spacedBy(12.dp),
			verticalAlignment = Alignment.CenterVertically) {
			val request = remember(manga.id, manga.coverUrl, manga.source) {
				manga.coverUrl?.let {
					ImageRequest.Builder(context).data(it).mangaSourceExtra(manga.source).build()
				}
			}
			if (request != null) {
				AsyncImage(model = request, imageLoader = imageLoader,
					contentDescription = null, modifier = Modifier.width(48.dp).height(66.dp))
			}
			Column(Modifier.weight(1f)) {
				Text(manga.title, style = MaterialTheme.typography.bodyMedium,
					maxLines = 2, overflow = TextOverflow.Ellipsis)
				Text(manga.source.getTitle(context).toString() + " → " +
					(targetSource ?: "Belum ada source tujuan"),
					style = MaterialTheme.typography.bodySmall)
				if (targetTitle != null) Text(targetTitle, style = MaterialTheme.typography.bodySmall,
					maxLines = 1, overflow = TextOverflow.Ellipsis)
				Text(stringResource(status.labelRes()), style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.primary)
				Text("Tinjau / pilih kandidat ›", style = MaterialTheme.typography.labelSmall)
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
					Text(result.outcome.destinationSource.orEmpty() + " · " +
						stringResource(result.outcome.status.labelRes()),
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
			Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)) {
				Button(onClick = onSearchAgain, modifier = Modifier.fillMaxWidth()) {
					Text("Kembali ke pencarian")
				}
			}
		}
	}
}
