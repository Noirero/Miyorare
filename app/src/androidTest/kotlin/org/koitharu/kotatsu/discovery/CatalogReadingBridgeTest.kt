package org.koitharu.kotatsu.discovery

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.alternatives.domain.AlternativeSearchEvent
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.discovery.data.*
import org.koitharu.kotatsu.discovery.domain.*
import org.koitharu.kotatsu.discovery.ui.*
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.tsuki.model.TsukiPluginProvider
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService as Service
import javax.inject.Inject

/** Production wire adapter -> repository -> ViewModel -> explicit source bridge, without a network. */
@HiltAndroidTest
class CatalogReadingBridgeTest {
	@get:Rule val hilt = HiltAndroidRule(this)
	@Inject lateinit var accountRepository: TrackerCatalogAccountRepository

	private val instrumentation = InstrumentationRegistry.getInstrumentation()
	private val app = instrumentation.targetContext
	private val context = object : ContextWrapper(app) { override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("hub-bridge-$name", mode) }
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val store = ViewModelStore()
	private val key = TrackerCatalogKey(Service.ANILIST, CatalogMediaKind.MANGA, "42")
	private lateinit var settings: AppSettings
	private lateinit var vm: TrackerCatalogViewModel
	private lateinit var repository: TrackerCatalogRepository
	private var providerCalls = 0
	private val resolver = Resolver()
	private class Resolver : CatalogSourceResolution {
		var known = emptyList<Manga>(); var candidates = emptyList<Manga>(); var searches = 0; var allowed = true
		var gate: CompletableDeferred<Unit>? = null
		val scopes = java.util.concurrent.CopyOnWriteArrayList<CatalogSearchScope>()
		val cancelled = CompletableDeferred<Unit>()
		override suspend fun known(key: TrackerCatalogKey) = known
		override suspend fun canOpen(manga: Manga, kind: CatalogMediaKind) = allowed
		override suspend fun availableSources(kind: CatalogMediaKind) = emptyList<org.koitharu.kotatsu.discovery.domain.CatalogReadingSource>()
		override suspend fun search(details: TrackerCatalogDetails, scope: org.koitharu.kotatsu.discovery.domain.CatalogSearchScope): CatalogReadingSearch = CatalogReadingSearch(flow {
			searches++
			scopes += scope
			val blocked = if (scope == CatalogSearchScope.Preferred) gate else null
			try {
				blocked?.await()
				for (candidate in candidates) emit(AlternativeSearchEvent.Result(candidate))
			} finally {
				if (blocked != null) cancelled.complete(Unit)
			}
		}, canExpand = scope == CatalogSearchScope.Preferred)
	}
	@Before fun setup() {
		hilt.inject()
		settings = AppSettings(context); settings.isNsfwContentDisabled = true
		val http = CatalogHttp(OkHttpClient.Builder().addInterceptor { chain ->
			providerCalls++
			val query = okio.Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
			val media = """{"id":42,"type":"MANGA","format":"MANGA","isAdult":false,"title":{"userPreferred":"Catalogue title"}}"""
			val body = if (query.contains("Media(id:")) """{"data":{"Media":$media}}""" else """{"data":{"Page":{"media":[$media],"pageInfo":{"hasNextPage":false}}}}"""
			Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(body.toResponseBody()).build()
		}.build())
		repository = TrackerCatalogRepository(setOf(AniListCatalogProvider(http)), object : CatalogSnapshotStore {
			override fun read(key: CatalogHomeRequest, now: Long): TrackerCatalogHome? = null
			override fun write(key: CatalogHomeRequest, home: TrackerCatalogHome) = true
		}, scope)
		val saved = SavedStateHandle(mapOf(CatalogArguments.MODE to CatalogScreenMode.DETAILS.name, CatalogArguments.SERVICE to key.service.name, CatalogArguments.KIND to key.kind.name, CatalogArguments.ID to key.remoteId))
		instrumentation.runOnMainSync {
			vm = ViewModelProvider(store, object : ViewModelProvider.Factory { @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = TrackerCatalogViewModel(saved, repository, accountRepository, resolver, settings) as T })[TrackerCatalogViewModel::class.java]
		}
		waitFor { vm.state.value.details != null }
	}
	@After fun cleanup() { instrumentation.runOnMainSync { store.clear() }; scope.cancel(); context.getSharedPreferences("${app.packageName}_preferences", Context.MODE_PRIVATE).edit().clear().commit() }
	private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
	private fun waitFor(test: () -> Boolean) = runBlocking { withTimeout(5000) { while (!test()) delay(10) } }

	@Test fun detailIsMetadataOnlyUntilReadingCtaAndWeakSingleMatchRequiresChoice() = runBlocking {
		assertEquals("Catalogue title", vm.state.value.details!!.item.title); assertEquals(0, resolver.searches)
		resolver.candidates = listOf(SampleData.manga)
		val navigation = async(Dispatchers.Default) { vm.openSource.first() }
		main { vm.read() }; waitFor { !vm.state.value.resolution.searching && vm.state.value.resolution.candidates.isNotEmpty() }
		assertFalse(navigation.isCompleted); assertEquals(1, providerCalls)
		main { vm.chooseSource(SampleData.manga) }; assertEquals(SampleData.manga, withTimeout(5000) { navigation.await() })
	}
	@Test fun ambiguousCandidatesCannotOpenWithoutOwnerSelection() = runBlocking {
		resolver.candidates = listOf(SampleData.manga, SampleData.manga.copy(id = 123L))
		val navigation = async(Dispatchers.Default) { vm.openSource.first() }
		try {
			main { vm.read() }; waitFor { vm.state.value.resolution.candidates.size == 2 && !vm.state.value.resolution.searching }
			assertFalse(navigation.isCompleted)
		} finally { navigation.cancelAndJoin() }
	}
	@Test fun noSourceLeavesMetadataIntactAndAllSourceRetryIsExplicit() {
		main { vm.read() }; waitFor { resolver.searches == 1 && !vm.state.value.resolution.searching }
		assertTrue(vm.state.value.resolution.candidates.isEmpty()); assertNotNull(vm.state.value.details)
		assertTrue(vm.state.value.resolution.canExpand)
		main { vm.startResolution(CatalogSearchScope.AllEnabled) }; waitFor { resolver.searches == 2 && !vm.state.value.resolution.searching }
		assertEquals(CatalogSearchScope.AllEnabled, vm.state.value.resolution.scope)
		assertFalse(vm.state.value.resolution.canExpand)
	}
	@Test fun exactPersistedSourceOpensWithoutExtensionFanoutAndIsRecheckedAtClick() = runBlocking {
		resolver.known = listOf(SampleData.manga)
		val navigation = async(Dispatchers.Default) { vm.openSource.first() }
		main { vm.read() }; assertEquals(SampleData.manga, withTimeout(5000) { navigation.await() }); assertEquals(0, resolver.searches)
	}
	@Test fun closingSourceResolutionCancelsWorkAndPreventsLateCandidates() {
		val gate = CompletableDeferred<Unit>(); resolver.gate = gate; resolver.candidates = listOf(SampleData.manga)
		main { vm.read() }; waitFor { resolver.searches == 1 }
		main { vm.closeResolution() }; gate.complete(Unit)
		assertFalse(vm.state.value.resolution.visible); assertTrue(vm.state.value.resolution.candidates.isEmpty())
	}
	@Test fun switchingToSingleSourceCancelsTheOldScopeAndOnlyShowsNewResults() = runBlocking {
		val gate = CompletableDeferred<Unit>()
		resolver.gate = gate
		main { vm.read() }
		waitFor { resolver.scopes.size == 1 }
		val selected = SampleData.manga.copy(id = 123L)
		resolver.candidates = listOf(selected)
		val single = CatalogSearchScope.Single(selected.source.name)
		main { vm.startResolution(single, "Selected source") }
		withTimeout(5000) { resolver.cancelled.await() }
		gate.complete(Unit)
		waitFor { !vm.state.value.resolution.searching && vm.state.value.resolution.candidates.isNotEmpty() }
		assertEquals(listOf(CatalogSearchScope.Preferred, single), resolver.scopes.toList())
		assertEquals(listOf(selected), vm.state.value.resolution.candidates)
		assertEquals(single, vm.state.value.resolution.scope)
	}
	@Test fun singleScopeBuildsAnExclusivePoolFromEnabledCompatibleSources() {
		val selected = readingSourceFixture(TsukiPluginProvider.MIYORARE)
		val other = readingSourceFixture(TsukiPluginProvider.GEKKOUSHI)
		assertTrue(selectCatalogSource(listOf(LocalMangaSource), CatalogMediaKind.MANGA, LocalMangaSource.name).isEmpty())
		assertEquals(listOf(selected), selectCatalogSource(listOf(other, selected), CatalogMediaKind.MANGA, selected.name))
		assertTrue(selectCatalogSource(listOf(other), CatalogMediaKind.MANGA, selected.name).isEmpty())
		assertTrue(selectCatalogSource(listOf(selected), CatalogMediaKind.NOVEL, selected.name).isEmpty())
	}
	@Test fun privacyEligibilityIsRecheckedBeforeChosenSourceCanOpen() = runBlocking {
		resolver.candidates = listOf(SampleData.manga)
		val navigation = async(Dispatchers.Default) { vm.openSource.first() }
		try {
			main { vm.read() }; waitFor { vm.state.value.resolution.candidates.isNotEmpty() }
			resolver.allowed = false; main { vm.chooseSource(SampleData.manga) }
			assertFalse(navigation.isCompleted); assertTrue(vm.state.value.resolution.visible)
		} finally { navigation.cancelAndJoin() }
	}
	@Test fun editingMetadataQueryInvalidatesItsOldResultsAndPaginationWithoutStartingNewSourceWork() {
		lateinit var search: TrackerCatalogViewModel
		main {
			search = TrackerCatalogViewModel(SavedStateHandle(mapOf(CatalogArguments.MODE to CatalogScreenMode.SEARCH.name, CatalogArguments.SERVICE to Service.ANILIST.name, CatalogArguments.QUERY to "first")), repository, accountRepository, resolver, settings)
			store.put("metadata-search", search)
		}
		waitFor { search.state.value.items.isNotEmpty() }
		val before = providerCalls
		main { search.updateQuery("second"); search.nextPage() }
		assertTrue(search.state.value.items.isEmpty()); assertNull(search.state.value.nextPage); assertFalse(search.state.value.searched)
		assertEquals(before, providerCalls); assertEquals(0, resolver.searches)
		main { search.reload() }; waitFor { search.state.value.items.isNotEmpty() && !search.state.value.loading }
		assertTrue(search.state.value.searched); assertEquals("second", search.state.value.query); assertEquals(before + 1, providerCalls)
	}
}
