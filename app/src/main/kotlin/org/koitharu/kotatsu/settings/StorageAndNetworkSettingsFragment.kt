package org.koitharu.kotatsu.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.LocalMiyorareVisualPalette
import org.koitharu.kotatsu.core.ui.MiyorareVisualTokens
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.parsers.util.names
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.EditTextSettingsItem
import org.koitharu.kotatsu.settings.compose.ListSettingsItem
import org.koitharu.kotatsu.settings.compose.NavigationSettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsGroup
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
                    onOpenAdvanced = {
                        (activity as? SettingsActivity)?.openFragment(
                            AdvancedNetworkSettingsFragment::class.java,
                            null,
                            isFromRoot = false,
                        )
                    },
                    proxySummary = buildProxySummary(),
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
    onDataRemoval: () -> Unit,
    onOpenProxy: () -> Unit,
    onOpenAdvanced: () -> Unit,
    proxySummary: String,
) {
    val ctx = LocalContext.current
    val palette = LocalMiyorareVisualPalette.current
    val modern = palette.isModern

    val networkPolicyEntries = remember { ctx.resources.getStringArray(R.array.network_policy).toList() }
    val networkPolicyValues = remember { ctx.resources.getStringArray(R.array.values_network_policy).toList() }
    val dohEntries = remember { ctx.resources.getStringArray(R.array.doh_providers).toList() }
    val dohValues = remember { DoHProvider.entries.names().toList() }

    var prefetchContent by rememberStringPref(AppSettings.KEY_PREFETCH_CONTENT, "1")
    var doh by rememberStringPref(AppSettings.KEY_DOH, DoHProvider.NONE.name)
    var customDohUrl by rememberStringPref(DoHManager.KEY_CUSTOM_URL, "")
    var adblock by rememberBooleanPref(AppSettings.KEY_ADBLOCK, false)

    val selectedDohProvider = remember(doh) {
        DoHProvider.entries.firstOrNull { it.name == doh } ?: DoHProvider.NONE
    }
    val customDohIsValid = remember(customDohUrl) {
        customDohUrl.trim().toHttpUrlOrNull()?.scheme == "https"
    }
    val enabledLabel = stringResource(R.string.enabled)
    val disabledLabel = stringResource(R.string.disabled)
    val invalidLabel = stringResource(R.string.settings_status_invalid)
    val dohStatus = when {
        selectedDohProvider == DoHProvider.NONE -> disabledLabel
        selectedDohProvider == DoHProvider.CUSTOM && !customDohIsValid -> "⚠ $invalidLabel"
        else -> "✓ $enabledLabel"
    }
    val storageShape = if (modern) {
        RoundedCornerShape(MiyorareVisualTokens.RADIUS_CARD_DP.dp)
    } else {
        RoundedCornerShape(
            topStart = 24.dp,
            topEnd = 24.dp,
            bottomStart = 4.dp,
            bottomEnd = 4.dp,
        )
    }

    SettingsScaffold {
        item {
            StorageUsageRow(
                usage = usage,
                shape = storageShape,
            )
        }
        item {
            Spacer(
                Modifier
                    .height(if (modern) MiyorareVisualTokens.SPACING_S_DP.dp else 2.dp)
                    .fillMaxWidth(),
            )
        }
        item {
            SettingsGroup {
                item { pos ->
                    NavigationSettingsItem(
                        title = stringResource(R.string.data_removal),
                        icon = R.drawable.ic_delete,
                        shape = if (modern) {
                            pos.shape
                        } else {
                            RoundedCornerShape(
                                topStart = 4.dp,
                                topEnd = 4.dp,
                                bottomStart = 24.dp,
                                bottomEnd = 24.dp,
                            )
                        },
                        onClick = onDataRemoval,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
        item {
            SettingsGroup(title = stringResource(R.string.settings_group_network)) {
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
                        val customDohStatus = if (customDohIsValid) "✓ $enabledLabel" else "⚠ $invalidLabel"
                        EditTextSettingsItem(
                            title = "${stringResource(R.string.settings_custom_dns_address)} • $customDohStatus",
                            value = customDohUrl,
                            hint = "https://example.com/dns-query",
                            onValueChange = { customDohUrl = it.trim() },
                            icon = R.drawable.ic_web,
                            shape = pos.shape,
                        )
                    }
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
        item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
        item {
            SettingsGroup(title = stringResource(R.string.settings_advanced)) {
                item { pos ->
                    NavigationSettingsItem(
                        title = stringResource(R.string.settings_advanced_network),
                        subtitle = stringResource(R.string.settings_advanced_network_summary),
                        icon = R.drawable.ic_script,
                        shape = pos.shape,
                        onClick = onOpenAdvanced,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
    }
}
