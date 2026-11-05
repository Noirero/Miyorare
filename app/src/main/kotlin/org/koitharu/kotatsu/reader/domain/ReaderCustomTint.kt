package org.koitharu.kotatsu.reader.domain

import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RenderEffect
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi

/**
 * Optional on-screen tint applied AFTER the existing brightness/contrast/grayscale/invert
 * matrix. Never alters downloaded image files or decoded bitmap data.
 *
 * Dodge/Lighten and Burn/Darken use Color Dodge/Burn on API 29+; Android 26-28
 * uses the mathematically supported Lighten/Darken modes.
 */
data class ReaderCustomTint(
    val enabled: Boolean = false,
    val red: Int = 70,
    val green: Int = 0,
    val blue: Int = 171,
    val alpha: Int = 115,
    val mode: ReaderTintBlendMode = ReaderTintBlendMode.OVERLAY,
) {
    val isActive: Boolean get() = enabled && alpha > 0

    fun toPlatformColorFilter(): ColorFilter {
        val argb = Color.argb(
            alpha.coerceIn(0, 255), red.coerceIn(0, 255),
            green.coerceIn(0, 255), blue.coerceIn(0, 255),
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            BlendModeColorFilter(argb, mode.toBlendMode())
        } else {
            PorterDuffColorFilter(argb, mode.toPorterDuffMode())
        }
    }

    companion object {
        val DEFAULT = ReaderCustomTint()

        /**
         * Hardware composition preserves the existing per-image ColorMatrixColorFilter.
         * RenderEffect avoids forcing an explicit hardware layer on Android 12+.
         * Only call this on owned manga image/preview views.
         */
        fun applyToImage(
            view: View,
            settings: ReaderColorFilter?,
            setImageColorFilter: (ColorFilter?) -> Unit,
        ) {
            val tint = settings?.custom?.takeIf(ReaderCustomTint::isActive)
            val matrix = settings?.takeIf(ReaderColorFilter::hasMatrixEffects)?.toColorFilter()
            // The tint MUST run on bitmap pixels rather than the entire View layer:
            // SRC_OVER on transparent letterboxing otherwise paints colored rectangles.
            setImageColorFilter(tint?.toPlatformColorFilter() ?: matrix)
            if (tint != null && matrix != null) {
                // An additional matrix is needed only when custom tint and standard
                // effects are both on. This avoids GPU layers for tint-only reading.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    view.setRenderEffect(RenderEffect.createColorFilterEffect(matrix))
                } else {
                    view.setLayerType(View.LAYER_TYPE_HARDWARE, Paint().apply { colorFilter = matrix })
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                view.setRenderEffect(null)
            } else if (view.layerType == View.LAYER_TYPE_HARDWARE) {
                view.setLayerType(View.LAYER_TYPE_NONE, null)
            }
        }
    }
}

enum class ReaderTintBlendMode {
    DEFAULT, MULTIPLY, SCREEN, OVERLAY, DODGE_LIGHTEN, BURN_DARKEN;

    @RequiresApi(Build.VERSION_CODES.Q)
    fun toBlendMode(): BlendMode = when (this) {
        DEFAULT -> BlendMode.SRC_OVER
        MULTIPLY -> BlendMode.MULTIPLY
        SCREEN -> BlendMode.SCREEN
        OVERLAY -> BlendMode.OVERLAY
        DODGE_LIGHTEN -> BlendMode.COLOR_DODGE
        BURN_DARKEN -> BlendMode.COLOR_BURN
    }

    fun toPorterDuffMode(): PorterDuff.Mode = when (this) {
        DEFAULT -> PorterDuff.Mode.SRC_OVER
        MULTIPLY -> PorterDuff.Mode.MULTIPLY
        SCREEN -> PorterDuff.Mode.SCREEN
        OVERLAY -> PorterDuff.Mode.OVERLAY
        DODGE_LIGHTEN -> PorterDuff.Mode.LIGHTEN
        BURN_DARKEN -> PorterDuff.Mode.DARKEN
    }

    companion object {
        fun fromStored(value: String?): ReaderTintBlendMode =
            entries.firstOrNull { it.name == value } ?: OVERLAY
    }
}
