package org.koitharu.kotatsu.list.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Rect
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
			assertTrue(cover.findViewById<View>(R.id.cover_legacy_counter).isVisible)
			assertEquals("78", cover.findViewById<TextView>(R.id.cover_legacy_counter).text.toString())
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
		assertFalse(cover.findViewById<View>(R.id.cover_legacy_counter).isVisible)
		assertNull(cover.contentDescription)
		assertFalse(cover.titleView.contentDescription.toString().contains("78"))
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
					assertEquals(oldCover.strokeWidth, newCover.strokeWidth)
					assertEquals(oldCover.width, newCover.width)
					assertEquals(oldCover.height, newCover.height)
					val oldProgress = legacy.findViewById<ReadingProgressView>(R.id.progressView)
					val progress = browsing.findViewById<ReadingProgressView>(R.id.progressView)
					assertSame(browsing.findViewById<View>(R.id.layout_indicators), progress.parent)
					assertEquals(bounds(legacy, oldProgress), bounds(browsing, progress))
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
						if (space == FavouriteSpace.NORMAL) {
							assertEquals((14 * themed.resources.displayMetrics.density).toInt(), p.height)
						} else {
							assertSame(collection.findViewById<View>(R.id.layout_indicators), p.parent)
							assertEquals(bounds(legacy, oldProgress), bounds(collection, p))
						}
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
		val holder = adapter.onCreateViewHolder(FrameLayout(context), adapter.getItemViewType(0))
		adapter.onBindViewHolder(holder, 0)
		return (holder.itemView as ViewGroup).also { layout(it) }
	}

	private fun bounds(root: ViewGroup, child: View): Rect = Rect().also {
		child.getDrawingRect(it)
		root.offsetDescendantRectToMyCoords(child, it)
	}

	private fun model() = MangaGridModel(SampleData.manga.copy(coverUrl = null, source = UnknownMangaSource), null, 0, null, false, false)

	private fun layout(view: View) {
		val density = view.resources.displayMetrics.density
		view.measure(View.MeasureSpec.makeMeasureSpec((194 * density).roundToInt(), View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec((344 * density).roundToInt(), View.MeasureSpec.EXACTLY))
		view.layout(0, 0, view.measuredWidth, view.measuredHeight)
	}
}
