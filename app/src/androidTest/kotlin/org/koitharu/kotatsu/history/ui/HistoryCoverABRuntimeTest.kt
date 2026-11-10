package org.koitharu.kotatsu.history.ui

import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hannesdorfmann.adapterdelegates4.ListDelegationAdapter
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.core.model.UnknownMangaSource
import org.koitharu.kotatsu.core.prefs.MiyorareAppearance
import org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle
import org.koitharu.kotatsu.history.ui.util.ReadingProgressView
import org.koitharu.kotatsu.list.ui.FavouriteCoverOverlayView
import org.koitharu.kotatsu.list.ui.MangaCardStatus
import org.koitharu.kotatsu.list.ui.MangaIndicatorsView
import org.koitharu.kotatsu.list.ui.adapter.MangaDetailsClickListener
import org.koitharu.kotatsu.list.ui.adapter.mangaGridItemAD
import org.koitharu.kotatsu.list.ui.model.MangaGridModel
import org.koitharu.kotatsu.list.ui.model.MangaListModel
import org.koitharu.kotatsu.list.ui.model.ModernCoverContext
import org.koitharu.kotatsu.list.ui.size.ItemSizeResolver
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaTag
import kotlin.math.roundToInt
import javax.inject.Inject
import javax.inject.Provider

/** Debug-only synthetic card test, no network or owner's physical device required. */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class HistoryCoverABRuntimeTest {

	@get:Rule val hiltRule = HiltAndroidRule(this)
	@Inject lateinit var imageLoaderProvider: Provider<coil3.ImageLoader>

	@Before fun setUp() { hiltRule.inject() }

	@Test fun historyGridAndCoverRebindA_B_AWithoutStaleIndicators() {
		assertTrue("Instrumentation must target a debug build for the diagnostic probe", BuildConfig.DEBUG)
		// HiltTestApplication does not run the production image-loader initialization.
		imageLoaderProvider.get()
		val context = instrumentation.targetContext
		val prefs = PreferenceManager.getDefaultSharedPreferences(context)
		val old = prefs.getString(MiyorareAppearance.KEY_DESIGN_STYLE, null)
		try {
			prefs.edit().putString(MiyorareAppearance.KEY_DESIGN_STYLE, MiyorareDesignStyle.MODERN.name).commit()
			// CoverImageView uses Hilt view injection and requires an @AndroidEntryPoint host.
			ActivityScenario.launch(
				org.koitharu.kotatsu.scrobbling.mangaupdates.ui.MangaUpdatesAuthActivity::class.java,
			).use { scenario ->
				scenario.onActivity { activity ->
				val themed = ContextThemeWrapper(activity, R.style.Theme_Kotatsu)
				val density = themed.resources.displayMetrics.density
				val resolver = object : ItemSizeResolver {
					override val cellWidth = (194 * density).roundToInt()
					override fun attachToView(view: View, textView: TextView?, progressView: ReadingProgressView?) = Unit
				}
				val listener = object : MangaDetailsClickListener {
					override fun onReadClick(manga: Manga, view: View) = Unit
					override fun onTagClick(manga: Manga, tag: MangaTag, view: View) = Unit
					override fun onItemClick(item: MangaListModel, view: View) = Unit
				}
				var bypass = false
				val adapter = ListDelegationAdapter(
					mangaGridItemAD(resolver, listener, coverContext = ModernCoverContext.BROWSING,
						debugHistoryOverlayBypass = { bypass }),
				)
				val model = MangaGridModel(
					manga = SampleData.manga.copy(coverUrl = null, source = UnknownMangaSource),
					override = null, counter = 42, progress = null, isFavorite = true, isSaved = true,
					isTitleOverCover = true, unreadCount = 17,
				)
				adapter.items = listOf(model)
				val holder = adapter.createViewHolder(FrameLayout(themed), adapter.getItemViewType(0))
				val root = holder.itemView as ViewGroup
				val overlay = root.findViewById<FavouriteCoverOverlayView>(R.id.favourite_cover_overlays)
				val icons = root.findViewById<MangaIndicatorsView>(R.id.iconsView)
				val coverParent = root.findViewById<View>(R.id.imageView_cover).parent
				val legacyIndicators = root.findViewById<LinearLayout>(R.id.layout_indicators)
				val progress = root.findViewById<ReadingProgressView>(R.id.progressView)
				for (coverOnly in listOf(false, true)) {
					for (value in listOf(false, true, false)) {
						bypass = value
						adapter.items = listOf(model.copy(isTitleHidden = coverOnly))
						adapter.bindViewHolder(holder, 0)
						measure(root)
						assertEquals("Cover visibility after A/B/A", !bypass, overlay.isVisible)
						if (bypass) {
							assertTrue("Legacy indicators restored in B", icons.isVisible)
							assertEquals(MangaCardStatus.LIBRARY_DOWNLOADED, icons.status)
							assertSame("Progress reparented to legacy owner in B", legacyIndicators, progress.parent)
						} else {
							assertFalse("Old status icons suppressed in A", icons.isVisible)
							assertEquals(MangaCardStatus.NONE, icons.status)
							assertSame("Progress reparented to Modern cover in A", coverParent, progress.parent)
						}
					}
				}
				}
			}
		} finally {
			prefs.edit().apply {
				if (old == null) remove(MiyorareAppearance.KEY_DESIGN_STYLE)
				else putString(MiyorareAppearance.KEY_DESIGN_STYLE, old)
			}.commit()
		}
	}

	private fun measure(view: View) {
		val density = view.resources.displayMetrics.density
		view.measure(View.MeasureSpec.makeMeasureSpec((194 * density).roundToInt(), View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec((344 * density).roundToInt(), View.MeasureSpec.EXACTLY))
		view.layout(0, 0, view.measuredWidth, view.measuredHeight)
	}
}
