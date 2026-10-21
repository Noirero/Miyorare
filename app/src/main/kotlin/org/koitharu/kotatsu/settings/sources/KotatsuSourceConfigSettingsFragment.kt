package org.koitharu.kotatsu.settings.sources

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.kotatsu.runtime.KotatsuRuntimeManager
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.InfoSettingsItem
import org.koitharu.kotatsu.settings.compose.MiyorareTheme
import org.koitharu.kotatsu.settings.compose.SettingsItem
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import javax.inject.Inject

@AndroidEntryPoint
class KotatsuSourceConfigSettingsFragment : BaseComposeSettingsFragment(R.string.kotatsu_source_config_title) {

	@Inject lateinit var runtimeManager: KotatsuRuntimeManager

	private val sourceKey: String by lazy {
		requireNotNull(arguments?.getString(ARG_SOURCE_KEY)) { "Missing Kotatsu source key" }
	}
	private var uiState by mutableStateOf(KotatsuSourceConfigUiState())

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			MiyorareTheme {
				KotatsuSourceConfigScreen(
					state = uiState,
					onValueChanged = ::setConfigValue,
				)
			}
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		loadConfig()
	}

	private fun loadConfig() {
		viewLifecycleOwner.lifecycleScope.launch {
			uiState = uiState.copy(loading = true, error = null)
			val result = withContext(Dispatchers.IO) {
				runCatching {
					runtimeManager.initialize()
					val source = runtimeManager.getAvailableSources().firstOrNull { it.sourceKey == sourceKey }
						requireNotNull(source) { "Kotatsu source is unavailable in the active runtime: $sourceKey" }
					SourceConfigLoadResult(
						title = source.displayName,
						items = parseConfig(runtimeManager.getSourceConfig(sourceKey)),
					)
				}
			}
			result.fold(
				onSuccess = { loaded ->
					uiState = KotatsuSourceConfigUiState(
						loading = false,
						sourceTitle = loaded.title,
						items = loaded.items,
					)
				},
				onFailure = { error ->
					uiState = uiState.copy(
						loading = false,
						error = error.message ?: error.javaClass.simpleName,
					)
				},
			)
		}
	}

	private fun setConfigValue(item: KotatsuSourceConfigItem, value: Any?) {
		viewLifecycleOwner.lifecycleScope.launch {
			val result = withContext(Dispatchers.IO) {
				runCatching { parseConfig(runtimeManager.setSourceConfigValue(sourceKey, item.key, value)) }
			}
			result.fold(
				onSuccess = { updated -> uiState = uiState.copy(items = updated, error = null) },
				onFailure = { error -> uiState = uiState.copy(error = error.message ?: error.javaClass.simpleName) },
			)
		}
	}

	private data class SourceConfigLoadResult(
		val title: String,
		val items: List<KotatsuSourceConfigItem>,
	)

	companion object {
		const val ARG_SOURCE_KEY = "kotatsu_source_key"
	}
}

private data class KotatsuSourceConfigUiState(
	val loading: Boolean = true,
	val sourceTitle: String = "",
	val items: List<KotatsuSourceConfigItem> = emptyList(),
	val error: String? = null,
)

private data class KotatsuSourceConfigItem(
	val key: String,
	val type: String,
	val value: Any?,
	val defaultValue: Any?,
	val options: List<KotatsuSourceConfigOption>,
)

private data class KotatsuSourceConfigOption(
	val value: Any?,
	val title: String,
)

private fun parseConfig(root: JSONObject): List<KotatsuSourceConfigItem> {
	val array = root.optJSONArray("items") ?: return emptyList()
	return buildList(array.length()) {
		for (index in 0 until array.length()) {
			val item = array.optJSONObject(index) ?: continue
			val key = item.optString("key").takeIf { it.isNotBlank() } ?: continue
			val optionsArray = item.optJSONArray("options")
			val options = buildList {
				if (optionsArray != null) {
					for (optionIndex in 0 until optionsArray.length()) {
						val option = optionsArray.optJSONObject(optionIndex) ?: continue
						add(
							KotatsuSourceConfigOption(
								value = option.opt("value").takeUnless { it === JSONObject.NULL },
								title = option.optString("title"),
							),
						)
					}
				}
			}
			add(
				KotatsuSourceConfigItem(
					key = key,
					type = item.optString("type", "string"),
					value = item.opt("value").takeUnless { it === JSONObject.NULL },
					defaultValue = item.opt("defaultValue").takeUnless { it === JSONObject.NULL },
					options = options,
				),
			)
		}
	}
}

@Composable
private fun KotatsuSourceConfigScreen(
	state: KotatsuSourceConfigUiState,
	onValueChanged: (KotatsuSourceConfigItem, Any?) -> Unit,
) {
	var choiceItem by remember { mutableStateOf<KotatsuSourceConfigItem?>(null) }
	LazyColumn(
		modifier = Modifier.fillMaxSize(),
		contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		item(key = "source") {
			InfoSettingsItem(
				title = state.sourceTitle.ifBlank { stringResource(R.string.kotatsu_source_config_title) },
				subtitle = null,
				icon = R.drawable.ic_manga_source,
			)
		}
		if (state.loading) {
			item(key = "loading") {
				Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
					CircularProgressIndicator()
				}
			}
		} else if (state.items.isEmpty() && state.error == null) {
			item(key = "empty") {
				InfoSettingsItem(
					title = stringResource(R.string.kotatsu_source_config_empty),
					subtitle = null,
					icon = R.drawable.ic_manga_source,
				)
			}
		}
		state.error?.let { error ->
			item(key = "error") {
				InfoSettingsItem(
					title = stringResource(R.string.kotatsu_source_config_error, error),
					subtitle = null,
					icon = R.drawable.ic_manga_source,
				)
			}
		}
		items(state.items, key = { it.key }) { item ->
			when (item.type) {
				"boolean" -> SwitchSettingsItem(
					title = configTitle(item.key),
					subtitle = item.key,
					checked = item.value as? Boolean ?: item.defaultValue as? Boolean ?: false,
					onCheckedChange = { onValueChanged(item, it) },
				)
				"choice" -> {
					val selected = item.options.firstOrNull { valuesEqual(it.value, item.value) }
					SettingsItem(
						title = configTitle(item.key),
						subtitle = selected?.title?.takeIf { it.isNotBlank() }
							?: item.value?.toString()
							?: stringResource(R.string.kotatsu_source_config_default),
						onClick = { choiceItem = item },
					)
				}
				else -> StringConfigItem(item = item, onSave = { onValueChanged(item, it) })
			}
		}
	}

	choiceItem?.let { item ->
		AlertDialog(
			onDismissRequest = { choiceItem = null },
			title = { Text(configTitle(item.key)) },
			text = {
				Column {
					item.options.forEach { option ->
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.clickable {
									onValueChanged(item, option.value)
									choiceItem = null
								}
								.padding(vertical = 8.dp),
							verticalAlignment = Alignment.CenterVertically,
						) {
							RadioButton(
								selected = valuesEqual(option.value, item.value),
								onClick = {
									onValueChanged(item, option.value)
									choiceItem = null
								},
							)
							Text(option.title.ifBlank { stringResource(R.string.kotatsu_source_config_default) })
						}
					}
				}
			},
			confirmButton = {
				TextButton(onClick = { choiceItem = null }) {
					Text(stringResource(android.R.string.cancel))
				}
			},
		)
	}
}

@Composable
private fun StringConfigItem(
	item: KotatsuSourceConfigItem,
	onSave: (String) -> Unit,
) {
	var value by remember(item.key, item.value) { mutableStateOf(item.value?.toString().orEmpty()) }
	Column(modifier = Modifier.fillMaxWidth()) {
		OutlinedTextField(
			value = value,
			onValueChange = { value = it },
			modifier = Modifier.fillMaxWidth(),
			label = { Text(configTitle(item.key)) },
			supportingText = { Text(item.key) },
			singleLine = true,
		)
		TextButton(
			onClick = { onSave(value) },
			modifier = Modifier.align(Alignment.End),
		) {
			Text(stringResource(R.string.kotatsu_source_config_save))
		}
	}
}

@Composable
private fun configTitle(key: String): String = when (key) {
	"domain" -> stringResource(R.string.kotatsu_source_config_domain)
	"show_suspicious" -> stringResource(R.string.kotatsu_source_config_show_suspicious)
	"user_agent" -> stringResource(R.string.kotatsu_source_config_user_agent)
	"split_translations" -> stringResource(R.string.kotatsu_source_config_split_translations)
	"img_server" -> stringResource(R.string.kotatsu_source_config_image_server)
	"disable_updates" -> stringResource(R.string.kotatsu_source_config_disable_updates)
	"intercept_cloudflare" -> stringResource(R.string.kotatsu_source_config_intercept_cloudflare)
	else -> key.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

private fun valuesEqual(first: Any?, second: Any?): Boolean = when {
	first == null && second == null -> true
	first == null || second == null -> false
	else -> first.toString() == second.toString()
}
