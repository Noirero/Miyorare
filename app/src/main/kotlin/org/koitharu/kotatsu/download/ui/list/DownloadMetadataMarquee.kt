package org.koitharu.kotatsu.download.ui.list

import android.animation.ValueAnimator
import android.animation.Keyframe
import android.animation.PropertyValuesHolder
import android.os.Build
import android.text.TextUtils
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.TextView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlin.math.roundToInt

/**
 * Ping-pong scrolling only for the overflowing source label. Its favicon lives in a
 * separate sibling View, so the icon never moves with the text. The animator is
 * suspended while the row is detached or its Activity is not started.
 */
internal class DownloadMetadataMarquee(
    private val label: TextView,
    private val lifecycleOwner: LifecycleOwner,
) : DefaultLifecycleObserver, View.OnAttachStateChangeListener {
    private var sourceLabel = false
    private var animator: ValueAnimator? = null
    private var animatedOverflow = -1

    init {
        label.addOnAttachStateChangeListener(this)
        lifecycleOwner.lifecycle.addObserver(this)
    }

    fun bind(isSourceLabel: Boolean) {
        if (sourceLabel != isSourceLabel) {
            sourceLabel = isSourceLabel
            stop()
            label.ellipsize = if (isSourceLabel) null else TextUtils.TruncateAt.END
            label.setHorizontallyScrolling(isSourceLabel)
            label.scrollTo(0, 0)
        }
        label.post { refresh() }
    }

    fun reset() = bind(false)

    override fun onStart(owner: LifecycleOwner) = refresh()
    override fun onStop(owner: LifecycleOwner) = stop()
    override fun onDestroy(owner: LifecycleOwner) {
        stop()
        label.removeOnAttachStateChangeListener(this)
        lifecycleOwner.lifecycle.removeObserver(this)
    }

    override fun onViewAttachedToWindow(v: View) {
        label.post { refresh() }
    }

    override fun onViewDetachedFromWindow(v: View) = stop()

    private fun refresh() {
        if (!sourceLabel || !label.isAttachedToWindow || !label.isShown ||
            !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled())
        ) {
            stop()
            return
        }
        val width = label.width - label.compoundPaddingLeft - label.compoundPaddingRight
        if (width <= 0) return
        val overflow = (label.paint.measureText(label.text.toString()) - width)
            .roundToInt().coerceAtLeast(0)
        if (overflow < 2) {
            stop()
            return
        }
        if (animator?.isRunning == true && overflow == animatedOverflow) return
        stop()

        val travelMs = ((overflow / label.resources.displayMetrics.density) / 32f * 1000f)
            .roundToInt().toLong().coerceAtLeast(900L)
        val holdMs = 800L
        val total = travelMs * 2 + holdMs * 2
        val startMoving = holdMs.toFloat() / total
        val atEnd = (holdMs + travelMs).toFloat() / total
        val startReturning = (holdMs * 2 + travelMs).toFloat() / total
        val values = PropertyValuesHolder.ofKeyframe(
            "offset",
            Keyframe.ofInt(0f, 0),
            Keyframe.ofInt(startMoving, 0),
            Keyframe.ofInt(atEnd, overflow),
            Keyframe.ofInt(startReturning, overflow),
            Keyframe.ofInt(1f, 0),
        )
        animatedOverflow = overflow
        animator = ValueAnimator.ofPropertyValuesHolder(values).apply {
            duration = total
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { label.scrollTo(it.getAnimatedValue("offset") as Int, 0) }
            start()
        }
    }

    private fun stop() {
        animator?.cancel()
        animator = null
        animatedOverflow = -1
        label.scrollTo(0, 0)
    }
}
