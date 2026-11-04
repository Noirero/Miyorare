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
    var appBarOffset = 0
    var navigationBarBottom = 0

    fun update() {
        val appBarOverlap = (appBar.totalScrollRange + appBarOffset).coerceAtLeast(0)
        val bottomInset = appBarOverlap + navigationBarBottom
        if (view.paddingBottom != bottomInset) view.updatePadding(bottom = bottomInset)
    }

    val offsetListener = AppBarLayout.OnOffsetChangedListener { _, offset ->
        appBarOffset = offset
        update()
    }
    // totalScrollRange can still be zero at onViewCreated, before the expanded
    // toolbar is measured. Without a second measurement the sticky footer can
    // land beneath the system navigation bar on first entry to a source.
    val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> update() }
    appBar.addOnOffsetChangedListener(offsetListener)
    appBar.addOnLayoutChangeListener(layoutListener)
    ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
        navigationBarBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        update()
        insets
    }
    view.doOnAttach { ViewCompat.requestApplyInsets(it) }
    view.doOnDetach {
        appBar.removeOnOffsetChangedListener(offsetListener)
        appBar.removeOnLayoutChangeListener(layoutListener)
        ViewCompat.setOnApplyWindowInsetsListener(view, null)
    }
    update()
}
