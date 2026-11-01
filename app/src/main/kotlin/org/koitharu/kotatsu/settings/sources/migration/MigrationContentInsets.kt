package org.koitharu.kotatsu.settings.sources.migration

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import androidx.core.view.doOnDetach
import androidx.core.view.updatePadding
import com.google.android.material.appbar.AppBarLayout

/**
 * Settings' scrolling fragment can extend below the system navigation bar while the
 * collapsing app bar is expanded. Account for both offsets once at the Compose host;
 * inner migration action panels must not apply navigationBarsPadding a second time.
 */
internal fun bindMigrationContentInsets(view: View, appBar: AppBarLayout) {
    var appBarOverlap = appBar.totalScrollRange
    var navigationBarBottom = 0

    fun update() {
        view.updatePadding(bottom = appBarOverlap + navigationBarBottom)
    }

    val offsetListener = AppBarLayout.OnOffsetChangedListener { bar, offset ->
        appBarOverlap = (bar.totalScrollRange + offset).coerceAtLeast(0)
        update()
    }
    appBar.addOnOffsetChangedListener(offsetListener)
    ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
        navigationBarBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        update()
        insets
    }
    view.doOnAttach { ViewCompat.requestApplyInsets(it) }
    view.doOnDetach {
        appBar.removeOnOffsetChangedListener(offsetListener)
        ViewCompat.setOnApplyWindowInsetsListener(view, null)
    }
    update()
}
