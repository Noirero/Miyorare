package org.koitharu.kotatsu.acceptance

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.MangaEntity
import org.koitharu.kotatsu.core.db.entity.MangaPrefsEntity
import org.koitharu.kotatsu.favourites.data.FavouriteCategoryEntity
import org.koitharu.kotatsu.favourites.data.FavouriteEntity
import org.koitharu.kotatsu.history.data.HistoryEntity
import java.io.File

@RunWith(AndroidJUnit4::class)
class SignedUpgradeAcceptanceHarnessTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val mangaId = 9_100_001L

    @Test
    fun seedStableRepresentativeState() = runBlocking {
        val db = MangaDatabase(context)
        try {
            assertEquals("stable database must really be schema 48", 48, db.openHelper.writableDatabase.version)
            db.clearAllTables()
            val categoryId = db.getFavouriteCategoriesDao().insert(
                FavouriteCategoryEntity(
                    categoryId = 0, createdAt = 101L, sortKey = 1,
                    title = "Runtime Acceptance", order = "NEWEST", track = true,
                    downloadNewChapters = false, isVisibleInLibrary = true, deletedAt = 0L, space = 0,
                ),
            )
            val manga = MangaEntity(
                id = mangaId, title = "Runtime Acceptance Manga", altTitles = null,
                url = "/acceptance/manga", publicUrl = "https://fixture.invalid/acceptance/manga",
                rating = -1f, isNsfw = false, contentRating = null, coverUrl = "",
                largeCoverUrl = null, state = null, authors = null, description = null,
                source = "MIHON_123", sourceTitle = "Acceptance Fixture",
            )
            db.getMangaDao().upsert(manga, emptyList())
            db.getFavouritesDao().upsert(
                FavouriteEntity(mangaId, categoryId, 7, true, 202L, 0L),
            )
            db.getHistoryDao().upsert(
                HistoryEntity(mangaId, 303L, 404L, 0L, 7, 0.5f, 0.25f, 0L, 12),
            )
            db.getPreferencesDao().upsert(
                MangaPrefsEntity(
                    mangaId = mangaId, mode = 4, cfBrightness = 0.25f, cfContrast = -0.15f,
                    cfInvert = true, cfGrayscale = true, cfBookEffect = true,
                    titleOverride = null, coverUrlOverride = null, contentRatingOverride = null,
                    authorOverride = null, artistOverride = null, descriptionOverride = null,
                    mergeScanlators = false,
                ),
            )
            context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
                .edit().putString("miyorare_design_style", "CLASSIC")
                .putString("miyorare_theme_preset", "VIOLET")
                .putBoolean("reader_volume_buttons", true).commit()
            File(context.filesDir, "runtime-acceptance-preserve.txt").writeText("stable-v1.4.4")
            evidence("baseline", 48, categoryId)
        } finally { db.close() }
    }

    @Test
    fun verifyCandidateMigrationAndPreservation() = runBlocking {
        val db = MangaDatabase(context)
        try {
            assertEquals("candidate database must migrate to schema 50", 50, db.openHelper.writableDatabase.version)
            val categories = db.getFavouriteCategoriesDao().findAll()
            val category = categories.single { it.title == "Runtime Acceptance" }
            val favourites = db.getFavouritesDao().findAll(category.categoryId.toLong())
            assertEquals(listOf(mangaId), favourites.map { it.manga.id })
            val history = requireNotNull(db.getHistoryDao().find(mangaId))
            assertEquals(7, history.page)
            assertEquals(0.5f, history.scroll)
            assertEquals(0.25f, history.percent)
            val prefs = requireNotNull(db.getPreferencesDao().find(mangaId))
            assertEquals(4, prefs.mode)
            assertEquals(0.25f, prefs.cfBrightness)
            assertTrue(prefs.cfInvert)
            val sp = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
            assertEquals("CLASSIC", sp.getString("miyorare_design_style", null))
            assertEquals("VIOLET", sp.getString("miyorare_theme_preset", null))
            assertTrue(sp.getBoolean("reader_volume_buttons", false))
            assertEquals("stable-v1.4.4", File(context.filesDir, "runtime-acceptance-preserve.txt").readText())
            val sql = db.openHelper.writableDatabase
            for (table in listOf("reader_journey_xp_events", "reader_journey_weekly_state", "library_sync_mappings", "library_sync_entries")) {
                sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use {
                    assertTrue("missing migrated table: $table", it.moveToFirst())
                }
            }
            evidence("candidate", 50, category.categoryId.toLong())
        } finally { db.close() }
    }

    private fun evidence(stage: String, version: Int, categoryId: Long) {
        val out = requireNotNull(context.getExternalFilesDir(null))
        File(out, "runtime-acceptance-$stage.txt").writeText(
            "stage=$stage\npackage=${context.packageName}\ndatabase_version=$version\n" +
                "manga_id=$mangaId\ncategory_id=$categoryId\n"
        )
    }
}
