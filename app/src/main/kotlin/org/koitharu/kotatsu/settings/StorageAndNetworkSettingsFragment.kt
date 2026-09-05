package org.koitharu.kotatsu.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.network.DoHManager
import org.koitharu.kotatsu.core.network.DoHProvider
import org.koitharu.kotatsu.core.network.UserAgentManager
import org.koitharu.kotatsu.core.network.UserAgentMode
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.FileSize
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.parsers.util.names
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.CategoryPalette
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.EditTextSettingsItem
import org.koitharu.kotatsu.settings.compose.ListSettingsItem
import org.koitharu.kotatsu.settings.compose.NavigationSettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.settings.compose.StorageUsageRow
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref
import org.koitharu.kotatsu.settings.compose.rememberStringPref
import org.koitharu.kotatsu.settings.userdata.storage.DataCleanupSettingsFragment
import org.koitharu.kotatsu.settings.userdata.storage.StorageUsage
import java.net.Proxy
import javax.inject.Inject

@AndroidEntryPoint
class StorageAndNetworkSettingsFragment : BaseComposeSettingsFragment(R.string.storage_and_network) {

	@Inject
	lateinit var settings: AppSettings

	@Inject
	lateinit var userAgentManager: UserAgentManager

	private val viewModel by viewModels<StorageAndNetworkSettingsViewModel>()
	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			DropSauceTheme {
				val usage by viewModel.storageUsage.collectAsState(null)
				StorageNetworkScreen(
					usage = usage,
					userAgentManager = userAgentManager,
					onDataRemoval = {
						(activity as? SettingsActivity)?.openFragment(
							DataCleanupSettingsFragment::class.java,
							null,
							isFromRoot = false,
						)
					},
					onOpenProxy = {
						(activity as? SettingsActivity)?.openFragment(
							ProxySettingsFragment::class.java,
							null,
							isFromRoot = false,
						)
					},
					proxySummary = buildProxySummary(),
					onSslRestartHint = {
						Snackbar.make(
							requireView(),
							R.string.settings_apply_restart_required,
							Snackbar.LENGTH_INDEFINITE,
						).show()
					},
				)
			}
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		viewModel.onError.observeEvent(viewLifecycleOwner) { err ->
			Snackbar.make(requireView(), err.message ?: "", Snackbar.LENGTH_SHORT).show()
		}
	}

	private fun buildProxySummary(): String {
		val type = settings.proxyType
		val address = settings.proxyAddress
		val port = settings.proxyPort
		return when {
			type == Proxy.Type.DIRECT -> getString(R.string.disabled)
			address.isNullOrEmpty() || port == 0 -> getString(R.string.invalid_proxy_configuration)
			else -> "$address:$port"
		}
	}
}

@Composable
private fun StorageNetworkScreen(
	usage: StorageUsage?,
	userAgentManager: UserAgentManager,
	onDataRemoval: () -> Unit,
	onOpenProxy: () -> Unit,
	proxySummary: String,
	onSslRestartHint: () -> Unit,
) {
	val ctx = LocalContext.current
	val colors = CategoryPalette.forKey("storage")

	val networkPolicyEntries = remember {
		ctx.resources.getStringArray(R.array.network_policy).toList()
	}
	val networkPolicyValues = remember {
		ctx.resources.getStringArray(R.array.values_network_policy).toList()
	}
	val dohEntries = remember { ctx.resources.getStringArray(R.array.doh_providers).toList() }
	val dohValues = remember { DoHProvider.entries.names().toList() }
	val imageProxyEntries = remember {
		ctx.resources.getStringArray(R.array.image_proxies).toList()
	}
	val imageProxyValues = remember {
		ctx.resources.getStringArray(R.array.values_image_proxies).toList()
	}

	var prefetchContent by rememberStringPref(AppSettings.KEY_PREFETCH_CONTENT, "1")
	var doh by rememberStringPref(AppSettings.KEY_DOH, DoHProvider.NONE.name)
	var customDohUrl by rememberStringPref(DoHManager.KEY_CUSTOM_URL, "")
	var userAgentMode by remember { mutableStateOf(userAgentManager.mode) }
	var customUserAgent by remember { mutableStateOf(userAgentManager.customUserAgent) }
	var randomUserAgent by remember { mutableStateOf(userAgentManager.randomUserAgent) }
	var imageProxy by rememberStringPref(AppSettings.KEY_IMAGES_PROXY, "-1")
	var sslBypass by rememberBooleanPref(AppSettings.KEY_SSL_BYPASS, false)
	var noOffline by rememberBooleanPref(AppSettings.KEY_OFFLINE_DISABLED, false)
	var adblock by rememberBooleanPref(AppSettings.KEY_ADBLOCK, false)

	val selectedDohProvider = remember(doh) {
		DoHProvider.entries.firstOrNull { it.name == doh } ?: DoHProvider.NONE
	}
	val customDohIsValid = remember(customDohUrl) {
		customDohUrl.trim().toHttpUrlOrNull()?.scheme == "https"
	}
	val dohStatus = when {
		selectedDohProvider == DoHProvider.NONE -> "Nonaktif"
		selectedDohProvider == DoHProvider.CUSTOM && !customDohIsValid -> "⚠ Belum aktif"
		else -> "✓ Aktif"
	}

	val storageSummary = usage?.let {
		val used = it.savedManga.bytes + it.pagesCache.bytes + it.otherCache.bytes
		val total = used + it.available.bytes
		ctx.getString(
			R.string.memory_usage_pattern,
			FileSize.BYTES.format(ctx, used),
			FileSize.BYTES.format(ctx, total),
		)
	}

	SettingsScaffold {
		// Inline storage chart + legend (segmented bar). Replaces the simple Info row.
		item {
			StorageUsageRow(
				usage = usage,
				shape = androidx.compose.foundation.shape.RoundedCornerShape(
					topStart = 24.dp,
					topEnd = 24.dp,
					bottomStart = 4.dp,
					bottomEnd = 4.dp,
				),
			)
		}
		item { Spacer(Modifier.height(2.dp).fillMaxWidth()) }
		item {
			SettingsGroup {
				item { pos ->
					NavigationSettingsItem(
						title = stringResource(R.string.data_removal),
						icon = R.drawable.ic_delete,
						shape = androidx.compose.foundation.shape.RoundedCornerShape(
							topStart = 4.dp,
							topEnd = 4.dp,
							bottomStart = 24.dp,
							bottomEnd = 24.dp,
						),
						onClick = onDataRemoval,
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = "Network") {
				item { pos ->
					ListSettingsItem(
						title = stringResource(R.string.prefetch_content),
						entries = networkPolicyEntries,
						entryValues = networkPolicyValues,
						selectedValue = prefetchContent,
						onValueChange = { prefetchContent = it },
						icon = R.drawable.ic_downloading,
						shape = pos.shape,
					)
				}
				item { pos ->
					NavigationSettingsItem(
						title = stringResource(R.string.proxy),
						subtitle = proxySummary,
						icon = R.drawable.ic_plug_large,
						shape = pos.shape,
						onClick = onOpenProxy,
					)
				}
				item { pos ->
					ListSettingsItem(
						title = "${stringResource(R.string.dns_over_https)} • $dohStatus",
						entries = dohEntries,
						entryValues = dohValues,
						selectedValue = doh,
						onValueChange = { doh = it },
						icon = R.drawable.ic_web,
						shape = pos.shape,
					)
				}
				if (selectedDohProvider == DoHProvider.CUSTOM) {
					item { pos ->
						EditTextSettingsItem(
							title = if (customDohIsValid) {
								"Alamat DNS Kustom • ✓ Aktif"
							} else {
								"Alamat DNS Kustom • ⚠ Tidak valid"
							},
							value = customDohUrl,
							hint = "https://example.com/dns-query",
							onValueChange = { customDohUrl = it.trim() },
							icon = R.drawable.ic_web,
							shape = pos.shape,
						)
					}
				}
				item { pos ->
					UserAgentSettingsItem(
						title = stringResource(R.string.user_agent),
						mode = userAgentMode,
						customUserAgent = customUserAgent,
						randomUserAgent = randomUserAgent,
						shape = pos.shape,
						onApply = { mode, custom, random ->
							userAgentManager.apply(mode, custom, random)
							userAgentMode = userAgentManager.mode
							customUserAgent = userAgentManager.customUserAgent
							randomUserAgent = userAgentManager.randomUserAgent
						},
					)
				}
				item { pos ->
					ListSettingsItem(
						title = stringResource(R.string.images_proxy_title),
						entries = imageProxyEntries,
						entryValues = imageProxyValues,
						selectedValue = imageProxy,
						onValueChange = { imageProxy = it },
						icon = R.drawable.ic_images,
						shape = pos.shape,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.ignore_ssl_errors),
						subtitle = stringResource(R.string.ignore_ssl_errors_summary),
						checked = sslBypass,
						onCheckedChange = {
							sslBypass = it
							onSslRestartHint()
						},
						icon = R.drawable.ic_lock,
						shape = pos.shape,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.disable_connectivity_check),
						subtitle = stringResource(R.string.disable_connectivity_check_summary),
						checked = noOffline,
						onCheckedChange = { noOffline = it },
						icon = R.drawable.ic_offline,
						shape = pos.shape,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.adblock),
						subtitle = stringResource(R.string.adblock_summary),
						checked = adblock,
						onCheckedChange = { adblock = it },
						icon = R.drawable.ic_disable,
						shape = pos.shape,
					)
				}
			}
		}
		item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
	}
}

@Composable
private fun UserAgentSettingsItem(
	title: String,
	mode: UserAgentMode,
	customUserAgent: String,
	randomUserAgent: String,
	shape: Shape,
	onApply: (UserAgentMode, String, String) -> Unit,
) {
	var showDialog by remember { mutableStateOf(false) }
	val isActive = when (mode) {
		UserAgentMode.DEFAULT -> true
		UserAgentMode.CUSTOM -> customUserAgent.isNotBlank()
		UserAgentMode.RANDOM -> randomUserAgent.isNotBlank()
	}
	val details = when (mode) {
		UserAgentMode.DEFAULT -> "Default (device WebView)"
		UserAgentMode.CUSTOM -> "Custom"
		UserAgentMode.RANDOM -> "Random • ${UserAgentManager.describe(randomUserAgent)}"
	}
	val summary = "${if (isActive) "✓ Aktif" else "⚠ Belum aktif"} • $details"
	SettingsItem(
		title = title,
		subtitle = summary,
		icon = R.drawable.ic_script,
		shape = shape,
		onClick = { showDialog = true },
	)
	if (showDialog) {
		UserAgentDialog(
			title = title,
			initialMode = mode,
			initialCustomUserAgent = customUserAgent,
			initialRandomUserAgent = randomUserAgent,
			onApply = onApply,
			onDismiss = { showDialog = false },
		)
	}
}

@Composable
private fun UserAgentDialog(
	title: String,
	initialMode: UserAgentMode,
	initialCustomUserAgent: String,
	initialRandomUserAgent: String,
	onApply: (UserAgentMode, String, String) -> Unit,
	onDismiss: () -> Unit,
) {
	var mode by remember { mutableStateOf(initialMode) }
	var customUserAgent by remember { mutableStateOf(initialCustomUserAgent) }
	var randomUserAgent by remember {
		mutableStateOf(
			initialRandomUserAgent.ifBlank { UserAgentManager.newRandomUserAgent() },
		)
	}
	val canApply = mode != UserAgentMode.CUSTOM || customUserAgent.isNotBlank()

	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(title) },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
				UserAgentModeRow(
					label = "Default (device WebView)",
					selected = mode == UserAgentMode.DEFAULT,
					onClick = { mode = UserAgentMode.DEFAULT },
				)
				UserAgentModeRow(
					label = "Custom",
					selected = mode == UserAgentMode.CUSTOM,
					onClick = { mode = UserAgentMode.CUSTOM },
				)
				UserAgentModeRow(
					label = "Random",
					selected = mode == UserAgentMode.RANDOM,
					onClick = { mode = UserAgentMode.RANDOM },
				)

				when (mode) {
					UserAgentMode.DEFAULT -> Text(
						text = "Uses the current device WebView User-Agent.",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.padding(top = 8.dp),
					)

					UserAgentMode.CUSTOM -> OutlinedTextField(
						value = customUserAgent,
						onValueChange = { customUserAgent = it },
						label = { Text("Custom User-Agent") },
						placeholder = { Text("Mozilla/5.0 (...)") },
						modifier = Modifier
							.fillMaxWidth()
							.padding(top = 8.dp),
						minLines = 3,
						maxLines = 5,
					)

					UserAgentMode.RANDOM -> Column(
						modifier = Modifier.padding(top = 8.dp),
						verticalArrangement = Arrangement.spacedBy(6.dp),
					) {
						Text(
							text = UserAgentManager.describe(randomUserAgent),
							style = MaterialTheme.typography.titleSmall,
						)
						Text(
							text = randomUserAgent,
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
						TextButton(
							onClick = {
								randomUserAgent = UserAgentManager.newRandomUserAgent(randomUserAgent)
							},
						) {
							Text("Randomize again")
						}
					}
				}
			}
		},
		confirmButton = {
			TextButton(
				enabled = canApply,
				onClick = {
					onApply(mode, customUserAgent.trim(), randomUserAgent.trim())
					onDismiss()
				},
			) {
				Text("Apply")
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss) {
				Text("Cancel")
			}
		},
	)
}

@Composable
private fun UserAgentModeRow(
	label: String,
	selected: Boolean,
	onClick: () -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onClick)
			.padding(vertical = 4.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		RadioButton(
			selected = selected,
			onClick = onClick,
		)
		Spacer(Modifier.width(8.dp))
		Text(
			text = label,
			style = MaterialTheme.typography.bodyLarge,
		)
	}
}