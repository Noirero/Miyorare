package org.koitharu.kotatsu.core.ui.dialog

import android.content.res.Configuration
import android.view.LayoutInflater
import androidx.appcompat.view.ContextThemeWrapper
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.shape.MaterialShapeDrawable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.MiyorareAppearance
import org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle
import org.koitharu.kotatsu.core.prefs.MiyorareModernPaletteSource
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import org.koitharu.kotatsu.core.ui.miyorareViewPaletteFromPreferences
import org.koitharu.kotatsu.databinding.DialogTwoButtonsBinding

/** Verifies actual inflated Android Material Views, not just pure ColorScheme role mappings. */
@RunWith(AndroidJUnit4::class)
class ModernConfirmationDialogColorsTest {

    @Test fun positiveActionIconAndSurfaceFollowAllModernPresetsWithoutChangingNegativeButton() {
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
                for (dark in listOf(false, true)) {
                    val config = Configuration(target.resources.configuration).apply {
                        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                            (if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
                    }
                    val themed = ContextThemeWrapper(
                        target.createConfigurationContext(config), R.style.Theme_Kotatsu,
                    ).apply { theme.applyStyle(R.style.ThemeOverlay_MiyorareModern, true) }
                    for (preset in listOf(
                        MiyorareThemePreset.MIYORARE,
                        MiyorareThemePreset.CYAN,
                        MiyorareThemePreset.SAKURA,
                        MiyorareThemePreset.VIOLET,
                        MiyorareThemePreset.CUSTOM,
                    )) {
                        prefs.edit()
                            .putString(MiyorareAppearance.KEY_DESIGN_STYLE, MiyorareDesignStyle.MODERN.name)
                            .putString(MiyorareAppearance.KEY_MODERN_PALETTE_SOURCE, MiyorareModernPaletteSource.MATCH_THEME.name)
                            .putString(MiyorareAppearance.KEY_THEME_PRESET, preset.name)
                            .commit()
                        val expected = requireNotNull(themed.miyorareViewPaletteFromPreferences())
                        val negativeBefore = DialogTwoButtonsBinding.inflate(LayoutInflater.from(themed))
                            .button3.strokeColor?.defaultColor
                        val builder = BigButtonsAlertDialog.Builder(themed)
                            .setPositiveButton(android.R.string.ok) { _, _ -> }
                            .setNegativeButton(android.R.string.cancel)
                        val bindingField = builder.javaClass.getDeclaredField("binding").apply { isAccessible = true }
                        val binding = bindingField.get(builder) as DialogTwoButtonsBinding
                        assertEquals("primary $preset dark=$dark", expected.primary, binding.button1.backgroundTintList?.defaultColor)
                        assertEquals("onPrimary $preset dark=$dark", expected.onPrimary, binding.button1.textColors.defaultColor)
                        assertEquals("icon $preset dark=$dark", expected.primary, binding.icon.imageTintList?.defaultColor)
                        assertEquals("icon container $preset dark=$dark", expected.primaryContainer, binding.icon.backgroundTintList?.defaultColor)
                        assertEquals("negative untouched $preset dark=$dark", negativeBefore, binding.button3.strokeColor?.defaultColor)
                        val dialogField = builder.javaClass.getDeclaredField("delegate").apply { isAccessible = true }
                        val dialogBuilder = dialogField.get(builder) as com.google.android.material.dialog.MaterialAlertDialogBuilder
                        val surface = dialogBuilder.background as? MaterialShapeDrawable
                        assertNotNull("MaterialShapeDrawable $preset", surface)
                        assertEquals("surface $preset dark=$dark", expected.surface, surface?.fillColor?.defaultColor)
                    }
                }
            }
        } finally {
            val editor = prefs.edit()
            for ((key, value) in saved) {
                if (value == null) editor.remove(key) else editor.putString(key, value)
            }
            editor.commit()
        }
    }
}
