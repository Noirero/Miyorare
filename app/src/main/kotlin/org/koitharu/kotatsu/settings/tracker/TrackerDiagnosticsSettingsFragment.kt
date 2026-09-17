package org.koitharu.kotatsu.settings.tracker

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings as SystemSettings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.powerManager
import org.koitharu.kotatsu.settings.compose.ActionSettingsItem
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.MiyorareTheme
import org.koitharu.kotatsu.settings.compose.PlainInfoSettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.tracker.ui.debug.TrackerDebugActivity

@AndroidEntryPoint
class TrackerDiagnosticsSettingsFragment : BaseComposeSettingsFragment(R.string.settings_diagnostics) {

    private val dozeAvailableState = MutableStateFlow(false)

    private val startForDozeResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        dozeAvailableState.value = isDozeIgnoreAvailable()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            MiyorareTheme {
                val dozeAvailable by dozeAvailableState.asStateFlow().collectAsState()
                TrackerDiagnosticsScreen(
                    dozeAvailable = dozeAvailable,
                    onTrackerDebug = {
                        startActivity(Intent(requireContext(), TrackerDebugActivity::class.java))
                    },
                    onIgnoreDoze = ::startIgnoreDoseActivity,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        dozeAvailableState.value = isDozeIgnoreAvailable()
    }

    private fun isDozeIgnoreAvailable(): Boolean {
        val context = context ?: return false
        val powerManager = context.powerManager ?: return false
        return !powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    @SuppressLint("BatteryLife")
    private fun startIgnoreDoseActivity() {
        val context = context ?: return
        val powerManager = context.powerManager ?: return
        if (powerManager.isIgnoringBatteryOptimizations(context.packageName)) return
        try {
            val intent = Intent(
                SystemSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                "package:${context.packageName}".toUri(),
            )
            startForDozeResult.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Snackbar.make(requireView(), R.string.operation_not_supported, Snackbar.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun TrackerDiagnosticsScreen(
    dozeAvailable: Boolean,
    onTrackerDebug: () -> Unit,
    onIgnoreDoze: () -> Unit,
) {
    SettingsScaffold {
        item {
            SettingsGroup(title = stringResource(R.string.settings_diagnostics)) {
                item { pos ->
                    ActionSettingsItem(
                        title = stringResource(R.string.tracker_debug_info),
                        subtitle = stringResource(R.string.tracker_debug_info_summary),
                        icon = R.drawable.ic_script,
                        shape = pos.shape,
                        onClick = onTrackerDebug,
                    )
                }
                if (dozeAvailable) {
                    item { pos ->
                        ActionSettingsItem(
                            title = stringResource(R.string.disable_battery_optimization),
                            subtitle = stringResource(R.string.disable_battery_optimization_summary),
                            icon = R.drawable.ic_battery_outline,
                            shape = pos.shape,
                            onClick = onIgnoreDoze,
                        )
                    }
                }
            }
        }
        item {
            PlainInfoSettingsItem(
                text = stringResource(R.string.tracker_warning),
                icon = R.drawable.ic_info_outline,
            )
        }
        item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
    }
}
