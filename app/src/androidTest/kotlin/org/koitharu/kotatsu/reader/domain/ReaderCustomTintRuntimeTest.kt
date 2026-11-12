package org.koitharu.kotatsu.reader.domain

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.view.View
import android.widget.ImageView
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.MangaPrefsEntity
import org.koitharu.kotatsu.core.db.entity.toEntity
import org.koitharu.kotatsu.core.db.migrations.Migration52To53
import org.koitharu.kotatsu.core.db.migrations.Migration53To54
import org.koitharu.kotatsu.core.db.migrations.Migration54To55
import kotlinx.coroutines.test.runTest

@RunWith(AndroidJUnit4::class)
class ReaderCustomTintRuntimeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "custom-tint-schema-regression.db"

    @After fun cleanup() {
        context.deleteDatabase(name)
    }

    @Test fun pixelTintUsesRealBlendFilterAndDoesNotChangeUnderlyingBitmap() {
        val tint = ReaderCustomTint(enabled = true, red = 70, green = 0, blue = 171, alpha = 115)
        val original = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
        original.setPixel(0, 0, Color.BLACK)
        original.setPixel(1, 0, Color.GRAY)
        val filtered = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { colorFilter = tint.toPlatformColorFilter() }
        Canvas(filtered).drawBitmap(original, 0f, 0f, paint)
        assertEquals(Color.BLACK, original.getPixel(0, 0))
        assertEquals(Color.GRAY, original.getPixel(1, 0))
        assertNotEquals(original.getPixel(1, 0), filtered.getPixel(1, 0))
        val image = ImageView(context)
        ReaderCustomTint.applyToImage(image, ReaderColorFilter.EMPTY.copy(custom = tint)) {
            image.colorFilter = it
        }
        assertNotNull(image.colorFilter)
        // Tint alone is applied only to bitmap pixels, with no extra full-view layer.
        // Android View has setRenderEffect(), but no public getter.
        // Retain pre-31 layer assertions and verify render-filter pixel output above.
        if (Build.VERSION.SDK_INT < 31) assertEquals(View.LAYER_TYPE_NONE, image.layerType)
        ReaderCustomTint.applyToImage(image, ReaderColorFilter.EMPTY.copy(brightness = 0.2f, custom = tint)) {
            image.colorFilter = it
        }
        assertNotNull(image.colorFilter)
        if (Build.VERSION.SDK_INT < 31) assertEquals(View.LAYER_TYPE_HARDWARE, image.layerType)
        ReaderCustomTint.applyToImage(image, null) { image.colorFilter = it }
        assertNull(image.colorFilter)
        if (Build.VERSION.SDK_INT < 31) assertEquals(View.LAYER_TYPE_NONE, image.layerType)
        filtered.recycle()
        original.recycle()
    }

    @Test fun migration52To53PreservesExistingReaderSettingsAndSetsTintOff() = runTest {
        context.deleteDatabase(name)
        val initial = Room.databaseBuilder(context, MangaDatabase::class.java, name).build()
        val manga = SampleData.manga.copy(id = 918822L)
        try {
            initial.getMangaDao().upsert(manga.toEntity())
            initial.getPreferencesDao().upsert(
                MangaPrefsEntity(
                    mangaId = manga.id, mode = 0,
                    cfBrightness = 0.2f, cfContrast = 0.1f,
                    cfInvert = true, cfGrayscale = false, cfBookEffect = true,
                    titleOverride = null, coverUrlOverride = null, contentRatingOverride = null,
                ),
            )
        } finally {
            initial.close()
        }
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(name).absolutePath, null, SQLiteDatabase.OPEN_READWRITE,
        ).use { sql ->
            for (column in listOf("mode", "alpha", "blue", "green", "red", "enabled")) {
                sql.execSQL("ALTER TABLE preferences DROP COLUMN cf_custom_$column")
            }
            sql.execSQL("DROP TABLE chapter_progress")
            // v52 predates the new v55 bookmark ownership column.
            sql.execSQL("ALTER TABLE bookmarks DROP COLUMN origin")
            sql.execSQL("DROP TABLE room_master_table")
            sql.version = 52
        }
        val migrated = Room.databaseBuilder(context, MangaDatabase::class.java, name)
            .addMigrations(Migration52To53(), Migration53To54(), Migration54To55()).build()
        try {
            val pref = checkNotNull(migrated.getPreferencesDao().find(manga.id))
            assertEquals(0.2f, pref.cfBrightness, 0f)
            assertTrue(pref.cfInvert)
            assertTrue(pref.cfBookEffect)
            assertFalse(pref.cfCustomEnabled)
            assertEquals(70, pref.cfCustomRed)
            assertEquals(171, pref.cfCustomBlue)
            assertEquals(115, pref.cfCustomAlpha)
            assertEquals("OVERLAY", pref.cfCustomMode)
            migrated.getPreferencesDao().upsert(
                pref.copy(cfCustomEnabled = true, cfCustomRed = 210, cfCustomAlpha = 88,
                    cfCustomMode = ReaderTintBlendMode.SCREEN.name),
            )
        } finally {
            migrated.close()
        }
        val reopened = Room.databaseBuilder(context, MangaDatabase::class.java, name).build()
        try {
            val persisted = checkNotNull(reopened.getPreferencesDao().find(manga.id))
            assertTrue(persisted.cfCustomEnabled)
            assertEquals(210, persisted.cfCustomRed)
            assertEquals(88, persisted.cfCustomAlpha)
            assertEquals("SCREEN", persisted.cfCustomMode)
        } finally {
            reopened.close()
        }
    }
}
