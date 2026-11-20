package org.koitharu.kotatsu.core.ui

import android.app.Activity
import android.content.res.Resources
import android.os.Build
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatDelegate
import org.koitharu.kotatsu.R

/**
 * Persist the next platform splash independently of Android's current system night mode.
 * The regular app UI continues to use AppCompatDelegate and AppSettings.KEY_THEME.
 */
internal object SplashThemeController {

    @StyleRes
    internal fun themeForDisplayMode(mode: Int): Int = when (mode) {
        AppCompatDelegate.MODE_NIGHT_YES -> R.style.Theme_Miyorare_Splash_Dark
        AppCompatDelegate.MODE_NIGHT_NO -> R.style.Theme_Miyorare_Splash_Light
        else -> Resources.ID_NULL // Follow System (and unsupported/invalid values).
    }

    private var lastAppliedTheme: Int? = null

    fun sync(activity: Activity, displayMode: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val target = themeForDisplayMode(displayMode)
        if (lastAppliedTheme == target) return
        activity.splashScreen.setSplashScreenTheme(target)
        lastAppliedTheme = target
    }
}
