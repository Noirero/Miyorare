package org.koitharu.kotatsu.reader.ui.epub.translation

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatEditText
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.scrobbling.mangaupdates.ui.MangaUpdatesAuthActivity

/** Opt-in actual Android golden screenshots on a 720x1606 px, 320dpi dark viewport. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class NovelTranslationLanguagePickerVisualTest {
    @get:Rule val hilt = HiltAndroidRule(this)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Before fun setUp() = hilt.inject()

    @Test fun captureSourceAndTargetPickerGeometry() {
        ActivityScenario.launch(MangaUpdatesAuthActivity::class.java).use { scenario ->
            for ((name, source, selected) in listOf(
                Triple("source", true, "auto"),
                Triple("target", false, "id"),
            )) {
                lateinit var dialog: AlertDialog
                scenario.onActivity { activity ->
                    // Screenshot-protected login Activity is used only as a test host.
                    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    activity.findViewById<ViewGroup>(android.R.id.content).apply {
                        removeAllViews()
                        setBackgroundColor(Color.rgb(11, 11, 17))
                    }
                    dialog = NovelTranslationLanguagePicker(
                        context = activity,
                        source = source,
                        selectedCode = selected,
                        onSelected = {},
                        onCustomLanguage = {},
                    ).show()
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val root = checkNotNull(dialog.window).decorView
                    val list = checkNotNull(findView(root, RecyclerView::class.java))
                    val input = checkNotNull(findView(root, AppCompatEditText::class.java))
                    val searchSurface = input.parent as View
                    assertTrue("Rounded search surface", searchSurface.background is GradientDrawable)
                    assertTrue("List density", list.childCount >= 7)
                    val first = list.getChildAt(0)
                    assertEquals("Rows must fill list width", list.width, first.width)
                    assertTrue("Compact rows", first.height <= 72 * activity.resources.displayMetrics.density)
                    assertTrue("Selected highlight visible", (0 until list.childCount).any {
                        list.getChildAt(it).isSelected
                    })
                    assertTrue("Search and rows share a grid", kotlin.math.abs(searchSurface.width - list.width) <= 2)
                }
                SystemClock.sleep(350)
                instrumentation.waitForIdleSync()
                val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                try {
                    assertEquals(720, screenshot.width)
                    assertEquals(1606, screenshot.height)
                    writeCapture(name, screenshot)
                } finally {
                    screenshot.recycle()
                    scenario.onActivity { dialog.dismiss() }
                }
            }
        }
    }

    private fun writeCapture(name: String, bitmap: Bitmap) {
        val relativePath = "Download/miyorare-novel-language-picker/"
        val fileName = name + ".png"
        val resolver = context.contentResolver
        resolver.delete(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
            arrayOf(relativePath, fileName),
        )
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        resolver.openOutputStream(uri, "w").use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, checkNotNull(output)))
        }
    }

    private fun <T : View> findView(root: View, type: Class<T>): T? {
        if (type.isInstance(root)) return type.cast(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) {
            findView(root.getChildAt(i), type)?.let { return it }
        }
        return null
    }
}
