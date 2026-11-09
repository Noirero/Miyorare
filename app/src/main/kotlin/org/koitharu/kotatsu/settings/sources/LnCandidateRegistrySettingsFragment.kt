package org.koitharu.kotatsu.settings.sources

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.settings.compose.ActionSettingsItem
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.InfoSettingsItem
import org.koitharu.kotatsu.settings.compose.MiyorareTheme
import org.koitharu.kotatsu.settings.compose.SettingsItem
import javax.inject.Inject

/**
 * Candidate inventory is informational until a per-plugin sealed executable Source Pack is
 * validated. Listing 198 catalog entries must never install or execute unreviewed JS.
 */
@AndroidEntryPoint
class LnCandidateRegistrySettingsFragment : BaseComposeSettingsFragment(R.string.miyorare_novel_candidates_title) {
    @Inject lateinit var registry: LnCandidateRegistry

    private var snapshot by mutableStateOf<LnCandidateSnapshot?>(null)
    private var loading by mutableStateOf(false)
    private var errorMessage by mutableStateOf<String?>(null)

    override fun onResume() {
        super.onResume()
        if (snapshot == null && !loading && errorMessage == null) refresh()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MiyorareTheme {
                    LnCandidateRegistryScreen(
                        snapshot = snapshot,
                        loading = loading,
                        errorMessage = errorMessage,
                        onRefresh = ::refresh,
                    )
                }
            }
        }

    private fun refresh() {
        if (loading) return
        // Do not retain stale metadata while the upstream registry is being refreshed.
        loading = true
        snapshot = null
        errorMessage = null
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                snapshot = registry.fetch()
                errorMessage = null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                errorMessage = when (failure) {
                    is LnCandidateRegistryHttpException ->
                        if (failure.statusCode == 404 || failure.statusCode == 410) {
                            getString(R.string.miyorare_novel_candidates_not_published)
                        } else {
                            getString(R.string.miyorare_novel_candidates_network_error)
                        }
                    is java.io.IOException -> getString(R.string.miyorare_novel_candidates_network_error)
                    else -> getString(R.string.miyorare_novel_candidates_invalid)
                }
            } finally {
                loading = false
            }
        }
    }
}

@Composable
internal fun LnCandidateRegistryScreen(
    snapshot: LnCandidateSnapshot?,
    loading: Boolean,
    errorMessage: String?,
    onRefresh: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val candidates = remember(snapshot, query) {
        filterLnCandidates(snapshot?.candidates.orEmpty(), query)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("ln-candidates-list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "summary") {
            InfoSettingsItem(
                title = stringResource(R.string.miyorare_novel_candidates_title),
                subtitle = stringResource(R.string.miyorare_novel_candidates_read_only) +
                    (snapshot?.let {
                        " · " + stringResource(R.string.miyorare_novel_candidates_count, it.candidates.size)
                    } ?: ""),
                icon = R.drawable.ic_info_outline,
            )
        }
        item(key = "refresh") {
            ActionSettingsItem(
                title = stringResource(R.string.miyorare_novel_candidates_refresh),
                enabled = !loading,
                modifier = Modifier.testTag("ln-candidates-refresh"),
                icon = R.drawable.ic_updated,
                onClick = onRefresh,
            )
        }
        if (loading) item(key = "loading") {
            InfoSettingsItem(
                title = stringResource(R.string.tsuki_plugin_working),
                modifier = Modifier.testTag("ln-candidates-loading"),
                icon = R.drawable.ic_info_outline,
            )
        }
        if (errorMessage != null) item(key = "error") {
            InfoSettingsItem(
                title = stringResource(R.string.miyorare_novel_candidates_unavailable),
                modifier = Modifier.testTag("ln-candidates-error"),
                subtitle = errorMessage,
                icon = R.drawable.ic_info_outline,
            )
        }
        if (snapshot != null) {
            item(key = "query") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().testTag("ln-candidates-search"),
                    label = { Text(stringResource(R.string.tsuki_source_search_hint)) },
                    singleLine = true,
                )
            }
            if (candidates.isEmpty()) {
                item(key = "no-results") {
                    InfoSettingsItem(
                        title = stringResource(R.string.miyorare_novel_candidates_no_results),
                        modifier = Modifier.testTag("ln-candidates-empty"),
                        icon = R.drawable.ic_info_outline,
                    )
                }
            }
            for ((lang, label) in listOf("en" to "English", "id" to "Bahasa Indonesia")) {
                val filtered = candidates.filter { it.language == lang }
                if (filtered.isNotEmpty()) {
                    item(key = "section:$lang") {
                        InfoSettingsItem(
                            title = label,
                            subtitle = stringResource(R.string.miyorare_novel_candidates_count, filtered.size),
                            modifier = Modifier.testTag("ln-candidates-section:$lang"),
                            icon = R.drawable.ic_info_outline,
                        )
                    }
                }
                items(filtered, key = { it.id }) { candidate ->
                    SettingsItem(
                        modifier = Modifier.testTag("ln-candidate:" + candidate.id),
                        title = candidate.name,
                        subtitle = candidate.version + " · " + candidate.state +
                            " · API " + candidate.apiCheck +
                            " · Android " + candidate.androidCheck +
                            " · " + candidate.site,
                        icon = R.drawable.ic_info_outline,
                    )
                }
            }
        }
    }
}
