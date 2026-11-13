package org.koitharu.kotatsu.explore.ui

import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
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
private fun SourceOverview(
    title: String,
    state: SourceOverviewViewModel.State,
    onBack: () -> Unit,
    onSearch: (String) -> Unit,
    onFilter: () -> Unit,
    onCatalog: () -> Unit,
    onSeeAll: (SortOrder) -> Unit,
    onManga: (Manga) -> Unit,
    onRetry: () -> Unit,
) {
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme
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
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.shelves, key = { it.order.name }) { shelf ->
                        ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
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
                                entries == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
                                entries.isEmpty() -> Text(stringResource(R.string.nothing_found), Modifier.padding(16.dp))
                                else -> LazyRow(
                                    contentPadding = PaddingValues(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
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

@Composable
private fun OverviewCover(manga: Manga, onClick: () -> Unit) {
    val context = LocalContext.current
    val request = remember(context, manga) {
        ImageRequest.Builder(context).data(manga.coverUrl).mangaExtra(manga).build()
    }
    Column(Modifier.width(104.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Text(manga.title, Modifier.padding(top = 6.dp).heightIn(min = 48.dp),
            maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
    }
}
