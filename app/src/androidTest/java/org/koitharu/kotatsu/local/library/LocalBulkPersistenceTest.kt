package org.koitharu.kotatsu.local.library

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** On-device contract test for the same JSON preference formats used by SmartLocalLibrary. */
@RunWith(AndroidJUnit4::class)
class LocalBulkPersistenceTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs by lazy {
        context.getSharedPreferences("smart_local_bulk_persistence_test", Context.MODE_PRIVATE)
    }

    @After fun clear() {
        prefs.edit().clear().commit()
    }

    @Test fun virtualGroupsSurvivePreferencesReopenAndRename() {
        val groups = JSONObject().put("Reading", JSONArray().put("book-key-a").put("book-key-b"))
        assertTrue(prefs.edit().putString("virtual_groups", groups.toString()).commit())
        val restored = JSONObject(prefs.getString("virtual_groups", "{}"))
        assertEquals(2, restored.getJSONArray("Reading").length())
        val renamed = JSONObject().put("Favorites", restored.getJSONArray("Reading"))
        assertTrue(prefs.edit().putString("virtual_groups", renamed.toString()).commit())
        val reopened = context.getSharedPreferences("smart_local_bulk_persistence_test", Context.MODE_PRIVATE)
        val result = JSONObject(reopened.getString("virtual_groups", "{}"))
        assertFalse(result.has("Reading"))
        assertEquals("book-key-a", result.getJSONArray("Favorites").getString(0))
    }

    @Test fun metadataOverridesSurviveReopenAndReset() {
        val overrides = JSONObject().put("book-key-a",
            JSONObject().put("title", "Edited title").put("description", "Edited description"))
        assertTrue(prefs.edit().putString("text_overrides", overrides.toString()).commit())
        val reopened = context.getSharedPreferences("smart_local_bulk_persistence_test", Context.MODE_PRIVATE)
        val restored = JSONObject(reopened.getString("text_overrides", "{}"))
        assertEquals("Edited title", restored.getJSONObject("book-key-a").getString("title"))
        restored.remove("book-key-a")
        assertTrue(prefs.edit().putString("text_overrides", restored.toString()).commit())
        assertFalse(JSONObject(reopened.getString("text_overrides", "{}")).has("book-key-a"))
    }
}
