package org.koitharu.kotatsu.settings.protect

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import androidx.activity.ComponentDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.settings.compose.DropSauceTheme

private const val MIN_PIN_LENGTH = 4
private const val MAX_PIN_LENGTH = 24

/**
 * M3 Expressive dialog shown when the user turns on "Require unlock": pick device/biometric auth
 * (recommended) or set up a custom PIN. PIN setup happens inline in two steps (enter → confirm).
 */
fun showProtectMethodDialog(
	activity: FragmentActivity,
	deviceAuthSupported: Boolean,
	onSelectDevice: () -> Unit,
	onPinConfirmed: (String) -> Unit,
	onCancel: () -> Unit = {},
) {
	showDialog(activity) { dismiss ->
		ProtectMethodContent(
			deviceAuthSupported = deviceAuthSupported,
			onSelectDevice = {
				dismiss()
				onSelectDevice()
			},
			onPinConfirmed = { pin ->
				dismiss()
				onPinConfirmed(pin)
			},
			onCancel = {
				dismiss()
				onCancel()
			},
		)
	}
}

/** Direct two-step PIN setup used by Private Favourites security settings/fallback. */
fun showPinSetupDialog(
	activity: FragmentActivity,
	onPinConfirmed: (String) -> Unit,
	onCancel: () -> Unit = {},
) {
	showDialog(activity) { dismiss ->
		PrivatePinDialogContent(
			onPinConfirmed = { pin ->
				dismiss()
				onPinConfirmed(pin)
			},
			onCancel = {
				dismiss()
				onCancel()
			},
		)
	}
}

private fun showDialog(
	activity: FragmentActivity,
	content: @Composable (dismiss: () -> Unit) -> Unit,
) {
	val dialog = ComponentDialog(activity)
	dialog.setContentView(
		ComposeView(activity).apply {
			setContent {
				DropSauceTheme {
					content(dialog::dismiss)
				}
			}
		},
	)
	dialog.window?.run {
		setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
		setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
	}
	val observer = object : DefaultLifecycleObserver {
		override fun onDestroy(owner: LifecycleOwner) = dialog.dismiss()
	}
	activity.lifecycle.addObserver(observer)
	dialog.setOnDismissListener { activity.lifecycle.removeObserver(observer) }
	dialog.show()
}

@Composable
private fun ProtectMethodContent(
	deviceAuthSupported: Boolean,
	onSelectDevice: () -> Unit,
	onPinConfirmed: (String) -> Unit,
	onCancel: () -> Unit,
) {
	var showPinSetup by remember { mutableStateOf(false) }
	DialogShell {
		if (showPinSetup) {
			PinSetup(onPinConfirmed = onPinConfirmed, onCancel = onCancel)
		} else {
			MethodChoice(
				deviceAuthSupported = deviceAuthSupported,
				onSelectDevice = onSelectDevice,
				onSelectPin = { showPinSetup = true },
			)
		}
	}
}

@Composable
private fun PrivatePinDialogContent(
	onPinConfirmed: (String) -> Unit,
	onCancel: () -> Unit,
) {
	DialogShell {
		PinSetup(onPinConfirmed = onPinConfirmed, onCancel = onCancel)
	}
}

@Composable
private fun DialogShell(content: @Composable () -> Unit) {
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 24.dp),
		contentAlignment = Alignment.Center,
	) {
		Surface(
			shape = RoundedCornerShape(28.dp),
			color = MaterialTheme.colorScheme.surfaceContainerHigh,
			modifier = Modifier
				.fillMaxWidth()
				.widthIn(max = 400.dp),
		) {
			Column(
				modifier = Modifier.padding(24.dp),
				horizontalAlignment = Alignment.CenterHorizontally,
			) {
				Box(
					modifier = Modifier
						.size(56.dp)
						.background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
					contentAlignment = Alignment.Center,
				) {
					Icon(
						painter = painterResource(R.drawable.ic_lock),
						contentDescription = null,
						tint = MaterialTheme.colorScheme.onSecondaryContainer,
						modifier = Modifier.size(28.dp),
					)
				}
				Spacer(Modifier.height(16.dp))
				content()
			}
		}
	}
}

@Composable
private fun MethodChoice(
	deviceAuthSupported: Boolean,
	onSelectDevice: () -> Unit,
	onSelectPin: () -> Unit,
) {
	Text(
		text = stringResource(R.string.lock_method_title),
		style = MaterialTheme.typography.headlineSmall,
		color = MaterialTheme.colorScheme.onSurface,
		textAlign = TextAlign.Center,
	)
	Spacer(Modifier.height(8.dp))
	Text(
		text = if (deviceAuthSupported) {
			stringResource(R.string.lock_method_message)
		} else {
			stringResource(R.string.require_unlock_unavailable)
		},
		style = MaterialTheme.typography.bodyMedium,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
		textAlign = TextAlign.Center,
	)
	Spacer(Modifier.height(24.dp))
	Button(
		onClick = onSelectDevice,
		enabled = deviceAuthSupported,
		shape = RoundedCornerShape(28.dp),
		modifier = Modifier
			.fillMaxWidth()
			.height(56.dp),
	) {
		Text(
			text = stringResource(R.string.lock_method_device),
			style = MaterialTheme.typography.labelLarge,
		)
	}
	Spacer(Modifier.height(12.dp))
	FilledTonalButton(
		onClick = onSelectPin,
		shape = RoundedCornerShape(28.dp),
		modifier = Modifier
			.fillMaxWidth()
			.height(56.dp),
	) {
		Text(
			text = stringResource(R.string.lock_method_pin),
			style = MaterialTheme.typography.labelLarge,
		)
	}
}

/** Self-contained two-step PIN setup: enter, then confirm; mismatch restarts from entry. */
@Composable
private fun PinSetup(
	onPinConfirmed: (String) -> Unit,
	onCancel: () -> Unit,
) {
	var firstPin by remember { mutableStateOf<String?>(null) }
	var pin by remember { mutableStateOf("") }
	var mismatch by remember { mutableStateOf(false) }
	val isConfirmStep = firstPin != null

	fun submit() {
		val first = firstPin
		if (first == null) {
			firstPin = pin
			pin = ""
		} else if (pin == first) {
			onPinConfirmed(pin)
		} else {
			mismatch = true
			firstPin = null
			pin = ""
		}
	}

	Text(
		text = stringResource(if (isConfirmStep) R.string.confirm_pin else R.string.create_pin),
		style = MaterialTheme.typography.headlineSmall,
		color = MaterialTheme.colorScheme.onSurface,
		textAlign = TextAlign.Center,
	)
	Spacer(Modifier.height(8.dp))
	Text(
		text = when {
			mismatch -> stringResource(R.string.pins_mismatch)
			isConfirmStep -> stringResource(R.string.confirm_pin_hint)
			else -> stringResource(R.string.create_pin_hint)
		},
		style = MaterialTheme.typography.bodyMedium,
		color = if (mismatch) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
		textAlign = TextAlign.Center,
	)
	Spacer(Modifier.height(16.dp))
	OutlinedTextField(
		value = pin,
		onValueChange = { new ->
			mismatch = false
			pin = new.filter(Char::isDigit).take(MAX_PIN_LENGTH)
		},
		singleLine = true,
		visualTransformation = PasswordVisualTransformation(),
		keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
		shape = RoundedCornerShape(16.dp),
		modifier = Modifier.fillMaxWidth(),
	)
	Spacer(Modifier.height(24.dp))
	Button(
		onClick = ::submit,
		enabled = pin.length >= MIN_PIN_LENGTH,
		shape = RoundedCornerShape(28.dp),
		modifier = Modifier
			.fillMaxWidth()
			.height(56.dp),
	) {
		Text(
			text = stringResource(if (isConfirmStep) R.string.confirm else R.string.next),
			style = MaterialTheme.typography.labelLarge,
		)
	}
	Spacer(Modifier.height(8.dp))
	FilledTonalButton(
		onClick = onCancel,
		shape = RoundedCornerShape(28.dp),
		modifier = Modifier
			.fillMaxWidth()
			.height(56.dp),
	) {
		Text(
			text = stringResource(android.R.string.cancel),
			style = MaterialTheme.typography.labelLarge,
		)
	}
}
