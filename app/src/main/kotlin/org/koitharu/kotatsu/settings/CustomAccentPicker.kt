package org.koitharu.kotatsu.settings

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.MiyorareAppearance
import org.koitharu.kotatsu.settings.compose.ActionSettingsItem

/**
 * Visual editor for the Modern Custom accent.
 *
 * The persisted value remains the existing #RRGGBB preference. The dialog owns a draft so
 * dismissing it never mutates the preference; only the Apply action calls [onValueChange].
 */
@Composable
internal fun CustomAccentSettingsItem(
    value: String,
    onValueChange: (String) -> Unit,
    shape: Shape,
) {
    var showPicker by remember { mutableStateOf(false) }

    ActionSettingsItem(
        title = stringResource(R.string.miyorare_custom_accent),
        subtitle = value,
        icon = R.drawable.ic_palette,
        shape = shape,
        onClick = { showPicker = true },
    )

    if (showPicker) {
        CustomAccentPickerDialog(
            initialValue = value,
            onDismiss = { showPicker = false },
            onConfirm = {
                onValueChange(it)
                showPicker = false
            },
        )
    }
}

@Composable
private fun CustomAccentPickerDialog(
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val normalizedInitial = MiyorareAppearance.normalizeAccent(initialValue)
        ?: MiyorareAppearance.DEFAULT_CUSTOM_ACCENT
    val initialArgb = AndroidColor.parseColor(normalizedInitial)
    val initialHsv = remember(normalizedInitial) {
        FloatArray(3).also { AndroidColor.colorToHSV(initialArgb, it) }
    }

    var hue by remember(normalizedInitial) { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember(normalizedInitial) { mutableFloatStateOf(initialHsv[1]) }
    var brightness by remember(normalizedInitial) { mutableFloatStateOf(initialHsv[2]) }
    var hexText by remember(normalizedInitial) { mutableStateOf(normalizedInitial) }

    fun syncHexFromHsv() {
        hexText = hsvToHex(hue, saturation, brightness)
    }

    val normalizedDraft = MiyorareAppearance.normalizeAccent(hexText)
    val previewHex = normalizedDraft ?: hsvToHex(hue, saturation, brightness)
    val previewColor = Color(AndroidColor.parseColor(previewHex))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.miyorare_custom_accent)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = previewColor,
                ) {}

                SaturationValuePicker(
                    hue = hue,
                    saturation = saturation,
                    brightness = brightness,
                    onValueChange = { newSaturation, newBrightness ->
                        saturation = newSaturation
                        brightness = newBrightness
                        syncHexFromHsv()
                    },
                )

                ColorSliderRow(
                    label = "Hue",
                    value = hue,
                    valueRange = 0f..360f,
                    preview = Color(AndroidColor.HSVToColor(floatArrayOf(hue, 1f, 1f))),
                    onValueChange = {
                        hue = it
                        syncHexFromHsv()
                    },
                )

                OutlinedTextField(
                    value = hexText,
                    onValueChange = { candidate ->
                        hexText = candidate
                        val normalized = MiyorareAppearance.normalizeAccent(candidate)
                        if (normalized != null) {
                            val hsv = FloatArray(3)
                            AndroidColor.colorToHSV(AndroidColor.parseColor(normalized), hsv)
                            hue = hsv[0]
                            saturation = hsv[1]
                            brightness = hsv[2]
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = normalizedDraft == null,
                    label = { Text("HEX") },
                    supportingText = if (normalizedDraft == null) {
                        { Text(stringResource(R.string.miyorare_custom_accent_invalid)) }
                    } else {
                        null
                    },
                )
            }
        },
        confirmButton = {
            Button(
                enabled = normalizedDraft != null,
                onClick = { normalizedDraft?.let(onConfirm) },
            ) {
                Text(stringResource(android.R.string.ok))
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
private fun SaturationValuePicker(
    hue: Float,
    saturation: Float,
    brightness: Float,
    onValueChange: (Float, Float) -> Unit,
) {
    val hueColor = Color(AndroidColor.HSVToColor(floatArrayOf(hue, 1f, 1f)))

    fun updateFromOffset(offset: Offset, width: Float, height: Float) {
        if (width <= 0f || height <= 0f) return
        val newSaturation = (offset.x / width).coerceIn(0f, 1f)
        val newBrightness = (1f - offset.y / height).coerceIn(0f, 1f)
        onValueChange(newSaturation, newBrightness)
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .pointerInput(hue) {
                detectTapGestures { offset ->
                    updateFromOffset(offset, size.width.toFloat(), size.height.toFloat())
                }
            }
            .pointerInput(hue) {
                detectDragGestures(
                    onDragStart = { offset ->
                        updateFromOffset(offset, size.width.toFloat(), size.height.toFloat())
                    },
                    onDrag = { change, _ ->
                        updateFromOffset(change.position, size.width.toFloat(), size.height.toFloat())
                        change.consume()
                    },
                )
            },
    ) {
        drawRect(
            brush = Brush.horizontalGradient(listOf(Color.White, hueColor)),
        )
        drawRect(
            brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black)),
        )

        val marker = Offset(
            x = saturation.coerceIn(0f, 1f) * size.width,
            y = (1f - brightness.coerceIn(0f, 1f)) * size.height,
        )
        drawCircle(
            color = Color.White,
            radius = 8.dp.toPx(),
            center = marker,
            style = Stroke(width = 2.dp.toPx()),
        )
        drawCircle(
            color = Color.Black.copy(alpha = 0.55f),
            radius = 10.dp.toPx(),
            center = marker,
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}

@Composable
private fun ColorSliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    preview: Color,
    onValueChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .background(preview, CircleShape),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = preview,
                activeTrackColor = preview,
            ),
        )
    }
}

internal fun hsvToHex(hue: Float, saturation: Float, brightness: Float): String {
    val color = AndroidColor.HSVToColor(
        floatArrayOf(
            hue.coerceIn(0f, 360f),
            saturation.coerceIn(0f, 1f),
            brightness.coerceIn(0f, 1f),
        ),
    )
    return String.format("#%06X", color and 0xFFFFFF)
}