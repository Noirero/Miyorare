package org.koitharu.kotatsu.alternatives.domain

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Process
import androidx.hilt.work.HiltWorkerFactory
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.WorkManager
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.alternatives.work.MigrationBatchWork
import org.koitharu.kotatsu.bookmarks.data.BookmarkEntity
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.MangaPrefsEntity
import org.koitharu.kotatsu.core.model.MissingMangaSource
import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ChapterReadOverridesMigrationStore
import org.koitharu.kotatsu.details.data.ChapterPersonalEntity
import org.koitharu.kotatsu.details.data.MangaNotesRepository
import org.koitharu.kotatsu.favourites.data.*
import org.koitharu.kotatsu.favourites.groups.data.*
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.local.data.MangaIndex
import org.koitharu.kotatsu.local.data.index.LocalMangaIndex
import org.koitharu.kotatsu.local.data.index.LocalMangaIndexEntity
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.reader.ui.config.MangaReaderProfileStore
import org.koitharu.kotatsu.readerjourney.data.ReaderJourneyChapterEntity
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity
import org.koitharu.kotatsu.stats.data.StatsEntity
import org.koitharu.kotatsu.tracker.data.TrackEntity
import org.koitharu.kotatsu.tracker.data.TrackLogEntity
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Provider

/**
 * Invoked as two separate instrumentation processes by check-migration-process-persistence.sh.
 * Provider observations are deterministic fixtures; matching, selection, transfer, Room, preferences,
 * downloaded-page resolution and WorkManager persistence use production code. No live provider claim.
 */
@HiltAndroidTest
class MigrationEndToEndRuntimeTest {
    @get:Rule val hilt = HiltAndroidRule(this)
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var databaseProvider: Provider<MangaDatabase>
    @Inject lateinit var migrateProvider: Provider<MigrateUseCase>
    @Inject lateinit var dataProvider: Provider<MangaDataRepository>
    @Inject lateinit var settingsProvider: Provider<AppSettings>
    @Inject lateinit var profilesProvider: Provider<MangaReaderProfileStore>
    @Inject lateinit var notesProvider: Provider<MangaNotesRepository>
    @Inject lateinit var indexProvider: Provider<LocalMangaIndex>
    @Inject lateinit var localProvider: Provider<LocalMangaRepository>
    @Inject lateinit var networkProvider: Provider<NetworkState>
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root get() = File(context.filesDir, "migration-p5-fixture")
    private val manifest get() = File(root, "checkpoint.json")
    private val database get() = databaseProvider.get()
    private val settings get() = settingsProvider.get()
    private val notes get() = notesProvider.get()
    private val profiles get() = profilesProvider.get()
    private val index get() = indexProvider.get()
    private val old get() = manga(987001, "P5_OLD")
    private val target get() = manga(987002, "P5_NEW")
    private val interruptedOld get() = manga(988001, "P5_OLD")
    private val interruptedTarget get() = manga(988002, "P5_NEW")

    private fun initialize() {
        hilt.inject()
        WorkManager.initialize(context, Configuration.Builder().setWorkerFactory(workerFactory).build())
    }

    @Test fun prepareCommittedAndInterruptedCheckpoints() = runBlocking {
        initialize()
        assertTrue("Online fixture must exercise the legacy recalculation boundary", networkProvider.get().value)
        assertFalse("A previous test run must not supply our persistence checkpoint", manifest.exists())
        assertTrue(root.mkdirs())
        createCategories()
        val mate = manga(987003, "P5_OLD")
        persist(mate)
        database.getFavouritesDao().upsert(favourite(mate.id, NORMAL_A))
        database.getPrivateFavouritesDao().upsert(privateFavourite(mate.id))
        seed(old, target, privateMembership = true)
        seedFullState(old)
        val normalGroup = group(old.id, 0, mate.id)
        val privateGroup = group(old.id, 1, mate.id)
        database.getLibraryGroupsDao().insertTimeline(listOf(
            LibraryGroupTimelineItemEntity(normalGroup, old.id, chapter(old).id, 9),
            LibraryGroupTimelineItemEntity(privateGroup, old.id, chapter(old).id, 11),
        ))
        val normalPath = createDownload("normal", old)
        val privatePath = createDownload("private", old)
        database.getLocalMangaIndexDao().upsert(LocalMangaIndexEntity(old.id, normalPath.path))
        database.getFavouriteDownloadIndexDao().upsert(FavouriteDownloadIndexEntity(old.id, 0, normalPath.path))
        database.getFavouriteDownloadIndexDao().upsert(FavouriteDownloadIndexEntity(old.id, 1, privatePath.path))
        val files = physicalSnapshot()
        val expectedProfile = checkNotNull(profiles.get(old.id)).toString()
        val preview = discovery(old, target)
        assertEquals(MigrationMatchState.READY, preview.assessment?.state)
        assertEquals(MigrationBatchStatus.READY, MigrationBatchPolicy.assess(preview).status)
        val selection = MigrationSelection.of(target)
        assertNull(MigrationExecutionPolicy.select(preview.copy(incomplete = true), selection, false))
        val changed = target.copy(altTitles = setOf("A changed provider title"))
        assertNull(MigrationExecutionPolicy.select(discovery(old, changed), selection, false))
        val titleOnly = MigrationCandidateMatcher().assess(
            old.copy(authors = emptySet(), chapters = null),
            listOf(MigrationCandidateObservation(target.copy(authors = emptySet(), chapters = null))),
        )
        assertEquals(MigrationMatchState.NEEDS_REVIEW, titleOnly.state)
        val request = MigrationBatchRequest(old.id, old.source.name, MigrationBatchAction.MIGRATE, selection)
        val result = MigrationBatchPolicy.process(request,
            canAccess = { database.getMangaDao().isMigrationEntryVisible(old.id) },
            discover = { error("Execution must use the fresh matching gate below") },
            migrate = { selected ->
                val fresh = discovery(old, target)
                val replacement = checkNotNull(MigrationExecutionPolicy.select(fresh, selected, false))
                migrateProvider.get()(old, replacement, lossless = true, requireLibraryEntry = true)
                replacement
            },
        )
        assertEquals(MigrationBatchStatus.MIGRATED, result.status)
        assertEquals(target.id, result.destinationId)
        verifyFullState(normalGroup, privateGroup, expectedProfile)
        verifyDownloads(files)
        val replay = runCatching { migrateProvider.get()(old, target, lossless = true, requireLibraryEntry = true) }.exceptionOrNull()
        assertTrue(replay is MigrateUseCase.SourceChangedException)
        verifyFullState(normalGroup, privateGroup, expectedProfile)

        verifyManualReviewAndPrivateBoundary()
        verifyLegacyProgressAndAmbiguousRollback()
        verifyDownloadedDestinationCollisions()

        // Stop at the real non-Room preparation boundary. Room still owns the old manga.
        seed(interruptedOld, interruptedTarget)
        val sourceGroup = group(interruptedOld.id, 0)
        val conflictingGroup = group(interruptedTarget.id, 0)
        val preparedPath = createDownload("interrupted", interruptedOld)
        database.getLocalMangaIndexDao().upsert(LocalMangaIndexEntity(interruptedOld.id, preparedPath.path))
        profiles.saveCurrent(interruptedOld.id, settings)
        val plan = transfer().prepare(interruptedOld, interruptedTarget)
        assertTrue(profiles.prepareMove(interruptedOld.id, interruptedTarget.id))
        assertTrue(notes.prepareMove(interruptedOld.id, interruptedTarget.id))
        assertTrue(transfer().prepareReadOverrides(plan))
        ChapterReadOverridesMigrationStore(context).persistCurrent(interruptedTarget.id)
        assertNotNull(index.prepareDownloadIdentityMove(interruptedOld.id, interruptedTarget.id, plan.chapterTargets.mapValues { it.value.id }))
        assertTrue(database.getMangaDao().isMigrationEntryVisible(interruptedOld.id))
        manifest.writeText(JSONObject().put("pid", Process.myPid()).put("normalGroup", normalGroup)
            .put("privateGroup", privateGroup).put("profile", expectedProfile).put("files", files)
            .put("sourceGroup", sourceGroup).put("conflictingGroup", conflictingGroup).toString())
        println("P5_CHECKPOINT_PREPARED pid=" + Process.myPid() + " committed=987002 interrupted-before-Room=988001")
    }

    @Test fun reopenCheckpointsRetryWithoutLossAndClean() = runBlocking {
        initialize()
        assertTrue("Prior instrumentation process must have persisted its checkpoint", manifest.isFile)
        val saved = JSONObject(manifest.readText())
        assertNotEquals("The proof requires a fresh OS process, not new repository instances", saved.getInt("pid"), Process.myPid())
        verifyFullState(saved.getLong("normalGroup"), saved.getLong("privateGroup"), saved.getString("profile"))
        verifyDownloads(saved.getJSONObject("files"))

        // P4's finished results are reconstructed after its instrumentation process has ended.
        val works = WorkManager.getInstance(context).getWorkInfosByTagFlow(MigrationBatchWork.TAG).first()
        val snapshots = works.mapNotNull(MigrationBatchWork::snapshot)
        assertEquals(500, snapshots.count { it.source == "BATCH_LARGE" && it.outcome.status == MigrationBatchStatus.INTERRUPTED })
        val previous = MigrationBatchPolicy.latest(snapshots.filter { it.source == "BATCH_RUNTIME" })
        assertEquals(MigrationBatchStatus.INTERRUPTED, previous[986001L]?.outcome?.status)
        assertEquals(MigrationBatchStatus.NO_REPLACEMENT, previous[986002L]?.outcome?.status)
        assertEquals(MigrationBatchStatus.NO_REPLACEMENT, previous[986003L]?.outcome?.status)

        assertNotNull(profiles.get(interruptedOld.id))
        assertNotNull(profiles.get(interruptedTarget.id))
        assertEquals("P5 note", notes.get(interruptedOld.id))
        assertEquals("P5 note", notes.get(interruptedTarget.id))
        assertEquals(mapOf(chapter(interruptedOld).id to true), settings.getChapterReadOverrides(interruptedOld.id))
        assertEquals(mapOf(chapter(interruptedTarget).id to true), settings.getChapterReadOverrides(interruptedTarget.id))
        assertTrue(database.getMangaDao().isMigrationEntryVisible(interruptedOld.id))
        assertFalse(index.getCanonicalRemoteIds(listOf(interruptedOld.id)).containsKey(interruptedOld.id))
        assertEquals(File(root, "interrupted").canonicalPath, index.getDownloadAliasPaths(listOf(interruptedTarget.id))[interruptedTarget.id])
        val failedRetry = runCatching {
            migrateProvider.get()(interruptedOld, interruptedTarget, lossless = true, requireLibraryEntry = true)
        }.exceptionOrNull()
        assertTrue(failedRetry is LosslessMigrationConflictException)
        assertTrue(database.getMangaDao().isMigrationEntryVisible(interruptedOld.id))
        assertEquals(saved.getLong("sourceGroup"), database.getLibraryGroupsDao()
            .findMembersByMangaIds(listOf(interruptedOld.id), 0).single().groupId)
        assertEquals("P5 note", notes.get(interruptedTarget.id))
        assertNotNull(profiles.get(interruptedTarget.id))
        assertEquals(mapOf(chapter(interruptedTarget).id to true), settings.getChapterReadOverrides(interruptedTarget.id))
        assertNotNull(index.getDownloadAliasPaths(listOf(interruptedTarget.id))[interruptedTarget.id])

        database.getLibraryGroupsDao().deleteGroup(saved.getLong("conflictingGroup"))
        migrateProvider.get()(interruptedOld, interruptedTarget, lossless = true, requireLibraryEntry = true)
        assertFalse(database.getMangaDao().isMigrationEntryVisible(interruptedOld.id))
        assertTrue(database.getMangaDao().isMigrationEntryVisible(interruptedTarget.id))
        assertNull(notes.get(interruptedOld.id))
        assertEquals("P5 note", notes.get(interruptedTarget.id))
        assertNull(profiles.get(interruptedOld.id))
        assertNotNull(profiles.get(interruptedTarget.id))
        assertTrue(settings.getChapterReadOverrides(interruptedOld.id).isEmpty())
        assertEquals(mapOf(chapter(interruptedTarget).id to true), settings.getChapterReadOverrides(interruptedTarget.id))
        assertNotNull(index.getDownloadAliasPaths(listOf(interruptedTarget.id))[interruptedTarget.id])
        verifyReadable(interruptedTarget, File(root, "interrupted"))
        // A second source replacement must compose physical -> first -> second chapter identity.
        val secondTarget = manga(987004, "P5_FINAL")
        persist(secondTarget)
        val selectedAgain = checkNotNull(MigrationExecutionPolicy.select(
            discovery(target, secondTarget), MigrationSelection.of(secondTarget), false))
        val nextPlan = transfer().prepare(target, selectedAgain)
        assertNotNull(index.prepareDownloadIdentityMove(target.id, selectedAgain.id, nextPlan.chapterTargets.mapValues { it.value.id }))
        assertEquals(target.id, index.getCanonicalRemoteIds(listOf(old.id))[old.id])
        migrateProvider.get()(target, selectedAgain, lossless = true, requireLibraryEntry = true)
        assertTrue(index.getDownloadAliasPaths(listOf(target.id)).isEmpty())
        assertNull(database.getHistoryDao().find(target.id))
        assertNull(notes.get(target.id))
        assertNull(profiles.get(target.id))
        assertTrue(settings.getChapterReadOverrides(target.id).isEmpty())
        verifyFullState(saved.getLong("normalGroup"), saved.getLong("privateGroup"), saved.getString("profile"), secondTarget)
        verifyDownloads(saved.getJSONObject("files"), secondTarget)
        println("P5_CHECKPOINT_REOPENED pid=" + Process.myPid() + " previous-pid=" + saved.getInt("pid") + " durable-state-and-retry=verified")
        cleanup(listOf(saved.getLong("normalGroup"), saved.getLong("privateGroup"), saved.getLong("sourceGroup")))
    }

    private suspend fun verifyManualReviewAndPrivateBoundary() {
        val manualOld = manga(989001, "P5_OLD")
        val manualNew = manga(989002, "P5_NEW")
        seed(manualOld, manualNew)
        val ambiguous = discovery(manualOld, manualNew, manga(989003, "P5_OTHER"))
        assertEquals(MigrationMatchState.NEEDS_REVIEW, ambiguous.assessment?.state)
        assertNull(MigrationExecutionPolicy.select(ambiguous, MigrationSelection.of(manualNew), false))
        val reviewed = checkNotNull(MigrationExecutionPolicy.select(ambiguous, MigrationSelection.of(manualNew), true))
        migrateProvider.get()(manualOld, reviewed, lossless = true, requireLibraryEntry = true)
        assertEquals("P5 note", notes.get(manualNew.id))
        assertFalse(database.getMangaDao().isMigrationEntryVisible(manualOld.id))

        val privateOld = manga(990001, "P5_OLD")
        val privateNew = manga(990002, "P5_NEW")
        seed(privateOld, privateNew, privateMembership = true)
        database.getHistoryDao().insert(history(privateOld))
        database.getFavouritesDao().delete(privateOld.id)
        assertFalse(database.getMangaDao().isMigrationEntryVisible(privateOld.id))
        val veto = runCatching { migrateProvider.get()(privateOld, privateNew, lossless = true, requireLibraryEntry = true) }.exceptionOrNull()
        assertTrue(veto is MigrateUseCase.SourceChangedException)
        assertEquals("P5 note", notes.get(privateOld.id))
        assertNull(notes.get(privateNew.id))
        // An authorized private context can use the lossless engine without the public admission opt-in.
        migrateProvider.get()(privateOld, privateNew, lossless = true)
        assertTrue(database.getPrivateFavouritesDao().isPrivateOnly(privateNew.id))
        assertTrue(database.getFavouritesDao().findAllRaw(privateNew.id).isEmpty())
        assertEquals("P5 note", notes.get(privateNew.id))
        assertEquals(history(privateNew), database.getHistoryDao().find(privateNew.id))
        assertNull(database.getHistoryDao().find(privateOld.id))
        assertFalse(database.getMangaDao().isMigrationEntryVisible(privateNew.id))

        val publicOld = manga(993001, "P5_OLD")
        val privateDestination = manga(993002, "P5_NEW")
        seed(publicOld, privateDestination)
        database.getPrivateFavouritesDao().upsert(privateFavourite(privateDestination.id))
        val collision = runCatching { migrateProvider.get()(publicOld, privateDestination, lossless = true, requireLibraryEntry = true) }.exceptionOrNull()
        assertTrue(collision is MigrateUseCase.SourceChangedException)
        assertTrue(database.getMangaDao().isMigrationEntryVisible(publicOld.id))
        assertTrue(database.getPrivateFavouritesDao().isPrivateOnly(privateDestination.id))
        assertTrue(database.getFavouritesDao().findAllRaw(privateDestination.id).isEmpty())
    }

    private suspend fun verifyLegacyProgressAndAmbiguousRollback() {
        val legacyOld = manga(991001, "P5_OLD")
        val legacyNew = manga(991002, "P5_NEW")
        seed(legacyOld, legacyNew)
        database.getHistoryDao().insert(history(legacyOld))
        migrateProvider.get()(legacyOld, legacyNew)
        assertEquals("Legacy progress recalculation remains intentional", 1f,
            checkNotNull(database.getHistoryDao().find(legacyNew.id)).percent, 0f)

        val ambiguousOld = manga(992001, "P5_OLD")
        val oneTarget = manga(992002, "P5_NEW")
        val ambiguousTarget = oneTarget.copy(chapters = listOf(chapter(oneTarget), chapter(oneTarget).copy(id = 992202)))
        seed(ambiguousOld, ambiguousTarget)
        database.getBookmarksDao().insert(bookmark(ambiguousOld, 992301))
        val error = runCatching { migrateProvider.get()(ambiguousOld, ambiguousTarget, lossless = true, requireLibraryEntry = true) }.exceptionOrNull()
        assertTrue(error is LosslessMigrationConflictException)
        assertTrue(database.getMangaDao().isMigrationEntryVisible(ambiguousOld.id))
        assertEquals(chapter(ambiguousOld).id, database.getBookmarksDao().findAll(ambiguousOld.id).single().chapterId)
        assertEquals("P5 note", notes.get(ambiguousOld.id))
        assertNull(notes.get(ambiguousTarget.id))
    }

    private suspend fun verifyDownloadedDestinationCollisions() {
        val source = manga(994001, "P5_OLD")
        val destination = manga(994002, "P5_NEW")
        seed(source, destination)
        val sourcePath = createDownload("collision-source", source)
        val targetPath = createDownload("collision-target", destination)
        database.getLocalMangaIndexDao().upsert(LocalMangaIndexEntity(source.id, sourcePath.path))
        database.getLocalMangaIndexDao().upsert(LocalMangaIndexEntity(destination.id, targetPath.path))
        val containers = listOf(sourcePath, targetPath).flatMap { directory ->
            listOf(File(directory, "index.json"), File(directory, "chapter.cbz"))
        }.associateWith { it.readBytes() }
        // No favourite_download_index target exists: the local_index row itself must veto collision.
        val indexedCollision = runCatching {
            migrateProvider.get()(source, destination, lossless = true, requireLibraryEntry = true)
        }.exceptionOrNull()
        assertTrue(indexedCollision is IllegalStateException)
        assertTrue(indexedCollision?.message?.contains("different downloaded container") == true)
        assertTrue(database.getMangaDao().isMigrationEntryVisible(source.id))
        assertTrue(database.getFavouritesDao().findAllRaw(destination.id).isEmpty())
        assertEquals("P5 note", notes.get(source.id))
        assertNull(notes.get(destination.id))
        assertTrue(index.getDownloadAliasPaths(listOf(destination.id)).isEmpty())
        assertEquals(sourcePath.path, database.getLocalMangaIndexDao().findPath(source.id))
        assertEquals(targetPath.path, database.getLocalMangaIndexDao().findPath(destination.id))
        // A pre-existing destination alias is never owned by source-attempt rollback either.
        index.registerDownloadAlias(destination.id, destination.id, targetPath)
        val aliasCollision = runCatching {
            migrateProvider.get()(source, destination, lossless = true, requireLibraryEntry = true)
        }.exceptionOrNull()
        assertTrue(aliasCollision is IllegalStateException)
        assertEquals(targetPath.path, index.getDownloadAliasPaths(listOf(destination.id))[destination.id])
        assertEquals("P5 note", notes.get(source.id))
        assertNull(notes.get(destination.id))
        for ((file, bytes) in containers) assertArrayEquals("Collision must not touch " + file.name, bytes, file.readBytes())
    }

    private suspend fun seedFullState(manga: Manga) {
        profiles.saveCurrent(manga.id, settings)
        settings.setChapterReadOverrides(manga.id, mapOf(chapter(manga).id to true))
        ChapterReadOverridesMigrationStore(context).persistCurrent(manga.id)
        database.getHistoryDao().insert(history(manga))
        database.getBookmarksDao().insert(bookmark(manga, 987301))
        database.getPreferencesDao().upsert(preferences(manga.id))
        database.getChapterPersonalDao().upsert(ChapterPersonalEntity(manga.id, manga.source.name, chapter(manga).url, 5, "Chapter note"))
        database.getStatsDao().upsert(StatsEntity(manga.id, 40, 1200, 12, 1))
        database.getScrobblingDao().upsert(ScrobblingEntity(909123, 1, manga.id, 1234, "READING", 7, "Personal tracker note", 0.8f))
        database.getTracksDao().upsert(TrackEntity(manga.id, chapter(manga).id, 2, 60, 70, TrackEntity.RESULT_HAS_UPDATE, null))
        database.getTrackLogsDao().insert(TrackLogEntity(987401, manga.id, "Chapter 7", chapter(manga).id.toString(), 80, true))
        database.getReaderJourneyDao().mergeChapterAward(ReaderJourneyChapterEntity(manga.id, chapter(manga).id, false, 12, 1, 13, 90, 90))
    }

    private suspend fun verifyFullState(normalGroup: Long, privateGroup: Long, expectedProfile: String, destination: Manga = this.target) {
        val target = destination
        assertTrue(database.getFavouritesDao().findAllRaw(old.id).isEmpty())
        assertEquals(listOf(favourite(target.id, NORMAL_A), favourite(target.id, NORMAL_B)),
            database.getFavouritesDao().findAllRaw(target.id).sortedBy { it.categoryId })
        assertTrue(database.getPrivateFavouritesDao().findAllRaw(old.id).isEmpty())
        assertEquals(listOf(privateFavourite(target.id)), database.getPrivateFavouritesDao().findAllRaw(target.id))
        assertNull(database.getHistoryDao().find(old.id))
        assertEquals(history(target), database.getHistoryDao().find(target.id))
        assertEquals(listOf(bookmark(target, 987301)), database.getBookmarksDao().findAll(target.id))
        assertTrue(database.getBookmarksDao().findAll(old.id).isEmpty())
        assertEquals(preferences(target.id), database.getPreferencesDao().find(target.id))
        assertNull(database.getPreferencesDao().find(old.id))
        assertEquals(expectedProfile, checkNotNull(profiles.get(target.id)).toString())
        assertNull(profiles.get(old.id))
        assertEquals("P5 note", notes.get(target.id))
        assertNull(notes.get(old.id))
        assertEquals(mapOf(chapter(target).id to true), settings.getChapterReadOverrides(target.id))
        assertTrue(settings.getChapterReadOverrides(old.id).isEmpty())
        val personal = checkNotNull(database.getChapterPersonalDao().find(target.id, target.source.name, chapter(target).url))
        assertEquals(5, personal.rating)
        assertEquals("Chapter note", personal.note)
        assertTrue(database.getChapterPersonalDao().findAll(listOf(old.id)).isEmpty())
        assertEquals(StatsEntity(target.id, 40, 1200, 12, 1), database.getStatsDao().findAll(target.id).single())
        assertTrue(database.getStatsDao().findAll(old.id).isEmpty())
        val scrobbling = checkNotNull(database.getScrobblingDao().find(909123, target.id))
        assertEquals(1, scrobbling.id)
        assertEquals(target.id, scrobbling.mangaId)
        assertEquals(1234L, scrobbling.targetId)
        assertEquals("READING", scrobbling.status)
        assertEquals(7, scrobbling.chapter)
        assertEquals(0.8f, scrobbling.rating, 0f)
        assertEquals("Personal tracker note", scrobbling.comment)
        assertTrue(database.getScrobblingDao().findAll(old.id).isEmpty())
        val track = checkNotNull(database.getTracksDao().find(target.id))
        assertEquals(chapter(target).id, track.lastChapterId)
        assertEquals(2, track.newChapters)
        assertEquals(60L, track.lastCheckTime)
        assertEquals(70L, track.lastChapterDate)
        assertEquals(TrackEntity.RESULT_HAS_UPDATE, track.lastResult)
        assertNull(track.lastError)
        assertNull(database.getTracksDao().find(old.id))
        val log = database.getTrackLogsDao().findAllForManga(target.id).single()
        assertEquals(987401L, log.id)
        assertEquals("Chapter 7", log.chapters)
        assertEquals(chapter(target).id.toString(), log.chapterIds)
        assertEquals(80L, log.createdAt)
        assertTrue(log.isUnread)
        assertTrue(database.getTrackLogsDao().findAllForManga(old.id).isEmpty())
        val journey = database.getReaderJourneyDao().getAllChapterAwards()
        assertFalse(journey.any { it.mangaId == old.id })
        assertEquals(ReaderJourneyChapterEntity(target.id, chapter(target).id, false, 12, 1, 13, 90, 90),
            journey.single { it.mangaId == target.id })
        for ((groupId, position) in listOf(normalGroup to 9, privateGroup to 11)) {
            val members = database.getLibraryGroupsDao().findMembers(groupId)
            assertEquals(listOf(target.id, 987003L), members.map { it.mangaId })
            assertEquals(listOf(0, 1), members.map { it.position })
            assertEquals(listOf(LibraryGroupTimelineItemEntity(groupId, target.id, chapter(target).id, position)),
                database.getLibraryGroupsDao().findTimeline(groupId))
        }
    }

    private suspend fun verifyDownloads(expected: JSONObject, destination: Manga = this.target) {
        val target = destination
        val actual = physicalSnapshot()
        assertEquals(expected.length(), actual.length())
        val keys = expected.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            assertEquals("Physical file bytes, size and timestamp must remain unchanged: " + key,
                expected.getString(key), actual.getString(key))
        }
        assertEquals(File(root, "normal").canonicalPath, database.getLocalMangaIndexDao().findPath(old.id))
        assertNull(database.getLocalMangaIndexDao().findPath(target.id))
        assertEquals(File(root, "normal").canonicalPath, index.getDownloadAliasPaths(listOf(target.id))[target.id])
        assertEquals(target.id, index.getCanonicalRemoteIds(listOf(old.id))[old.id])
        for ((space, folder) in listOf(0 to "normal", 1 to "private")) {
            val path = File(root, folder)
            assertNull(database.getFavouriteDownloadIndexDao().findEntry(space, old.id))
            assertEquals(path.canonicalPath, database.getFavouriteDownloadIndexDao().findEntry(space, target.id)?.path)
            verifyReadable(target, path)
        }
        assertEquals(old.id, MangaIndex.read(File(root, "normal/index.json"))?.getMangaInfo()?.id)
        assertNotNull(index.get(target.id, withDetails = true))
        val indexed = checkNotNull(localProvider.get().findSavedMangaIndexed(target))
        assertEquals(chapter(target).id, checkNotNull(indexed.manga.chapters).single().id)
    }

    private suspend fun verifyReadable(remote: Manga, path: File) {
        val local = checkNotNull(localProvider.get().findSavedMangaAtPath(remote, path, rememberIdentity = false))
        val linked = checkNotNull(local.manga.chapters).single()
        assertEquals(chapter(remote).id, linked.id)
        val pages = localProvider.get().getPages(linked)
        assertEquals(2, pages.size)
        ZipFile(File(path, "chapter.cbz")).use { zip ->
            for (page in pages) {
                val name = checkNotNull(android.net.Uri.parse(page.url).fragment)
                val bytes = zip.getInputStream(checkNotNull(zip.getEntry(name))).use { it.readBytes() }
                val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                assertEquals(2, bitmap.width)
                assertEquals(2, bitmap.height)
                bitmap.recycle()
            }
        }
    }

    private suspend fun seed(source: Manga, destination: Manga, privateMembership: Boolean = false) {
        persist(source); persist(destination)
        database.getFavouritesDao().upsert(favourite(source.id, NORMAL_A))
        database.getFavouritesDao().upsert(favourite(source.id, NORMAL_B))
        if (privateMembership) database.getPrivateFavouritesDao().upsert(privateFavourite(source.id))
        notes.set(source.id, "P5 note")
        if (source.id == interruptedOld.id) {
            settings.setChapterReadOverrides(source.id, mapOf(chapter(source).id to true))
            ChapterReadOverridesMigrationStore(context).persistCurrent(source.id)
        }
    }

    private suspend fun persist(manga: Manga) = dataProvider.get().storeManga(
        manga, replaceExisting = true, stripAppliedOverride = false, detailsFetched = true,
    )

    private suspend fun createCategories() {
        for ((id, space) in listOf(NORMAL_A to 0, NORMAL_B to 0, PRIVATE to 1)) {
            database.getFavouriteCategoriesDao().upsert(FavouriteCategoryEntity(id, 1, 0, "P5 fixture", "NAME", false, false, true, 0, space))
        }
    }

    private suspend fun group(id: Long, space: Int, mate: Long? = null): Long {
        val dao = database.getLibraryGroupsDao()
        val groupId = dao.insertGroup(LibraryGroupEntity(title = "P5 fixture", coverUrl = null, createdAt = 1, space = space))
        dao.insertMembers(listOfNotNull(LibraryGroupMemberEntity(groupId, id, 0), mate?.let { LibraryGroupMemberEntity(groupId, it, 1) }))
        return groupId
    }

    private fun createDownload(name: String, manga: Manga): File {
        val directory = File(root, name).apply { check(mkdirs()) }
        val metadata = MangaIndex(null).apply { setMangaInfo(manga); addChapter(IndexedValue(0, chapter(manga)), "chapter.cbz") }
        File(directory, "index.json").writeText(metadata.toString())
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        }
        bitmap.recycle()
        ZipOutputStream(File(directory, "chapter.cbz").outputStream()).use { zip ->
            for (page in listOf("1.png", "2.png")) {
                zip.putNextEntry(ZipEntry(page)); zip.write(bytes); zip.closeEntry()
            }
        }
        return directory.canonicalFile
    }

    private fun physicalSnapshot(): JSONObject = JSONObject().also { snapshot ->
        for (folder in listOf("normal", "private")) for (name in listOf("index.json", "chapter.cbz")) {
            val file = File(root, folder + "/" + name)
            val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            snapshot.put(folder + "/" + name, hash + ":" + file.length() + ":" + file.lastModified())
        }
    }

    private fun transfer() = LosslessMigrationTransfer(database, settings, notes, profiles)
    private fun discovery(source: Manga, vararg candidates: Manga) =
        MigrationDiscovery(source, MigrationCandidateMatcher().assess(source, candidates.map { MigrationCandidateObservation(it) }))
    private fun chapter(manga: Manga) = checkNotNull(manga.chapters).first()
    private fun manga(id: Long, source: String): Manga {
        val provider = MissingMangaSource(source)
        return SampleData.mangaDetails.copy(id = id, title = "Migration Runtime Story", altTitles = emptySet(),
            authors = setOf("Yuna Harukawa"), tags = emptySet(), source = provider, url = "/" + id,
            publicUrl = "https://fixture.invalid/" + id, coverUrl = null, largeCoverUrl = null,
            chapters = listOf(MangaChapter(id = id + 100, title = "Chapter 7", number = 7f, volume = 0,
                url = "/" + id + "/7", scanlator = null, uploadDate = 100, branch = "main", source = provider)))
    }

    private fun favourite(id: Long, category: Int) = FavouriteEntity(id, category.toLong(), category - NORMAL_A, category == NORMAL_A, 10, 0)
    private fun privateFavourite(id: Long) = PrivateFavouriteEntity(id, PRIVATE.toLong(), 3, true, 20, 0)
    private fun history(manga: Manga) = HistoryEntity(manga.id, 20, 30, chapter(manga).id, 4, 0.35f, 0.7f, 0, 1)
    private fun bookmark(manga: Manga, pageId: Long) = BookmarkEntity(manga.id, pageId, chapter(manga).id, 3, 0, "https://fixture.invalid/page.png", 10, 0.3f, "Bookmark note")
    private fun preferences(id: Long) = MangaPrefsEntity(id, 1, 0.2f, 0.3f, true, false, true,
        "Personal title", null, null, "Personal author", "Personal artist", "Personal description", true)

    private suspend fun cleanup(groups: List<Long>) {
        groups.forEach { database.getLibraryGroupsDao().deleteGroup(it) }
        val ids = listOf(987001L, 987002L, 987003L, 987004L, 988001L, 988002L, 989001L, 989002L,
            990001L, 990002L, 991001L, 991002L, 992001L, 992002L, 993001L, 993002L, 994001L, 994002L)
        for (id in ids) {
            notes.set(id, null); profiles.clear(id)
            settings.setChapterReadOverrides(id, emptyMap())
            ChapterReadOverridesMigrationStore(context).persistCurrent(id)
            index.delete(id)
            database.getScrobblingDao().delete(909123, id)
            database.openHelper.writableDatabase.execSQL("DELETE FROM reader_journey_chapters WHERE manga_id = ?", arrayOf(id))
            database.getMangaDao().find(id)?.let { database.getMangaDao().delete(listOf(it.manga)) }
        }
        database.getReaderJourneyDao().rebuildProfileFromLedger()
        database.openHelper.writableDatabase.execSQL("DELETE FROM favourite_categories WHERE category_id IN (987901, 987902, 987903)")
        check(root.deleteRecursively())
    }

    private companion object {
        const val NORMAL_A = 987901
        const val NORMAL_B = 987902
        const val PRIVATE = 987903
    }
}
