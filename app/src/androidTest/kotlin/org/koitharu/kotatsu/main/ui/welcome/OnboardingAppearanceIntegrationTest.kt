package org.koitharu.kotatsu.main.ui.welcome

import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.preference.PreferenceManager
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ColorScheme
import org.koitharu.kotatsu.core.prefs.MiyorareAppearance
import org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle
import org.koitharu.kotatsu.core.prefs.MiyorareModernPaletteSource
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel
import org.koitharu.kotatsu.core.prefs.VisualEffectPreferences
import org.koitharu.kotatsu.core.ui.LocalMiyorareVisualPalette
import org.koitharu.kotatsu.core.ui.resolveMiyorareThemeColors
import org.koitharu.kotatsu.settings.compose.MiyorareTheme
import org.koitharu.kotatsu.settings.compose.MiyorareVisualThemePickerRow
import org.koitharu.kotatsu.settings.compose.rememberStringPref

/** Integration against onboarding's actual AppSettings keys and the shared Compose renderer. */
class OnboardingAppearanceIntegrationTest {

    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val keys = listOf(
        AppSettings.KEY_THEME, AppSettings.KEY_THEME_AMOLED, AppSettings.KEY_COLOR_THEME,
        MiyorareAppearance.KEY_DESIGN_STYLE, MiyorareAppearance.KEY_THEME_PRESET,
        MiyorareAppearance.KEY_MODERN_PALETTE_SOURCE, VisualEffectPreferences.KEY_LEVEL,
        AppSettings.KEY_ONBOARDING_COMPLETED, AppSettings.KEY_ONBOARDING_INSTALL_ID,
    )

    @Test fun existingSelectionsWritesAndFinishPreserveSharedPreferences() = restoringPrefs { prefs ->
        prefs.edit()
            .putString(MiyorareAppearance.KEY_DESIGN_STYLE, MiyorareDesignStyle.MODERN.name)
            .putString(MiyorareAppearance.KEY_THEME_PRESET, MiyorareThemePreset.VIOLET.name)
            .putString(MiyorareAppearance.KEY_MODERN_PALETTE_SOURCE, MiyorareModernPaletteSource.MATCH_THEME.name)
            .putString(AppSettings.KEY_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM.toString())
            .commit()
        val settings = AppSettings(context)
        assertEquals(MiyorareDesignStyle.MODERN, settings.miyorareDesignStyle)
        assertEquals(MiyorareThemePreset.VIOLET, settings.miyorareThemePreset)
        assertEquals(MiyorareModernPaletteSource.MATCH_THEME, settings.miyorareModernPaletteSource)
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, settings.theme)

        settings.setMiyorareDesignStyle(MiyorareDesignStyle.CLASSIC)
        settings.setColorScheme(ColorScheme.MIKU)
        assertEquals(MiyorareDesignStyle.CLASSIC.name, prefs.getString(MiyorareAppearance.KEY_DESIGN_STYLE, null))
        assertEquals(ColorScheme.MIKU.name, prefs.getString(AppSettings.KEY_COLOR_THEME, null))
        settings.setMiyorareDesignStyle(MiyorareDesignStyle.MODERN)
        for (preset in MiyorareThemePreset.entries) {
            settings.setMiyorareThemePreset(preset)
            assertEquals(preset.name, prefs.getString(MiyorareAppearance.KEY_THEME_PRESET, null))
        }
        for (palette in MiyorareModernPaletteSource.getAvailableList()) {
            settings.setMiyorareModernPaletteSource(palette)
            assertEquals(palette.name, prefs.getString(MiyorareAppearance.KEY_MODERN_PALETTE_SOURCE, null))
        }
        for (mode in listOf(
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            AppCompatDelegate.MODE_NIGHT_NO,
            AppCompatDelegate.MODE_NIGHT_YES,
        )) {
            settings.setTheme(mode)
            assertEquals(mode, settings.theme)
            assertEquals(mode.toString(), prefs.getString(AppSettings.KEY_THEME, null))
        }
        settings.setAmoledTheme(true)
        assertTrue(settings.isAmoledTheme)
        assertTrue(prefs.getBoolean(AppSettings.KEY_THEME_AMOLED, false))
        settings.setMiyorareThemePreset(MiyorareThemePreset.SAKURA)
        settings.setMiyorareModernPaletteSource(MiyorareModernPaletteSource.MATCH_THEME)
        settings.isOnboardingCompleted = true
        assertEquals(MiyorareDesignStyle.MODERN, settings.miyorareDesignStyle)
        assertEquals(MiyorareThemePreset.SAKURA, settings.miyorareThemePreset)
        assertEquals(MiyorareModernPaletteSource.MATCH_THEME, settings.miyorareModernPaletteSource)
        assertEquals(ColorScheme.MIKU, settings.colorScheme)
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, settings.theme)
        assertTrue(settings.isAmoledTheme)
    }

    @Test fun sharedPickerWritesStoredPresetAndReflectsSelection() = restoringPrefs { prefs ->
        prefs.edit()
            .putString(MiyorareAppearance.KEY_DESIGN_STYLE, MiyorareDesignStyle.MODERN.name)
            .putString(MiyorareAppearance.KEY_THEME_PRESET, MiyorareThemePreset.MIYORARE.name)
            .putString(MiyorareAppearance.KEY_MODERN_PALETTE_SOURCE, MiyorareModernPaletteSource.MATCH_THEME.name)
            .commit()
        val settings = AppSettings(context)
        compose.setContent {
            MiyorareTheme {
                val preset by rememberStringPref(
                    MiyorareAppearance.KEY_THEME_PRESET, MiyorareThemePreset.MIYORARE.name,
                )
                MiyorareVisualThemePickerRow(
                    selectedValue = preset,
                    onValueChange = { settings.setMiyorareThemePreset(MiyorareThemePreset.valueOf(it)) },
                    customAccent = MiyorareAppearance.DEFAULT_CUSTOM_ACCENT,
                    adaptivePalette = null,
                    darkTheme = isSystemInDarkTheme(),
                    amoled = false,
                    effectLevel = VisualEffectLevel.BALANCED,
                )
            }
        }
        val sakura = context.getString(R.string.miyorare_theme_sakura)
        compose.onNodeWithContentDescription(sakura).performClick()
        compose.waitUntil(5_000) { settings.miyorareThemePreset == MiyorareThemePreset.SAKURA }
        compose.onNodeWithContentDescription(sakura).assertIsSelected()
        assertEquals(MiyorareThemePreset.SAKURA.name, prefs.getString(MiyorareAppearance.KEY_THEME_PRESET, null))
    }

    @Test fun globalThemeRecomposesAndMatchesCanonicalResolver() = restoringPrefs { prefs ->
        prefs.edit()
            .putString(MiyorareAppearance.KEY_DESIGN_STYLE, MiyorareDesignStyle.MODERN.name)
            .putString(MiyorareAppearance.KEY_THEME_PRESET, MiyorareThemePreset.MIYORARE.name)
            .putString(MiyorareAppearance.KEY_MODERN_PALETTE_SOURCE, MiyorareModernPaletteSource.MATCH_THEME.name)
            .putString(VisualEffectPreferences.KEY_LEVEL, VisualEffectLevel.BALANCED.name)
            .putBoolean(AppSettings.KEY_THEME_AMOLED, false)
            .commit()
        val sample = AtomicReference<Sample>()
        compose.setContent {
            MiyorareTheme {
                val colors = MaterialTheme.colorScheme
                val palette = LocalMiyorareVisualPalette.current
                val dark = isSystemInDarkTheme()
                SideEffect {
                    sample.set(Sample(colors.primary, colors.secondary, colors.background, palette.selectedSurface, dark))
                }
            }
        }
        compose.waitUntil(5_000) { sample.get() != null }
        val initial = checkNotNull(sample.get())
        fun expected(preset: MiyorareThemePreset) = resolveMiyorareThemeColors(
            paletteSource = MiyorareModernPaletteSource.MATCH_THEME,
            preset = preset,
            customAccent = MiyorareAppearance.DEFAULT_CUSTOM_ACCENT,
            darkTheme = initial.dark,
            amoled = false,
            effectLevel = VisualEffectLevel.BALANCED,
        )
        val first = expected(MiyorareThemePreset.MIYORARE)
        assertEquals(first.colorScheme.primary, initial.primary)
        assertEquals(first.colorScheme.secondary, initial.secondary)
        assertEquals(first.colorScheme.background, initial.background)
        assertEquals(first.visualPalette.selectedSurface, initial.selectedSurface)
        val second = expected(MiyorareThemePreset.VIOLET)
        prefs.edit().putString(MiyorareAppearance.KEY_THEME_PRESET, MiyorareThemePreset.VIOLET.name).commit()
        compose.waitUntil(5_000) { sample.get()?.primary == second.colorScheme.primary }
        val updated = checkNotNull(sample.get())
        assertEquals(second.colorScheme.secondary, updated.secondary)
        assertEquals(second.colorScheme.background, updated.background)
        assertEquals(second.visualPalette.selectedSurface, updated.selectedSurface)
        assertNotEquals(initial.primary, updated.primary)
        assertNotEquals(initial.secondary, updated.secondary)
    }

    @Test fun onboardingNavigationSettlesOnePageAtATimeAndFinishesOnlyAtEnd() {
        var finished = 0
        compose.setContent {
            MiyorareTheme {
                OnboardingScreen(
                    selectedTheme = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
                    selectedColorScheme = ColorScheme.MIKU,
                    selectedDesignStyle = MiyorareDesignStyle.MODERN,
                    selectedThemePreset = MiyorareThemePreset.MIYORARE,
                    selectedPaletteSource = MiyorareModernPaletteSource.MATCH_THEME,
                    customAccent = MiyorareAppearance.DEFAULT_CUSTOM_ACCENT,
                    customAdaptivePalette = null,
                    effectLevel = VisualEffectLevel.BALANCED,
                    isAmoledEnabled = false,
                    storageSummary = null,
                    isLoading = false,
                    permissions = OnboardingPermissions(false, false, false),
                    actions = OnboardingActions(
                        onThemeChange = {},
                        onColorSchemeChange = {},
                        onDesignStyleChange = {},
                        onThemePresetChange = {},
                        onPaletteSourceChange = {},
                        onAmoledChange = {},
                        onAmoledReset = {},
                        onSelectDestination = {},
                        onPermissionInstall = {},
                        onPermissionNotifications = {},
                        onPermissionBattery = {},
                        onSignInGoogle = {},
                        onRestoreMiyorare = {},
                        onRestoreTachiyomi = {},
                        onOpenGithub = {},
                        onOpenDiscord = {},
                        onVisitWebsite = {},
                        onFinish = { finished++ },
                    ),
                )
            }
        }
        val next = compose.onNodeWithText(context.getString(R.string._continue))
        // The pager may retain an offscreen, disabled Back node from another page.
        val back = compose.onNode(hasText(context.getString(R.string.modern_onboarding_back)) and isEnabled())
        val done = compose.onNodeWithText(context.getString(R.string.done))
        fun assertPage(title: Int) {
            compose.waitForIdle()
            compose.onNodeWithText(context.getString(title)).assertIsDisplayed()
        }
        fun rapidClick(node: androidx.compose.ui.test.SemanticsNodeInteraction) {
            // Deliver two callbacks in the same UI turn, before recomposition can disable the button.
            node.performSemanticsAction(SemanticsActions.OnClick) { click ->
                click()
                click()
            }
        }

        assertPage(R.string.modern_onboarding_welcome_title)
        rapidClick(next)
        assertPage(R.string.modern_onboarding_storage_title)
        rapidClick(next)
        assertPage(R.string.modern_onboarding_sync_title)
        rapidClick(back)
        assertPage(R.string.modern_onboarding_storage_title)
        next.performClick()
        assertPage(R.string.modern_onboarding_sync_title)
        next.performClick()
        assertPage(R.string.modern_onboarding_finish_title)
        compose.runOnIdle { assertEquals(0, finished) }
        done.performClick()
        compose.runOnIdle { assertEquals(1, finished) }
    }

    private fun restoringPrefs(block: (SharedPreferences) -> Unit) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val original = keys.associateWith { prefs.all[it] }
        try {
            block(prefs)
        } finally {
            val editor = prefs.edit()
            for ((key, value) in original) {
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    null -> editor.remove(key)
                    else -> error("Unexpected stored type for $key")
                }
            }
            editor.commit()
        }
    }

    private data class Sample(
        val primary: Color,
        val secondary: Color,
        val background: Color,
        val selectedSurface: Color,
        val dark: Boolean,
    )
}
