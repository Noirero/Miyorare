package org.koitharu.kotatsu.widget.common

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.view.ContextThemeWrapper
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.MiyorareAppearance
import org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle
import org.koitharu.kotatsu.core.prefs.MiyorareModernPaletteSource
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import org.koitharu.kotatsu.core.ui.miyorareViewPaletteFromPreferences

/** B24: compare real widget projection to the same canonical View palette used by Modern UI. */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class ModernWidgetThemeRuntimeTest {
    @get:Rule val hilt = HiltAndroidRule(this)

    @Before fun createHiltComponent() {
        // WidgetTheme.colors() resolves AppSettings via the production Application entry point.
        // The Android instrumentation process uses HiltTestApplication, whose per-test graph
        // must be initialized before the widget theme can be queried.
        hilt.inject()
    }

    @Test fun presetNightAmoledAndCacheChangesUseCanonicalWidgetColors() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val keys = listOf(
            AppSettings.KEY_THEME, AppSettings.KEY_THEME_AMOLED,
            MiyorareAppearance.KEY_DESIGN_STYLE,
            MiyorareAppearance.KEY_THEME_PRESET,
            MiyorareAppearance.KEY_MODERN_PALETTE_SOURCE,
        )
        val before = keys.associateWith { prefs.all[it] }
        try {
            prefs.edit()
                .putString(MiyorareAppearance.KEY_DESIGN_STYLE, MiyorareDesignStyle.MODERN.name)
                .putString(MiyorareAppearance.KEY_MODERN_PALETTE_SOURCE, MiyorareModernPaletteSource.MATCH_THEME.name)
                .commit()
            WidgetTheme.invalidate()
            for (dark in listOf(false, true)) {
                for (amoled in if (dark) listOf(false, true) else listOf(false)) {
                    prefs.edit()
                        .putString(AppSettings.KEY_THEME, (if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO).toString())
                        .putBoolean(AppSettings.KEY_THEME_AMOLED, amoled)
                        .commit()
                    for (preset in listOf(
                        MiyorareThemePreset.MIYORARE,
                        MiyorareThemePreset.CYAN,
                        MiyorareThemePreset.SAKURA,
                        MiyorareThemePreset.VIOLET,
                        MiyorareThemePreset.CUSTOM,
                    )) {
                        prefs.edit().putString(MiyorareAppearance.KEY_THEME_PRESET, preset.name).commit()
                        val actual = WidgetTheme.colors(context)
                        assertNotNull("Widget requires Android 12+ runtime", actual)
                        val expected = themedContext(context, dark).miyorareViewPaletteFromPreferences()!!
                        val tag = "$preset dark=$dark amoled=$amoled"
                        assertEquals("$tag primary", expected.primary, actual!!.primary)
                        assertEquals("$tag primary container", expected.primaryContainer, actual.primaryContainer)
                        assertEquals("$tag onPrimary", expected.onPrimary, actual.onPrimary)
                        assertEquals("$tag onPrimaryContainer", expected.onPrimaryContainer, actual.onPrimaryContainer)
                        assertEquals("$tag surface", expected.surfaceContainer, actual.surfaceContainer)
                        assertEquals("$tag surface high", expected.surfaceContainerHigh, actual.surfaceContainerHigh)
                        assertEquals("$tag surface highest", expected.surfaceContainerHighest, actual.surfaceContainerHighest)
                        assertEquals("$tag onSurface", expected.onSurface, actual.onSurface)
                        assertEquals("$tag onSurfaceVariant", expected.onSurfaceVariant, actual.onSurfaceVariant)
                    }
                }
            }
            prefs.edit()
                .putString(MiyorareAppearance.KEY_THEME_PRESET, MiyorareThemePreset.MIYORARE.name)
                .commit()
            val a = WidgetTheme.colors(context)!!.primary
            prefs.edit()
                .putString(MiyorareAppearance.KEY_THEME_PRESET, MiyorareThemePreset.CYAN.name)
                .commit()
            val b = WidgetTheme.colors(context)!!.primary
            assertNotEquals("Modern preset must invalidate the effective widget accent", a, b)

            // Returning to Classic must not inherit a stale Modern cache entry.
            prefs.edit()
                .putString(MiyorareAppearance.KEY_DESIGN_STYLE, MiyorareDesignStyle.CLASSIC.name)
                .commit()
            assertNotNull(WidgetTheme.colors(context))
        } finally {
            val editor = prefs.edit()
            before.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    null -> editor.remove(key)
                    else -> error("Unexpected persisted type for $key: ${value::class.java.name}")
                }
            }
            editor.commit()
            WidgetTheme.invalidate()
        }
    }

    private fun themedContext(context: Context, dark: Boolean): Context {
        val config = Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                (if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        }
        return ContextThemeWrapper(context.createConfigurationContext(config), R.style.Theme_Kotatsu)
            .apply { theme.applyStyle(R.style.ThemeOverlay_MiyorareModern, true) }
    }
}
