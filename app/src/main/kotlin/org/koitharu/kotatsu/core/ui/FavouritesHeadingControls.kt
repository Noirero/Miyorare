package org.koitharu.kotatsu.core.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageButton
import androidx.core.view.isVisible
import com.google.android.material.appbar.AppBarLayout
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.favourites.domain.FavouriteHeadingPreferences
import kotlin.math.roundToInt

/**
 * Owns the transient chevron visibility and the heading-only height animation.
 * The preference is the durable source of truth; no category/list or AppBar scroll state is changed.
 */
internal class FavouritesHeadingControls(private val header: MiyorareFavouritesHeaderLayout) {

	private var collapsed = false
	private var heading: View? = null
	private var activeButton: ImageButton? = null
	private var revealed = false
	private var running = false
	private var animator: ValueAnimator? = null
	private var animatedHeading: View? = null
	private val hide = Runnable {
		if (running && !keepVisibleForAccessibility()) setRevealed(false, animate = true)
	}

	fun sync(modern: Boolean, privateFavourites: Boolean, landscape: Boolean) {
		val inline = header.findViewById<View>(R.id.favourites_heading_copy) ?: return
		val hideInline = header.findViewById<ImageButton>(R.id.button_favourites_heading_hide)
		val show = header.findViewById<ImageButton>(R.id.button_favourites_heading_show)
		// The landscape copy belongs to the shared toolbar. Never write it from a detached
		// fragment or a header temporarily reparented into a navigation preview.
		val ownsAppBar = header.isAttachedToWindow && header.parent is AppBarLayout
		val landscapeCopy = if (landscape && ownsAppBar) {
			header.rootView.findViewById<View>(R.id.landscape_favourites_copy)
		} else null
		val hideLandscape = landscapeCopy?.findViewById<ImageButton>(R.id.button_landscape_favourites_heading_hide)
		val eligible = modern && !privateFavourites
		val next = eligible && FavouriteHeadingPreferences.isCollapsed(header.context)
		val nextHeading = if (landscape) landscapeCopy else inline
		if (next != collapsed || nextHeading !== heading || !eligible) {
			finishAnimation()
			collapsed = next
			heading = nextHeading
		}
		if (heading !== inline) inline.isVisible = false
		if (!eligible) {
			inline.isVisible = modern && !landscape
			hideInline?.isVisible = false
			show?.isVisible = false
			hideLandscape?.isVisible = false
			bind(null)
			return
		}
		if (animator == null) heading?.isVisible = !collapsed
		hideInline?.isVisible = !landscape && !collapsed
		hideLandscape?.isVisible = landscape && !collapsed
		show?.isVisible = collapsed
		val button = if (collapsed) show else if (landscape) hideLandscape else hideInline
		bind(if (ownsAppBar) button else null)
		if (ownsAppBar && header.isShown && header.windowVisibility == View.VISIBLE && header.hasWindowFocus()) {
			if (!running && activeButton != null) {
				running = true
				reveal()
			}
		} else {
			pause()
		}
	}

	private fun bind(button: ImageButton?) {
		if (activeButton === button) return
		running = false
		header.removeCallbacks(hide)
		setRevealed(false, animate = false)
		activeButton?.setOnClickListener(null)
		activeButton?.onFocusChangeListener = null
		activeButton = button
		button?.apply {
			setOnClickListener { onClick() }
			onFocusChangeListener = View.OnFocusChangeListener { _, focused ->
				if (!running) return@OnFocusChangeListener
				if (focused) reveal() else scheduleHide()
			}
		}
	}

	private fun onClick() {
		if (!running || animator != null || header.parent !is AppBarLayout || !header.isShown) return
		if (!revealed) {
			reveal()
			return
		}
		val row = heading ?: return
		val next = !collapsed
		FavouriteHeadingPreferences.setCollapsed(header.context, next)
		collapsed = next
		if (!next) {
			val hideId = if (row.id == R.id.landscape_favourites_copy) {
				R.id.button_landscape_favourites_heading_hide
			} else R.id.button_favourites_heading_hide
			row.findViewById<View>(hideId)?.isVisible = true
		}
		// Start the heading animation before switching controls. Refreshes during the
		// animation must not replace its height with the persisted endpoint.
		animateHeading(row, show = !next)
		sync(modern = true, privateFavourites = false, landscape = row.id == R.id.landscape_favourites_copy)
	}

	private fun reveal() {
		setRevealed(true, animate = true)
		scheduleHide()
	}

	private fun scheduleHide() {
		header.removeCallbacks(hide)
		if (running && !keepVisibleForAccessibility()) header.postDelayed(hide, AUTO_HIDE_MS)
	}

	private fun keepVisibleForAccessibility(): Boolean =
		activeButton?.hasFocus() == true ||
		(header.context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager)
			?.isTouchExplorationEnabled == true

	private fun setRevealed(value: Boolean, animate: Boolean) {
		revealed = value
		activeButton?.apply {
			// Alpha does not change hit bounds or layout; the invisible target reveals first.
			this.animate().cancel()
			contentDescription = context.getString(
				if (!value) R.string.favourites_heading_reveal_control
				else if (collapsed) R.string.favourites_heading_show else R.string.favourites_heading_hide,
			)
			val target = if (value) 1f else 0f
			if (animate) this.animate().alpha(target).setDuration(FADE_MS).start()
			else alpha = target
		}
	}

	fun pause() {
		running = false
		header.removeCallbacks(hide)
		setRevealed(false, animate = false)
		finishAnimation()
	}

	fun detach() {
		bind(null)
		pause()
		heading = null
	}

	private fun animateHeading(row: View, show: Boolean) {
		finishAnimation()
		val startHeight = if (show) 0 else row.height
		row.isVisible = true
		val endHeight = if (show) {
			val landscape = row.id == R.id.landscape_favourites_copy
			// A collapsed wrap-content toolbar slot has zero width. Measure against the
			// attached header instead of treating that zero as a missing viewport.
			val availableWidth = if (landscape) header.width else (row.parent as? View)?.width ?: header.width
			if (availableWidth <= 0) {
				row.isVisible = true
				return
			}
			val mode = if (landscape) View.MeasureSpec.AT_MOST else View.MeasureSpec.EXACTLY
			row.measure(
				View.MeasureSpec.makeMeasureSpec(availableWidth, mode),
				View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
			)
			row.measuredHeight
		} else 0
		if (startHeight == endHeight) {
			row.isVisible = show
			return
		}
		animatedHeading = row
		row.layoutParams = row.layoutParams.apply { height = startHeight }
		row.alpha = if (show) 0f else 1f
		animator = ValueAnimator.ofFloat(0f, 1f).apply {
			duration = SLIDE_MS
			interpolator = AccelerateDecelerateInterpolator()
			addUpdateListener {
				val fraction = it.animatedValue as Float
				row.layoutParams = row.layoutParams.apply {
					height = (startHeight + (endHeight - startHeight) * fraction).roundToInt()
				}
				row.alpha = if (show) fraction else 1f - fraction
			}
			addListener(object : AnimatorListenerAdapter() {
				override fun onAnimationEnd(animation: Animator) {
					animator = null
					animatedHeading = null
					resetRow(row)
				}
			})
		}
		animator?.start()
	}

	private fun finishAnimation() {
		val current = animator ?: return
		animator = null
		current.removeAllListeners()
		current.removeAllUpdateListeners()
		current.cancel()
		animatedHeading?.let(::resetRow)
		animatedHeading = null
	}

	private fun resetRow(row: View) {
		row.isVisible = !collapsed
		row.alpha = 1f
		row.layoutParams = row.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
	}

	private companion object {
		const val AUTO_HIDE_MS = 3_000L
		const val FADE_MS = 180L
		const val SLIDE_MS = 250L
	}
}
