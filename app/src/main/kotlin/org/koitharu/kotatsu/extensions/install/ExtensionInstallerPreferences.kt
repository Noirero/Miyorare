package org.koitharu.kotatsu.extensions.install

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.settings.sources.catalog.ExtensionInstallMode
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton

enum class ExtensionInstallerMethod {
	SHIZUKU,
	SYSTEM,
	PRIVATE,
	;

	val installMode: ExtensionInstallMode
		get() = if (this == PRIVATE) ExtensionInstallMode.SANDBOX else ExtensionInstallMode.SYSTEM
}

enum class ShizukuInstallerStatus {
	NOT_INSTALLED,
	NOT_RUNNING,
	PERMISSION_REQUIRED,
	READY,
}

@Singleton
class ExtensionInstallerPreferences @Inject constructor(
	@ApplicationContext context: Context,
) {
	private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

	init {
		migrateLegacyPreferences()
	}

	val hasUserSelection: Boolean
		get() = prefs.getBoolean(KEY_SELECTED, false)

	val method: ExtensionInstallerMethod
		get() = readStoredMethod() ?: legacyMethod()

	fun select(method: ExtensionInstallerMethod) {
		prefs.edit {
			putString(KEY_METHOD, method.name)
			putBoolean(KEY_SELECTED, true)
			// Compatibility mirrors for code that still distinguishes system/private ownership.
			putBoolean(AppSettings.KEY_SHIZUKU_INSTALLER, method == ExtensionInstallerMethod.SHIZUKU)
			putBoolean(AppSettings.KEY_PRIVATE_INSTALLER, method == ExtensionInstallerMethod.PRIVATE)
		}
	}

	private fun migrateLegacyPreferences() {
		val stored = readStoredMethod()
		if (stored != null) {
			if (!prefs.contains(KEY_SELECTED)) prefs.edit { putBoolean(KEY_SELECTED, true) }
			syncLegacyKeys(stored)
			return
		}

		val hasLegacyChoice = prefs.contains(AppSettings.KEY_SHIZUKU_INSTALLER) ||
			prefs.contains(AppSettings.KEY_PRIVATE_INSTALLER)
		if (!hasLegacyChoice) return

		val migrated = legacyMethod()
		prefs.edit {
			putString(KEY_METHOD, migrated.name)
			putBoolean(KEY_SELECTED, true)
			putBoolean(AppSettings.KEY_SHIZUKU_INSTALLER, migrated == ExtensionInstallerMethod.SHIZUKU)
			putBoolean(AppSettings.KEY_PRIVATE_INSTALLER, migrated == ExtensionInstallerMethod.PRIVATE)
		}
	}

	private fun syncLegacyKeys(method: ExtensionInstallerMethod) {
		val shizuku = method == ExtensionInstallerMethod.SHIZUKU
		val privateMode = method == ExtensionInstallerMethod.PRIVATE
		if (
			prefs.getBoolean(AppSettings.KEY_SHIZUKU_INSTALLER, false) == shizuku &&
			prefs.getBoolean(AppSettings.KEY_PRIVATE_INSTALLER, false) == privateMode
		) return
		prefs.edit {
			putBoolean(AppSettings.KEY_SHIZUKU_INSTALLER, shizuku)
			putBoolean(AppSettings.KEY_PRIVATE_INSTALLER, privateMode)
		}
	}

	private fun readStoredMethod(): ExtensionInstallerMethod? {
		val raw = prefs.getString(KEY_METHOD, null) ?: return null
		return runCatching { ExtensionInstallerMethod.valueOf(raw) }.getOrNull()
	}

	private fun legacyMethod(): ExtensionInstallerMethod = when {
		prefs.getBoolean(AppSettings.KEY_PRIVATE_INSTALLER, false) -> ExtensionInstallerMethod.PRIVATE
		prefs.getBoolean(AppSettings.KEY_SHIZUKU_INSTALLER, false) -> ExtensionInstallerMethod.SHIZUKU
		else -> ExtensionInstallerMethod.SYSTEM
	}

	companion object {
		const val KEY_METHOD = "extension_installer_method"
		const val KEY_SELECTED = "extension_installer_method_selected"
	}
}

fun ShizukuExtensionInstaller.currentStatus(): ShizukuInstallerStatus {
	if (!isInstalled) return ShizukuInstallerStatus.NOT_INSTALLED
	if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
		return ShizukuInstallerStatus.NOT_RUNNING
	}
	return if (
		runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
	) {
		ShizukuInstallerStatus.READY
	} else {
		ShizukuInstallerStatus.PERMISSION_REQUIRED
	}
}

fun Context.extensionInstallerMethodTitle(method: ExtensionInstallerMethod): String = getString(
	when (method) {
		ExtensionInstallerMethod.SYSTEM -> R.string.extension_installer_system
		ExtensionInstallerMethod.SHIZUKU -> R.string.extension_installer_shizuku
		ExtensionInstallerMethod.PRIVATE -> R.string.extension_installer_private
	},
)

fun Context.shizukuInstallerStatusText(status: ShizukuInstallerStatus): String = getString(
	when (status) {
		ShizukuInstallerStatus.READY -> R.string.extension_installer_shizuku_status_ready
		ShizukuInstallerStatus.PERMISSION_REQUIRED -> R.string.extension_installer_shizuku_status_permission
		ShizukuInstallerStatus.NOT_RUNNING -> R.string.extension_installer_shizuku_status_not_running
		ShizukuInstallerStatus.NOT_INSTALLED -> R.string.extension_installer_shizuku_status_not_installed
	},
)

fun Context.extensionInstallerMethodSummary(
	method: ExtensionInstallerMethod,
	shizukuStatus: ShizukuInstallerStatus,
): String = when (method) {
	ExtensionInstallerMethod.SYSTEM -> getString(R.string.extension_installer_system_summary)
	ExtensionInstallerMethod.PRIVATE -> getString(R.string.extension_installer_private_summary)
	ExtensionInstallerMethod.SHIZUKU -> getString(
		R.string.extension_installer_shizuku_summary_with_status,
		shizukuInstallerStatusText(shizukuStatus),
	)
}

fun Context.extensionInstallerChoiceLabel(
	method: ExtensionInstallerMethod,
	shizukuStatus: ShizukuInstallerStatus,
): String = when (method) {
	ExtensionInstallerMethod.SHIZUKU -> getString(
		R.string.extension_installer_choice_shizuku,
		extensionInstallerMethodTitle(method),
		getString(R.string.extension_installer_shizuku_flow),
		shizukuInstallerStatusText(shizukuStatus),
	)
	ExtensionInstallerMethod.SYSTEM -> getString(
		R.string.extension_installer_choice_system,
		extensionInstallerMethodTitle(method),
		getString(R.string.extension_installer_system_flow),
	)
	ExtensionInstallerMethod.PRIVATE -> getString(
		R.string.extension_installer_choice_private,
		extensionInstallerMethodTitle(method),
		getString(R.string.extension_installer_private_flow),
	)
}

const val SHIZUKU_PACKAGE_NAME = "moe.shizuku.privileged.api"
