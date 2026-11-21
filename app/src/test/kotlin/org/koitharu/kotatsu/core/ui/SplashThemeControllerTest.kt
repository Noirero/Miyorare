package org.koitharu.kotatsu.core.ui

import android.content.res.Resources
import androidx.appcompat.app.AppCompatDelegate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.koitharu.kotatsu.R

class SplashThemeControllerTest {

    @Test fun explicitDarkUsesFixedDarkSplashEvenWhenSystemIsLight() {
        assertEquals(
            R.style.Theme_Miyorare_Splash_Dark,
            SplashThemeController.themeForDisplayMode(AppCompatDelegate.MODE_NIGHT_YES),
        )
    }

    @Test fun explicitLightUsesFixedLightSplashEvenWhenSystemIsDark() {
        assertEquals(
            R.style.Theme_Miyorare_Splash_Light,
            SplashThemeController.themeForDisplayMode(AppCompatDelegate.MODE_NIGHT_NO),
        )
        assertNotEquals(
            SplashThemeController.themeForDisplayMode(AppCompatDelegate.MODE_NIGHT_NO),
            SplashThemeController.themeForDisplayMode(AppCompatDelegate.MODE_NIGHT_YES),
        )
    }

    @Test fun followSystemClearsPersistedOverride() {
        assertEquals(
            Resources.ID_NULL,
            SplashThemeController.themeForDisplayMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
        )
    }

    @Test fun unknownModeDoesNotLeaveStaleForcedSplash() {
        assertEquals(Resources.ID_NULL, SplashThemeController.themeForDisplayMode(Int.MAX_VALUE))
        assertEquals(
            Resources.ID_NULL,
            SplashThemeController.themeForDisplayMode(AppCompatDelegate.MODE_NIGHT_UNSPECIFIED),
        )
    }
}
