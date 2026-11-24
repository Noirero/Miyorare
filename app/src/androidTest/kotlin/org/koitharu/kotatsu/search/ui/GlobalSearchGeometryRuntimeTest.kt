package org.koitharu.kotatsu.search.ui

import android.content.res.Configuration
import android.graphics.Rect
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hannesdorfmann.adapterdelegates4.ListDelegationAdapter
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.model.UnknownMangaSource
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.MiyorareAppearance
import org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.list.ui.MangaSelectionDecoration
import org.koitharu.kotatsu.list.ui.adapter.MangaDetailsClickListener
import org.koitharu.kotatsu.list.ui.model.MangaGridModel
import org.koitharu.kotatsu.list.ui.model.MangaListModel
import org.koitharu.kotatsu.list.ui.size.DynamicItemSizeResolver
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.scrobbling.mangaupdates.ui.MangaUpdatesAuthActivity
import org.koitharu.kotatsu.search.ui.multi.SearchResultsListModel
import org.koitharu.kotatsu.search.ui.multi.adapter.searchResultsAD
import javax.inject.Inject
import javax.inject.Provider
import kotlin.math.abs
import kotlin.math.roundToInt

/** Measure real grouped-search rows, with offline synthetic data and no changes to app code. */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class GlobalSearchGeometryRuntimeTest {

    @get:Rule val hiltRule = HiltAndroidRule(this)
    @Inject lateinit var imageLoaderProvider: Provider<coil3.ImageLoader>
    @Inject lateinit var settings: AppSettings

    @Before fun setUp() {
        hiltRule.inject()
        imageLoaderProvider.get()
    }

    @Test fun narrowHeadersKeepMetadataAndActionsReadable() = withRows { row, width, scale, bind ->
        val failures = mutableListOf<String>()
        val header = row.findViewById<LinearLayout>(R.id.source_header)
        val title = row.findViewById<TextView>(R.id.textView_title)
        val language = row.findViewById<TextView>(R.id.text_view_source_language)
        val count = row.findViewById<TextView>(R.id.text_view_result_count)
        val status = row.findViewById<TextView>(R.id.text_view_status)
        val more = row.findViewById<TextView>(R.id.button_more)
        for (loading in listOf(false, true)) for (longName in listOf(false, true)) {
            bind(0, loading)
            // Simulate extension metadata without requiring a third-party extension or network.
            title.text = if (longName) "A Very Long Extension Source Name" else "Shinigami"
            language.text = "EN-GB"
            measureRow(row, width)
            val tag = "${width}dp font=$scale loading=$loading longName=$longName"
            val views = listOf("title" to title, "language" to language, "count" to count,
                "status" to status, "showAll" to more)
            val report = views.joinToString { (name, view) ->
                val layout = view.layout
                "$name=${view.width}x${view.height}px lines=${layout?.lineCount} ellipsis=" +
                    (layout?.let { l -> (0 until l.lineCount).sumOf { l.getEllipsisCount(it) } } ?: -1)
            }
            Log.i("GlobalSearchGeometry", "$tag header=${header.width}x${header.height}px $report")
            if (scale == 1f && header.orientation != LinearLayout.HORIZONTAL) {
                failures += "$tag ordinary header must remain horizontal"
            }
            if (scale == 2f && header.orientation != LinearLayout.VERTICAL) {
                failures += "$tag constrained header must give the action its own row"
            }
            if (header.orientation == LinearLayout.VERTICAL &&
                more.top < row.findViewById<View>(R.id.source_identity).bottom) {
                failures += "$tag action overlaps identity row"
            }
            for ((name, view) in views) {
                val bounds = Rect(0, 0, view.width, view.height)
                header.offsetDescendantRectToMyCoords(view, bounds)
                val textLayout = view.layout
                val fits = view.visibility == View.VISIBLE && view.width > 0 && view.height > 0 &&
                    bounds.left >= 0 && bounds.top >= 0 &&
                    bounds.right <= header.width && bounds.bottom <= header.height &&
                    view.width > view.compoundPaddingLeft + view.compoundPaddingRight &&
                    textLayout != null && textLayout.lineCount > 0 &&
                    textLayout.height <= view.height - view.compoundPaddingTop - view.compoundPaddingBottom
                val complete = textLayout != null && textLayout.lineCount > 0 &&
                    (0 until textLayout.lineCount).all { textLayout.getEllipsisCount(it) == 0 } &&
                    textLayout.getLineEnd(textLayout.lineCount - 1) >= textLayout.text.length
                // Long source names may intentionally ellipsize; metadata/actions and short
                // names must remain readable. Do not fail merely because a header grows taller.
                val readable = if (name == "title" && longName) {
                    textLayout != null && textLayout.lineCount > 0 &&
                        (textLayout.lineCount > 1 || textLayout.getEllipsisCount(0) == 0 ||
                            textLayout.getEllipsisStart(0) > 0)
                } else complete
                if (!fits || !readable) failures += "$tag $name fits=$fits readable=$readable $report"
            }
        }
        // The same holder must recover its horizontal layout when the available width
        // increases, then return to stacked rows without stale state on a narrow screen.
        if (scale == 2f) {
            bind(0, false)
            title.text = "Shinigami"
            language.text = "EN-GB"
            for ((probeWidth, expected) in listOf(width to LinearLayout.VERTICAL,
                600 to LinearLayout.HORIZONTAL, width to LinearLayout.VERTICAL)) {
                measureRow(row, probeWidth)
                if (header.orientation != expected) {
                    failures += "font=$scale width=$probeWidth stale orientation=${header.orientation}, expected=$expected"
                }
            }
        }
        failures
    }

    @Test fun singleAndMultipleResultsKeepSameMeasuredCoverGeometry() = withRows { row, width, scale, bind ->
        val failures = mutableListOf<String>()
        val rail = row.findViewById<RecyclerView>(R.id.recyclerView)
        var reference: Pair<Int, Int>? = null
        for (count in listOf(1, 5, 1)) {
            bind(count, false)
            measureRow(row, width)
            // A second layout lets the attach-driven DynamicItemSizeResolver settle.
            measureRow(row, width)
            val covers = (0 until rail.childCount).map { index ->
                val cover = rail.getChildAt(index).findViewById<View>(R.id.imageView_cover)
                cover.width to cover.height
            }
            val tag = "${width}dp font=$scale count=$count"
            Log.i("GlobalSearchGeometry", "$tag rail=${rail.width}x${rail.height}px covers=$covers")
            if (covers.isEmpty() || (count == 5 && covers.size < 2)) {
                failures += "$tag missing visible cover measurements: $covers"
                continue
            }
            if (reference == null) reference = covers.first()
            for ((w, h) in covers) {
                if (w <= 0 || h <= 0 || abs(h - w * 18f / 13f) > 2f ||
                    (w to h) != reference) {
                    failures += "$tag cover=${w}x${h}px reference=$reference (expected same 13:18 geometry)"
                }
            }
        }
        failures
    }

    private fun withRows(probe: (View, Int, Float, (Int, Boolean) -> Unit) -> List<String>) {
        val failures = mutableListOf<String>()
        ActivityScenario.launch(MangaUpdatesAuthActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
                val styleKey = MiyorareAppearance.KEY_DESIGN_STYLE
                val oldStyle = prefs.getString(styleKey, null)
                val hadGridSize = prefs.contains(AppSettings.KEY_GRID_SIZE)
                val oldGridSize = settings.gridSize
                try {
                    prefs.edit().putString(styleKey, MiyorareDesignStyle.MODERN.name)
                        .putInt(AppSettings.KEY_GRID_SIZE, 100).commit()
                    for (width in listOf(320, 360)) for (scale in listOf(1f, 1.3f, 2f)) {
                        val config = Configuration(activity.resources.configuration).apply {
                            screenWidthDp = width
                            fontScale = scale
                            setLocale(java.util.Locale.ENGLISH)
                        }
                        // Keep the Activity in the context chain for Hilt-injected image views.
                        val themed = ContextThemeWrapper(activity, R.style.Theme_Kotatsu).apply {
                            applyOverrideConfiguration(config)
                            theme.applyStyle(R.style.ThemeOverlay_MiyorareModern, true)
                        }
                        val host = FrameLayout(themed)
                        activity.addContentView(host, ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                        try {
                            val resolver = DynamicItemSizeResolver(themed.resources, activity, settings, adjustWidth = true)
                            val listener = object : MangaDetailsClickListener {
                                override fun onReadClick(manga: Manga, view: View) = Unit
                                override fun onTagClick(manga: Manga, tag: MangaTag, view: View) = Unit
                                override fun onItemClick(item: MangaListModel, view: View) = Unit
                            }
                            val adapter = ListDelegationAdapter(searchResultsAD(
                                RecyclerView.RecycledViewPool(), resolver, MangaSelectionDecoration(themed),
                                listener, OnListItemClickListener<SearchResultsListModel> { _, _ -> },
                            ))
                            fun group(count: Int, loading: Boolean) = SearchResultsListModel(
                                titleResId = 0, source = LocalMangaSource, listFilter = null, sortOrder = null,
                                list = List(count) { index ->
                                    MangaGridModel(
                                        manga = SampleData.manga.copy(id = SampleData.manga.id + index,
                                            coverUrl = null, source = UnknownMangaSource),
                                        override = null, counter = 0, progress = null, isFavorite = false,
                                        isSaved = false, isTitleOverCover = true, unreadCount = null,
                                    )
                                }, error = null, isLoading = loading,
                            )
                            adapter.items = listOf(group(0, false))
                            val holder = adapter.createViewHolder(host, adapter.getItemViewType(0))
                            val row = holder.itemView
                            host.addView(row)
                            failures += probe(row, width, scale) { count, loading ->
                                adapter.items = listOf(group(count, loading))
                                adapter.bindViewHolder(holder, 0)
                            }
                        } finally {
                            host.removeAllViews()
                            (host.parent as? ViewGroup)?.removeView(host)
                        }
                    }
                } finally {
                    prefs.edit().apply {
                        if (oldStyle == null) remove(styleKey) else putString(styleKey, oldStyle)
                        if (hadGridSize) putInt(AppSettings.KEY_GRID_SIZE, oldGridSize)
                        else remove(AppSettings.KEY_GRID_SIZE)
                    }.commit()
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun measureRow(row: View, widthDp: Int) {
        val width = (widthDp * row.resources.displayMetrics.density).roundToInt()
        row.requestLayout()
        row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        row.layout(0, 0, row.measuredWidth, row.measuredHeight)
    }
}
