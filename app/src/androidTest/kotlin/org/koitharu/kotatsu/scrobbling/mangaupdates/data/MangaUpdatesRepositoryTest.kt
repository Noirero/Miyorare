package org.koitharu.kotatsu.scrobbling.mangaupdates.data

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.MangaEntity
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity
import org.koitharu.kotatsu.scrobbling.common.domain.model.*
import org.koitharu.kotatsu.scrobbling.common.ui.selector.ScrobblingSelectorViewModel
import org.koitharu.kotatsu.scrobbling.mangaupdates.domain.MangaUpdatesScrobbler
import java.io.IOException
import java.util.Collections
import java.util.UUID
import javax.inject.Inject

/** Real Room and API request paths; the terminal transport can never reach a provider. */
@HiltAndroidTest
class MangaUpdatesRepositoryTest {
	@get:Rule val hilt = HiltAndroidRule(this)
	@Inject lateinit var mangaRepositoryFactory: MangaRepository.Factory
	private val app = InstrumentationRegistry.getInstrumentation().targetContext
	private val prefix = "mu-fixture-${UUID.randomUUID()}-"
	private val preferences = mutableSetOf<String>()
	private val context = object : ContextWrapper(app) {
		override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefix + name, mode).also { preferences += name }
	}
	private lateinit var db: MangaDatabase
	private lateinit var settings: AppSettings
	private lateinit var sessions: Sessions
	private lateinit var transport: Transport
	private lateinit var scope: CoroutineScope
	private val target = 17360452316L
	private val user = ScrobblerUser(1, "Fixture User", null, ScrobblerService.MANGAUPDATES)

	@Before fun setup() {
		hilt.inject()
		db = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java).build()
		settings = AppSettings(context).apply { isIncognitoModeEnabled = false }
		sessions = Sessions(); transport = Transport(target); scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	}
	@After fun cleanup() = runBlocking {
		scope.coroutineContext[Job]?.cancelAndJoin()
		db.close()
		preferences.forEach { app.deleteSharedPreferences(prefix + it) }
	}
	private fun repository(authorized: Boolean = true, operations: Mutex = Mutex()): MangaUpdatesRepository {
		if (authorized && sessions.snapshot() == null) sessions.save("fixture-session", user, sessions.generation.value)
		val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
			.addInterceptor(MangaUpdatesInterceptor(sessions)).addInterceptor(transport).build()
		return MangaUpdatesRepository(MangaUpdatesApi(client, sessions, MANGAUPDATES_API), sessions, db, settings, scope, MangaUpdatesWriteGate({ 0L }, {}), operations)
	}
	private suspend fun manga(id: Long = 41) = db.getMangaDao().upsert(MangaEntity(id, "Source title", null, "file:///fixture-$id.cbz", "file:///fixture-$id.cbz", 0f, false, null, "", null, null, null, null, "LOCAL", null))
	private suspend fun linked(repo: MangaUpdatesRepository) { manga(); repo.createRate(41, target) }
	private fun mutations() = transport.requests.filter { it.path.startsWith("/v1/lists/series") && it.method == "POST" }

	@Test fun loginVerifiesProfileBeforePersistingSessionAndNeverPersistsPassword() = runBlocking {
		val repo = repository(authorized = false)
		assertEquals(user, repo.signIn("fixture-user", "  fixture password  "))
		assertTrue(repo.isAuthorized)
		assertEquals("fixture-session", sessions.snapshot()?.token)
		val login = transport.requests.single { it.path == "/v1/account/login" }
		assertNull(login.authorization)
		assertEquals("  fixture password  ", login.body!!.jsonObject.getValue("password").jsonPrimitive.content)
		assertEquals("Bearer fixture-session", transport.requests.single { it.path == "/v1/account/profile" }.authorization)
		assertFalse(sessions.snapshot().toString().contains("fixture password"))
	}

	@Test fun failedLoginAndMalformedSessionNeverBecomeAuthorized() = runBlocking {
		val repo = repository(authorized = false)
		transport.loginCode = 401
		try { repo.signIn("fixture-user", "fixture-password"); fail("Expected login failure") } catch (_: IOException) { }
		assertFalse(repo.isAuthorized)
		transport.loginCode = 200; transport.loginBody = """{"status":"success","context":{"user_id":1}}"""
		try { repo.signIn("fixture-user", "fixture-password"); fail("Expected malformed session") } catch (_: IOException) { }
		assertFalse(repo.isAuthorized)
		assertTrue(transport.requests.none { it.path == "/v1/account/profile" })
	}

	@Test fun logoutClearsSessionImmediatelyAndRevokesOnlyAtProviderOrigin() = runBlocking {
		val repo = repository()
		val before = sessions.generation.value
		repo.logout()
		assertFalse(repo.isAuthorized); assertNull(repo.cachedUser)
		assertTrue(sessions.generation.value > before)
		withTimeout(5000) { while (transport.requests.none { it.path == "/v1/account/logout" }) delay(10) }
		assertEquals("Bearer fixture-session", transport.requests.single { it.path == "/v1/account/logout" }.authorization)
	}

	@Test fun expiredSessionInvalidatesTheAccountWithoutRemovingTheLocalAssociation() = runBlocking {
		val repo = repository(); linked(repo)
		transport.profileCode = 401
		try { repo.loadUser(); fail("Expected expired session") } catch (_: IOException) { }
		assertFalse(repo.isAuthorized)
		assertEquals(target, db.getScrobblingDao().find(6, 41)?.targetId)
	}

	@Test fun searchKeepsCatalogIdentityAndDoesNotCreateTrackingOrFavorites() = runBlocking {
		val repo = repository()
		val result = repo.findManga("Fixture", 0, ScrobblerMangaType.MANGA).single()
		assertEquals(target, result.id)
		assertEquals("Fixture title", result.name)
		assertNotNull(result.cover)
		assertFalse(result.isBestMatch)
		assertNull(db.getScrobblingDao().find(6, 41))
		assertEquals(0, count("favourites"))
		assertTrue(mutations().isEmpty())
		assertNull(transport.requests.single { it.path == "/v1/series/search" }.authorization)
	}

	@Test fun existingRemoteStateIsAdoptedWithoutResettingCustomListProgressOrRating() = runBlocking {
		val repo = repository(); manga()
		assertTrue(repo.createRate(41, target))
		val row = db.getScrobblingDao().find(6, 41)!!
		assertEquals(0, row.id); assertEquals(target, row.targetId)
		assertEquals("read", row.status); assertEquals(31, row.chapter); assertEquals(0.8f, row.rating, 0f)
		assertEquals(6, repo.getVolume(41))
		assertTrue(mutations().isEmpty())
	}

	@Test fun catalogPaginationHonorsTheProviderPageSizeAndKeepsLargeIDsDistinct() = runBlocking {
		val repo = repository()
		fun page(number: Int) = buildJsonObject {
			put("page", number); put("per_page", 25); put("total_hits", 50)
			put("results", buildJsonArray { repeat(25) { index -> add(buildJsonObject {
				put("record", buildJsonObject { put("series_id", target + (number - 1) * 25 + index); put("title", "Title $number/$index") })
			}) } })
		}.toString()
		transport.searchBody = page(1)
		val first = repo.findManga("Fixture", 0, ScrobblerMangaType.MANGA)
		transport.searchBody = page(2)
		val second = repo.findManga("Fixture", first.size, ScrobblerMangaType.MANGA)
		assertEquals(50, (first + second).map { it.id }.toSet().size)
		assertTrue(repo.findManga("Fixture", 50, ScrobblerMangaType.MANGA).isEmpty())
		assertEquals(listOf(1, 2), transport.requests.filter { it.path == "/v1/series/search" }.map { it.body!!.jsonObject.getValue("page").jsonPrimitive.int })
		assertEquals(0, count("scrobblings")); assertEquals(0, count("favourites"))
	}

	@Test fun newAssociationUsesDiscoveredWishlistIdentityAndRetainsFullSeriesID() = runBlocking {
		transport.remote = null; transport.rating = null
		val repo = repository(); manga()
		assertFalse(repo.createRate(41, target))
		val row = db.getScrobblingDao().find(6, 41)!!
		assertEquals(target, row.targetId); assertEquals(0, row.id)
		assertEquals("wish", row.status)
		assertEquals(77L, transport.remote?.getValue("list_id")?.jsonPrimitive?.long)
		val payload = mutations().single().body!!.jsonArray.single().jsonObject
		assertEquals(target, payload.getValue("series").jsonObject.getValue("id").jsonPrimitive.long)
	}

	@Test fun chapterUpdatePreservesVolumeCustomListPriorityAndRating() = runBlocking {
		val repo = repository(); linked(repo)
		repo.updateRate(0, 41, 40)
		assertEquals(40, db.getScrobblingDao().find(6, 41)?.chapter)
		assertEquals(6, repo.getVolume(41))
		val body = mutations().single().body!!.jsonArray.single().jsonObject
		assertEquals(9000L, body.getValue("list_id").jsonPrimitive.long)
		assertEquals(2, body.getValue("priority").jsonPrimitive.int)
		assertEquals(setOf("chapter", "volume"), body.getValue("status").jsonObject.keys)
		assertEquals(0.8f, db.getScrobblingDao().find(6, 41)!!.rating, 0f)
		assertTrue(transport.requests.none { it.method in setOf("PUT", "DELETE") && it.path.endsWith("/rating") })
	}

	@Test fun statusAndVolumeEditsPreserveUnrelatedProgressAndUseVerifiedListTypes() = runBlocking {
		val repo = repository(); linked(repo)
		for ((status, listId) in listOf("wish" to 77L, "read" to 42L, "complete" to 88L, "hold" to 99L, "unfinished" to 111L)) {
			repo.updateRate(0, 41, 0.8f, status, "unsupported note", true)
			assertEquals(status, db.getScrobblingDao().find(6, 41)?.status)
			assertEquals(listId, transport.remote!!.getValue("list_id").jsonPrimitive.long)
		}
		repo.updateVolume(41, 9)
		assertEquals(9, repo.getVolume(41))
		assertEquals(31, db.getScrobblingDao().find(6, 41)?.chapter)
		assertNull(db.getScrobblingDao().find(6, 41)?.comment)
	}

	@Test fun ratingEditsUseTenPointScaleAndZeroExplicitlyRemovesRating() = runBlocking {
		val repo = repository(); linked(repo)
		repo.updateRate(0, 41, 0.7f, "read", null, false)
		assertEquals(7f, transport.rating!!, 0f)
		assertEquals(0.7f, db.getScrobblingDao().find(6, 41)!!.rating, 0f)
		repo.updateRate(0, 41, 0f, "read", null, false)
		assertNull(transport.rating)
		assertTrue(transport.requests.any { it.method == "DELETE" && it.path.endsWith("/rating") })
		assertTrue(mutations().isEmpty())
	}

	@Test fun uncertainWriteResponseIsReconciledWithoutRepeatingOrIncrementingProgress() = runBlocking {
		val repo = repository(); linked(repo)
		transport.loseAcknowledgement = true
		repo.updateRate(0, 41, 45)
		assertEquals(45, db.getScrobblingDao().find(6, 41)?.chapter)
		assertEquals(1, mutations().size)
		assertEquals(6, repo.getVolume(41))
	}

	@Test fun throttleRetriesAreBoundedAndFailedWritesNeverAdvanceRoomProgress() = runBlocking {
		val repo = repository(); linked(repo)
		transport.failWrites = 1; transport.failureCode = 412
		repo.updateRate(0, 41, 40)
		assertEquals(2, mutations().size)
		assertEquals(40, db.getScrobblingDao().find(6, 41)?.chapter)
		transport.failWrites = 3; transport.failureCode = 503
		try { repo.updateRate(0, 41, 50); fail("Expected failed write") } catch (_: IOException) { }
		assertEquals(4, mutations().size)
		assertEquals(40, db.getScrobblingDao().find(6, 41)?.chapter)
	}

	@Test fun remoteProgressRefreshFeedsExistingSynchronizationWithoutCreatingLibraryEntries() = runBlocking {
		val repo = repository(); linked(repo)
		transport.remote = transport.state(chapter = 55, volume = 11)
		val refreshed = repo.refreshRate(db.getScrobblingDao().find(6, 41)!!)
		assertEquals(55, refreshed.chapter)
		assertEquals(55, db.getScrobblingDao().find(6, 41)?.chapter)
		assertEquals(11, repo.getVolume(41))
		assertEquals(0, count("favourites")); assertEquals(0, count("history"))
		assertTrue(mutations().isEmpty())
	}

	@Test fun peopleAndRecommendationsUsePublicReadsWithoutTrackingOrLibrarySideEffects() = runBlocking {
		val repo = repository(); linked(repo)
		val key = TrackerTarget(ScrobblerService.MANGAUPDATES, target.toString())
		val staff = repo.loadStaff(key, TrackerPage()) as TrackerResult.Success
		assertEquals(listOf("Author", "Artist"), staff.items.single().roles)
		assertEquals("https://image.invalid/author", staff.items.single().image)
		assertSame(TrackerResult.Unsupported, repo.loadCharacters(key, TrackerPage()))
		val recommendations = repo.loadRecommendations(key, TrackerPage()) as TrackerResult.Success
		assertEquals("70994361491", recommendations.items.single().target.id)
		assertEquals(1, count("scrobblings")); assertEquals(0, count("favourites"))
		assertTrue(mutations().isEmpty())
		assertTrue(transport.requests.filter { it.path.startsWith("/v1/authors/") || it.path == "/v1/series/$target" }.all { it.authorization == null })
	}

	@Test fun accountChangeDuringAResponseRejectsStaleStateAndQueuedWritesRespectIncognito() = runBlocking {
		val repo = repository(); linked(repo)
		transport.beforeReply = { request -> if (request.path == "/v1/lists/series/$target") sessions.save("replacement-fixture", user.copy(id = 2), sessions.generation.value) }
		try { repo.refreshRate(db.getScrobblingDao().find(6, 41)!!); fail("Expected stale session rejection") } catch (_: CancellationException) { }
		assertEquals(31, db.getScrobblingDao().find(6, 41)?.chapter)
		assertEquals(2L, sessions.snapshot()?.user?.id)
		transport.beforeReply = null; settings.isIncognitoModeEnabled = true
		repo.enqueueProgress(41, 80, false)
		assertTrue(mutations().isEmpty())
	}

	@Test fun backgroundProgressReportsFailureAndExplicitRetryConfirmsTheSameTarget() = runBlocking {
		val repo = repository(); linked(repo)
		transport.failWrites = 2
		repo.enqueueProgress(41, 60, false)
		withTimeout(5000) { repo.failedProgress.first { 41L in it } }
		assertEquals(31, db.getScrobblingDao().find(6, 41)?.chapter)
		repo.retryProgress(41)
		withTimeout(5000) { db.getScrobblingDao().observe(6, 41).first { it?.chapter == 60 } }
		assertTrue(repo.failedProgress.value.isEmpty())
		assertEquals(target, db.getScrobblingDao().find(6, 41)?.targetId)
	}

	@Test fun providerLocalRateKeysRemainDistinctInBackupPaginationAndReverseMapping() = runBlocking {
		repeat(320) { index -> manga(index.toLong() + 1); db.getScrobblingDao().upsert(ScrobblingEntity(6, 0, index.toLong() + 1, target + index, "read", index, null, 0f)) }
		val rows = db.getScrobblingDao().dumpEnabled().toList()
		assertEquals(320, rows.size)
		assertEquals(320, rows.map { it.mangaId }.toSet().size)
		assertEquals(target + 319, rows.last().targetId)
		assertEquals(320L, db.getScrobblingDao().findByTarget(6, target + 319).single().mangaId)
		assertTrue(db.getScrobblingDao().findByTarget(2, target + 319).isEmpty())
	}

	@Test fun explicitReassociationClearsAnOldFailureAndNeverRetriesThePreviousTarget() = runBlocking {
		val repo = repository(); linked(repo)
		transport.failWrites = 2
		repo.enqueueProgress(41, 60, false)
		withTimeout(5000) { repo.failedProgress.first { 41L in it } }
		val writes = mutations().size
		transport.id = target + 1
		transport.remote = transport.state()
		repo.createRate(41, target + 1)
		assertTrue(repo.failedProgress.value.isEmpty())
		repo.retryProgress(41)
		assertEquals(target + 1, db.getScrobblingDao().find(6, 41)?.targetId)
		assertEquals(writes, mutations().size)
	}

	@Test fun waitingExplicitEditsCannotUseAReplacementAccount() = runBlocking {
		val operations = ObservedMutex()
		val repo = repository(operations = operations); linked(repo)
		val original = db.getScrobblingDao().find(6, 41)!!
		val edits: List<suspend () -> Unit> = listOf(
			{ repo.updateRate(0, 41, 0.6f, "complete", null, false) },
			{ repo.updateRate(0, 41, 60) },
			{ repo.updateVolume(41, 8) },
			{ repo.createRate(41, target) },
			{ repo.refreshRate(original) },
		)
		for ((index, edit) in edits.withIndex()) {
			operations.queued = CompletableDeferred()
			operations.lock()
			val waiting = async(start = CoroutineStart.UNDISPATCHED) { edit() }
			try {
				withTimeout(5000) { operations.queued.await() }
				transport.loginBody = """{"status":"success","context":{"session_token":"replacement-fixture-$index"}}"""
				transport.profileId = index.toLong() + 2
				repo.signIn("replacement-user", "fixture password")
				val requests = transport.requests.size
				operations.unlock()
				try { withTimeout(5000) { waiting.await() }; fail("Expected stale initiating account") } catch (e: CancellationException) { if (e is TimeoutCancellationException) throw e }
				assertEquals("A waiting edit must send no replacement-account requests", requests, transport.requests.size)
				assertEquals(original.targetId, db.getScrobblingDao().find(6, 41)?.targetId)
				assertEquals(original.chapter, db.getScrobblingDao().find(6, 41)?.chapter)
			} finally {
				if (operations.isLocked) operations.unlock()
				waiting.cancelAndJoin()
			}
		}
		assertTrue(mutations().isEmpty())
		assertEquals(0.8f, db.getScrobblingDao().find(6, 41)!!.rating, 0f)
	}

	@Test fun anEditCapturedAtTheUiEventCannotAdoptASessionBeforeTheJobStarts() = runBlocking {
		val repo = repository(); linked(repo)
		val edit = repo.captureEdit(41, target)!!
		transport.profileId = 2
		transport.loginBody = """{"status":"success","context":{"session_token":"replacement-fixture"}}"""
		repo.signIn("replacement-user", "fixture password")
		val requests = transport.requests.size
		try { repo.updateRate(edit, 0.6f, "complete"); fail("Expected stale UI context") } catch (_: CancellationException) { }
		assertEquals(requests, transport.requests.size)
		assertEquals("read", db.getScrobblingDao().find(6, 41)?.status)
		assertNull(repo.captureEdit(41, target, edit.ticket.generation))
	}

	@Test fun associationReplacementWhileAnEditWaitsCannotRedirectItsMutation() = runBlocking {
		val operations = ObservedMutex()
		val repo = repository(operations = operations); linked(repo)
		val original = db.getScrobblingDao().find(6, 41)!!
		val edits: List<suspend () -> Unit> = listOf(
			{ repo.updateRate(0, 41, 0.6f, "complete", null, false) },
			{ repo.updateRate(0, 41, 60) },
			{ repo.updateVolume(41, 8) },
			{ repo.createRate(41, target) },
			{ repo.refreshRate(original) },
		)
		for (edit in edits) {
			db.getScrobblingDao().upsert(original)
			operations.queued = CompletableDeferred(); operations.lock()
			val waiting = async(start = CoroutineStart.UNDISPATCHED) { edit() }
			try {
				withTimeout(5000) { operations.queued.await() }
				db.getScrobblingDao().upsert(ScrobblingEntity(6, 0, 41, target + 1, "wish", 2, null, 0.2f))
				val requests = transport.requests.size
				operations.unlock()
				try { withTimeout(5000) { waiting.await() }; fail("Expected stale association") } catch (e: CancellationException) { if (e is TimeoutCancellationException) throw e }
				assertEquals(requests, transport.requests.size)
				assertEquals(target + 1, db.getScrobblingDao().find(6, 41)?.targetId)
				assertEquals(2, db.getScrobblingDao().find(6, 41)?.chapter)
			} finally {
				if (operations.isLocked) operations.unlock()
				waiting.cancelAndJoin()
			}
		}
		assertTrue(mutations().isEmpty())
	}

	@Test fun aCapturedUiEditCannotAdoptAnAssociationBeforeTheJobStarts() = runBlocking {
		val repo = repository(); linked(repo)
		val edit = repo.captureEdit(41, target)!!
		db.getScrobblingDao().upsert(ScrobblingEntity(6, 0, 41, target + 1, "wish", 2, null, 0.2f))
		val requests = transport.requests.size
		try { repo.updateRate(edit, 0.6f, "complete"); fail("Expected stale UI association") } catch (_: CancellationException) { }
		assertEquals(requests, transport.requests.size)
		assertEquals(target + 1, db.getScrobblingDao().find(6, 41)?.targetId)
		assertEquals("wish", db.getScrobblingDao().find(6, 41)?.status)
	}

	@Test fun aLowerReaderUpdateRetriesTheHigherUnsynchronizedChapter() = runBlocking {
		val repo = repository(); linked(repo)
		transport.failWrites = 2
		repo.enqueueProgress(41, 60, false)
		withTimeout(5000) { repo.failedProgress.first { 41L in it } }
		assertEquals(31, db.getScrobblingDao().find(6, 41)?.chapter)
		repo.enqueueProgress(41, 40, false)
		withTimeout(5000) { db.getScrobblingDao().observe(6, 41).first { it?.chapter == 60 } }
		assertEquals(60, transport.remote!!.getValue("status").jsonObject.getValue("chapter").jsonPrimitive.int)
		assertTrue(repo.failedProgress.value.isEmpty())
		assertEquals(target, db.getScrobblingDao().find(6, 41)?.targetId)
	}

	@Test fun associationChangesDuringAReadAreRecheckedBeforeAListOrRatingMutation() = runBlocking {
		val repo = repository(); linked(repo)
		val original = db.getScrobblingDao().find(6, 41)!!
		for (path in listOf("/v1/lists/series/$target", "/v1/series/$target/rating")) {
			db.getScrobblingDao().upsert(original)
			transport.beforeReply = { request ->
				if (request.path == path) runBlocking {
					db.getScrobblingDao().upsert(ScrobblingEntity(6, 0, 41, target + 1, "wish", 2, null, 0.2f))
				}
			}
			try { repo.updateRate(0, 41, 0.6f, if (path.contains("lists/series")) "complete" else null, null, false); fail("Expected stale association") } catch (_: CancellationException) { }
			assertTrue(mutations().isEmpty())
			assertTrue(transport.requests.none { it.method in setOf("PUT", "DELETE") && it.path.endsWith("/rating") })
		}
		transport.beforeReply = null
	}

	@Test fun selectorRejectsAnAssociationReplacedWhileHistoryIsSuspended() = runBlocking {
		selectorLink(target, { replaceSelectorAssociation(target + 1) }, target + 1, stale = true)
	}

	@Test fun selectorPreservesAnAssociationCreatedWhileAnAbsenceSnapshotIsSuspended() = runBlocking {
		selectorLink(null, { replaceSelectorAssociation(target + 1) }, target + 1, stale = true)
	}

	@Test fun selectorDoesNotRecreateAnAssociationRemovedWhileHistoryIsSuspended() = runBlocking {
		selectorLink(target, { db.getScrobblingDao().delete(6, 41) }, null, stale = true)
	}

	@Test fun selectorStillAdoptsAnUnchangedAssociationAfterHistorySuspension() = runBlocking {
		selectorLink(target, {}, target, stale = false)
	}

	@Test fun selectorStillCreatesALinkFromAnUnchangedAbsenceSnapshot() = runBlocking {
		selectorLink(null, {}, target, stale = false, remoteExists = false)
	}

	@Test fun selectorAllowsAnExplicitRemapFromAnUnchangedDifferentTarget() = runBlocking {
		selectorLink(target + 1, {}, target, stale = false)
	}

	@Test fun selectorRechecksItsSnapshotAfterRemoteAdoptionBeforePersistence() = runBlocking {
		selectorLink(target, {
			transport.beforeReply = { request ->
				if (request.path == "/v1/lists/series/$target") runBlocking { replaceSelectorAssociation(target + 1) }
			}
		}, target + 1, stale = true, allowReads = true)
	}

	private suspend fun replaceSelectorAssociation(targetId: Long) {
		db.getScrobblingDao().upsert(ScrobblingEntity(6, 0, 41, targetId, "hold", 12, null, 0.4f))
	}

	private fun assertAssociationUnchanged(expected: ScrobblingEntity?, actual: ScrobblingEntity?) {
		if (expected == null) {
			assertNull(actual)
			return
		}
		assertNotNull(actual)
		val persisted = actual!!
		assertEquals(expected.scrobbler, persisted.scrobbler)
		assertEquals(expected.id, persisted.id)
		assertEquals(expected.mangaId, persisted.mangaId)
		assertEquals(expected.targetId, persisted.targetId)
		assertEquals(expected.status, persisted.status)
		assertEquals(expected.chapter, persisted.chapter)
		assertEquals(expected.comment, persisted.comment)
		assertEquals(expected.rating, persisted.rating, 0f)
	}

	private suspend fun selectorLink(
		initialTarget: Long?, whilePaused: suspend () -> Unit, expectedTarget: Long?, stale: Boolean,
		remoteExists: Boolean = true, allowReads: Boolean = false,
	) = coroutineScope {
		val repo = repository(); manga()
		if (initialTarget != null) replaceSelectorAssociation(initialTarget)
		if (!remoteExists) { transport.remote = null; transport.rating = null }
		val sourceManga = Manga(
			id = 41, title = "Source title", altTitles = emptySet(), url = "file:///fixture-41.cbz", publicUrl = "file:///fixture-41.cbz",
			rating = 0f, contentRating = null, coverUrl = null, largeCoverUrl = null, description = null, tags = emptySet(),
			state = null, authors = emptySet(), chapters = null, source = LocalMangaSource,
		)
		val enteredHistory = CompletableDeferred<Unit>()
		val resumeHistory = CompletableDeferred<Unit>()
		val observedLoading = CompletableDeferred<Unit>()
		val viewModels = ViewModelStore()
		val selector = withContext(Dispatchers.Main) {
			ScrobblingSelectorViewModel(
				SavedStateHandle(mapOf(AppRouter.KEY_MANGA to ParcelableManga(sourceManga))),
				setOf(MangaUpdatesScrobbler(repo, db, mangaRepositoryFactory)),
				readHistory = { seed ->
					enteredHistory.complete(Unit)
					resumeHistory.await()
					assertNull(db.getHistoryDao().find(seed.id))
					null
				}, database = db,
			).also { viewModels.put("selector", it) }
		}
		var completed: Deferred<Boolean>? = null
		try {
			withTimeout(5000) {
				assertEquals(initialTarget, selector.mangaUpdatesAssociation.first { it != null }!!.targetId)
				selector.content.first { !selector.isEmpty }
			}
			val finish = async(start = CoroutineStart.UNDISPATCHED) {
				selector.isLoading.first { it }
				observedLoading.complete(Unit)
				selector.isLoading.first { !it }
			}
			completed = finish
			withContext(Dispatchers.Main) { selector.selectItem(target); selector.onDoneClick() }
			withTimeout(5000) { enteredHistory.await(); observedLoading.await() }
			whilePaused()
			val before = db.getScrobblingDao().find(6, 41)
			val requests = transport.requests.size
			val remote = transport.remote
			resumeHistory.complete(Unit)
			withTimeout(5000) { finish.await() }
			val after = db.getScrobblingDao().find(6, 41)
			assertEquals(expectedTarget, after?.targetId)
			if (stale) {
				if (!allowReads) assertEquals("Stale selector must stop before provider requests", requests, transport.requests.size)
				assertTrue(mutations().isEmpty())
				assertTrue(transport.requests.none { it.method in setOf("PUT", "DELETE") && it.path.endsWith("/rating") })
				assertEquals(remote, transport.remote)
				if (!allowReads) assertAssociationUnchanged(before, after)
				else assertAssociationUnchanged(ScrobblingEntity(6, 0, 41, target + 1, "hold", 12, null, 0.4f), after)
			} else {
				assertNotNull(after)
				assertEquals(if (remoteExists) 31 else 0, after!!.chapter)
				if (!remoteExists) assertEquals(1, mutations().size)
			}
			assertEquals(0, count("favourites")); assertEquals(0, count("private_favourites"))
		} finally {
			resumeHistory.complete(Unit)
			withContext(Dispatchers.Main) { viewModels.clear() }
			selector.viewModelScope.coroutineContext[Job]?.join()
			completed?.cancelAndJoin()
			transport.beforeReply = null
		}
	}

	private class ObservedMutex(private val delegate: Mutex = Mutex()) : Mutex by delegate {
		var queued = CompletableDeferred<Unit>()
		override suspend fun lock(owner: Any?) {
			if (delegate.isLocked) queued.complete(Unit)
			delegate.lock(owner)
		}
	}

	private fun count(table: String): Int = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }

	private class Sessions : MangaUpdatesSessionStore {
		override val generation = MutableStateFlow(0L)
		private var value: MangaUpdatesSession? = null
		@Synchronized override fun snapshot() = value
		@Synchronized override fun save(token: String, user: ScrobblerUser, expectedGeneration: Long): Boolean { if (expectedGeneration != generation.value) return false; generation.value++; value = MangaUpdatesSession(token, user, generation.value); return true }
		@Synchronized override fun clear(expectedGeneration: Long?): Boolean { if (expectedGeneration != null && expectedGeneration != generation.value) return false; generation.value++; value = null; return true }
	}
	private data class Recorded(val path: String, val method: String, val authorization: String?, val body: JsonElement?)
	private class Transport(var id: Long) : Interceptor {
		val requests: MutableList<Recorded> = Collections.synchronizedList(mutableListOf())
		var remote: JsonObject? = state()
		var rating: Float? = 8f
		var loginCode = 200
		var loginBody = """{"status":"success","context":{"session_token":"fixture-session"}}"""
		var profileCode = 200
		var profileId = 1L
		var searchBody: String? = null
		var failWrites = 0
		var failureCode = 503
		var loseAcknowledgement = false
		var beforeReply: ((Recorded) -> Unit)? = null
		private fun root(body: String) = Json.parseToJsonElement(body).jsonObject
		fun state(chapter: Int = 31, volume: Int = 6, listId: Long = 9000, type: String = "read", priority: Int = 2) = root("""{"series":{"id":$id},"list_id":$listId,"list_type":"$type","status":{"chapter":$chapter,"volume":$volume},"priority":$priority}""")
		private val lists = """[{"list_id":42,"type":"read","custom":false},{"list_id":77,"type":"wish","custom":false},{"list_id":88,"type":"complete","custom":false},{"list_id":99,"type":"hold","custom":false},{"list_id":111,"type":"unfinished","custom":false},{"list_id":9000,"type":"read","custom":true}]"""
		@Synchronized override fun intercept(chain: Interceptor.Chain): Response {
			val request = chain.request()
			val text = request.body?.let { Buffer().also(it::writeTo).readUtf8() }.orEmpty()
			val recorded = Recorded(request.url.encodedPath, request.method, request.header("Authorization"), text.takeIf { it.isNotBlank() }?.let(Json::parseToJsonElement))
			requests += recorded; beforeReply?.invoke(recorded)
			var code = 200
			val body = when {
				recorded.path == "/v1/account/login" -> { code = loginCode; loginBody }
				recorded.path == "/v1/account/profile" -> { code = profileCode; """{"user_id":$profileId,"username":"Fixture User"}""" }
				recorded.path == "/v1/account/logout" -> "{}"
				recorded.path == "/v1/lists" -> lists
				recorded.path == "/v1/series/search" -> searchBody ?: """{"results":[{"record":{"series_id":$id,"title":"Fixture title","url":"https://www.mangaupdates.com/series/fixture","image":{"url":{"original":"https://image.invalid/cover"}}}}]}"""
				recorded.path == "/v1/series/$id" -> """{"series_id":$id,"title":"Fixture title","url":"https://www.mangaupdates.com/series/fixture","latest_chapter":700,"authors":[{"author_id":9545965743,"name":"Creator","type":"Author"},{"author_id":9545965743,"name":"Creator","type":"Artist"}],"recommendations":[{"series_id":70994361491,"series_name":"Other title","series_url":"https://www.mangaupdates.com/series/other"}]}"""
				recorded.path == "/v1/authors/9545965743" -> """{"id":9545965743,"name":"Creator","image":{"url":{"original":"https://image.invalid/author"}}}"""
				recorded.path == "/v1/series/$id/rating" -> when (recorded.method) {
					"PUT" -> { rating = recorded.body!!.jsonObject.getValue("rating").jsonPrimitive.float; "{}" }
					"DELETE" -> { rating = null; "{}" }
					else -> rating?.let { """{"rating":$it}""" } ?: "{}".also { code = 404 }
				}
				recorded.path == "/v1/lists/series/$id" -> remote?.toString() ?: "{}".also { code = 404 }
				recorded.path in setOf("/v1/lists/series", "/v1/lists/series/update") -> {
					if (failWrites > 0) { failWrites--; code = failureCode } else {
						val update = recorded.body!!.jsonArray.single().jsonObject
						assertEquals(id, update.getValue("series").jsonObject.getValue("id").jsonPrimitive.long)
						assertEquals(setOf("series", "list_id", "status", "priority"), update.keys)
						val listId = update.getValue("list_id").jsonPrimitive.long
						val type = Json.parseToJsonElement(lists).jsonArray.single { it.jsonObject.getValue("list_id").jsonPrimitive.long == listId }.jsonObject.getValue("type").jsonPrimitive.content
						val status = update.getValue("status").jsonObject
						remote = state(status.getValue("chapter").jsonPrimitive.int, status.getValue("volume").jsonPrimitive.int, listId, type, update.getValue("priority").jsonPrimitive.int)
						if (loseAcknowledgement) { loseAcknowledgement = false; throw IOException("Fixture acknowledgement lost") }
					}
					"{}"
				}
				else -> throw IOException("Unexpected fixture route")
			}
			return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(body.toResponseBody("application/json".toMediaType())).build()
		}
	}
}
