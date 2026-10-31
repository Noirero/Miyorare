package org.koitharu.kotatsu.settings.sources.migration

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.core.view.MenuProvider
import androidx.core.view.doOnDetach
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import com.google.android.material.appbar.AppBarLayout
import org.koitharu.kotatsu.main.ui.owners.AppBarOwner
import dagger.hilt.android.AndroidEntryPoint
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.parser.favicon.faviconUri
import org.koitharu.kotatsu.core.exceptions.resolve.CaptchaHandler.Companion.suppressCaptchaErrors
import org.koitharu.kotatsu.core.ui.image.FaviconDrawable
import org.koitharu.kotatsu.core.util.ext.addMenuProvider
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.main.ui.nav.DrawablePainter
import org.koitharu.kotatsu.settings.SettingsActivity
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.MiyorareTheme
import javax.inject.Inject

@AndroidEntryPoint
class BrokenSourcesMigrationFragment :
	BaseComposeSettingsFragment(R.string.migrate_broken_sources) {

	private val viewModel by viewModels<BrokenSourcesMigrationViewModel>()

	@Inject
	lateinit var imageLoader: ImageLoader

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			MiyorareTheme {
				val state by viewModel.state.collectAsState()
				BrokenSourcesMigrationScreen(
					state = state,
					imageLoader = imageLoader,
					onToggleManga = viewModel::toggleManga,
					onSelectManga = viewModel::selectManga,
					onRetry = viewModel::retry,
					onContinue = {
						(requireActivity() as SettingsActivity).openFragment(
							SourceMangaListFragment::class.java,
							SourceMangaListFragment.argsForMangaIds(
								state.selectedMangaIds.toList(), getString(R.string.manga_migration),
							),
							isFromRoot = false,
						)
					},
				)
			}
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		// The fragment container is laid out for the collapsed appbar (ScrollingViewBehavior), so
		// while the appbar is expanded the bottom of this view is pushed off-screen. Compensate
		// with bottom padding equal to the appbar's current expansion so the Fix bar stays visible.
		val appBar = (requireActivity() as AppBarOwner).appBar
		val offsetListener = AppBarLayout.OnOffsetChangedListener { bar, verticalOffset ->
			view.updatePadding(bottom = bar.totalScrollRange + verticalOffset)
		}
		appBar.addOnOffsetChangedListener(offsetListener)
		view.doOnDetach { appBar.removeOnOffsetChangedListener(offsetListener) }
		addMenuProvider(object : MenuProvider {
			override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
				menu.add(Menu.NONE, MENU_INFO, Menu.NONE, R.string.migration_information).apply {
					setIcon(R.drawable.ic_info)
					setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
				}
			}

			override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
				if (menuItem.itemId != MENU_INFO) return false
				viewModel.toggleInfo()
				return true
			}
		})
	}

	private companion object {
		const val MENU_INFO = 0x4D4947
	}
}

@Composable
private fun BrokenSourcesMigrationScreen(
	state: BrokenSourcesMigrationState,
	imageLoader: ImageLoader,
	onToggleManga: (Long) -> Unit,
	onSelectManga: (Collection<Long>) -> Unit,
	onRetry: () -> Unit,
	onContinue: () -> Unit,
) {
	var query by rememberSaveable { mutableStateOf("") }
	var showSelected by rememberSaveable { mutableStateOf(false) }
	var expandedGroup by rememberSaveable { mutableStateOf<String?>(null) }
	var variantFilter by rememberSaveable { mutableStateOf<String?>(null) }
	val context = LocalContext.current
	val allowedKeys = state.sources.flatMapTo(hashSetOf()) { it.sourceKeys }
	val selectedVariant = variantFilter?.takeIf { it in allowedKeys }
	val visibleManga = state.manga.filter { manga ->
		(selectedVariant == null || manga.source.name == selectedVariant) &&
			(!showSelected || manga.id in state.selectedMangaIds) &&
			(query.isBlank() || manga.title.contains(query.trim(), ignoreCase = true) ||
				manga.source.getTitle(context).toString().contains(query.trim(), ignoreCase = true))
	}
	Column(Modifier.fillMaxSize()) {
		when {
			state.isLoading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
				CircularProgressIndicator()
			}
			state.loadFailed -> Column(
				Modifier.weight(1f).fillMaxWidth().padding(24.dp),
				verticalArrangement = Arrangement.Center,
				horizontalAlignment = Alignment.CenterHorizontally,
			) {
				Text("Daftar manga tidak dapat dimuat", style = MaterialTheme.typography.titleMedium)
				Spacer(Modifier.height(8.dp))
				TextButton(onClick = onRetry) { Text(stringResource(R.string.migration_retry)) }
			}
			else -> {
				LazyColumn(
					modifier = Modifier.weight(1f).fillMaxWidth()
						.nestedScroll(rememberNestedScrollInteropConnection()),
					contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
					verticalArrangement = Arrangement.spacedBy(10.dp),
				) {
					item {
						Text("Pilih manga yang ingin dimigrasikan", style = MaterialTheme.typography.titleLarge)
						Text(
							"Source yang belum diperiksa belum tentu rusak.",
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
					item {
						OutlinedTextField(
							value = query, onValueChange = { query = it },
							label = { Text("Cari judul atau source") }, singleLine = true,
							modifier = Modifier.fillMaxWidth(),
						)
					}
					item {
						Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
							FilterChip(selected = !showSelected, onClick = { showSelected = false }, label = { Text("Semua") })
							FilterChip(selected = showSelected, onClick = { showSelected = true },
								label = { Text("Dipilih (" + state.selectedMangaIds.size + ")") })
						}
					}
					if (state.sources.isNotEmpty()) {
						item {
							Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
								Text("Kelompok source", style = MaterialTheme.typography.titleSmall,
									modifier = Modifier.weight(1f))
								TextButton(onClick = { variantFilter = null }) { Text("Semua source") }
							}
						}
						items(state.sources, key = LibrarySourceOption::key) { group ->
							val multi = group.variants.size > 1
							Column {
								Row(
									modifier = Modifier.fillMaxWidth()
										.clip(RoundedCornerShape(12.dp))
										.clickable {
											if (multi) {
												expandedGroup = if (expandedGroup == group.key) null else group.key
											} else {
												variantFilter = group.sourceKeys.singleOrNull()
											}
										}
										.padding(vertical = 7.dp, horizontal = 4.dp),
									verticalAlignment = Alignment.CenterVertically,
								) {
									SourceIcon(group, imageLoader)
									Spacer(Modifier.width(12.dp))
									Column(Modifier.weight(1f)) {
										val displayTitle = if (multi) group.title.substringBefore(" (") else group.title
										Text(displayTitle + if (multi) " (" + group.variants.size + ")" else "",
											style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
										Text(
											pluralStringResource(R.plurals.manga_count, group.mangaCount, group.mangaCount),
											style = MaterialTheme.typography.bodySmall,
											color = MaterialTheme.colorScheme.onSurfaceVariant,
										)
									}
									Text(if (multi) (if (expandedGroup == group.key) "⌃" else "⌄") else "›")
								}
								if (multi && expandedGroup == group.key) {
									for (variant in group.variants) {
										Row(
											modifier = Modifier.fillMaxWidth()
												.clickable { variantFilter = variant.key }
												.padding(start = 50.dp, top = 7.dp, bottom = 7.dp),
											verticalAlignment = Alignment.CenterVertically,
										) {
											Text(variant.title, modifier = Modifier.weight(1f),
												style = MaterialTheme.typography.bodySmall)
											if (variantFilter == variant.key) Text("✓", color = MaterialTheme.colorScheme.primary)
										}
									}
								}
							}
						}
					}
					item {
						Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
							Text(
								"Manga (" + visibleManga.size + ")",
								style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
							)
							TextButton(onClick = {
								onSelectManga(MigrationSelectionPolicy.toggleVisible(
									state.selectedMangaIds, visibleManga.mapTo(hashSetOf()) { it.id },
								))
							}, enabled = visibleManga.isNotEmpty()) {
								Text(if (visibleManga.isNotEmpty() &&
									visibleManga.all { it.id in state.selectedMangaIds }) "Batalkan pilihan"
									else "Pilih semua")
							}
						}
					}
					if (visibleManga.isEmpty()) {
						item {
							Text(if (state.manga.isEmpty()) "Tidak ada manga yang dapat dimigrasikan."
								else "Tidak ada manga untuk filter ini.",
								style = MaterialTheme.typography.bodyMedium,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
								modifier = Modifier.padding(vertical = 18.dp))
						}
					}
					items(visibleManga, key = Manga::id) { manga ->
						Row(
							modifier = Modifier.fillMaxWidth()
								.clip(RoundedCornerShape(12.dp))
								.clickable { onToggleManga(manga.id) }
								.padding(vertical = 8.dp),
							verticalAlignment = Alignment.CenterVertically,
						) {
							val request = remember(manga.id, manga.coverUrl, manga.source) {
								manga.coverUrl?.let {
									ImageRequest.Builder(context).data(it).mangaSourceExtra(manga.source).build()
								}
							}
							if (request != null) {
								AsyncImage(model = request, imageLoader = imageLoader, contentDescription = null,
									modifier = Modifier.width(48.dp).height(68.dp)
										.clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
							} else {
								Box(Modifier.width(48.dp).height(68.dp),
									contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_placeholder), null) }
							}
							Spacer(Modifier.width(12.dp))
							Column(Modifier.weight(1f)) {
								Text(manga.title, style = MaterialTheme.typography.bodyMedium,
									maxLines = 2, overflow = TextOverflow.Ellipsis)
								Text(manga.source.getTitle(context).toString(),
									style = MaterialTheme.typography.bodySmall,
									color = MaterialTheme.colorScheme.onSurfaceVariant)
								Text("Belum diperiksa dalam sesi ini",
									style = MaterialTheme.typography.labelSmall,
									color = MaterialTheme.colorScheme.onSurfaceVariant)
							}
							Checkbox(
								checked = manga.id in state.selectedMangaIds,
								onCheckedChange = { onToggleManga(manga.id) },
							)
						}
					}
				}
				Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surfaceContainer) {
					Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)) {
						Text(state.selectedMangaIds.size.toString() + " manga dipilih",
							style = MaterialTheme.typography.bodySmall)
						Spacer(Modifier.height(8.dp))
						Button(onClick = onContinue, enabled = state.selectedMangaIds.isNotEmpty(),
							modifier = Modifier.fillMaxWidth().height(52.dp)) {
							Text("Lanjut ke pencarian")
						}
					}
				}
			}
		}
	}
}

@Composable
private fun SourceSectionHeader(
	title: String,
	isChecked: Boolean? = null,
	onToggle: (() -> Unit)? = null,
	topPadding: androidx.compose.ui.unit.Dp = 8.dp,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.let { if (onToggle != null) it.clickable(onClick = onToggle) else it }
			.padding(
				start = 24.dp,
				end = 16.dp,
				top = topPadding,
				bottom = 6.dp,
			),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			text = title,
			style = MaterialTheme.typography.titleSmall,
			fontWeight = FontWeight.SemiBold,
			color = MaterialTheme.colorScheme.primary,
		)
		if (isChecked != null && onToggle != null) {
			Spacer(Modifier.weight(1f))
			Checkbox(
				checked = isChecked,
				onCheckedChange = { onToggle() },
			)
		}
	}
}

@Composable
private fun SourceIcon(
	source: LibrarySourceOption,
	imageLoader: ImageLoader,
) {
	val context = LocalContext.current
	val fallbackPainter = remember(context, source.title) {
		DrawablePainter(
			FaviconDrawable(
				context = context,
				styleResId = R.style.FaviconDrawable_Small,
				name = source.title,
			),
		)
	}
	Box(
		modifier = Modifier
			.width(40.dp)
			.height(40.dp)
			.clip(RoundedCornerShape(8.dp)),
	) {
		if (source.isUnavailable) {
			Image(
				painter = fallbackPainter,
				contentDescription = null,
				modifier = Modifier.fillMaxSize(),
			)
		} else {
			val mangaSource = remember(source.iconSourceKey, source.title) {
				MangaSource(source.iconSourceKey, source.title)
			}
			val request = remember(context, source.iconUrl, mangaSource) {
				ImageRequest.Builder(context)
					.data(source.iconUrl ?: mangaSource.faviconUri())
					.apply {
						if (source.iconUrl == null) {
							mangaSourceExtra(mangaSource)
							suppressCaptchaErrors()
						}
					}
					.build()
			}
			AsyncImage(
				model = request,
				imageLoader = imageLoader,
				contentDescription = null,
				error = fallbackPainter,
				fallback = fallbackPainter,
				contentScale = ContentScale.Crop,
				modifier = Modifier.fillMaxSize(),
			)
		}
	}
}

@Composable
private fun SourceCheckboxRow(
	source: LibrarySourceOption,
	imageLoader: ImageLoader,
	isChecked: Boolean,
	onToggle: () -> Unit,
	onOpenManga: () -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.heightIn(min = 64.dp)
			.clickable(onClick = onToggle)
			.padding(horizontal = 16.dp, vertical = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		SourceIcon(source = source, imageLoader = imageLoader)
		Spacer(Modifier.width(12.dp))
		Column(modifier = Modifier.weight(1f)) {
			Text(
				text = source.title,
				style = MaterialTheme.typography.bodyLarge,
				color = MaterialTheme.colorScheme.onSurface,
			)
			Row(
				modifier = Modifier.clickable(onClick = onOpenManga),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(
					text = pluralStringResource(R.plurals.manga_count, source.mangaCount, source.mangaCount),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.primary,
				)
				Spacer(Modifier.width(4.dp))
				Icon(
					painter = painterResource(R.drawable.ic_chevron_right),
					contentDescription = null,
					tint = MaterialTheme.colorScheme.primary,
					modifier = Modifier.width(8.dp).height(12.dp),
				)
			}
		}
		Spacer(Modifier.width(12.dp))
		Checkbox(
			checked = isChecked,
			onCheckedChange = { onToggle() },
		)
	}
}

@Composable
private fun SourceDivider() {
	HorizontalDivider(
		modifier = Modifier.padding(start = 68.dp, end = 16.dp),
		thickness = 1.dp,
		color = MaterialTheme.colorScheme.outlineVariant,
	)
}
