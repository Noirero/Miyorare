package org.koitharu.kotatsu.core.ui.widgets

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import android.view.View
import androidx.annotation.RequiresApi

/**
 * A transient optical effect on the bottom bar's own rendered pixels, including icons and badges.
 * No screenshots, bitmap copies, page content or additional gesture/layout ownership.
 */
internal class RefractGlassRenderer(private val host: View) {
	private var backend: Backend? = null
	private var failed = false
	var isActive = false
		private set

	val isSupported: Boolean
		get() = Build.VERSION.SDK_INT >= 33 && host.isHardwareAccelerated && !failed

	fun update(
		shape: EnhancedNavigationMotionGeometry.Shape,
		density: Float,
		stripLeft: Float,
		stripRight: Float,
		amount: Float,
		light: Boolean,
	) {
		if (!isSupported || stripRight <= stripLeft) {
			clear()
			return
		}
		if (Build.VERSION.SDK_INT >= 33) {
			try {
				val renderer = backend ?: ShaderBackend(host).also { backend = it }
				renderer.update(shape, density, stripLeft, stripRight, amount, light)
				isActive = true
			} catch (e: RuntimeException) {
				// Some device GPU drivers may reject the shader; retain the existing lens renderer.
				clear()
				failed = true
				Log.w("RefractGlass", "Refraction unavailable; using translucent lens", e)
			}
		}
	}

	fun clear() {
		if (!isActive) return
		backend?.clear()
		isActive = false
	}

	private interface Backend {
		fun update(shape: EnhancedNavigationMotionGeometry.Shape, density: Float, stripLeft: Float,
			stripRight: Float, amount: Float, light: Boolean)
		fun clear()
	}

	@RequiresApi(33)
	private class ShaderBackend(private val host: View) : Backend {
		private val shader = RuntimeShader(REFRACTION)

		override fun update(shape: EnhancedNavigationMotionGeometry.Shape, density: Float,
			stripLeft: Float, stripRight: Float, amount: Float, light: Boolean) {
			shader.setFloatUniform("center", (shape.left + shape.right) * density * 0.5f,
				(shape.top + shape.bottom) * density * 0.5f)
			shader.setFloatUniform("extent", (shape.right - shape.left) * density * 0.5f,
				(shape.bottom - shape.top) * density * 0.5f)
			shader.setFloatUniform("radius", shape.radius * density)
			shader.setFloatUniform("strip", stripLeft, stripRight)
			shader.setFloatUniform("density", density)
			shader.setFloatUniform("amount", amount)
			shader.setFloatUniform("rimTone", if (light) 0.12f else 0.95f)
			// RenderEffect captures the updated uniforms; the RuntimeShader program is reused.
			host.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"))
		}

		override fun clear() = host.setRenderEffect(null)
	}

	private companion object {
		const val REFRACTION = """
			uniform shader content;
			uniform float2 center;
			uniform float2 extent;
			uniform float radius;
			uniform float2 strip;
			uniform float density;
			uniform float amount;
			uniform float rimTone;

			half4 main(float2 p) {
				float2 delta = p - center;
				float2 q = abs(delta) - extent + float2(radius);
				float d = length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - radius;
				half4 original = content.eval(p);
				if (d > 1.5 * density || amount <= 0.001) return original;

				// Magnify the center gently, with a much stronger curved bevel near the edge.
				float bevel = smoothstep(-13.0 * density, -1.0 * density, d);
				float2 direction = delta / max(length(delta), 1.0);
				float2 samplePoint = p - (delta * 0.08 + direction * bevel * 12.0 * density) * amount;
				// Never sample the separate Continue Reading control.
				samplePoint.x = clamp(samplePoint.x, strip.x, strip.y);
				float inside = 1.0 - smoothstep(-1.0 * density, 0.5 * density, d);
				half4 bent = mix(original, content.eval(samplePoint), half(inside * amount));

				// A thin directional rim. Compose in premultiplied alpha, preserving transparent edges.
				float rim = 1.0 - smoothstep(0.0, 1.5 * density, abs(d));
				half a = half(rim * (0.24 + 0.16 * max(0.0, -direction.y)) * amount);
				return half4(half3(half(rimTone)) * a, a) + bent * (1.0 - a);
			}
		"""
	}
}
