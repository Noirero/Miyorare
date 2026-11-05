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
internal fun bindMigrationContentInsets(
    view: View,
    appBar: AppBarLayout,
    diagnostic: ((String) -> Unit)? = null,
) {
    var appBarOffset = 0
    var navigationBarBottom = 0

    // Temporary exact-path diagnostic. No output or extra reads outside the targeted test.
    fun record(event: String) {
        if (diagnostic == null) return
        diagnostic(
            "$event range=${appBar.totalScrollRange} offset=$appBarOffset nav=$navigationBarBottom " +
                "padding=${view.paddingBottom} hostAttached=${view.isAttachedToWindow} " +
                "barAttached=${appBar.isAttachedToWindow} barLaidOut=${appBar.isLaidOut}"
        )
    }

    fun update() {
        record("update.enter")
        val appBarOverlap = (appBar.totalScrollRange + appBarOffset).coerceAtLeast(0)
        val bottomInset = appBarOverlap + navigationBarBottom
        if (view.paddingBottom != bottomInset) view.updatePadding(bottom = bottomInset)
        record("update.exit expected=$bottomInset")
    }

    val offsetListener = AppBarLayout.OnOffsetChangedListener { _, offset ->
        appBarOffset = offset
        record("appBar.offset")
        update()
    }
    // totalScrollRange can still be zero at onViewCreated, before the expanded
    // toolbar is measured. Without a second measurement the sticky footer can
    // land beneath the system navigation bar on first entry to a source.
    val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        record("appBar.layout")
        update()
    }
    appBar.addOnOffsetChangedListener(offsetListener)
    appBar.addOnLayoutChangeListener(layoutListener)
    ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
        navigationBarBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        record("window.insets")
        update()
        insets
    }
    view.doOnAttach {
        record("host.attach")
        ViewCompat.requestApplyInsets(it)
    }
    view.doOnDetach {
        record("host.detach.removeListeners")
        appBar.removeOnOffsetChangedListener(offsetListener)
        appBar.removeOnLayoutChangeListener(layoutListener)
        ViewCompat.setOnApplyWindowInsetsListener(view, null)
    }
    record("bind.beforeInitialUpdate")
    update()
}
