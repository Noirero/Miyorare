package org.koitharu.kotatsu.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** All Settings layout variants need the Journey model button when Kartu hides its in-panel picker. */
class SettingsJourneyModelButtonLayoutRegressionTest {

    @Test
    fun `model picker button exists in every Settings layout variant`() {
        for (variant in listOf("layout", "layout-w840dp", "layout-w600dp-land")) {
            val button = elementById(layout(variant), "@+id/button_journey_model")
            assertEquals(variant, "ImageButton", button.tagName)
            assertEquals(variant, "gone", button.getAttributeNS(ANDROID_NS, "visibility"))
            assertEquals(variant, "@drawable/ic_palette", button.getAttributeNS(ANDROID_NS, "src"))
            assertEquals(variant, "@string/journey_panel_model_label", button.getAttributeNS(ANDROID_NS, "contentDescription"))
        }
    }

    @Test
    fun `responsive header leaves room for the model picker button`() {
        for (variant in listOf("layout-w840dp", "layout-w600dp-land")) {
            val document = layout(variant)
            val header = elementById(document, "@+id/textView_header")
            val button = elementById(document, "@+id/button_journey_model")
            assertEquals(variant, "@id/button_journey_model", header.getAttributeNS(APP_NS, "layout_constraintEnd_toStartOf"))
            assertEquals(variant, "parent", button.getAttributeNS(APP_NS, "layout_constraintEnd_toEndOf"))
            assertEquals(variant, "@id/textView_header", button.getAttributeNS(APP_NS, "layout_constraintTop_toTopOf"))
            assertEquals(variant, "@id/textView_header", button.getAttributeNS(APP_NS, "layout_constraintBottom_toBottomOf"))
        }
    }

    private fun layout(variant: String): Document {
        val relativePath = "res/$variant/activity_settings.xml"
        val file = sequenceOf(File("src/main", relativePath), File("app/src/main", relativePath))
            .firstOrNull(File::isFile) ?: error("Missing Settings layout: $relativePath")
        return DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(file)
    }

    private fun elementById(document: Document, id: String): Element {
        val nodes = document.getElementsByTagName("*")
        return (0 until nodes.length)
            .mapNotNull { nodes.item(it) as? Element }
            .firstOrNull { it.getAttributeNS(ANDROID_NS, "id") == id }
            ?: error("Missing view: $id")
    }

    companion object {
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        private const val APP_NS = "http://schemas.android.com/apk/res-auto"
    }
}
