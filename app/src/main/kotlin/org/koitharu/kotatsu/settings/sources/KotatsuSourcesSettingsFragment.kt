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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.kotatsu.model.KotatsuMangaSource
import org.koitharu.kotatsu.kotatsu.runtime.KotatsuRuntimeManager
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.InfoSettingsItem
import org.koitharu.kotatsu.settings.compose.MiyorareTheme
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class KotatsuSourcesSettingsFragment : BaseComposeSettingsFragment(R.string.kotatsu_parsers_sources) {

	@Inject lateinit var runtimeManager: KotatsuRuntimeManager

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			MiyorareTheme {
				val sources by runtimeManager.sources.collectAsState(initial = emptyList())
				val enabledSourceKeys by runtimeManager.enabledSourceKeys.collectAsState()
				KotatsuSourcesScreen(
					sources = sources,
					enabledSourceKeys = enabledSourceKeys,
					onSourceEnabled = runtimeManager::setSourceEnabled,
				)
			}
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
			runtimeManager.initialize()
		}
	}
}

@Composable
private fun KotatsuSourcesScreen(
	sources: List<KotatsuMangaSource>,
	enabledSourceKeys: Set<String>,
	onSourceEnabled: (String, Boolean) -> Unit,
) {
	var query by rememberSaveable { mutableStateOf("") }
	val normalizedQuery = query.trim().lowercase(Locale.ROOT)
	val visibleSources = sources.asSequence()
		.filter { source ->
			normalizedQuery.isEmpty() ||
				source.displayName.lowercase(Locale.ROOT).contains(normalizedQuery) ||
				source.sourceKey.lowercase(Locale.ROOT).contains(normalizedQuery) ||
				source.language.lowercase(Locale.ROOT).contains(normalizedQuery) ||
				source.contentType.lowercase(Locale.ROOT).contains(normalizedQuery)
		}
		.sortedWith(compareBy<KotatsuMangaSource> { it.displayName.lowercase(Locale.ROOT) }.thenBy { it.sourceKey })
		.toList()
	val availableCount = sources.count { !it.isBroken }
	val enabledCount = sources.count { !it.isBroken && it.sourceKey in enabledSourceKeys }

	LazyColumn(
		modifier = Modifier.fillMaxSize(),
		contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		item(key = "summary") {
			InfoSettingsItem(
				title = stringResource(R.string.kotatsu_parsers_sources),
				subtitle = stringResource(R.string.kotatsu_parsers_enabled_count, enabledCount, availableCount),
				icon = R.drawable.ic_manga_source,
			)
		}
		item(key = "search") {
			OutlinedTextField(
				value = query,
				onValueChange = { query = it },
				modifier = Modifier.fillMaxWidth(),
				label = { androidx.compose.material3.Text(stringResource(R.string.kotatsu_parsers_search_sources)) },
				singleLine = true,
			)
		}
		items(
			items = visibleSources,
			key = { it.sourceKey },
		) { source ->
			val metadata = buildString {
				append(source.sourceKey)
				if (source.language.isNotBlank()) {
					append(" • ")
					append(source.language.uppercase(Locale.ROOT))
				}
				if (source.contentType.isNotBlank()) {
					append(" • ")
					append(source.contentType)
				}
				if (source.isBroken) {
					append("\n")
					append(stringResource(R.string.kotatsu_parsers_broken_source))
				}
			}
			SwitchSettingsItem(
				title = source.displayName,
				subtitle = metadata,
				checked = source.sourceKey in enabledSourceKeys && !source.isBroken,
				onCheckedChange = { checked -> onSourceEnabled(source.sourceKey, checked) },
				icon = R.drawable.ic_manga_source,
				enabled = !source.isBroken,
			)
		}
	}
}
