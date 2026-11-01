package org.koitharu.kotatsu.settings.sources.migration

import android.os.Bundle
import android.view.LayoutInflater
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
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
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.main.ui.nav.DrawablePainter
import org.koitharu.kotatsu.settings.SettingsActivity
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.MiyorareTheme
import javax.inject.Inject

@AndroidEntryPoint
class BrokenSourcesMigrationFragment :
	BaseComposeSettingsFragment(R.string.manga_migration) {

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
					onRetry = viewModel::retry,
					onOpenManga = { mangaIds ->
						if (mangaIds.isNotEmpty()) (requireActivity() as SettingsActivity).openFragment(
							SourceMangaListFragment::class.java,
							SourceMangaListFragment.argsForMangaIds(
								mangaIds, getString(R.string.manga_migration),
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
		// with bottom padding equal to the appbar's current expansion so the bottom action remains visible.
		val appBar = (requireActivity() as AppBarOwner).appBar
		val offsetListener = AppBarLayout.OnOffsetChangedListener { bar, verticalOffset ->
			view.updatePadding(bottom = bar.totalScrollRange + verticalOffset)
		}
		appBar.addOnOffsetChangedListener(offsetListener)
		view.doOnDetach { appBar.removeOnOffsetChangedListener(offsetListener) }
	}

}

@Composable
internal fun BrokenSourcesMigrationScreen(
	state: BrokenSourcesMigrationState,
	imageLoader: ImageLoader,
	onRetry: () -> Unit,
	onOpenManga: (Collection<Long>) -> Unit,
) {
	// A source row opens its manga. "Semua" opens the same migration for all its manga.
	// Exact stored source keys, rather than display titles, define both actions.
	var sourceKey by rememberSaveable { mutableStateOf<String?>(null) }
	var query by rememberSaveable { mutableStateOf("") }
	val context = LocalContext.current
	val sourceRows = state.sources.flatMap { group ->
		group.variants.map { variant -> group to variant }
	}
	val activeSource = sourceRows.firstOrNull { it.second.key == sourceKey }
	androidx.activity.compose.BackHandler(enabled = activeSource != null) {
		sourceKey = null
		query = ""
	}
	Column(Modifier.fillMaxSize()) {
		when {
			state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
				CircularProgressIndicator()
			}
			state.loadFailed -> Column(Modifier.fillMaxSize().padding(24.dp),
				verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
				Text("Daftar source tidak dapat dimuat", style = MaterialTheme.typography.titleMedium)
				TextButton(onClick = onRetry) { Text(stringResource(R.string.migration_retry)) }
			}
			activeSource == null -> LazyColumn(
				modifier = Modifier.fillMaxSize().nestedScroll(rememberNestedScrollInteropConnection()),
				contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
				verticalArrangement = Arrangement.spacedBy(10.dp),
			) {
				item { Text("Pilih source", style = MaterialTheme.typography.titleLarge) }
				if (sourceRows.isEmpty()) item { Text("Tidak ada manga yang dapat dimigrasikan.") }
				items(sourceRows, key = { it.second.key }) { (group, variant) ->
					val ids = state.manga.filter { it.source.name == variant.key }.map(Manga::id)
					Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
						.clickable { sourceKey = variant.key; query = "" }
						.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
						SourceIcon(group, imageLoader)
						Spacer(Modifier.width(12.dp))
						Column(Modifier.weight(1f)) {
							Text(variant.title, style = MaterialTheme.typography.bodyMedium,
								fontWeight = FontWeight.SemiBold)
							Text(pluralStringResource(R.plurals.manga_count, ids.size, ids.size),
								style = MaterialTheme.typography.bodySmall)
							if (group.isUnavailable) Text("Source tidak tersedia",
								style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
						}
						TextButton(onClick = { onOpenManga(ids) }, enabled = ids.isNotEmpty()) { Text("Semua") }
					}
				}
			}
			else -> {
				val source = activeSource.second
				val manga = state.manga.filter { item ->
					item.source.name == source.key &&
						(query.isBlank() || item.title.contains(query.trim(), ignoreCase = true))
				}
				Column(Modifier.fillMaxSize()) {
					Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
						verticalAlignment = Alignment.CenterVertically) {
						TextButton(onClick = { sourceKey = null; query = "" }) { Text("‹ Source") }
						Text(source.title, style = MaterialTheme.typography.titleMedium,
							modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
					}
					LazyColumn(Modifier.weight(1f),
						contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
						verticalArrangement = Arrangement.spacedBy(8.dp)) {
						item {
							OutlinedTextField(value = query, onValueChange = { query = it },
								label = { Text("Cari manga") }, singleLine = true,
								modifier = Modifier.fillMaxWidth().testTag("migration-landing-search"))
						}
						items(manga, key = Manga::id) { item ->
							Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
								.clickable { onOpenManga(listOf(item.id)) }
								.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
								val cover = remember(item.id, item.coverUrl, item.source) {
									item.coverUrl?.let {
										ImageRequest.Builder(context).data(it).mangaSourceExtra(item.source).build()
									}
								}
								if (cover != null) AsyncImage(model = cover, imageLoader = imageLoader,
									contentDescription = null, modifier = Modifier.width(48.dp).height(68.dp)
										.clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
								Spacer(Modifier.width(12.dp))
								Column(Modifier.weight(1f)) {
									Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
									Text(item.source.getTitle(context).toString(),
										style = MaterialTheme.typography.bodySmall)
								}
								Text("›")
							}
						}
					}
					Surface(tonalElevation = 3.dp) {
						Button(onClick = { onOpenManga(state.manga.filter { it.source.name == source.key }.map(Manga::id)) },
							modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)) {
							Text("Migrasikan semua (" + state.manga.count { it.source.name == source.key } + ")")
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
