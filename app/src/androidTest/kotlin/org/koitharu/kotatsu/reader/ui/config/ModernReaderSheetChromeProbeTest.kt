package org.koitharu.kotatsu.reader.ui.config

import android.content.res.Configuration
import android.util.Log
import android.view.LayoutInflater
import androidx.appcompat.view.ContextThemeWrapper
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.color.MaterialColors
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.MiyorareAppearance
import org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle
import org.koitharu.kotatsu.core.prefs.MiyorareModernPaletteSource
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import org.koitharu.kotatsu.core.ui.miyorareViewPaletteFromPreferences
import org.koitharu.kotatsu.databinding.SheetReaderConfigBinding
import androidx.appcompat.R as appcompatR
import com.google.android.material.R as materialR

/**
 * P1 B10 targeted runtime probe: ReaderConfigSheet's content uses MiyorareTheme (Compose),
 * while the sheet/header Views inflate from the host XML theme. Measure that gap without
 * mutating any Reader setting or depending on book content / actual playback.
 */
@RunWith(AndroidJUnit4::class)
class ModernReaderSheetChromeProbeTest {

    @Test fun inflatedReaderSheetHostThemeDiffersFromModernCyanSemanticPalette() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val prefs = PreferenceManager.getDefaultSharedPreferences(target)
        val keys = listOf(
            MiyorareAppearance.KEY_DESIGN_STYLE,
            MiyorareAppearance.KEY_THEME_PRESET,
            MiyorareAppearance.KEY_MODERN_PALETTE_SOURCE,
        )
        val saved = keys.associateWith { prefs.getString(it, null) }
        try {
            instrumentation.runOnMainSync {
                prefs.edit()
                    .putString(MiyorareAppearance.KEY_DESIGN_STYLE, MiyorareDesignStyle.MODERN.name)
                    .putString(MiyorareAppearance.KEY_THEME_PRESET, MiyorareThemePreset.CYAN.name)
                    .putString(MiyorareAppearance.KEY_MODERN_PALETTE_SOURCE, MiyorareModernPaletteSource.MATCH_THEME.name)
                    .commit()
                val config = Configuration(target.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
                }
                val themed = ContextThemeWrapper(
                    target.createConfigurationContext(config), R.style.Theme_Kotatsu,
                ).apply { theme.applyStyle(R.style.ThemeOverlay_MiyorareModern, true) }
                val actualLayout = SheetReaderConfigBinding.inflate(LayoutInflater.from(themed))
                assertNotNull(actualLayout.headerBar)
                val semantic = requireNotNull(themed.miyorareViewPaletteFromPreferences())
                val hostPrimary = MaterialColors.getColor(themed, appcompatR.attr.colorPrimary, 0)
                val hostSurface = MaterialColors.getColor(themed, materialR.attr.colorSurface, 0)
                Log.i("ReaderColorProbe", "B10 native XML primary=$hostPrimary surface=$hostSurface semantic primary=${semantic.primary} surface=${semantic.surface}")
                assertNotEquals("B10 XML primary should expose the original static-host mismatch", semantic.primary, hostPrimary)
                assertNotEquals("B10 XML surface should expose the original static-host mismatch", semantic.surface, hostSurface)
            }
        } finally {
            val editor = prefs.edit()
            saved.forEach { (key, value) ->
                if (value == null) editor.remove(key) else editor.putString(key, value)
            }
            editor.commit()
        }
    }
}
