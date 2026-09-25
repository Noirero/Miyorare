package org.koitharu.kotatsu.settings.sources

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveDialogTextButton
import org.koitharu.kotatsu.core.ui.dialog.ExpressivePillButton
import org.koitharu.kotatsu.core.ui.dialog.showComposeDialog
import org.koitharu.kotatsu.extensions.install.ExtensionInstallerMethod
import org.koitharu.kotatsu.extensions.install.ShizukuInstallerStatus

/**
 * Scan-friendly installer picker shared by Extension settings and the extension catalog.
 *
 * The previous dialog flattened every method into one dense multi-line list. This picker keeps
 * title, short purpose, install flow, requirement and live Shizuku state visually separate.
 * Selection is staged until the user presses the confirmation button so an accidental tap never
 * changes installer ownership or starts Private migration.
 */
fun showExtensionInstallerMethodPicker(
	context: Context,
	initialMethod: ExtensionInstallerMethod,
	shizukuStatus: ShizukuInstallerStatus,
	onSelected: (ExtensionInstallerMethod) -> Unit,
	onCancel: (() -> Unit)? = null,
) {
	showComposeDialog(
		context = context,
		onCancel = onCancel,
	) { dismiss ->
		ExtensionInstallerMethodPickerContent(
			initialMethod = initialMethod,
			shizukuStatus = shizukuStatus,
			onConfirm = { method ->
				dismiss()
				onSelected(method)
			},
			onCancel = {
				dismiss()
				onCancel?.invoke()
			},
		)
	}
}

@Composable
private fun ExtensionInstallerMethodPickerContent(
	initialMethod: ExtensionInstallerMethod,
	shizukuStatus: ShizukuInstallerStatus,
	onConfirm: (ExtensionInstallerMethod) -> Unit,
	onCancel: () -> Unit,
) {
	var selected by remember(initialMethod) { mutableStateOf(initialMethod) }
	val methods = remember {
		listOf(
			ExtensionInstallerMethod.SHIZUKU,
			ExtensionInstallerMethod.SYSTEM,
			ExtensionInstallerMethod.PRIVATE,
		)
	}

	Box(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 20.dp),
		contentAlignment = Alignment.Center,
	) {
		Surface(
			modifier = Modifier
				.fillMaxWidth()
				.widthIn(max = 460.dp),
			shape = RoundedCornerShape(28.dp),
			color = MaterialTheme.colorScheme.surfaceContainerHigh,
			shadowElevation = 10.dp,
		) {
			Column(modifier = Modifier.padding(20.dp)) {
				Text(
					text = stringResource(R.string.extension_installer_choose_title),
					style = MaterialTheme.typography.headlineSmall,
					fontWeight = FontWeight.SemiBold,
					color = MaterialTheme.colorScheme.onSurface,
				)
				Spacer(Modifier.height(6.dp))
				Text(
					text = stringResource(R.string.extension_installer_choose_message),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				Spacer(Modifier.height(16.dp))
				Column(
					modifier = Modifier
						.fillMaxWidth()
						.heightIn(max = 390.dp)
						.verticalScroll(rememberScrollState()),
				) {
					methods.forEachIndexed { index, method ->
						InstallerMethodCard(
							method = method,
							selected = selected == method,
							shizukuStatus = shizukuStatus,
							onClick = { selected = method },
						)
						if (index != methods.lastIndex) Spacer(Modifier.height(10.dp))
					}
				}
				Spacer(Modifier.height(16.dp))
				ExpressivePillButton(
					text = stringResource(R.string.extension_installer_confirm),
					onClick = { onConfirm(selected) },
				)
				Spacer(Modifier.height(4.dp))
				ExpressiveDialogTextButton(
					text = stringResource(android.R.string.cancel),
					onClick = onCancel,
				)
			}
		}
	}
}

@Composable
private fun InstallerMethodCard(
	method: ExtensionInstallerMethod,
	selected: Boolean,
	shizukuStatus: ShizukuInstallerStatus,
	onClick: () -> Unit,
) {
	val scheme = MaterialTheme.colorScheme
	val icon = when (method) {
		ExtensionInstallerMethod.SHIZUKU -> R.drawable.ic_auth_key_large
		ExtensionInstallerMethod.SYSTEM -> R.drawable.ic_download
		ExtensionInstallerMethod.PRIVATE -> R.drawable.ic_shield
	}
	val title = when (method) {
		ExtensionInstallerMethod.SHIZUKU -> R.string.extension_installer_shizuku
		ExtensionInstallerMethod.SYSTEM -> R.string.extension_installer_system
		ExtensionInstallerMethod.PRIVATE -> R.string.extension_installer_private
	}
	val tag = when (method) {
		ExtensionInstallerMethod.SHIZUKU -> R.string.extension_installer_shizuku_tag
		ExtensionInstallerMethod.SYSTEM -> R.string.extension_installer_system_tag
		ExtensionInstallerMethod.PRIVATE -> R.string.extension_installer_private_tag
	}
	val flow = when (method) {
		ExtensionInstallerMethod.SHIZUKU -> R.string.extension_installer_shizuku_flow
		ExtensionInstallerMethod.SYSTEM -> R.string.extension_installer_system_flow
		ExtensionInstallerMethod.PRIVATE -> R.string.extension_installer_private_flow
	}
	val requirement = when (method) {
		ExtensionInstallerMethod.SHIZUKU -> R.string.extension_installer_shizuku_requirement
		ExtensionInstallerMethod.SYSTEM -> R.string.extension_installer_system_requirement
		ExtensionInstallerMethod.PRIVATE -> R.string.extension_installer_private_requirement
	}

	Surface(
		onClick = onClick,
		modifier = Modifier.fillMaxWidth(),
		shape = RoundedCornerShape(20.dp),
		color = if (selected) {
			scheme.primaryContainer.copy(alpha = 0.34f)
		} else {
			scheme.surfaceContainerLow
		},
		border = BorderStroke(
			width = if (selected) 1.5.dp else 1.dp,
			color = if (selected) scheme.primary else scheme.outlineVariant.copy(alpha = 0.72f),
		),
		shadowElevation = if (selected) 3.dp else 0.dp,
	) {
		Row(
			modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
			verticalAlignment = Alignment.Top,
		) {
			Surface(
				modifier = Modifier.size(44.dp),
				shape = RoundedCornerShape(14.dp),
				color = if (selected) scheme.primaryContainer else scheme.secondaryContainer.copy(alpha = 0.72f),
			) {
				Icon(
					painter = painterResource(icon),
					contentDescription = null,
					tint = if (selected) scheme.onPrimaryContainer else scheme.onSecondaryContainer,
					modifier = Modifier.padding(10.dp),
				)
			}
			Spacer(Modifier.size(12.dp))
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = stringResource(title),
					style = MaterialTheme.typography.titleMedium,
					fontWeight = FontWeight.SemiBold,
					color = scheme.onSurface,
				)
				Spacer(Modifier.height(2.dp))
				Text(
					text = stringResource(tag),
					style = MaterialTheme.typography.labelMedium,
					fontWeight = FontWeight.SemiBold,
					color = if (selected) scheme.primary else scheme.onSurfaceVariant,
				)
				Spacer(Modifier.height(8.dp))
				Text(
					text = stringResource(flow),
					style = MaterialTheme.typography.bodyMedium,
					color = scheme.onSurface,
				)
				Spacer(Modifier.height(4.dp))
				Text(
					text = "• " + stringResource(requirement),
					style = MaterialTheme.typography.bodySmall,
					color = scheme.onSurfaceVariant,
				)
				if (method == ExtensionInstallerMethod.PRIVATE) {
					Spacer(Modifier.height(2.dp))
					Text(
						text = "• " + stringResource(R.string.extension_installer_private_storage),
						style = MaterialTheme.typography.bodySmall,
						color = scheme.onSurfaceVariant,
					)
				}
				if (method == ExtensionInstallerMethod.SHIZUKU) {
					Spacer(Modifier.height(8.dp))
					ShizukuStatusPill(shizukuStatus)
				}
			}
			RadioButton(
				selected = selected,
				onClick = onClick,
				modifier = Modifier.padding(start = 4.dp),
			)
		}
	}
}

@Composable
private fun ShizukuStatusPill(status: ShizukuInstallerStatus) {
	val scheme = MaterialTheme.colorScheme
	val ready = status == ShizukuInstallerStatus.READY
	val label = when (status) {
		ShizukuInstallerStatus.READY -> R.string.extension_installer_shizuku_status_ready
		ShizukuInstallerStatus.PERMISSION_REQUIRED -> R.string.extension_installer_shizuku_status_permission
		ShizukuInstallerStatus.NOT_RUNNING -> R.string.extension_installer_shizuku_status_not_running
		ShizukuInstallerStatus.NOT_INSTALLED -> R.string.extension_installer_shizuku_status_not_installed
	}
	Surface(
		shape = RoundedCornerShape(999.dp),
		color = if (ready) scheme.tertiaryContainer else scheme.errorContainer,
		contentColor = if (ready) scheme.onTertiaryContainer else scheme.onErrorContainer,
	) {
		Text(
			text = stringResource(label),
			style = MaterialTheme.typography.labelMedium,
			fontWeight = FontWeight.Medium,
			modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
		)
	}
}
