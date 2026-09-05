package org.koitharu.kotatsu.settings.sources

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.explore.data.SourcesSortOrder
import org.koitharu.kotatsu.extensions.install.ExtensionInstallerMethod
import org.koitharu.kotatsu.extensions.install.ExtensionInstallerPreferences
import org.koitharu.kotatsu.extensions.install.SHIZUKU_PACKAGE_NAME
import org.koitharu.kotatsu.extensions.install.ShizukuExtensionInstaller
import org.koitharu.kotatsu.extensions.install.ShizukuInstallerStatus
import org.koitharu.kotatsu.extensions.install.currentStatus
import org.koitharu.kotatsu.extensions.install.extensionInstallerChoiceLabel
import org.koitharu.kotatsu.extensions.install.extensionInstallerMethodSummary
import org.koitharu.kotatsu.extensions.install.extensionInstallerMethodTitle
import org.koitharu.kotatsu.extensions.install.shizukuInstallerStatusText
import org.koitharu.kotatsu.parsers.util.names
import org.koitharu.kotatsu.settings.SettingsActivity
import org.koitharu.kotatsu.settings.compose.ActionSettingsItem
import org.koitharu.kotatsu.settings.compose.CategoryPalette
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.PlainInfoSettingsItem
import org.koitharu.kotatsu.settings.compose.ListSettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref
import org.koitharu.kotatsu.settings.compose.rememberStringPref
import org.koitharu.kotatsu.settings.sources.migration.BrokenSourcesMigrationFragment
import rikka.shizuku.Shizuku
import javax.inject.Inject

@AndroidEntryPoint
class ExtensionsSettingsFragment : BaseComposeSettingsFragment(R.string.extensions) {

	@Inject
	lateinit var installerPreferences: ExtensionInstallerPreferences

	@Inject
	lateinit var shizukuInstaller: ShizukuExtensionInstaller

	private var installerUiState by mutableStateOf(InstallerUiState())

	private val shizukuPermissionListener = object : Shizuku.OnRequestPermissionResultListener {
		override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
			if (requestCode != SHIZUKU_PERMISSION_REQUEST_CODE) return
			runCatching { Shizuku.removeRequestPermissionResultListener(this) }
			refreshInstallerUiState()
			if (grantResult != PackageManager.PERMISSION_GRANTED) {
				Toast.makeText(requireContext(), R.string.shizuku_permission_denied, Toast.LENGTH_LONG).show()
			}
		}
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		refreshInstallerUiState()
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			DropSauceTheme {
				ExtensionsScreen(
					installerUiState = installerUiState,
					onChooseInstallerMethod = ::showInstallerMethodDialog,
					onOpenCatalog = { router.openSourcesCatalog(isExternalOnly = true) },
					onOpenStores = router::openExtensionStores,
					onOpenBrokenSourcesMigration = {
						(requireActivity() as SettingsActivity).openFragment(
							BrokenSourcesMigrationFragment::class.java,
							args = null,
							isFromRoot = false,
						)
					},
				)
			}
		}
	}

	override fun onResume() {
		super.onResume()
		refreshInstallerUiState()
	}

	override fun onDestroy() {
		runCatching { Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener) }
		super.onDestroy()
	}

	private fun refreshInstallerUiState() {
		if (!::installerPreferences.isInitialized || !::shizukuInstaller.isInitialized) return
		installerUiState = InstallerUiState(
			selected = installerPreferences.hasUserSelection,
			method = installerPreferences.method,
			shizukuStatus = shizukuInstaller.currentStatus(),
		)
	}

	private fun showInstallerMethodDialog() {
		val context = requireContext()
		val status = shizukuInstaller.currentStatus()
		val methods = ExtensionInstallerMethod.entries
		val labels = methods.map { context.extensionInstallerChoiceLabel(it, status) }.toTypedArray()
		MaterialAlertDialogBuilder(context)
			.setTitle(R.string.extension_installer_choose_title)
			.setItems(labels) { _, which ->
				val method = methods.getOrNull(which) ?: return@setItems
				val hadSelection = installerPreferences.hasUserSelection
				val previous = installerPreferences.method
				installerPreferences.select(method)
				refreshInstallerUiState()
				when {
					method == ExtensionInstallerMethod.SHIZUKU &&
						shizukuInstaller.currentStatus() != ShizukuInstallerStatus.READY -> {
						showShizukuNotReadyDialog()
					}
					method == ExtensionInstallerMethod.PRIVATE &&
						(!hadSelection || previous != ExtensionInstallerMethod.PRIVATE) -> {
						// Reuse the catalog's existing auto-migration flow instead of inventing another one.
						router.openSourcesCatalog(isExternalOnly = true, autoMigrate = true)
					}
				}
			}
			.show()
	}

	private fun showShizukuNotReadyDialog() {
		val context = requireContext()
		val status = shizukuInstaller.currentStatus()
		if (status == ShizukuInstallerStatus.READY) {
			refreshInstallerUiState()
			return
		}
		val positive = if (status == ShizukuInstallerStatus.PERMISSION_REQUIRED) {
			R.string.extension_installer_grant_permission
		} else {
			R.string.extension_installer_check_shizuku
		}
		MaterialAlertDialogBuilder(context)
			.setTitle(R.string.extension_installer_shizuku_not_ready_title)
			.setMessage(
				getString(
					R.string.extension_installer_shizuku_not_ready_message,
					context.shizukuInstallerStatusText(status),
				),
			)
			.setPositiveButton(positive) { _, _ -> checkOrRequestShizuku(status) }
			.setNeutralButton(R.string.extension_installer_change_method) { _, _ -> showInstallerMethodDialog() }
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun checkOrRequestShizuku(status: ShizukuInstallerStatus) {
		when (status) {
			ShizukuInstallerStatus.READY -> refreshInstallerUiState()
			ShizukuInstallerStatus.PERMISSION_REQUIRED -> runCatching {
				Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
				Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
			}.onFailure {
				runCatching { Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener) }
				Toast.makeText(requireContext(), R.string.shizuku_permission_denied, Toast.LENGTH_LONG).show()
			}
			ShizukuInstallerStatus.NOT_RUNNING -> {
				val intent = requireContext().packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE_NAME)
				if (intent != null) {
					startActivity(intent)
				} else {
					Toast.makeText(requireContext(), R.string.shizuku_not_running, Toast.LENGTH_LONG).show()
				}
			}
			ShizukuInstallerStatus.NOT_INSTALLED -> {
				Toast.makeText(requireContext(), R.string.shizuku_not_installed, Toast.LENGTH_LONG).show()
			}
		}
	}

	private companion object {
		const val SHIZUKU_PERMISSION_REQUEST_CODE = 14045
	}
}

private data class InstallerUiState(
	val selected: Boolean = false,
	val method: ExtensionInstallerMethod = ExtensionInstallerMethod.SYSTEM,
	val shizukuStatus: ShizukuInstallerStatus = ShizukuInstallerStatus.NOT_INSTALLED,
)

@Composable
private fun ExtensionsScreen(
	installerUiState: InstallerUiState,
	onChooseInstallerMethod: () -> Unit,
	onOpenCatalog: () -> Unit,
	onOpenStores: () -> Unit,
	onOpenBrokenSourcesMigration: () -> Unit,
) {
	val ctx = LocalContext.current
	val colors = CategoryPalette.forKey("extensions")
	val sortEntries = remember {
		SourcesSortOrder.entries.map { ctx.getString(it.titleResId) }
	}
	val sortValues = remember { SourcesSortOrder.entries.names().toList() }
	val incognitoEntries = remember {
		ctx.resources.getStringArray(R.array.incognito_nsfw_options).toList()
	}
	val incognitoValues = remember {
		ctx.resources.getStringArray(R.array.incognito_nsfw_values).toList()
	}

	var sortOrder by rememberStringPref(AppSettings.KEY_SOURCES_ORDER, SourcesSortOrder.ALPHABETIC.name)
	var grid by rememberBooleanPref(AppSettings.KEY_SOURCES_GRID, true)
	var noNsfw by rememberBooleanPref(AppSettings.KEY_DISABLE_NSFW, false)
	var incognitoNsfw by rememberStringPref(AppSettings.KEY_INCOGNITO_NSFW, "ASK")
	var autoUpdateExtensions by rememberBooleanPref(AppSettings.KEY_AUTO_UPDATE_EXTENSIONS, false)
	var updateNotifications by rememberBooleanPref(AppSettings.KEY_EXTENSION_UPDATE_NOTIFICATIONS, true)

	val installerTitle = if (installerUiState.selected) {
		ctx.extensionInstallerMethodTitle(installerUiState.method)
	} else {
		stringResource(R.string.extension_installer_not_selected)
	}
	val installerSummary = if (installerUiState.selected) {
		ctx.extensionInstallerMethodSummary(installerUiState.method, installerUiState.shizukuStatus)
	} else {
		stringResource(R.string.extension_installer_footer_not_selected_summary)
	}
	val autoUpdateEnabled = installerUiState.selected && installerUiState.method != ExtensionInstallerMethod.SYSTEM

	SettingsScaffold {
		item {
			SettingsGroup(title = "Catalog") {
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.manage_extensions),
						subtitle = stringResource(R.string.manage_extensions_summary),
						icon = R.drawable.ic_download,
						shape = pos.shape,
						onClick = onOpenCatalog,
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.manage_stores),
						subtitle = stringResource(R.string.manage_stores_summary),
						icon = R.drawable.ic_storefront,
						shape = pos.shape,
						onClick = onOpenStores,
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.extension_installer_method),
						subtitle = "$installerTitle\n$installerSummary",
						icon = R.drawable.ic_auth_key_large,
						shape = pos.shape,
						onClick = onChooseInstallerMethod,
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.migrate_broken_sources),
						subtitle = stringResource(R.string.migrate_broken_sources_summary),
						icon = R.drawable.ic_migrate,
						shape = pos.shape,
						onClick = onOpenBrokenSourcesMigration,
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = stringResource(R.string.appearance)) {
				item { pos ->
					ListSettingsItem(
						title = stringResource(R.string.sort_order),
						entries = sortEntries,
						entryValues = sortValues,
						selectedValue = sortOrder,
						onValueChange = { sortOrder = it },
						icon = R.drawable.ic_sort_asc,
						shape = pos.shape,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.show_in_grid_view),
						checked = grid,
						onCheckedChange = { grid = it },
						icon = R.drawable.ic_grid,
						shape = pos.shape,
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = stringResource(R.string.filter)) {
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.disable_nsfw),
						subtitle = stringResource(R.string.disable_nsfw_summary),
						checked = noNsfw,
						onCheckedChange = { noNsfw = it },
						icon = R.drawable.ic_nsfw,
						shape = pos.shape,
					)
				}
				item { pos ->
					ListSettingsItem(
						title = stringResource(R.string.incognito_for_nsfw),
						entries = incognitoEntries,
						entryValues = incognitoValues,
						selectedValue = incognitoNsfw,
						onValueChange = { incognitoNsfw = it },
						icon = R.drawable.ic_incognito,
						shape = pos.shape,
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = stringResource(R.string.auto_update)) {
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.ext_auto_update_title),
						subtitle = stringResource(R.string.ext_auto_update_summary),
						checked = autoUpdateExtensions,
						onCheckedChange = { autoUpdateExtensions = it },
						icon = R.drawable.ic_updated,
						shape = pos.shape,
						enabled = autoUpdateEnabled,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.ext_update_notifications_title),
						subtitle = stringResource(R.string.ext_update_notifications_summary),
						checked = updateNotifications,
						onCheckedChange = { updateNotifications = it },
						icon = R.drawable.ic_updated,
						shape = pos.shape,
					)
				}
			}
		}
		if (installerUiState.selected) {
			item {
				PlainInfoSettingsItem(
					text = stringResource(
						when (installerUiState.method) {
							ExtensionInstallerMethod.SYSTEM -> R.string.extension_installer_settings_system_note
							ExtensionInstallerMethod.SHIZUKU -> R.string.extension_installer_settings_shizuku_note
							ExtensionInstallerMethod.PRIVATE -> R.string.extension_installer_settings_private_note
						},
					),
					icon = R.drawable.ic_info_outline,
				)
			}
		}
		item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
	}
}
