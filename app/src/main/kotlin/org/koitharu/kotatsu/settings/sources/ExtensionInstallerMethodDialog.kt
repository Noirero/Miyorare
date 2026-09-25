package org.koitharu.kotatsu.settings.sources

import android.content.Context
import android.content.DialogInterface
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.extensions.install.ExtensionInstallerMethod
import org.koitharu.kotatsu.extensions.install.ShizukuInstallerStatus
import org.koitharu.kotatsu.settings.compose.MiyorareTheme

/**
 * Compact, scan-friendly installer picker shared by Extension settings and the extension catalog.
 *
 * The old dialog flattened every method into a multi-line list item. Keeping each choice in its own
 * card makes the install flow, requirement and Shizuku state visible without reading a paragraph.
 * Selection is staged until the user presses "Choose", so an accidental tap cannot change installer
 * ownership or start Private migration immediately.
 */
fun showExtensionInstallerMethodPicker(
	context: Context,
	initialMethod: ExtensionInstallerMethod,
	shizukuStatus: ShizukuInstallerStatus,
	onSelected: (ExtensionInstallerMethod) -> Unit,
	onCancel: (() -> Unit)? = null,
) {
	var pendingMethod = initialMethod
	val composeView = ComposeView(context).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
		setContent {
			MiyorareTheme {
				ExtensionInstallerMethodPickerContent(
					initialMethod = initialMethod,
					shizukuStatus = shizukuStatus,
					onSelectionChanged = { pendingMethod = it },
				)
			}
		}
	}

	val dialog = MaterialAlertDialogBuilder(context)
		.setView(composeView)
		.setNegativeButton(android.R.string.cancel) { _, _ -> onCancel?.invoke() }
		.setPositiveButton(R.string.extension_installer_confirm, null)
		.setOnCancelListener { onCancel?.invoke() }
		.create()

	dialog.setOnShowListener {
		dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
			val selected = pendingMethod
			dialog.dismiss()
			onSelected(selected)
		}
	}
	dialog.show()
}

@Composable
private fun ExtensionInstallerMethodPickerContent(
	initialMethod: ExtensionInstallerMethod,
	shizukuStatus: ShizukuInstallerStatus,
	onSelectionChanged: (ExtensionInstallerMethod) -> Unit,
) {
	var selected by remember(initialMethod) { mutableStateOf(initialMethod) }
	val methods = remember {
		listOf(
			ExtensionInstallerMethod.SHIZUKU,
			ExtensionInstallerMethod.SYSTEM,
			ExtensionInstallerMethod.PRIVATE,
		)
	}

	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(start = 4.dp, end = 4.dp, top = 8.dp),
	) {
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
		Spacer(Modifier.height(18.dp))
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.heightIn(max = 510.dp)
				.verticalScroll(rememberScrollState()),
		) {
			methods.forEachIndexed { index, method ->
				InstallerMethodCard(
					method = method,
					selected = selected == method,
					shizukuStatus = shizukuStatus,
					onClick = {
						selected = method
						onSelectionChanged(method)
					},
				)
				if (index != methods.lastIndex) Spacer(Modifier.height(10.dp))
			}
			Spacer(Modifier.height(4.dp))
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
	) {
		Row(
			modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
			verticalAlignment = Alignment.Top,
		) {
			Surface(
				modifier = Modifier.size(46.dp),
				shape = RoundedCornerShape(14.dp),
				color = if (selected) scheme.primaryContainer else scheme.secondaryContainer.copy(alpha = 0.72f),
			) {
				Icon(
					painter = painterResource(icon),
					contentDescription = null,
					tint = if (selected) scheme.onPrimaryContainer else scheme.onSecondaryContainer,
					modifier = Modifier.padding(11.dp),
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
				Spacer(Modifier.height(9.dp))
				Text(
					text = stringResource(flow),
					style = MaterialTheme.typography.bodyMedium,
					color = scheme.onSurface,
				)
				Spacer(Modifier.height(5.dp))
				Text(
					text = "• " + stringResource(requirement),
					style = MaterialTheme.typography.bodySmall,
					color = scheme.onSurfaceVariant,
				)
				if (method == ExtensionInstallerMethod.PRIVATE) {
					Spacer(Modifier.height(3.dp))
					Text(
						text = "• " + stringResource(R.string.extension_installer_private_storage),
						style = MaterialTheme.typography.bodySmall,
						color = scheme.onSurfaceVariant,
					)
				}
				if (method == ExtensionInstallerMethod.SHIZUKU) {
					Spacer(Modifier.height(9.dp))
					ShizukuStatusPill(shizukuStatus)
				}
			}
			RadioButton(
				selected = selected,
				onClick = onClick,
				modifier = Modifier.padding(start = 6.dp),
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
