package org.koitharu.kotatsu.core.ui.widgets

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SlidingBottomNavigationViewTest {

	@Test
	fun hideWhileDetachedDoesNotForceMeasurement() {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val view = ThrowOnMeasureBottomNavigationView(context)

		assertFalse(view.isAttachedToWindow)

		// SearchView state restoration can request a hide before MainActivity's hierarchy is attached.
		// The regression is specifically that hide() must not synchronously measure that detached tree.
		view.hide()
	}

	private class ThrowOnMeasureBottomNavigationView(context: Context) : SlidingBottomNavigationView(context) {
		override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
			throw AssertionError("Detached hide must not force child measurement")
		}
	}
}
