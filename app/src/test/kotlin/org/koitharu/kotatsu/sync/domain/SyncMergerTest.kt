package org.koitharu.kotatsu.sync.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import kotlinx.serialization.json.Json
import org.koitharu.kotatsu.backup.local.data.model.MangaBackup
import org.koitharu.kotatsu.backup.local.data.model.ReaderAchievementBackup
import org.koitharu.kotatsu.backup.local.data.model.ReaderJourneyBackup
import org.koitharu.kotatsu.backup.local.data.model.ReaderJourneyWeeklyStateBackup
import org.koitharu.kotatsu.backup.local.data.model.ReaderJourneyXpEventBackup
import org.koitharu.kotatsu.sync.data.model.SyncCategory
import org.koitharu.kotatsu.sync.data.model.SyncFavourite
import org.koitharu.kotatsu.sync.data.model.SyncFeedEntry
import org.koitharu.kotatsu.sync.data.model.SyncMangaPrefs
import org.koitharu.kotatsu.sync.data.model.SyncSnapshot

class SyncMergerTest {

	@Test
	fun `deletion safety keeps the live remote favorite`() {
		val localDeletion = favourite(deletedAt = 300L)
		val remoteLive = favourite(deletedAt = 0L)

		val result = SyncMerger.mergeFavourites(
			local = listOf(localDeletion),
			remote = listOf(remoteLive),
			propagateDeletions = false,
		)

		assertEquals(1, result.size)
		assertSame(remoteLive, result.single())
	}

	@Test
	fun `deletions still propagate when safety is disabled`() {
		val localDeletion = favourite(deletedAt = 300L)
		val remoteLive = favourite(deletedAt = 0L)

		val result = SyncMerger.mergeFavourites(
			local = listOf(localDeletion),
			remote = listOf(remoteLive),
			propagateDeletions = true,
		)

		assertEquals(1, result.size)
		assertSame(localDeletion, result.single())
	}

	@Test
	fun `deletion safety removes category tombstones from the cloud result`() {
		val deletion = category(deletedAt = 300L)

		val result = SyncMerger.mergeCategories(
			local = listOf(deletion),
			remote = emptyList(),
			propagateDeletions = false,
		)

		assertEquals(emptyList<SyncCategory>(), result)
	}

	@Test
	fun `remote category colliding on id is remapped, not merged into a different category`() {
		// Phone: "Reading" is id 1. Tablet: "Manhwa" is id 1 with a favourite in it.
		val localReading = category(deletedAt = 0L).copy(categoryId = 1, title = "Reading")
		val remoteManhwa = category(deletedAt = 0L).copy(categoryId = 1, title = "Manhwa", createdAt = 999L)
		val remoteFavourite = favourite(deletedAt = 0L).copy(categoryId = 1L)

		val (categories, favourites) = SyncMerger.remapRemoteCategories(
			remoteCategories = listOf(remoteManhwa),
			remoteFavourites = listOf(remoteFavourite),
			localCategories = listOf(localReading),
		)

		val manhwa = categories.single()
		assertEquals("Manhwa", manhwa.title)
		assertEquals(2, manhwa.categoryId) // moved off the colliding id
		assertEquals(2L, favourites.single().categoryId) // favourite follows its category

		// And the id-keyed merge now keeps BOTH categories instead of clobbering "Reading".
		val merged = SyncMerger.mergeCategories(listOf(localReading), categories)
		assertEquals(setOf("Reading", "Manhwa"), merged.mapTo(HashSet()) { it.title })
	}

	@Test
	fun `same-titled category on two devices converges onto the local id`() {
		val local = category(deletedAt = 0L).copy(categoryId = 3, title = "Reading")
		val remote = category(deletedAt = 0L).copy(categoryId = 7, title = " reading ")
		val remoteFavourite = favourite(deletedAt = 0L).copy(categoryId = 7L)

		val (categories, favourites) = SyncMerger.remapRemoteCategories(
			remoteCategories = listOf(remote),
			remoteFavourites = listOf(remoteFavourite),
			localCategories = listOf(local),
		)

		assertEquals(3, categories.single().categoryId)
		assertEquals(3L, favourites.single().categoryId)
	}

	@Test
	fun `remap is identity when id spaces already agree`() {
		val local = category(deletedAt = 0L).copy(categoryId = 2, title = "Reading")
		val remote = category(deletedAt = 0L).copy(categoryId = 2, title = "Reading")
		val remoteFavourites = listOf(favourite(deletedAt = 0L))

		val (categories, favourites) = SyncMerger.remapRemoteCategories(
			remoteCategories = listOf(remote),
			remoteFavourites = remoteFavourites,
			localCategories = listOf(local),
		)

		assertSame(remote, categories.single())
		assertSame(remoteFavourites, favourites)
	}

	@Test
	fun `same feed event detected on two devices is merged once`() {
		val local = feed(chapters = "Chapter 11\nChapter 12", createdAt = 200L, unread = true)
		val remote = feed(chapters = " chapter 12 \nCHAPTER 11", createdAt = 100L, unread = false)

		val result = SyncMerger.mergeFeed(listOf(local), listOf(remote))

		assertEquals(1, result.size)
		assertEquals(100L, result.single().createdAt)
		assertFalse(result.single().isUnread)
	}

	@Test
	fun `same feed event keeps available chapter ids`() {
		val local = feed(
			chapters = "Chapter 11\nChapter 12",
			chapterIds = "",
			createdAt = 100L,
		)
		val remote = feed(
			chapters = "chapter 12\nchapter 11",
			chapterIds = "111\n222",
			createdAt = 200L,
		)

		val result = SyncMerger.mergeFeed(listOf(local), listOf(remote))

		assertEquals("111\n222", result.single().chapterIds)
	}

	@Test
	fun `different feed chapter events remain separate`() {
		val result = SyncMerger.mergeFeed(
			local = listOf(feed(chapters = "Chapter 11")),
			remote = listOf(feed(chapters = "Chapter 12")),
		)

		assertEquals(2, result.size)
	}

	@Test
	fun `same Reader Journey completion from two devices does not double XP`() {
		val local = journey(chapterId = 10L, completionCount = 1, awardedXp = 10L, lastCompletedAt = 100L)
		val remote = journey(chapterId = 10L, completionCount = 1, awardedXp = 10L, lastCompletedAt = 200L)

		val result = SyncMerger.mergeReaderJourney(listOf(local), listOf(remote))

		assertEquals(1, result.size)
		assertEquals(10L, result.single().awardedXp)
		assertEquals(1, result.single().completionCount)
		assertEquals(100L, result.single().firstCompletedAt)
		assertEquals(200L, result.single().lastCompletedAt)
	}

	@Test
	fun `Reader Journey merge keeps strongest reread state without addition`() {
		val local = journey(chapterId = 10L, completionCount = 2, awardedXp = 11L, lastCompletedAt = 200L)
		val remote = journey(chapterId = 10L, completionCount = 4, awardedXp = 13L, lastCompletedAt = 400L)

		val result = SyncMerger.mergeReaderJourney(listOf(local), listOf(remote)).single()

		assertEquals(4, result.completionCount)
		assertEquals(13L, result.awardedXp)
		assertEquals(400L, result.lastCompletedAt)
	}

	@Test
	fun `different Reader Journey chapters remain separate`() {
		val result = SyncMerger.mergeReaderJourney(
			local = listOf(journey(chapterId = 10L)),
			remote = listOf(journey(chapterId = 11L)),
		)

		assertEquals(setOf(10L, 11L), result.mapTo(HashSet()) { it.chapterId })
	}


	@Test
	fun `Reader Journey XP event merge is idempotent and monotonic`() {
		val local = ReaderJourneyXpEventBackup(
			eventKey = "weekly:2026-09-21:slot:0",
			source = "WEEKLY_TASK",
			xp = 25,
			occurredAt = 200L,
			context = "READ_3_CHAPTERS",
		)
		val remote = ReaderJourneyXpEventBackup(
			eventKey = local.eventKey,
			source = "WEEKLY_TASK",
			xp = 20,
			occurredAt = 100L,
			context = "READ_2_DAYS",
		)

		val result = SyncMerger.mergeReaderJourneyXpEvents(listOf(local), listOf(remote))

		assertEquals(1, result.size)
		assertEquals(25, result.single().xp)
		assertEquals(100L, result.single().occurredAt)
		assertEquals("READ_3_CHAPTERS", result.single().context)
	}

	@Test
	fun `Reader Journey reroll divergence cannot award the same weekly slot twice`() {
		val local = ReaderJourneyXpEventBackup(
			eventKey = "weekly:2026-09-21:slot:4",
			source = "WEEKLY_TASK",
			xp = 35,
			occurredAt = 200L,
			context = "READ_5_CHAPTERS",
		)
		val remote = ReaderJourneyXpEventBackup(
			eventKey = "weekly:2026-09-21:slot:4",
			source = "WEEKLY_TASK",
			xp = 30,
			occurredAt = 300L,
			context = "READ_2_NOVELS",
		)

		val result = SyncMerger.mergeReaderJourneyXpEvents(listOf(local), listOf(remote))

		assertEquals(1, result.size)
		assertEquals(35, result.single().xp)
		assertEquals("READ_5_CHAPTERS", result.single().context)
	}

	@Test
	fun `Rested bonus slot from two devices merges once and keeps stronger reward`() {
		val manga = ReaderJourneyXpEventBackup(
			eventKey = "rested:1000:slot:0",
			source = "RESTED",
			xp = 3,
			occurredAt = 2_000L,
			mangaId = 1L,
			chapterId = 10L,
			context = "1000",
			profileDelta = true,
		)
		val novel = ReaderJourneyXpEventBackup(
			eventKey = manga.eventKey,
			source = "RESTED",
			xp = 5,
			occurredAt = 2_100L,
			mangaId = 2L,
			chapterId = 20L,
			context = "1000",
			profileDelta = true,
		)

		val result = SyncMerger.mergeReaderJourneyXpEvents(listOf(manga), listOf(novel)).single()

		assertEquals(5, result.xp)
		assertEquals(2L, result.mangaId)
		assertEquals(20L, result.chapterId)
	}

	@Test
	fun `forked offline Rested windows converge to one capped comeback set`() {
		val local = buildList {
			add(
				ReaderJourneyXpEventBackup(
					eventKey = "rested-window:1000",
					source = "RESTED_WINDOW",
					xp = 0,
					occurredAt = 10_000L,
					context = "1000",
					profileDelta = false,
				),
			)
			repeat(5) { slot ->
				add(
					ReaderJourneyXpEventBackup(
						eventKey = "rested:1000:slot:" + slot,
						source = "RESTED",
						xp = 3,
						occurredAt = 10_100L + slot,
						mangaId = 1L,
						chapterId = 10L + slot,
						context = "1000",
						profileDelta = true,
					),
				)
			}
		}
		val remote = buildList {
			add(
				ReaderJourneyXpEventBackup(
					eventKey = "rested-window:2000",
					source = "RESTED_WINDOW",
					xp = 0,
					occurredAt = 11_000L,
					context = "2000",
					profileDelta = false,
				),
			)
			repeat(5) { slot ->
				add(
					ReaderJourneyXpEventBackup(
						eventKey = "rested:2000:slot:" + slot,
						source = "RESTED",
						xp = 5,
						occurredAt = 11_100L + slot,
						mangaId = 2L,
						chapterId = 20L + slot,
						context = "2000",
						profileDelta = true,
					),
				)
			}
		}

		val result = SyncMerger.mergeReaderJourneyXpEvents(local, remote)
		val rested = result.filter { it.source == "RESTED" }

		assertEquals(1, result.count { it.source == "RESTED_WINDOW" })
		assertEquals(5, rested.size)
		assertEquals(25, rested.sumOf { it.xp })
		assertTrue(rested.all { it.context == "1000" })
		assertEquals(
			(0 until 5).map { "rested:1000:slot:" + it }.toSet(),
			rested.mapTo(HashSet()) { it.eventKey },
		)
	}

	@Test
	fun `forked offline Welcome Back windows converge to three rewards`() {
		val local = listOf(
			ReaderJourneyXpEventBackup(
				eventKey = "welcome-window:1000",
				source = "WELCOME_BACK_WINDOW",
				xp = 0,
				occurredAt = 20_000L,
				context = "1000",
				profileDelta = false,
			),
		) + (0 until 3).map { slot ->
			ReaderJourneyXpEventBackup(
				eventKey = "welcome:1000:slot:" + slot,
				source = "WELCOME_BACK",
				xp = 3,
				occurredAt = 20_100L + slot,
				mangaId = 1L,
				chapterId = 30L + slot,
				context = "1000",
				profileDelta = true,
			)
		}
		val remote = listOf(
			ReaderJourneyXpEventBackup(
				eventKey = "welcome-window:2000",
				source = "WELCOME_BACK_WINDOW",
				xp = 0,
				occurredAt = 21_000L,
				context = "2000",
				profileDelta = false,
			),
		) + (0 until 3).map { slot ->
			ReaderJourneyXpEventBackup(
				eventKey = "welcome:2000:slot:" + slot,
				source = "WELCOME_BACK",
				xp = 5,
				occurredAt = 21_100L + slot,
				mangaId = 2L,
				chapterId = 40L + slot,
				context = "2000",
				profileDelta = true,
			)
		}

		val result = SyncMerger.mergeReaderJourneyXpEvents(local, remote)
		assertEquals(1, result.count { it.source == "WELCOME_BACK_WINDOW" })
		assertEquals(3, result.count { it.source == "WELCOME_BACK" })
		assertEquals(15, result.filter { it.source == "WELCOME_BACK" }.sumOf { it.xp })
	}

	@Test
	fun `equal XP weekly reroll events converge independent of local device`() {
		val earlier = ReaderJourneyXpEventBackup(
			eventKey = "weekly-reroll:2026-09-21:slot:2",
			source = "WEEKLY_REROLL",
			xp = 0,
			occurredAt = 100L,
			context = "READ_2_TITLES",
			profileDelta = false,
		)
		val later = ReaderJourneyXpEventBackup(
			eventKey = earlier.eventKey,
			source = earlier.source,
			xp = earlier.xp,
			occurredAt = 200L,
			mangaId = earlier.mangaId,
			chapterId = earlier.chapterId,
			context = "READ_1_NOVEL",
			profileDelta = earlier.profileDelta,
		)

		val a = SyncMerger.mergeReaderJourneyXpEvents(listOf(earlier), listOf(later)).single()
		val b = SyncMerger.mergeReaderJourneyXpEvents(listOf(later), listOf(earlier)).single()

		assertEquals(a.eventKey, b.eventKey)
		assertEquals(a.source, b.source)
		assertEquals(a.xp, b.xp)
		assertEquals(a.occurredAt, b.occurredAt)
		assertEquals(a.mangaId, b.mangaId)
		assertEquals(a.chapterId, b.chapterId)
		assertEquals(a.context, b.context)
		assertEquals(a.profileDelta, b.profileDelta)
		assertEquals("READ_2_TITLES", a.context)
		assertEquals(100L, a.occurredAt)
	}

	@Test
	fun `Reader Journey weekly merge never restores rerolls`() {
		val local = ReaderJourneyWeeklyStateBackup(
			weekKey = "2026-09-21",
			taskIds = "A,B,C,D,E,F",
			rerollsUsed = 2,
			updatedAt = 200L,
		)
		val remote = ReaderJourneyWeeklyStateBackup(
			weekKey = "2026-09-21",
			taskIds = "A,B,C,D,E,G",
			rerollsUsed = 1,
			updatedAt = 300L,
		)

		val result = SyncMerger.mergeReaderJourneyWeekly(listOf(local), listOf(remote)).single()

		assertEquals(2, result.rerollsUsed)
		assertEquals(remote.taskIds, result.taskIds)
		assertEquals(300L, result.updatedAt)
	}

	@Test
	fun `Reader achievement merge keeps earliest unlock and never duplicates`() {
		val local = ReaderAchievementBackup("CHAPTERS_100", 300L)
		val remote = ReaderAchievementBackup("CHAPTERS_100", 200L)

		val result = SyncMerger.mergeReaderAchievements(listOf(local), listOf(remote))

		assertEquals(1, result.size)
		assertEquals("CHAPTERS_100", result.single().achievementId)
		assertEquals(200L, result.single().unlockedAt)
	}

	@Test
	fun `different Reader achievements remain separate`() {
		val result = SyncMerger.mergeReaderAchievements(
			local = listOf(ReaderAchievementBackup("FIRST_CHAPTER", 100L)),
			remote = listOf(ReaderAchievementBackup("FIRST_NOVEL", 200L)),
		)

		assertEquals(setOf("FIRST_CHAPTER", "FIRST_NOVEL"), result.mapTo(HashSet()) { it.achievementId })
	}

	@Test
	fun `Reader Journey Lifetime XP floor merges monotonically without identity`() {
		val result = SyncMerger.combine(
			listOf(
				SyncSnapshot(readerJourneyLifetimeXp = 1_000L),
				SyncSnapshot(readerJourneyLifetimeXp = 1_500L),
			),
		)

		assertEquals(1_500L, result?.readerJourneyLifetimeXp)
	}

	@Test
	fun `schema one snapshots remain readable`() {
		val snapshot = Json.decodeFromString<SyncSnapshot>("""{"schema":1}""")
		val prefs = Json.decodeFromString<SyncMangaPrefs>(
			"""
			{
				"manga_id": 1,
				"mode": 0,
				"cf_brightness": 0.0,
				"cf_contrast": 0.0,
				"cf_invert": false,
				"cf_grayscale": false,
				"cf_book": false,
				"cover_override": "file:///old-device/cover.jpg"
			}
			""".trimIndent(),
		)

		assertEquals(1, snapshot.schemaVersion)
		assertEquals(emptyList<SyncFeedEntry>(), snapshot.feed)
		assertEquals(emptyList<ReaderJourneyBackup>(), snapshot.readerJourney)
		assertEquals(emptyList<ReaderJourneyXpEventBackup>(), snapshot.readerJourneyXpEvents)
		assertEquals(emptyList<ReaderJourneyWeeklyStateBackup>(), snapshot.readerJourneyWeekly)
		assertEquals(0L, snapshot.readerJourneyLifetimeXp)
		assertEquals(emptyList<ReaderAchievementBackup>(), snapshot.readerAchievements)
		assertNull(prefs.coverData)
	}

	private fun favourite(deletedAt: Long) = SyncFavourite(
		mangaId = MANGA_ID,
		categoryId = 2L,
		sortKey = 0,
		isPinned = false,
		createdAt = 100L,
		deletedAt = deletedAt,
		manga = manga(),
	)

	private fun category(deletedAt: Long) = SyncCategory(
		categoryId = 2,
		createdAt = 100L,
		sortKey = 0,
		title = "Reading",
		order = "NEWEST",
		track = true,
		downloadNewChapters = false,
		isVisibleInLibrary = true,
		deletedAt = deletedAt,
	)

	private fun feed(
		chapters: String,
		chapterIds: String = "",
		createdAt: Long = 100L,
		unread: Boolean = true,
	) = SyncFeedEntry(
		mangaId = MANGA_ID,
		chapters = chapters,
		chapterIds = chapterIds,
		createdAt = createdAt,
		isUnread = unread,
		manga = manga(),
	)

	private fun journey(
		chapterId: Long,
		completionCount: Int = 1,
		awardedXp: Long = 10L,
		lastCompletedAt: Long = 100L,
	) = ReaderJourneyBackup(
		mangaId = MANGA_ID,
		chapterId = chapterId,
		isNovel = false,
		readingUnits = 0,
		completionCount = completionCount,
		awardedXp = awardedXp,
		firstCompletedAt = 100L,
		lastCompletedAt = lastCompletedAt,
	)

	private fun manga() = MangaBackup(
		id = MANGA_ID,
		title = "Manga",
		url = "/manga",
		publicUrl = "https://example.com/manga",
		coverUrl = "https://example.com/cover.jpg",
		source = "TEST",
	)

	private companion object {

		const val MANGA_ID = 1L
	}
}
