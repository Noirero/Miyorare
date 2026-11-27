package org.koitharu.kotatsu.explore.ui

import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.util.ext.mangaExtra
import org.koitharu.kotatsu.databinding.ActivitySourceOverviewBinding
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.settings.compose.MiyorareTheme

@AndroidEntryPoint
class SourceOverviewActivity : BaseActivity<ActivitySourceOverviewBinding>() {
    private val model by viewModels<SourceOverviewViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ActivitySourceOverviewBinding.inflate(layoutInflater))
        viewBinding.root.setContent {
            MiyorareTheme {
                val state by model.state.collectAsState()
                LaunchedEffect(state.openCatalog) {
                    if (state.openCatalog && !isFinishing) {
                        router.openList(state.source, null, null)
                        finish()
                    }
                }
                SourceOverview(
                    title = state.source.getTitle(this),
                    state = state,
                    onBack = { finish() },
                    onSearch = { router.openSearch(state.source, it) },
                    onFilter = { router.openSourceFilters(state.source) },
                    onCatalog = { router.openList(state.source, null, null) },
                    onSeeAll = { router.openSourceShelf(state.source, it) },
                    onManga = { router.openDetails(it) },
                    onRetry = model::reload,
                )
            }
        }
    }

    override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat = insets

    override fun isNsfwContent() = model.state.map { it.source.isNsfw() }

    companion object {
        internal const val EXTRA_TRANSIENT_SORT = "source_overview_transient_sort"
        internal const val EXTRA_SHOW_FILTERS = "source_overview_show_filters"
    }
}

@Composable
internal fun SourceOverview(
    title: String,
    state: SourceOverviewViewModel.State,
    onBack: () -> Unit,
    onSearch: (String) -> Unit,
    onFilter: () -> Unit,
    onCatalog: () -> Unit,
    onSeeAll: (SortOrder) -> Unit,
    onManga: (Manga) -> Unit,
    onRetry: () -> Unit,
    skeletonEffect: OverviewSkeletonEffect = OverviewSkeletonEffect.EDGE_AND_SOFT,
) {
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme
    val shimmerPhase = rememberOverviewSkeletonPhase(
        !state.loading && !state.openCatalog && !state.failed &&
            state.shelves.any { !it.failed && it.entries == null },
    )
    Scaffold(
        // The Classic theme bridge maps the host surface pair; its background may still be a Material default.
        containerColor = colors.surface,
        contentColor = colors.onSurface,
        contentWindowInsets = WindowInsets.safeDrawing,
        floatingActionButton = {
            if (state.shelves.isNotEmpty()) {
                ExtendedFloatingActionButton(onClick = onFilter) { Text(stringResource(R.string.filter)) }
            }
        },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = 56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { if (searching) searching = false else onBack() }) {
                    Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                }
                if (searching) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(stringResource(R.string.search)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(
                    onClick = { if (!searching) searching = true else onSearch(query.trim()) },
                    enabled = !searching || query.isNotBlank(),
                ) {
                    Icon(painterResource(R.drawable.ic_search), stringResource(R.string.search))
                }
            }
            when {
                state.loading || state.openCatalog -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.failed -> Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.source_overview_load_failed))
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                    TextButton(onClick = onCatalog) { Text(stringResource(R.string.source_overview_see_all)) }
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag("source_overview_shelves"),
                    contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.shelves, key = { it.order.name }) { shelf ->
                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.elevatedCardColors(
                                containerColor = colors.surface,
                                contentColor = colors.onSurface,
                            ),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    stringResource(if (shelf.order == SortOrder.UPDATED) R.string.source_overview_latest else R.string.popular),
                                    Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                                )
                                TextButton(onClick = { onSeeAll(shelf.order) }) {
                                    Text(stringResource(R.string.source_overview_see_all))
                                }
                            }
                            val entries = shelf.entries
                            when {
                                shelf.failed -> Column(Modifier.padding(horizontal = 16.dp)) {
                                    Text(stringResource(R.string.source_overview_load_failed))
                                    TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                                }
                                entries?.isEmpty() == true -> Text(stringResource(R.string.nothing_found), Modifier.padding(16.dp))
                                else -> LazyRow(
                                    modifier = Modifier.testTag("source_overview_shelf_${shelf.order.name}"),
                                    contentPadding = PaddingValues(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    userScrollEnabled = entries != null,
                                ) {
                                    if (entries == null) {
                                        // Match the shelf's existing maximum; only visible placeholders are composed.
                                        items(12) {
                                            OverviewCoverFrame(
                                                title = null,
                                                modifier = Modifier.testTag("source_overview_skeleton_${shelf.order.name}"),
                                                coverModifier = Modifier.overviewSkeletonShimmer(
                                                    phase = shimmerPhase,
                                                    effect = skeletonEffect,
                                                    highlight = colors.onSurface,
                                                ),
                                            ) {}
                                        }
                                    } else {
                                        items(entries, key = { it.id }) { manga -> OverviewCover(manga) { onManga(manga) } }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewCover(manga: Manga, onClick: () -> Unit) {
    val context = LocalContext.current
    val request = remember(context, manga) {
        ImageRequest.Builder(context).data(manga.coverUrl).mangaExtra(manga).build()
    }
    OverviewCoverFrame(title = manga.title, modifier = Modifier.clickable(onClick = onClick)) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
    }
}

@Composable
private fun OverviewCoverFrame(
    title: String?,
    modifier: Modifier = Modifier,
    coverModifier: Modifier = Modifier,
    cover: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.width(104.dp).clip(shape).then(modifier)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .then(coverModifier),
            content = cover,
        )
        // Reserve the same two-line typography space at every font scale, without a visible fake title.
        val titleModifier = Modifier.padding(top = 6.dp).heightIn(min = 48.dp)
        Text(
            text = title.orEmpty(),
            modifier = if (title == null) titleModifier.clearAndSetSemantics {} else titleModifier,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Local comparison variants share one renderer; only EDGE_AND_SOFT is the app default. */
internal enum class OverviewSkeletonEffect {
    SOFT,
    EDGE,
    EDGE_AND_SOFT,
}

/** One clock per resumed overview, only while a supported shelf is still loading. */
@Composable
internal fun rememberOverviewSkeletonPhase(hasPendingShelves: Boolean): State<Float>? {
    if (!hasPendingShelves) return null
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    if (!lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) return null

    // Compose respects the system animator duration scale. At scale zero it settles
    // at the target value (1f), which the renderer deliberately draws as static.
    return rememberInfiniteTransition(label = "overview skeleton").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 2400, easing = LinearEasing)),
        label = "shared shimmer phase",
    )
}

/** Read the shared phase only at draw time; cache brushes and keep layout unchanged. */
internal fun Modifier.overviewSkeletonShimmer(
    phase: State<Float>?,
    effect: OverviewSkeletonEffect,
    highlight: Color,
): Modifier {
    if (phase == null) return this
    return drawWithCache {
        val bandWidth = size.width * 0.85f
        val edgeTilt = size.height * 0.16f
        val colors = listOf(highlight.copy(alpha = 0f), highlight.copy(alpha = 1f), highlight.copy(alpha = 0f))
        val faceBrush = Brush.horizontalGradient(colors, startX = 0f, endX = bandWidth)
        val edgeBrush = Brush.linearGradient(colors, start = Offset.Zero, end = Offset(bandWidth, edgeTilt))
        val strokeWidth = 1.dp.toPx()
        val inset = strokeWidth / 2f
        val corner = CornerRadius((10.dp.toPx() - inset).coerceAtLeast(0f))
        val edgeSize = Size((size.width - strokeWidth).coerceAtLeast(0f), (size.height - strokeWidth).coerceAtLeast(0f))

        onDrawBehind {
            val progress = phase.value
            if (progress <= 0f || progress >= 1f || bandWidth <= 0f) return@onDrawBehind

            if (effect != OverviewSkeletonEffect.EDGE) {
                val x = -bandWidth + (size.width + bandWidth) * progress
                translate(left = x) {
                    drawRect(faceBrush, topLeft = Offset(-x, 0f), size = size, alpha = 0.10f)
                }
            }
            if (effect != OverviewSkeletonEffect.SOFT) {
                // Keep the rounded stroke fixed while its diagonal highlight moves.
                val start = -bandWidth - edgeTilt * edgeTilt / bandWidth
                val end = size.width + size.height * edgeTilt / bandWidth
                val x = start + (end - start) * progress
                translate(left = x) {
                    drawRoundRect(
                        brush = edgeBrush,
                        topLeft = Offset(inset - x, inset),
                        size = edgeSize,
                        cornerRadius = corner,
                        alpha = 0.24f,
                        style = Stroke(strokeWidth),
                    )
                }
            }
        }
    }
}
