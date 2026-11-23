package org.koitharu.kotatsu.settings.sources

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import coil3.compose.AsyncImage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.kotatsu.model.KotatsuMangaSource
import org.koitharu.kotatsu.kotatsu.runtime.KotatsuRuntimeManager
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.settings.SettingsActivity
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.InfoSettingsItem
import org.koitharu.kotatsu.settings.compose.MiyorareSettingsTheme
import org.koitharu.kotatsu.settings.compose.SettingsItem
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class KotatsuSourcesSettingsFragment : BaseComposeSettingsFragment(R.string.kotatsu_parsers_sources) {

	@Inject lateinit var runtimeManager: KotatsuRuntimeManager
	@Inject lateinit var settings: AppSettings

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			MiyorareSettingsTheme("extensions") {
				val sources by runtimeManager.sources.collectAsState(initial = emptyList())
				val enabledSourceKeys by runtimeManager.enabledSourceKeys.collectAsState()
				val nsfwDisabled by remember {
					settings.observeAsFlow(AppSettings.KEY_DISABLE_NSFW) { isNsfwContentDisabled }
				}.collectAsState(initial = settings.isNsfwContentDisabled)
				KotatsuSourcesScreen(
					sources = sources,
					enabledSourceKeys = enabledSourceKeys,
					nsfwDisabled = nsfwDisabled,
					onSourceEnabled = runtimeManager::setSourceEnabled,
					onSourceOpen = { router.openSourceOverview(it) },
					onSourceSettings = ::openSourceSettings,
					loadWebsiteIcon = ::findWebsiteIcon,
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

	private fun openSourceSettings(source: KotatsuMangaSource) {
		(requireActivity() as SettingsActivity).openFragment(
			KotatsuSourceConfigSettingsFragment::class.java,
			args = Bundle().apply {
				putString(KotatsuSourceConfigSettingsFragment.ARG_SOURCE_KEY, source.sourceKey)
			},
			isFromRoot = false,
		)
	}

	private suspend fun findWebsiteIcon(source: KotatsuMangaSource): String? = withContext(Dispatchers.IO) {
		runCatchingCancellable {
			val options = runtimeManager.getSourceConfig(source.sourceKey).optJSONArray("items")
				?: return@runCatchingCancellable null
			for (index in 0 until options.length()) {
				val option = options.optJSONObject(index) ?: continue
				if (option.optString("key") == "domain") {
					return@runCatchingCancellable kotatsuWebsiteIconUrl(option.optString("value"))
				}
			}
			null
		}.getOrNull()
	}
}

@Composable
internal fun KotatsuSourcesScreen(
	sources: List<KotatsuMangaSource>,
	enabledSourceKeys: Set<String>,
	nsfwDisabled: Boolean,
	onSourceEnabled: (String, Boolean) -> Unit,
	onSourceOpen: (KotatsuMangaSource) -> Unit,
	onSourceSettings: (KotatsuMangaSource) -> Unit,
	loadWebsiteIcon: suspend (KotatsuMangaSource) -> String?,
) {
	var query by rememberSaveable { mutableStateOf("") }
	var language by rememberSaveable { mutableStateOf<String?>(null) }
	val visibleSources = remember(sources, query, language) { filterKotatsuSources(sources, query, language) }
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
				label = { Text(stringResource(R.string.kotatsu_parsers_search_sources)) },
				singleLine = true,
			)
		}
		item(key = "language") {
			KotatsuLanguageFilter(sources, language) { language = it }
		}
		if (sources.isNotEmpty() && visibleSources.isEmpty()) {
			item(key = "empty") {
				Text(stringResource(R.string.nothing_found), Modifier.padding(16.dp))
			}
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
			val enabled = source.sourceKey in enabledSourceKeys && !source.isBroken
			val canBrowse = !source.isBroken && !(nsfwDisabled && source.isNsfw())
			val switchLabel = stringResource(R.string.kotatsu_source_enable_named, source.displayName)
			SettingsItem(
				title = source.displayName,
				subtitle = metadata,
				enabled = !source.isBroken,
				onClick = if (canBrowse) ({ onSourceOpen(source) }) else null,
				leading = { KotatsuWebsiteIcon(source, canBrowse, loadWebsiteIcon) },
				trailing = {
					Row(verticalAlignment = Alignment.CenterVertically) {
						IconButton(onClick = { onSourceSettings(source) }, enabled = !source.isBroken) {
							Icon(
								painterResource(R.drawable.ic_more_vert),
								contentDescription = stringResource(R.string.kotatsu_source_settings_named, source.displayName),
							)
						}
						Switch(
							checked = enabled,
							onCheckedChange = { checked -> onSourceEnabled(source.sourceKey, checked) },
							enabled = !source.isBroken,
							modifier = Modifier.semantics { contentDescription = switchLabel },
						)
					}
				},
			)
		}
	}
}

@Composable
private fun KotatsuLanguageFilter(
	sources: List<KotatsuMangaSource>,
	selected: String?,
	onSelect: (String?) -> Unit,
) {
	var expanded by remember { mutableStateOf(false) }
	val displayLocale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
	val unknown = stringResource(R.string.kotatsu_parsers_unknown_language)
	val all = stringResource(R.string.all_languages)
	val languages = remember(sources, displayLocale, unknown) {
		val collator = java.text.Collator.getInstance(displayLocale)
		sources.map { it.language.trim().lowercase(Locale.ROOT) }.distinct()
			.map { code ->
				val label = if (code.isEmpty()) unknown else Locale.forLanguageTag(code.replace('_', '-'))
					.getDisplayName(displayLocale).ifBlank { code.uppercase(Locale.ROOT) }
				code to label
			}
			.sortedWith { left, right -> collator.compare(left.second, right.second) }
	}
	Box {
		FilterChip(
			selected = selected != null,
			onClick = { expanded = true },
			enabled = languages.isNotEmpty(),
			label = { Text(languages.firstOrNull { it.first == selected }?.second ?: all) },
			leadingIcon = {
				Icon(painterResource(R.drawable.ic_language), contentDescription = null, modifier = Modifier.size(18.dp))
			},
		)
		DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
			DropdownMenuItem(
				text = { Text(all) },
				onClick = {
					expanded = false
					onSelect(null)
				},
			)
			for ((code, label) in languages) {
				DropdownMenuItem(
					text = { Text(label) },
					onClick = {
						expanded = false
						onSelect(code)
					},
				)
			}
		}
	}
}

@Composable
private fun KotatsuWebsiteIcon(
	source: KotatsuMangaSource,
	load: Boolean,
	loadWebsiteIcon: suspend (KotatsuMangaSource) -> String?,
) {
	val url by produceState<String?>(null, source, load) {
		value = if (load) loadWebsiteIcon(source) else null
	}
	val fallback = painterResource(R.drawable.ic_web)
	AsyncImage(
		model = url,
		contentDescription = null,
		placeholder = fallback,
		error = fallback,
		fallback = fallback,
		modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)),
	)
}

internal fun filterKotatsuSources(
	sources: List<KotatsuMangaSource>,
	query: String,
	language: String?,
): List<KotatsuMangaSource> {
	val search = query.trim()
	return sources.asSequence()
		.filter { language == null || it.language.trim().equals(language, ignoreCase = true) }
		.filter { source ->
			source.displayName.contains(search, ignoreCase = true) ||
				source.sourceKey.contains(search, ignoreCase = true) ||
				source.language.contains(search, ignoreCase = true) ||
				source.contentType.contains(search, ignoreCase = true)
		}
		.sortedWith(compareBy<KotatsuMangaSource> { it.displayName.lowercase(Locale.ROOT) }.thenBy { it.sourceKey })
		.toList()
}

internal fun kotatsuWebsiteIconUrl(domain: String): String? {
	val value = domain.trim()
	if (value.isEmpty()) return null
	val home = (if ("://" in value) value else "https://$value").toHttpUrlOrNull() ?: return null
	if (home.username.isNotEmpty() || home.password.isNotEmpty()) return null
	return home.resolve("/favicon.ico")?.toString()
}
