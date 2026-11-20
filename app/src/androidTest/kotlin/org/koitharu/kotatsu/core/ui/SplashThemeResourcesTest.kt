package org.koitharu.kotatsu.core.ui

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.ContextThemeWrapper
import androidx.core.content.res.use
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SdkSuppress
import org.junit.Assert.assertEquals
import org.junit.Test
import org.koitharu.kotatsu.R

/** Validates real Android resource resolution, including the inherited existing splash icon. */
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.S)
class SplashThemeResourcesTest {

    @Test fun fixedLightAndDarkStylesPreserveTheExistingSplashIcon() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        for ((style, color) in listOf(
            R.style.Theme_Miyorare_Splash_Light to Color.rgb(0xFF, 0xF8, 0xFC),
            R.style.Theme_Miyorare_Splash_Dark to Color.rgb(0x09, 0x06, 0x0E),
        )) {
            val themed = ContextThemeWrapper(app, style)
            themed.obtainStyledAttributes(intArrayOf(
                android.R.attr.windowSplashScreenBackground,
                android.R.attr.windowSplashScreenAnimatedIcon,
            )).use { attrs ->
                assertEquals(color, attrs.getColor(0, Color.TRANSPARENT))
                assertEquals(R.drawable.miyorare_splash_icon, attrs.getResourceId(1, 0))
            }
        }
    }
}
