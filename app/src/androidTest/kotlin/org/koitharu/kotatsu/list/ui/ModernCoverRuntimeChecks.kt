package org.koitharu.kotatsu.list.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.preference.PreferenceManager
import com.hannesdorfmann.adapterdelegates4.ListDelegationAdapter
import org.junit.Assert.*
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.model.UnknownMangaSource
import org.koitharu.kotatsu.core.prefs.MiyorareAppearance
import org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle
import org.koitharu.kotatsu.core.prefs.ProgressIndicatorMode
import org.koitharu.kotatsu.history.ui.util.ReadingProgressView
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.list.domain.ReadingProgress
import org.koitharu.kotatsu.list.ui.adapter.MangaDetailsClickListener
import org.koitharu.kotatsu.list.ui.adapter.mangaGridItemAD
import org.koitharu.kotatsu.list.ui.model.MangaGridModel
import org.koitharu.kotatsu.list.ui.model.MangaListModel
import org.koitharu.kotatsu.list.ui.model.ModernCoverContext
import org.koitharu.kotatsu.list.ui.model.ModernCoverState
import org.koitharu.kotatsu.list.ui.size.ItemSizeResolver
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaTag
import kotlin.math.roundToInt

internal object ModernCoverRuntimeChecks {
	fun projectionAndRecycling(context: Context) {
		val themed = ContextThemeWrapper(context, R.style.Theme_Kotatsu)
		val cover = FavouriteCoverOverlayView(themed)
		val model = model().copy(manga = model().manga.copy(source = LocalMangaSource), counter = 78,
			isPinned = true, isLocalSource = true, languageLabel = "JA", showContinueReading = true, unreadCount = 115)
		var clicks = 0
		cover.bind(ModernCoverState.from(model, ModernCoverContext.COLLECTION)) { clicks++ }
		layout(cover)
		assertTrue(cover.findViewById<View>(R.id.cover_pinned).isVisible)
		assertTrue(cover.findViewById<View>(R.id.cover_continue).performClick())
		assertEquals(1, clicks)
		for (mask in listOf(1, 2, 3, 0, 3, 0)) {
			val value = model.copy(isFavorite = mask and 1 != 0, isSaved = mask and 2 != 0, unreadCount = null)
			val state = ModernCoverState.from(value, ModernCoverContext.BROWSING)
			assertFalse(state.isPinned)
			assertFalse(state.showContinueReading)
			assertNull(state.unreadCount)
			assertEquals(78, state.legacyCounter)
			assertTrue(state.isLocalSource)
			assertNull("Local has no declared source language", state.languageLabel)
			cover.bind(state) { clicks++ }
			layout(cover)
			val statuses = cover.findViewById<ViewGroup>(R.id.cover_permanent_status)
			val active = statuses.children.filter { it.isVisible }.toList()
			assertEquals(Integer.bitCount(mask), active.size)
			assertEquals(mask != 0, statuses.isVisible)
			if (active.isNotEmpty()) {
				assertEquals(statuses.paddingLeft, active.first().left)
				assertEquals(statuses.width - statuses.paddingRight, active.last().right)
			}
			assertFalse(cover.findViewById<View>(R.id.cover_unread).isVisible)
			assertTrue(cover.findViewById<View>(R.id.cover_chapter_counter).isVisible)
			assertEquals("78", cover.findViewById<TextView>(R.id.cover_legacy_counter).text.toString())
			val counter = cover.findViewById<View>(R.id.cover_chapter_counter)
			assertTrue(counter.background is GradientDrawable)
			assertEquals("${themed.getString(R.string.chapters)}: 78", counter.contentDescription)
			assertTrue(cover.findViewById<View>(R.id.cover_counter_icon).isVisible)
			assertFalse(cover.findViewById<View>(R.id.cover_continue).performClick())
			assertEquals(1, clicks)
			val metadata = cover.findViewById<ViewGroup>(R.id.cover_metadata)
			assertEquals(listOf(R.id.cover_local), metadata.children.filter { it.isVisible }.map { it.id }.toList())
		}
		// An explicit cached unread projection is independent of the legacy tracker/chapter counter.
		cover.bind(ModernCoverState.from(model.copy(unreadCount = 999), ModernCoverContext.BROWSING)) {}
		layout(cover)
		assertTrue(cover.findViewById<View>(R.id.cover_unread).isVisible)
		assertEquals("999", cover.findViewById<TextView>(R.id.cover_unread_number).text.toString())
		assertEquals("78", cover.findViewById<TextView>(R.id.cover_legacy_counter).text.toString())
		cover.bind(ModernCoverState.from(model().copy(counter = 0, isTitleHidden = true, unreadCount = 10), ModernCoverContext.BROWSING)) {}
		layout(cover)
		cover.bind(ModernCoverState.from(model().copy(counter = 0), ModernCoverContext.BROWSING)) {}
		layout(cover)
		assertFalse(cover.findViewById<View>(R.id.cover_metadata).isVisible)
		assertFalse(cover.findViewById<View>(R.id.cover_chapter_counter).isVisible)
		assertNull(cover.contentDescription)
		assertFalse(cover.titleView.contentDescription.toString().contains("78"))
	}

	fun counterCompactionAndThemes(context: Context) {
		for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
			for (fontScale in listOf(1f, 1.5f)) {
				val config = Configuration(context.resources.configuration).apply {
					uiMode = night
					this.fontScale = fontScale
				}
				val themed = ContextThemeWrapper(context.createConfigurationContext(config), R.style.Theme_Kotatsu)
				val cover = FavouriteCoverOverlayView(themed)
				for (count in listOf(999, 1000, 0, 78)) {
					cover.bind(ModernCoverState.from(model().copy(counter = count, unreadCount = 115,
						isFavorite = true, isSaved = true), ModernCoverContext.BROWSING)) {}
					for ((w, h) in listOf(194 to 344, 55 to 33, 59 to 105, 128 to 170, 194 to 344)) {
						for (selected in listOf(false, true, false)) {
							cover.isSelected = selected
							layout(cover, w, h)
							val children = cover.children.filter { it.isVisible }.toList()
							val reserved = (18 * themed.resources.displayMetrics.density).roundToInt()
							for (child in children) assertTrue("Modern counter compaction stays above Progress", child.left >= 0 &&
								child.top >= 0 && child.right <= cover.width && child.bottom <= cover.height - reserved)
							for (a in children.indices) for (b in a + 1 until children.size) {
								assertFalse("Modern counter collision", Rect.intersects(bounds(cover, children[a]), bounds(cover, children[b])))
							}
						}
					}
					val counter = cover.findViewById<View>(R.id.cover_chapter_counter)
					assertEquals(count > 0, counter.isVisible)
					val label = cover.findViewById<TextView>(R.id.cover_legacy_counter)
					assertEquals(if (count > 999) "99+" else if (count > 0) count.toString() else "", label.text.toString())
					if (count == 0) assertNull(counter.contentDescription)
					else assertEquals("${themed.getString(R.string.chapters)}: $count", counter.contentDescription)
				}
			}
		}
	}

	fun actualAdapterGatingAndProgress(activity: Activity) {
		val themed = ContextThemeWrapper(activity, R.style.Theme_Kotatsu)
		val preferences = PreferenceManager.getDefaultSharedPreferences(themed)
		val oldStyle = preferences.getString(MiyorareAppearance.KEY_DESIGN_STYLE, null)
		val oldIntent = Intent(activity.intent)
		try {
			for (style in MiyorareDesignStyle.entries) {
				preferences.edit().putString(MiyorareAppearance.KEY_DESIGN_STYLE, style.name).commit()
				for (space in FavouriteSpace.entries) for (overCover in listOf(false, true)) {
					activity.intent.putExtra(EXTRA_FAVOURITE_SPACE, space.dbValue)
					val value = model().copy(isFavorite = true, isSaved = true, isTitleOverCover = overCover,
						progress = ReadingProgress(0.5f, 20, ProgressIndicatorMode.PERCENT_READ))
					val legacy = boundCard(themed, ModernCoverContext.LEGACY, value)
					val browsing = boundCard(themed, ModernCoverContext.BROWSING, value)
					val modern = style == MiyorareDesignStyle.MODERN
					assertEquals(modern, browsing.findViewById<View>(R.id.favourite_cover_overlays).isVisible)
					assertFalse(legacy.findViewById<View>(R.id.favourite_cover_overlays).isVisible)
					val oldCover = legacy.findViewById<com.google.android.material.imageview.ShapeableImageView>(R.id.imageView_cover)
					val newCover = browsing.findViewById<com.google.android.material.imageview.ShapeableImageView>(R.id.imageView_cover)
					assertEquals("Browsing never acquires a collection border", oldCover.strokeWidth, newCover.strokeWidth)
					assertEquals(oldCover.width, newCover.width)
					assertEquals(oldCover.height, newCover.height)
					val oldProgress = legacy.findViewById<ReadingProgressView>(R.id.progressView)
					val progress = browsing.findViewById<ReadingProgressView>(R.id.progressView)
					if (modern) assertModernProgress(browsing, progress) else {
						assertSame(browsing.findViewById<View>(R.id.layout_indicators), progress.parent)
						assertEquals(bounds(legacy, oldProgress), bounds(browsing, progress))
					}
					assertEquals(oldProgress.progress, progress.progress)
					if (modern) {
						val overlays = browsing.findViewById<FavouriteCoverOverlayView>(R.id.favourite_cover_overlays)
						for (selected in listOf(false, true, false)) {
							overlays.isSelected = selected
							layout(browsing)
							val protectedBounds = bounds(browsing, progress)
							for (child in overlays.children.filter { it.isVisible }) {
								assertFalse("Overlay/progress collision", Rect.intersects(protectedBounds, bounds(browsing, child)))
							}
						}
					}
					val collection = boundCard(themed, ModernCoverContext.COLLECTION, value.copy(unreadCount = 115))
					assertEquals(modern, collection.findViewById<View>(R.id.favourite_cover_overlays).isVisible)
					if (modern) {
						assertTrue(collection.findViewById<com.google.android.material.imageview.ShapeableImageView>(R.id.imageView_cover).strokeWidth > newCover.strokeWidth)
						val p = collection.findViewById<ReadingProgressView>(R.id.progressView)
						assertModernProgress(collection, p)
						assertEquals(value.progress, p.progress)
						val collectionCover = collection.findViewById<com.google.android.material.imageview.ShapeableImageView>(R.id.imageView_cover)
						assertEquals(collectionCover.shapeAppearanceModel.topLeftCornerSize, newCover.shapeAppearanceModel.topLeftCornerSize)
						val collectionScrim = collection.findViewById<View>(R.id.view_scrim).background as GradientDrawable
						val browsingScrim = browsing.findViewById<View>(R.id.view_scrim).background as GradientDrawable
						assertArrayEquals(collectionScrim.colors, browsingScrim.colors)
						assertFalse((legacy.findViewById<View>(R.id.view_scrim).background as GradientDrawable).colors.contentEquals(browsingScrim.colors))
					} else {
						val p = collection.findViewById<ReadingProgressView>(R.id.progressView)
						assertSame(collection.findViewById<View>(R.id.layout_indicators), p.parent)
						assertEquals(bounds(legacy, oldProgress), bounds(collection, p))
					}
				}
			}
		} finally {
			activity.intent = oldIntent
			preferences.edit().apply {
				if (oldStyle == null) remove(MiyorareAppearance.KEY_DESIGN_STYLE) else putString(MiyorareAppearance.KEY_DESIGN_STYLE, oldStyle)
			}.commit()
		}
	}

	private fun assertModernProgress(card: ViewGroup, progress: ReadingProgressView) {
		val cover = card.findViewById<View>(R.id.imageView_cover).parent as ViewGroup
		val density = card.resources.displayMetrics.density
		assertSame("Every opted-in Modern host uses the bottom-edge owner", cover, progress.parent)
		assertFalse(card.findViewById<View>(R.id.layout_indicators).isVisible)
		if (progress.progress == null || progress.progress?.mode == ProgressIndicatorMode.NONE) {
			assertEquals(0, progress.height)
		} else {
			assertEquals((14 * density).toInt(), progress.height)
			assertEquals((8 * density).toInt(), progress.left)
			assertEquals(cover.width - (8 * density).toInt(), progress.right)
			assertEquals(cover.height - (4 * density).toInt(), progress.bottom)
		}
	}

	private fun boundCard(context: Context, host: ModernCoverContext, value: MangaGridModel): ViewGroup {
		val resolver = object : ItemSizeResolver {
			override val cellWidth = (194 * context.resources.displayMetrics.density).roundToInt()
			override fun attachToView(view: View, textView: TextView?, progressView: ReadingProgressView?) = Unit
		}
		val listener = object : MangaDetailsClickListener {
			override fun onReadClick(manga: Manga, view: View) = Unit
			override fun onTagClick(manga: Manga, tag: MangaTag, view: View) = Unit
			override fun onItemClick(item: MangaListModel, view: View) = Unit
		}
		val adapter = ListDelegationAdapter(mangaGridItemAD(resolver, listener, coverContext = host))
		adapter.items = listOf(value)
		val holder = adapter.createViewHolder(FrameLayout(context), adapter.getItemViewType(0))
		adapter.bindViewHolder(holder, 0)
		val root = holder.itemView as ViewGroup
		for (progress in listOf(ReadingProgress(0.16f, 20, ProgressIndicatorMode.PERCENT_READ),
			ReadingProgress(0f, 20, ProgressIndicatorMode.PERCENT_READ),
			ReadingProgress(0.86f, 20, ProgressIndicatorMode.PERCENT_READ),
			ReadingProgress(0.56f, 20, ProgressIndicatorMode.PERCENT_READ),
			ReadingProgress(1f, 20, ProgressIndicatorMode.CHAPTERS_READ),
			ReadingProgress(0.5f, 20, ProgressIndicatorMode.NONE), null, value.progress)) {
			adapter.items = listOf(value.copy(progress = progress))
			adapter.bindViewHolder(holder, 0)
			layout(root)
			val view = root.findViewById<ReadingProgressView>(R.id.progressView)
			assertEquals(progress, view.progress)
			if (root.findViewById<View>(R.id.favourite_cover_overlays).isVisible) assertModernProgress(root, view)
		}
		return root
	}

	private fun bounds(root: ViewGroup, child: View): Rect = Rect().also {
		child.getDrawingRect(it)
		root.offsetDescendantRectToMyCoords(child, it)
	}

	private fun model() = MangaGridModel(SampleData.manga.copy(coverUrl = null, source = UnknownMangaSource), null, 0, null, false, false)

	private fun layout(view: View, widthDp: Int = 194, heightDp: Int = 344) {
		val density = view.resources.displayMetrics.density
		view.measure(View.MeasureSpec.makeMeasureSpec((widthDp * density).roundToInt(), View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec((heightDp * density).roundToInt(), View.MeasureSpec.EXACTLY))
		view.layout(0, 0, view.measuredWidth, view.measuredHeight)
	}
}
