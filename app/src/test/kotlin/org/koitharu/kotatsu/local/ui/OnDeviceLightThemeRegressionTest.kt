package org.koitharu.kotatsu.local.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Resource and ownership regressions for the On-Device-only light palette. */
class OnDeviceLightThemeRegressionTest {
    private fun source(path: String): String = sequenceOf(
        File("src/main/$path"), File("app/src/main/$path"),
    ).first { it.isFile }.readText()

    private fun color(directory: String, name: String): Int {
        val xml = source("$directory/colors_smart_local_cinematic.xml")
        val hex = Regex("""<color name="$name">#([0-9A-Fa-f]{6})</color>""")
            .find(xml)?.groupValues?.get(1)
            ?: error("Missing $directory/$name")
        return hex.toInt(16)
    }

    private fun luminance(rgb: Int): Double {
        fun linear(value: Int): Double {
            val normalized = value / 255.0
            return if (normalized <= 0.04045) normalized / 12.92
                else ((normalized + 0.055) / 1.055).pow(2.4)
        }
        return linear(rgb shr 16 and 255) * 0.2126 +
            linear(rgb shr 8 and 255) * 0.7152 +
            linear(rgb and 255) * 0.0722
    }

    private fun contrast(a: Int, b: Int): Double {
        val left = luminance(a)
        val right = luminance(b)
        return (max(left, right) + 0.05) / (min(left, right) + 0.05)
    }

    @Test fun lightTextColorsMeetMinimumNormalTextContrast() {
        val light = "res/values"
        val surface = color(light, "smart_local_surface")
        val ink = color(light, "smart_local_ink")
        val accentFill = color(light, "smart_local_gold")
        assertTrue(contrast(color(light, "smart_local_cream"), ink) >= 4.5)
        assertTrue(contrast(color(light, "smart_local_muted"), surface) >= 4.5)
        assertTrue(contrast(color(light, "smart_local_accent_text"), surface) >= 4.5)
        assertTrue(contrast(color(light, "smart_local_on_accent"), accentFill) >= 4.5)
    }

    @Test fun nightKeepsOriginalCinematicPaletteAndDrawableVariants() {
        val night = "res/values-night"
        assertTrue(color(night, "smart_local_ink") == 0x101C2B)
        assertTrue(color(night, "smart_local_surface") == 0x17283A)
        assertTrue(color(night, "smart_local_gold") == 0xF0C28D)
        assertTrue(color("res/values", "smart_local_ink") != color(night, "smart_local_ink"))
        for (drawable in listOf("hero", "stat", "resume")) {
            assertTrue(source("res/drawable-night/bg_smart_local_archive_$drawable.xml").contains("<layer-list"))
            assertTrue(source("res/drawable/bg_smart_local_archive_$drawable.xml").contains("<layer-list"))
        }
        assertTrue(source("res/layout-night/item_smart_local_group_row.xml").contains("#1D2B3D"))
        assertFalse(source("res/layout/item_smart_local_group_row.xml").contains("#1D2B3D"))
    }

    @Test fun noticeUsesMatchedSemanticForegroundInsteadOfCinematicColors() {
        val notice = source("res/drawable/bg_smart_local_notice.xml")
        val header = source("kotlin/org/koitharu/kotatsu/list/ui/adapter/ListHeaderAD.kt")
        assertTrue(notice.contains("?attr/colorSecondaryContainer"))
        assertTrue(header.contains("colorOnSecondaryContainer"))
        assertTrue(header.contains("if (notice) {"))
        assertTrue(header.contains("binding.buttonMore.setTextColor(noticeForeground)"))
        assertTrue(header.contains("binding.textViewTitle.compoundDrawableTintList"))
    }

    @Test fun localGalleryUsesSemanticTextAndOnlyLightModeArtworkScrim() {
        val gallery = source("kotlin/org/koitharu/kotatsu/details/ui/LocalChapterGallery.kt")
        assertFalse(gallery.contains("Color.White"))
        assertTrue(gallery.contains("MaterialTheme.colorScheme.onSurface"))
        assertTrue(gallery.contains("MaterialTheme.colorScheme.onSurfaceVariant"))
        assertTrue(gallery.contains("if (lightMode) Modifier"))
        assertTrue(gallery.contains("labelSurface"))
        // Existing decoded-thumbnail reuse path stays owned by Coil and the local library.
        assertTrue(gallery.contains("imageLoader?.memoryCache"))
        assertTrue(gallery.contains("coverForChapter(item.chapter)"))
    }
}
