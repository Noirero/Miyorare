package org.koitharu.kotatsu.favourites.ui.container

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan
import android.text.style.ReplacementSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.DrawableCompat
import androidx.preference.PreferenceManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator.TabConfigurationStrategy
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.prefs.MiyorareAppearance
import org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle
import org.koitharu.kotatsu.core.ui.MiyorareVisualTokens
import org.koitharu.kotatsu.core.ui.util.PopupMenuMediator
import org.koitharu.kotatsu.core.util.ext.getEnumValue
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.favourites.domain.DOWNLOADED_FAVOURITES_CATEGORY_ID
import org.koitharu.kotatsu.favourites.domain.LOCAL_FAVOURITES_CATEGORY_ID
import org.koitharu.kotatsu.favourites.ui.list.FavouritesListFragment.Companion.NO_ID
import java.text.NumberFormat
import java.util.Locale
import java.util.WeakHashMap
import kotlin.math.roundToInt
import androidx.appcompat.R as appcompatR
import com.google.android.material.R as materialR

class FavouritesTabConfigurationStrategy(
	private val adapter: FavouritesContainerAdapter,
	private val viewModel: FavouritesContainerViewModel,
	private val router: AppRouter,
	private val modern: Boolean,
) : TabConfigurationStrategy {

	private val baseBackgrounds = WeakHashMap<View, Drawable?>()

	override fun onConfigureTab(tab: TabLayout.Tab, position: Int) {
		val item = adapter.getItem(position)
		val view = tab.view
		favouriteTabModernFlags[view] = modern
		if (!baseBackgrounds.containsKey(view)) baseBackgrounds[view] = view.background
		if (modern) {
			applyModernHeaderDensity(view)
			val density = view.resources.displayMetrics.density
			val horizontal = (7f * density).roundToInt()
			view.minimumHeight = (34f * density).roundToInt()
			view.setPaddingRelative(horizontal, 0, horizontal, 0)
		}
		val title = item.title ?: view.context.getString(R.string.all_favourites)
		val style = systemStyle(item.id)
		if (style == null) {
			view.setBackgroundKeepingPadding(createCategoryBackground(view.context))
			tab.text = title
		} else {
			val separator = isLastSystemTab(position)
			view.setBackgroundKeepingPadding(createSystemBackground(view.context, style, separator))
			tab.text = createSystemTitle(view.context, title, style)
		}
		tab.tag = item
		favouriteTabBaseTitles[view] = tab.text ?: ""
		updateFavouriteTabBadge(tab, item.count, item.count > 0)
		if (item.id != LOCAL_FAVOURITES_CATEGORY_ID && item.id != DOWNLOADED_FAVOURITES_CATEGORY_ID) {
			PopupMenuMediator(
				FavouriteTabPopupMenuProvider(view.context, router, viewModel, item.id),
			).attach(view)
		}
	}

	/**
	 * Keep the compact reference-match treatment scoped to Modern without changing the XML defaults
	 * used by Classic. This is idempotent and runs while the category tabs are configured.
	 */
	private fun applyModernHeaderDensity(anchor: View) {
		val root = anchor.rootView
		val density = anchor.resources.displayMetrics.density
		fun dp(value: Float) = (value * density).roundToInt()

		root.findViewById<View>(R.id.layout_category_header)?.setPadding(0, dp(2f), 0, dp(2f))
		root.findViewById<TextView>(R.id.text_favourites_title)?.apply {
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 20.5f)
			includeFontPadding = false
			(layoutParams as? LinearLayout.LayoutParams)?.let { params ->
				params.marginStart = dp(16f)
				params.marginEnd = dp(16f)
				params.topMargin = 0
				params.bottomMargin = 0
				layoutParams = params
			}
		}
		root.findViewById<TextView>(R.id.text_favourites_subtitle)?.apply {
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
			includeFontPadding = false
			(layoutParams as? LinearLayout.LayoutParams)?.let { params ->
				params.marginStart = dp(16f)
				params.marginEnd = dp(16f)
				params.topMargin = 0
				params.bottomMargin = 0
				layoutParams = params
			}
		}
		root.findViewById<MaterialButtonToggleGroup>(R.id.toggle_content_type)?.apply {
			setPadding(dp(1f), dp(1f), dp(1f), dp(1f))
			(layoutParams as? LinearLayout.LayoutParams)?.let { params ->
				params.marginStart = dp(16f)
				params.marginEnd = dp(16f)
				params.topMargin = dp(3f)
				params.bottomMargin = dp(2f)
				layoutParams = params
			}
		}
		for (buttonId in intArrayOf(R.id.button_content_manga, R.id.button_content_novel)) {
			root.findViewById<MaterialButton>(buttonId)?.apply {
				minimumHeight = dp(32f)
				setPaddingRelative(paddingStart, 0, paddingEnd, 0)
				setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
			}
		}
		root.findViewById<TabLayout>(R.id.tabs)?.apply {
			(layoutParams as? LinearLayout.LayoutParams)?.let { params ->
				params.topMargin = dp(2f)
				params.bottomMargin = 0
				layoutParams = params
			}
		}
	}

	private fun isLastSystemTab(position: Int): Boolean {
		val current = adapter.getItem(position).id
		val next = position + 1
		return current.isSystemCategory() && next < adapter.itemCount && !adapter.getItem(next).id.isSystemCategory()
	}

	private fun createCategoryBackground(context: Context): Drawable = createSystemBackground(
		context = context,
		style = SystemStyle(
			iconRes = R.drawable.ic_tag,
			containerAttr = materialR.attr.colorPrimaryContainer,
			accentAttr = appcompatR.attr.colorPrimary,
		),
		separator = false,
	)

	private fun createSystemBackground(context: Context, style: SystemStyle, separator: Boolean): Drawable {
		val density = context.resources.displayMetrics.density
		val surface = context.getThemeColor(materialR.attr.colorSurface, Color.TRANSPARENT)
		val container = context.getThemeColor(style.containerAttr, surface)
		val accent = context.getThemeColor(style.accentAttr, container)
		val states = arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf())
		val radiusDp = if (modern) MiyorareVisualTokens.RADIUS_CONTROL_DP * 0.86f else 20f
		val selectedFill = if (modern) 0.52f else 0.96f
		val idleFill = if (modern) 0.025f else 0.13f
		val selectedStroke = if (modern) 0.46f else 0.95f
		val idleStroke = if (modern) 0.05f else 0.18f
		val shape = MaterialShapeDrawable(
			ShapeAppearanceModel.builder().setAllCornerSizes(radiusDp * density).build(),
		).apply {
			fillColor = ColorStateList(
				states,
				intArrayOf(
					ColorUtils.blendARGB(surface, container, selectedFill),
					ColorUtils.blendARGB(surface, container, idleFill),
				),
			)
			setStroke(
				(if (modern) 0.55f else 1f) * density,
				ColorStateList(
					states,
					intArrayOf(
						ColorUtils.blendARGB(surface, accent, selectedStroke),
						ColorUtils.blendARGB(surface, accent, idleStroke),
					),
				),
			)
		}
		val horizontal = ((if (modern) 1f else 4f) * density).roundToInt()
		val vertical = ((if (modern) 3f else 4f) * density).roundToInt()
		val separatorSpace = ((if (modern) 6f else 10f) * density).roundToInt()
		val pill = InsetDrawable(
			shape,
			horizontal,
			vertical,
			horizontal + if (separator) separatorSpace else 0,
			vertical,
		)
		val content = if (separator && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			val divider = GradientDrawable().apply {
				setColor(ColorUtils.blendARGB(surface, accent, if (modern) 0.12f else 0.34f))
			}
			LayerDrawable(arrayOf(pill, divider)).apply {
				setLayerSize(1, (1f * density).roundToInt().coerceAtLeast(1), ((if (modern) 18f else 20f) * density).roundToInt())
				setLayerGravity(1, Gravity.END or Gravity.CENTER_VERTICAL)
				setLayerInsetEnd(1, (2f * density).roundToInt())
			}
		} else {
			pill
		}
		return RippleDrawable(
			ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, if (modern) 16 else 48)),
			content,
			null,
		)
	}

	private fun createSystemTitle(context: Context, title: CharSequence, style: SystemStyle): CharSequence {
		val icon = ContextCompat.getDrawable(context, style.iconRes)?.let { drawable ->
			DrawableCompat.wrap(drawable.mutate()).also {
				DrawableCompat.setTint(it, context.getThemeColor(style.accentAttr, Color.GRAY))
				val size = ((if (modern) 13f else 16f) * context.resources.displayMetrics.density).roundToInt()
				it.setBounds(0, 0, size, size)
			}
		} ?: return title
		return SpannableStringBuilder().apply {
			append('\uFFFC')
			setSpan(
				ImageSpan(
					icon,
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ImageSpan.ALIGN_CENTER else ImageSpan.ALIGN_BOTTOM,
				),
				0,
				1,
				Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
			)
			append(' ').append(title)
		}
	}

	private fun systemStyle(categoryId: Long): SystemStyle? = when (categoryId) {
		NO_ID -> SystemStyle(R.drawable.ic_heart_outline, materialR.attr.colorPrimaryContainer, appcompatR.attr.colorPrimary)
		DOWNLOADED_FAVOURITES_CATEGORY_ID ->
			SystemStyle(R.drawable.ic_storage, materialR.attr.colorSecondaryContainer, materialR.attr.colorSecondary)
		LOCAL_FAVOURITES_CATEGORY_ID ->
			SystemStyle(R.drawable.ic_folder_file, materialR.attr.colorTertiaryContainer, materialR.attr.colorTertiary)
		else -> null
	}

	private fun Long.isSystemCategory() =
		this == NO_ID || this == DOWNLOADED_FAVOURITES_CATEGORY_ID || this == LOCAL_FAVOURITES_CATEGORY_ID

	private data class SystemStyle(val iconRes: Int, val containerAttr: Int, val accentAttr: Int)
}

private fun View.setBackgroundKeepingPadding(drawable: Drawable?) {
	val start = paddingStart
	val top = paddingTop
	val end = paddingEnd
	val bottom = paddingBottom
	background = drawable
	setPaddingRelative(start, top, end, bottom)
}

private data class FavouriteTabBasePadding(
	val start: Int,
	val top: Int,
	val end: Int,
	val bottom: Int,
)

private val favouriteTabBasePaddings = WeakHashMap<View, FavouriteTabBasePadding>()
private val favouriteTabBaseTitles = WeakHashMap<View, CharSequence>()
private val favouriteTabModernFlags = WeakHashMap<View, Boolean>()
private var favouriteCountFormatterLocale: Locale? = null
private var favouriteCountFormatter: NumberFormat? = null

/**
 * Modern renders the count as an inline mini-pill so it participates in tab measurement instead of
 * floating over the chip. Classic keeps the existing Material badge behavior unchanged.
 */
internal fun updateFavouriteTabBadge(tab: TabLayout.Tab, count: Int, isVisible: Boolean) {
	val safeCount = count.coerceAtLeast(0)
	val shouldShowBadge = isVisible && safeCount > 0
	val view = tab.view
	val density = view.resources.displayMetrics.density
	val modern = favouriteTabModernFlags[view] ?: (
		PreferenceManager.getDefaultSharedPreferences(view.context).getEnumValue(
			MiyorareAppearance.KEY_DESIGN_STYLE,
			MiyorareDesignStyle.CLASSIC,
		) == MiyorareDesignStyle.MODERN
	)
	val basePadding = favouriteTabBasePaddings.getOrPut(view) {
		FavouriteTabBasePadding(
			start = view.paddingStart,
			top = view.paddingTop,
			end = view.paddingEnd,
			bottom = view.paddingBottom,
		)
	}
	val baseTitle = favouriteTabBaseTitles.getOrPut(view) { tab.text ?: "" }

	if (modern) {
		tab.removeBadge()
		view.setPaddingRelative(basePadding.start, basePadding.top, basePadding.end, basePadding.bottom)
		tab.text = if (shouldShowBadge) {
			createInlineFavouriteCountTitle(baseTitle, safeCount, view)
		} else {
			baseTitle
		}
		return
	}

	if (tab.text !== baseTitle) tab.text = baseTitle
	val visibleDigits = safeCount.coerceAtMost(MAX_CATEGORY_BADGE_COUNT).toString().length
	val badgeSpace = if (shouldShowBadge) {
		((BADGE_BASE_END_SPACE_DP + visibleDigits * BADGE_PER_DIGIT_SPACE_DP) * density).roundToInt()
	} else {
		0
	}
	view.setPaddingRelative(
		basePadding.start,
		basePadding.top,
		basePadding.end + badgeSpace,
		basePadding.bottom,
	)

	tab.getOrCreateBadge().apply {
		maxCharacterCount = 6
		number = safeCount
		alpha = 255
		setHorizontalPadding((BADGE_HORIZONTAL_PADDING_DP * density).roundToInt())
		setHorizontalOffsetWithText(-((BADGE_BASE_OUTWARD_OFFSET_DP + visibleDigits * BADGE_PER_DIGIT_OFFSET_DP) * density).roundToInt())
		this.isVisible = shouldShowBadge
	}
}

private fun createInlineFavouriteCountTitle(baseTitle: CharSequence, count: Int, view: View): CharSequence {
	val density = view.resources.displayMetrics.density
	val textSizePx = TypedValue.applyDimension(
		TypedValue.COMPLEX_UNIT_SP,
		9f,
		view.resources.displayMetrics,
	)
	val surface = view.context.getThemeColor(materialR.attr.colorSurfaceContainerHighest, Color.DKGRAY)
	val primary = view.context.getThemeColor(appcompatR.attr.colorPrimary, Color.WHITE)
	val onSurface = view.context.getThemeColor(materialR.attr.colorOnSurface, Color.WHITE)
	val countText = formatFavouriteCount(count, view)
	return SpannableStringBuilder(baseTitle).apply {
		append(' ')
		val start = length
		append(countText)
		setSpan(
			FavouriteCountPillSpan(
				textSizePx = textSizePx,
				horizontalPaddingPx = 3.75f * density,
				heightPx = 15f * density,
				cornerRadiusPx = 7.5f * density,
				backgroundColor = ColorUtils.blendARGB(surface, primary, 0.13f),
				textColor = ColorUtils.blendARGB(onSurface, primary, 0.12f),
			),
			start,
			length,
			Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
		)
	}
}

private fun formatFavouriteCount(count: Int, view: View): String {
	val configuration = view.resources.configuration
	val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
		configuration.locales[0]
	} else {
		@Suppress("DEPRECATION")
		configuration.locale
	}
	val formatter = if (favouriteCountFormatterLocale == locale) {
		favouriteCountFormatter ?: NumberFormat.getIntegerInstance(locale).also {
			favouriteCountFormatter = it
		}
	} else {
		NumberFormat.getIntegerInstance(locale).also {
			favouriteCountFormatterLocale = locale
			favouriteCountFormatter = it
		}
	}
	return if (count > MAX_CATEGORY_BADGE_COUNT) {
		"${formatter.format(MAX_CATEGORY_BADGE_COUNT)}+"
	} else {
		formatter.format(count)
	}
}

private class FavouriteCountPillSpan(
	private val textSizePx: Float,
	private val horizontalPaddingPx: Float,
	private val heightPx: Float,
	private val cornerRadiusPx: Float,
	private val backgroundColor: Int,
	private val textColor: Int,
) : ReplacementSpan() {

	private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = backgroundColor }
	private val bounds = RectF()

	override fun getSize(
		paint: Paint,
		text: CharSequence,
		start: Int,
		end: Int,
		fm: Paint.FontMetricsInt?,
	): Int {
		val label = text.subSequence(start, end).toString()
		textPaint.set(paint)
		textPaint.isAntiAlias = true
		textPaint.textSize = textSizePx
		return (textPaint.measureText(label) + horizontalPaddingPx * 2f).roundToInt()
	}

	override fun draw(
		canvas: Canvas,
		text: CharSequence,
		start: Int,
		end: Int,
		x: Float,
		top: Int,
		y: Int,
		bottom: Int,
		paint: Paint,
	) {
		val label = text.subSequence(start, end).toString()
		textPaint.set(paint)
		textPaint.isAntiAlias = true
		textPaint.color = textColor
		textPaint.textSize = textSizePx
		val width = textPaint.measureText(label) + horizontalPaddingPx * 2f
		val centerY = (top + bottom) / 2f
		bounds.set(x, centerY - heightPx / 2f, x + width, centerY + heightPx / 2f)
		canvas.drawRoundRect(bounds, cornerRadiusPx, cornerRadiusPx, backgroundPaint)
		val metrics = textPaint.fontMetrics
		val baseline = centerY - (metrics.ascent + metrics.descent) / 2f
		canvas.drawText(label, x + horizontalPaddingPx, baseline, textPaint)
	}
}

private const val MAX_CATEGORY_BADGE_COUNT = 99_999
private const val BADGE_BASE_END_SPACE_DP = 16
private const val BADGE_PER_DIGIT_SPACE_DP = 4
private const val BADGE_HORIZONTAL_PADDING_DP = 3
private const val BADGE_BASE_OUTWARD_OFFSET_DP = 7
private const val BADGE_PER_DIGIT_OFFSET_DP = 2
