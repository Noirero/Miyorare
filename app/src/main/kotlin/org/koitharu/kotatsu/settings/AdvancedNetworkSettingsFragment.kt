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
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.network.UserAgentManager
import org.koitharu.kotatsu.core.network.UserAgentMode
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.ConfirmDialog
import org.koitharu.kotatsu.settings.compose.MiyorareTheme
import org.koitharu.kotatsu.settings.compose.ListSettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref
import org.koitharu.kotatsu.settings.compose.rememberStringPref
import javax.inject.Inject

@AndroidEntryPoint
class AdvancedNetworkSettingsFragment : BaseComposeSettingsFragment(R.string.settings_advanced_network) {

    @Inject
    lateinit var userAgentManager: UserAgentManager

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            MiyorareTheme {
                AdvancedNetworkScreen(
                    userAgentManager = userAgentManager,
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
}

@Composable
private fun AdvancedNetworkScreen(
    userAgentManager: UserAgentManager,
    onSslRestartHint: () -> Unit,
) {
    val context = LocalContext.current
    val imageProxyEntries = remember { context.resources.getStringArray(R.array.image_proxies).toList() }
    val imageProxyValues = remember { context.resources.getStringArray(R.array.values_image_proxies).toList() }

    var userAgentMode by remember { mutableStateOf(userAgentManager.mode) }
    var customUserAgent by remember { mutableStateOf(userAgentManager.customUserAgent) }
    var randomUserAgent by remember { mutableStateOf(userAgentManager.randomUserAgent) }
    var imageProxy by rememberStringPref(AppSettings.KEY_IMAGES_PROXY, "-1")
    var sslBypass by rememberBooleanPref(AppSettings.KEY_SSL_BYPASS, false)
    var noOffline by rememberBooleanPref(AppSettings.KEY_OFFLINE_DISABLED, false)
    var showSslConfirm by remember { mutableStateOf(false) }

    SettingsScaffold {
        item {
            SettingsGroup(title = stringResource(R.string.settings_advanced_network_compatibility)) {
                item { pos ->
                    MihonConnectTimeoutSettingsItem(shape = pos.shape)
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
            }
        }
        item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
        item {
            SettingsGroup(title = stringResource(R.string.settings_advanced_network_behavior)) {
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
            }
        }
        item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
        item {
            SettingsGroup(title = stringResource(R.string.settings_advanced_network_security)) {
                item { pos ->
                    SwitchSettingsItem(
                        title = stringResource(R.string.ignore_ssl_errors),
                        subtitle = stringResource(R.string.ignore_ssl_errors_summary),
                        checked = sslBypass,
                        onCheckedChange = { requested ->
                            if (requested) {
                                showSslConfirm = true
                            } else {
                                sslBypass = false
                                onSslRestartHint()
                            }
                        },
                        icon = R.drawable.ic_lock,
                        shape = pos.shape,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
    }

    if (showSslConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.ignore_ssl_errors),
            message = stringResource(R.string.ignore_ssl_errors_summary),
            confirmLabel = stringResource(android.R.string.ok),
            dismissLabel = stringResource(android.R.string.cancel),
            onConfirm = {
                sslBypass = true
                showSslConfirm = false
                onSslRestartHint()
            },
            onDismiss = { showSslConfirm = false },
        )
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
    val activeLabel = stringResource(R.string.enabled)
    val inactiveLabel = stringResource(R.string.disabled)
    val details = when (mode) {
        UserAgentMode.DEFAULT -> stringResource(R.string.settings_user_agent_default)
        UserAgentMode.CUSTOM -> stringResource(R.string.settings_user_agent_custom)
        UserAgentMode.RANDOM -> "${stringResource(R.string.settings_user_agent_random)} • ${UserAgentManager.describe(randomUserAgent)}"
    }
    val summary = "${if (isActive) "✓ $activeLabel" else "⚠ $inactiveLabel"} • $details"
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
        mutableStateOf(initialRandomUserAgent.ifBlank { UserAgentManager.newRandomUserAgent() })
    }
    val canApply = mode != UserAgentMode.CUSTOM || customUserAgent.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                UserAgentModeRow(
                    label = stringResource(R.string.settings_user_agent_default),
                    selected = mode == UserAgentMode.DEFAULT,
                    onClick = { mode = UserAgentMode.DEFAULT },
                )
                UserAgentModeRow(
                    label = stringResource(R.string.settings_user_agent_custom),
                    selected = mode == UserAgentMode.CUSTOM,
                    onClick = { mode = UserAgentMode.CUSTOM },
                )
                UserAgentModeRow(
                    label = stringResource(R.string.settings_user_agent_random),
                    selected = mode == UserAgentMode.RANDOM,
                    onClick = { mode = UserAgentMode.RANDOM },
                )

                when (mode) {
                    UserAgentMode.DEFAULT -> Text(
                        text = stringResource(R.string.settings_user_agent_default_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    UserAgentMode.CUSTOM -> OutlinedTextField(
                        value = customUserAgent,
                        onValueChange = { customUserAgent = it },
                        label = { Text(stringResource(R.string.settings_user_agent_custom_label)) },
                        placeholder = { Text("Mozilla/5.0 (...)") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
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
                        TextButton(onClick = {
                            randomUserAgent = UserAgentManager.newRandomUserAgent(randomUserAgent)
                        }) {
                            Text(stringResource(R.string.settings_user_agent_randomize_again))
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
                Text(stringResource(R.string.settings_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
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
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}
