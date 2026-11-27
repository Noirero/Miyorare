package org.koitharu.kotatsu.settings.nav

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.koitharu.kotatsu.core.prefs.NavItem
import org.koitharu.kotatsu.core.prefs.NavigationMotion
import org.koitharu.kotatsu.core.prefs.ScreenTransition
import org.koitharu.kotatsu.core.ui.widgets.DirectionalSlide
import org.koitharu.kotatsu.core.ui.widgets.DirectionalSlidePresentation
import org.koitharu.kotatsu.core.ui.widgets.InteractiveTabSlidePresentation
import org.koitharu.kotatsu.core.ui.widgets.TabSlideFrame
import org.koitharu.kotatsu.core.ui.widgets.FloatingBottomNavigationView
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import com.google.android.material.R as materialR

/** Local dummy pages and the actual bottom-nav renderer/gesture owner. No app routes or data. */
internal class NavigationTransitionPreview(context: Context) : LinearLayout(context) {
	private val items = listOf(NavItem.FAVORITES, NavItem.DISCOVERY_HUB, NavItem.EXPLORE)
	private val pages = FrameLayout(context)
	private val bar = FloatingBottomNavigationView(context)
	private val presentation = DirectionalSlidePresentation()
	private val interactive = InteractiveTabSlidePresentation()
	private val pageViews = linkedMapOf<Int, View>()
	private var committed = items.first().id
	private var visible = committed
	private var motion = NavigationMotion.CLASSIC_GLIDE
	private var transition = ScreenTransition.DIRECTIONAL_SLIDE
	private var detached = false

	init {
		orientation = VERTICAL
		clipChildren = true
		pages.clipChildren = true
		addView(pages, LayoutParams(LayoutParams.MATCH_PARENT, dp(152)))
		addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
		for (item in items) {
			val page = dummyPage(item)
			page.visibility = if (item.id == committed) View.VISIBLE else View.INVISIBLE
			pages.addView(page, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
			pageViews[item.id] = page
			bar.menu.add(Menu.NONE, item.id, Menu.NONE, item.title).setIcon(item.icon)
		}
		bar.setComposeItems(items)
		bar.selectedItemId = committed
		// Override the production Favorites long-click fallback; no authenticated or real navigation.
		bar.setOnItemLongClickListener { }
		bar.setOnItemReselectedListener { }
		bar.setOnItemSelectedListener { item ->
			if (item.itemId !in pageViews || detached) false else {
				committed = item.itemId
				render(committed)
				true
			}
		}
		bar.setTabDragListeners(
			preview = { id ->
				if (detached) true else if (id == null || id in pageViews) {
					if (id == null && interactive.isActive) restoreCommitted()
					true
				} else false
			},
			commit = ::commitDrag,
			slideFrame = ::seekDrag,
		)
	}

	fun configure(nextMotion: NavigationMotion, nextTransition: ScreenTransition, legacy: Boolean, labels: Boolean) {
		if (motion != nextMotion || transition != nextTransition) {
			bar.cancelTabDrag()
			restoreCommitted()
			motion = nextMotion
			transition = nextTransition
			bar.setNavigationMotion(motion)
		}
		bar.setUseLegacyNavigation(legacy)
		bar.setComposeLabeled(labels)
	}

	private fun seekDrag(frame: TabSlideFrame) {
		if (detached) return
		val current = pageViews[committed] ?: return
		if (!interactive.isActive) presentation.finish()
		if (!interactive.seek(frame, committed, current) { pageViews[it] }) restoreCommitted()
	}

	private fun commitDrag(id: Int) {
		if (detached || id !in pageViews) return
		val handoff = interactive.captureHandoff(id)
		val matched = interactive.contains(id)
		interactive.finish()
		if (matched) {
			// Local commit only. Suppress a second automatic slide after an interactive drag.
			visible = id
			pageViews.forEach { (key, view) -> view.visibility = if (key == id) View.VISIBLE else View.INVISIBLE }
		}
		bar.selectedItemId = id
		handoff?.let(presentation::resume)
	}

	private fun restoreCommitted() {
		presentation.finish()
		interactive.finish()
		visible = committed
		pageViews.forEach { (id, view) -> view.visibility = if (id == committed) View.VISIBLE else View.INVISIBLE }
	}

	private fun render(id: Int) {
		if (detached || id == visible) return
		val incoming = pageViews[id] ?: return
		val outgoing = pageViews[visible] ?: return
		val from = items.indexOfFirst { it.id == visible }
		val to = items.indexOfFirst { it.id == id }
		val direction = DirectionalSlide.direction(from, to, layoutDirection == View.LAYOUT_DIRECTION_RTL)
		visible = id
		when (transition) {
			ScreenTransition.DIRECTIONAL_SLIDE -> presentation.start(outgoing, incoming, direction, View.INVISIBLE)
		}
	}

	override fun onAttachedToWindow() {
		super.onAttachedToWindow()
		detached = false
	}

	override fun onDetachedFromWindow() {
		detached = true
		restoreCommitted()
		super.onDetachedFromWindow()
	}

	private fun dummyPage(item: NavItem): View = LinearLayout(context).apply {
		orientation = VERTICAL
		gravity = Gravity.CENTER
		setPadding(dp(12), dp(8), dp(12), dp(8))
		background = rounded(context.getThemeColor(materialR.attr.colorSurfaceContainer))
		addView(TextView(context).apply {
			text = context.getString(item.navTitle)
			setTextColor(context.getThemeColor(materialR.attr.colorOnSurface))
			textSize = 18f
			gravity = Gravity.CENTER
		}, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
		val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
		for (i in 0..2) {
			val tile = View(context).apply {
				background = rounded(context.getThemeColor(if (i == items.indexOf(item)) materialR.attr.colorPrimaryContainer else materialR.attr.colorSurfaceVariant))
			}
			row.addView(tile, LayoutParams(0, dp(64), 1f).apply { setMargins(dp(4), dp(8), dp(4), 0) })
		}
		addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
	}

	private fun rounded(color: Int) = GradientDrawable().apply {
		setColor(color)
		cornerRadius = dp(12).toFloat()
	}

	private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
