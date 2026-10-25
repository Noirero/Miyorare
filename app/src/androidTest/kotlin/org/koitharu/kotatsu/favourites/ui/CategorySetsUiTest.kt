package org.koitharu.kotatsu.favourites.ui

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.EditText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.work.Configuration
import androidx.work.WorkManager
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.*
import org.junit.Assert.*
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.NavItem
import org.koitharu.kotatsu.favourites.data.*
import org.koitharu.kotatsu.favourites.domain.*
import org.koitharu.kotatsu.favourites.ui.categories.FavouriteCategoriesActivity
import org.koitharu.kotatsu.favourites.ui.categories.edit.FavouritesCategoryEditActivity
import org.koitharu.kotatsu.favourites.ui.categories.select.FavoriteDialog
import org.koitharu.kotatsu.favourites.ui.container.FavouritesContainerFragment
import org.koitharu.kotatsu.favourites.ui.container.FavouritesContainerViewModel
import org.koitharu.kotatsu.favourites.ui.list.FavouritesListFragment
import org.koitharu.kotatsu.favourites.ui.list.FavouritesListViewModel
import org.koitharu.kotatsu.list.domain.ListSortOrder
import org.koitharu.kotatsu.main.ui.MainActivity
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

/** Exercises the production dialogs and Favourites with the owner's 80 / 15 category library. */
@HiltAndroidTest
class CategorySetsUiTest {
	@get:Rule(order = 0) val hilt = HiltAndroidRule(this)
	@get:Rule(order = 1) val compose = createEmptyComposeRule()
	@Inject lateinit var database: MangaDatabase
	@Inject lateinit var repository: FavouritesRepository
	@Inject lateinit var types: FavouriteContentTypeStore
	@Inject lateinit var selection: FavouriteSetSelectionStore
	@Inject lateinit var settings: AppSettings
	@Inject lateinit var quickFilters: FavouriteQuickFilterStore
	@Inject lateinit var sourceFilters: FavouriteSourceFilterStore
	@Inject lateinit var display: FavouriteDisplayPreferences
	@Inject lateinit var imageLoaderProvider: javax.inject.Provider<coil3.ImageLoader>
	private val instrumentation = InstrumentationRegistry.getInstrumentation()
	private val context get() = instrumentation.targetContext
	private val manga get() = SampleData.manga.copy(id = 9_900_001L, title = "SET_ONLY_MAIN", source = MangaSource("MIHON_123"), coverUrl = "", tags = emptySet(), contentRating = null)
	private val otherManga get() = manga.copy(id = 9_900_002L, title = "SET_ONLY_SECOND")
	private val sets = HashMap<FavouriteSpace, List<FavouriteCategorySetEntity>>()
	private val categoryIds = HashMap<FavouriteSpace, List<List<Long>>>()

	@Before fun prepare() = runBlocking {
		hilt.inject()
		// HiltTestApplication omits the production background warm-up. Build the real image/network
		// graph on the instrumentation thread before an Activity injects it on the main thread.
		imageLoaderProvider.get()
		runCatching { WorkManager.getInstance(context) }.getOrElse { WorkManager.initialize(context, Configuration.Builder().build()) }
		database.clearAllTables()
		context.getSharedPreferences("normal_favourite_category_memory", 0).edit().clear().commit()
		context.getSharedPreferences("favourite_set_navigation", 0).edit().clear().commit()
		types.removeCategories(types.novelCategoryIds.value)
		settings.isOnboardingCompleted = true
		settings.setMiyorareDesignStyle(org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle.MODERN)
		display.setShowCategoryTabs(FavouriteContentType.MANGA, true)
		display.setShowCategoryTabs(FavouriteContentType.NOVEL, true)
		settings.isAllFavouritesVisible = true
		settings.mainNavItems = listOf(NavItem.FAVORITES, NavItem.UPDATED, NavItem.HISTORY, NavItem.EXPLORE)
		for (space in FavouriteSpace.entries) {
			types.setSelectedType(FavouriteContentType.MANGA, space)
			for (type in FavouriteContentType.entries) { quickFilters.clear(type, space); sourceFilters.clear(type, space) }
			display.setVirtualCategoryVisible(space, FavouriteContentType.MANGA, DOWNLOADED_FAVOURITES_CATEGORY_ID, true)
			display.setVirtualCategoryVisible(space, FavouriteContentType.MANGA, LOCAL_FAVOURITES_CATEGORY_ID, true)
			val first = repository.getSets(space).first()
			val second = repository.createSet("Second", space)
			sets[space] = listOf(first, second)
			categoryIds[space] = listOf(first, second).mapIndexed { setIndex, set ->
				List(if (setIndex == 0) 80 else 15) { index ->
					repository.createCategory("${if (setIndex == 0) "U" else "S"} ${index.toString().padStart(3, '0')}", ListSortOrder.NEWEST, false, false, true, space, set.setId).id
				}
			}
			selection.select(first, space, FavouriteContentType.MANGA)
		}
		types.activateSpace(FavouriteSpace.NORMAL)
	}
	private fun launch(space: FavouriteSpace = FavouriteSpace.NORMAL) = ActivityScenario.launch<FavouriteCategoriesActivity>(Intent(context, FavouriteCategoriesActivity::class.java).putExtra(EXTRA_FAVOURITE_SPACE, space.dbValue))
	private fun showPicker(scenario: ActivityScenario<FavouriteCategoriesActivity>, space: FavouriteSpace, multi: Boolean = false) {
		scenario.onActivity { activity ->
			FavoriteDialog().apply { arguments = Bundle().apply {
				putParcelableArrayList(AppRouter.KEY_MANGA_LIST, arrayListOf(ParcelableManga(manga)).also { if (multi) it += ParcelableManga(otherManga) })
				putInt(EXTRA_FAVOURITE_SPACE, space.dbValue)
			} }.showNow(activity.supportFragmentManager, "category_sets_picker")
		}
		awaitPickerWindow(scenario)
		await { compose.onAllNodesWithTag("category-pages").fetchSemanticsNodes().isNotEmpty() }
	}
	private fun awaitPickerWindow(scenario: ActivityScenario<FavouriteCategoriesActivity>) = await {
		var ready = false
		scenario.onActivity { activity ->
			val picker = activity.supportFragmentManager.findFragmentByTag("category_sets_picker") as? FavoriteDialog
			ready = picker?.dialog?.window?.decorView?.hasWindowFocus() == true && picker.isResumed
		}
		ready
	}
	private fun await(condition: () -> Boolean) = compose.waitUntil(30_000, condition)
	private fun checkbox(id: Long) = compose.onNodeWithTag("category-checkbox:$id", useUnmergedTree = true)
	private fun choose(title: String, current: String) {
		compose.onNodeWithText(context.getString(R.string.category_set_selector, current)).performClick()
		compose.onNodeWithText(title, useUnmergedTree = true).performClick()
	}
	private fun pages(): List<Int> = Regex("\\d+").findAll(compose.onNodeWithTag("category-pages").fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()).map { it.value.toInt() }.toList()
	private fun memberships(space: FavouriteSpace = FavouriteSpace.NORMAL) = runBlocking { repository.getCategoriesIds(manga.id, space) }
	private fun closed(scenario: ActivityScenario<FavouriteCategoriesActivity>) {
		try {
			await {
				var removed = false
				scenario.onActivity { removed = it.supportFragmentManager.findFragmentByTag("category_sets_picker") == null }
				removed
			}
		} catch (error: ComposeTimeoutException) {
			var state = ""
			scenario.onActivity { activity ->
				val picker = activity.supportFragmentManager.findFragmentByTag("category_sets_picker") as? FavoriteDialog
				if (picker != null) {
					val model = ViewModelProvider(picker)[org.koitharu.kotatsu.favourites.ui.categories.select.FavoriteDialogViewModel::class.java]
					state = "resumed=${picker.isResumed}, focus=${picker.dialog?.window?.decorView?.hasWindowFocus()}, saving=${model.isSaving.value}, saved=${model.onSaved.value}, error=${model.onError.value}"
				}
			}
			throw AssertionError("Picker did not dismiss: $state", error)
		}
	}

	@Test fun ownerScenarioPendingStateIndependentPagesOkCancelAndRecreation() {
		val space = FavouriteSpace.NORMAL
		val first = categoryIds.getValue(space)[0]
		val second = categoryIds.getValue(space)[1]
		launch().use { scenario ->
			showPicker(scenario, space)
			val count = pages().last()
			assertTrue(count >= 4)
			compose.onNodeWithText(context.getString(android.R.string.ok)).assertIsDisplayed().assertIsEnabled()
			compose.onNodeWithContentDescription(context.getString(R.string.title_over_cover)).performClick()
			await { pages().last() >= count }
			compose.onNodeWithText(context.getString(android.R.string.ok)).assertIsDisplayed().assertIsEnabled()
			compose.onNodeWithContentDescription(context.getString(R.string.title_over_cover)).performClick()
			await { pages().last() == count }
			checkbox(first[0]).performClick()
			compose.onNodeWithTag("category-pages").performTouchInput { swipeLeft() }
			await { pages().first() == 2 }
			choose("Second", "Utama")
			await { compose.onAllNodesWithTag("category-checkbox:${second[0]}", true).fetchSemanticsNodes().isNotEmpty() }
			assertEquals(listOf(1, 1), pages())
			checkbox(second[0]).performClick()
			assertTrue(memberships().isEmpty())
			choose("Utama", "Second")
			await { pages().first() == 2 && pages().last() == count }
			compose.onNodeWithTag("category-pages").performTouchInput { swipeRight() }
			await { pages().first() == 1 }
			checkbox(first[0]).assertIsOn()
			scenario.recreate()
			awaitPickerWindow(scenario)
			await { compose.onAllNodesWithTag("category-checkbox:${first[0]}", true).fetchSemanticsNodes().isNotEmpty() }
			checkbox(first[0]).assertIsOn()
			compose.onNodeWithText(context.getString(android.R.string.ok)).assertIsDisplayed().assertIsEnabled().performClick()
			closed(scenario)
			assertEquals(setOf(first[0], second[0]), memberships())
			showPicker(scenario, space)
			checkbox(first[0]).assertIsOn()
			checkbox(first[1]).performClick()
			choose("Second", "Utama")
			await { compose.onAllNodesWithTag("category-checkbox:${second[1]}", true).fetchSemanticsNodes().isNotEmpty() }
			checkbox(second[0]).assertIsOn()
			checkbox(second[1]).performClick()
			compose.onNodeWithText(context.getString(android.R.string.cancel)).performClick()
			closed(scenario)
			assertEquals(setOf(first[0], second[0]), memberships())
		}
	}

	@Test fun privateEquivalentHasIndependentSetsMembershipAndSecureWindow() {
		val space = FavouriteSpace.PRIVATE
		val first = categoryIds.getValue(space)[0][0]
		val second = categoryIds.getValue(space)[1][0]
		launch(space).use { scenario ->
			showPicker(scenario, space)
			scenario.onActivity { activity ->
				val dialog = activity.supportFragmentManager.findFragmentByTag("category_sets_picker") as FavoriteDialog
				assertTrue(dialog.dialog!!.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
			}
			assertTrue(pages().last() >= 4)
			checkbox(first).performClick()
			choose("Second", "Utama")
			await { compose.onAllNodesWithTag("category-checkbox:$second", true).fetchSemanticsNodes().isNotEmpty() }
			assertEquals(listOf(1, 1), pages())
			checkbox(second).performClick()
			compose.onNodeWithText(context.getString(android.R.string.ok)).assertIsDisplayed().assertIsEnabled().performClick()
			closed(scenario)
			assertEquals(setOf(first, second), memberships(space))
			assertTrue(memberships(FavouriteSpace.NORMAL).isEmpty())
		}
	}

	@Test fun searchHeaderAndMultiSelectIndeterminateRemainSetScoped() {
		val space = FavouriteSpace.NORMAL
		val first = categoryIds.getValue(space)[0][0]
		val second = categoryIds.getValue(space)[1][0]
		runBlocking { repository.updateCategoryMemberships(listOf(manga), mapOf(first to true)) }
		launch().use { scenario ->
			showPicker(scenario, space, multi = true)
			checkbox(first).assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Indeterminate))
			checkbox(first).performClick()
			compose.onNodeWithText(context.getString(R.string.category_set_search)).performTextInput("U 079")
			await { compose.onAllNodesWithTag("category-checkbox:${categoryIds.getValue(space)[0][79]}", true).fetchSemanticsNodes().isNotEmpty() }
			compose.onNode(isToggleable() and !hasTestTag("category-checkbox:${categoryIds.getValue(space)[0][79]}"), useUnmergedTree = true).performClick()
			assertEquals(listOf(1, 1), pages())
			choose("Second", "Utama")
			await { compose.onAllNodesWithTag("category-checkbox:$second", true).fetchSemanticsNodes().isNotEmpty() }
			checkbox(second).performClick()
			compose.onNodeWithText(context.getString(android.R.string.ok)).assertIsDisplayed().assertIsEnabled().performClick()
			closed(scenario)
			assertEquals(setOf(first, second), memberships())
			assertEquals(setOf(first, second), runBlocking { repository.getCategoriesIds(otherManga.id) })
		}
	}

	@Test fun plusCreatesInActiveSetAndEditingExistingCategoryKeepsItsOwnSet() {
		val space = FavouriteSpace.NORMAL
		val first = categoryIds.getValue(space)[0][0]
		launch().use { scenario ->
			showPicker(scenario, space)
			choose("Second", "Utama")
			await { compose.onAllNodesWithTag("category-checkbox:${categoryIds.getValue(space)[1][0]}", true).fetchSemanticsNodes().isNotEmpty() }
			compose.onNodeWithContentDescription(context.getString(R.string.add_new_category)).performClick()
			await { var found = false; instrumentation.runOnMainSync { found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any { it is FavouritesCategoryEditActivity } }; found }
			instrumentation.runOnMainSync {
				val editor = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<FavouritesCategoryEditActivity>().single()
				editor.findViewById<EditText>(R.id.edit_name).setText("Created in Second")
				editor.findViewById<android.view.View>(R.id.button_done).performClick()
			}
			await { runBlocking { repository.observeCategories(space).first().any { it.title == "Created in Second" } } }
			val created = runBlocking { repository.observeCategories(space).first().first { it.title == "Created in Second" } }
			assertEquals(sets.getValue(space)[1].setId, runBlocking { repository.getCategorySet(created.id, space).setId })
			val intent = Intent(context, FavouritesCategoryEditActivity::class.java).putExtra(AppRouter.KEY_ID, first)
			ActivityScenario.launch<FavouritesCategoryEditActivity>(intent).use { editor ->
				await { var ready = false; editor.onActivity { ready = it.findViewById<EditText>(R.id.edit_name).text.isNotEmpty() }; ready }
				editor.onActivity { it.findViewById<EditText>(R.id.edit_name).setText("Edited in place"); it.findViewById<android.view.View>(R.id.button_done).performClick() }
				await { runBlocking { repository.getCategory(first).title == "Edited in place" } }
			}
			assertEquals(sets.getValue(space)[0].setId, runBlocking { repository.getCategorySet(first, space).setId })
		}
	}

	@Test fun categoryManagementDragReordersOnlyTheActiveSet() {
		val normal = FavouriteSpace.NORMAL
		val first = categoryIds.getValue(normal)[0]
		val second = categoryIds.getValue(normal)[1]
		launch().use { scenario ->
			var position = -1
			await {
				scenario.onActivity { activity ->
					val model = ViewModelProvider(activity)[org.koitharu.kotatsu.favourites.ui.categories.FavouritesCategoriesViewModel::class.java]
					position = model.content.value.indexOfFirst { it is org.koitharu.kotatsu.favourites.ui.categories.adapter.CategoryListModel && it.category.id == first[0] }
					if (position >= 0) activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerView).scrollToPosition(position)
				}
				position >= 0
			}
			val start = android.graphics.Rect()
			val end = android.graphics.Rect()
			await {
				var visible = false
				scenario.onActivity { activity ->
					val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerView)
					val firstRow = list.findViewHolderForAdapterPosition(position)?.itemView
					val secondRow = list.findViewHolderForAdapterPosition(position + 1)?.itemView
					visible = firstRow?.findViewById<android.view.View>(R.id.imageView_handle)?.getGlobalVisibleRect(start) == true && secondRow?.getGlobalVisibleRect(end) == true
				}
				visible
			}
			val downTime = android.os.SystemClock.uptimeMillis()
			fun pointer(action: Int, y: Float) {
				val event = android.view.MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(), action, start.exactCenterX(), y, 0)
				event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
				try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
				instrumentation.waitForIdleSync()
			}
			pointer(android.view.MotionEvent.ACTION_DOWN, start.exactCenterY())
			val destination = end.exactCenterY() + end.height() * 0.2f
			for (step in 1..12) pointer(android.view.MotionEvent.ACTION_MOVE, start.exactCenterY() + (destination - start.exactCenterY()) * step / 12)
			pointer(android.view.MotionEvent.ACTION_UP, destination)
			await { runBlocking { repository.observeCategoriesInSet(sets.getValue(normal)[0].setId, normal).first().first().id == first[1] } }
			assertEquals(second, runBlocking { repository.observeCategoriesInSet(sets.getValue(normal)[1].setId, normal).first().map { it.id } })
		}
	}

	@Test fun manageSetsCreatesRenamesReordersAndConfirmsSafeAndDestructiveDeletion() {
		val normal = FavouriteSpace.NORMAL
		val first = sets.getValue(normal)[0]
		val second = sets.getValue(normal)[1]
		val category = categoryIds.getValue(normal)[1][0]
		runBlocking { repository.updateCategoryMemberships(listOf(manga), mapOf(category to true)) }
		launch().use { scenario ->
			showPicker(scenario, normal)
			compose.onNodeWithText(context.getString(R.string.category_set_selector, "Utama")).performClick()
			compose.onNodeWithText(context.getString(R.string.category_set_manage)).performClick()
			await { compose.onAllNodesWithText(context.resources.getQuantityString(R.plurals.category_set_category_count, 80, 80)).fetchSemanticsNodes().isNotEmpty() }
			compose.onNodeWithTag("category-sets-sheet").assertIsDisplayed()
			compose.onNodeWithText(context.resources.getQuantityString(R.plurals.category_set_category_count, 80, 80)).assertIsDisplayed()
			compose.onNodeWithText(context.resources.getQuantityString(R.plurals.category_set_category_count, 15, 15)).assertIsDisplayed()
			capture("manage-two-sets")
			compose.onNodeWithTag("set-create").performClick()
			compose.onNodeWithText(context.getString(R.string.category_set_name)).performTextInput("Third")
			compose.onNodeWithText(context.getString(R.string.save)).performClick()
			await { runBlocking { repository.getSets(normal).size == 3 } }
			val third = runBlocking { repository.getSets(normal).first { it.title == "Third" } }
			compose.onNodeWithTag("set-overflow:${third.setId}").performScrollTo().performClick()
			compose.onNodeWithTag("set-rename:${third.setId}").performClick()
			compose.onNodeWithText(context.getString(R.string.category_set_name)).performTextReplacement("Archive")
			capture("set-rename")
			compose.onNodeWithText(context.getString(R.string.save)).performClick()
			await { runBlocking { repository.getSets(normal).any { it.syncKey == third.syncKey && it.title == "Archive" } } }
			compose.onNodeWithText(context.getString(R.string.category_set_no_categories)).assertIsDisplayed()
			compose.onNodeWithTag("set-row:${third.setId}").assertIsSelected()
			val beforeDrag = memberships()
			val destination = compose.onNodeWithTag("set-row:${second.setId}").fetchSemanticsNode().boundsInRoot.center.y
			val handle = compose.onNodeWithTag("set-drag:${third.setId}").fetchSemanticsNode().boundsInRoot.center.y
			compose.mainClock.autoAdvance = false
			try {
				compose.onNodeWithTag("set-drag:${third.setId}").performTouchInput {
					down(center)
					moveTo(center.copy(y = center.y + destination - handle), delayMillis = 800)
				}
				compose.mainClock.advanceTimeBy(64)
				capture("set-drag-in-progress")
				compose.onNodeWithTag("set-drag:${third.setId}").performTouchInput { up() }
			} finally { compose.mainClock.autoAdvance = true }
			await { runBlocking { repository.getSets(normal)[1].syncKey == third.syncKey } }
			assertEquals(beforeDrag, memberships())
			compose.onNodeWithTag("set-row:${third.setId}").assertIsSelected()
			val upActions = compose.onNodeWithTag("set-row:${third.setId}").fetchSemanticsNode().config[SemanticsActions.CustomActions]
			compose.runOnIdle { assertTrue(upActions.single { it.label == context.getString(R.string.category_set_up) }.action()) }
			await { runBlocking { repository.getSets(normal).first().setId == third.setId } }
			val downActions = compose.onNodeWithTag("set-row:${third.setId}").fetchSemanticsNode().config[SemanticsActions.CustomActions]
			compose.runOnIdle { assertTrue(downActions.single { it.label == context.getString(R.string.category_set_down) }.action()) }
			await { runBlocking { repository.getSets(normal)[1].setId == third.setId } }
			compose.onNodeWithTag("set-overflow:${second.setId}").performScrollTo().performClick()
			capture("set-overflow")
			compose.onNodeWithTag("set-delete:${second.setId}").performClick()
			await { compose.onAllNodesWithText(context.getString(R.string.category_set_delete_confirm, "Second", 15, 1)).fetchSemanticsNodes().isNotEmpty() }
			capture("set-safe-delete")
			compose.onNodeWithText(context.getString(R.string.category_set_move_delete)).performClick()
			await { runBlocking { repository.getSets(normal).none { it.setId == second.setId } } }
			assertEquals(first.setId, runBlocking { repository.getCategorySet(category, normal).setId })
			assertEquals(setOf(category), memberships())
			compose.onNodeWithTag("set-overflow:${third.setId}").performScrollTo().performClick()
			compose.onNodeWithTag("set-delete:${third.setId}").performClick()
			await { compose.onAllNodesWithText(context.getString(R.string.category_set_delete_confirm, "Archive", 0, 0)).fetchSemanticsNodes().isNotEmpty() }
			compose.onNodeWithText(context.getString(R.string.category_set_delete_categories)).performClick()
			assertTrue(runBlocking { repository.getSets(normal).any { it.setId == third.setId } })
			capture("set-destructive-confirmation")
			compose.onNodeWithText(context.getString(R.string.category_set_delete_categories)).performClick()
			await { runBlocking { repository.getSets(normal).size == 1 } }
			compose.onNodeWithTag("set-overflow:${first.setId}").performScrollTo().performClick()
			compose.onNodeWithTag("set-delete:${first.setId}").assertIsNotEnabled()
		}
	}

	@Test fun mainFavouritesNavigationAllCountsAndVirtualShelvesFollowSetSelection() {
		val first = categoryIds.getValue(FavouriteSpace.NORMAL)[0]
		val second = categoryIds.getValue(FavouriteSpace.NORMAL)[1]
		runBlocking {
			repository.updateCategoryMemberships(listOf(manga), mapOf(first[0] to true, first[1] to true))
			repository.updateCategoryMemberships(listOf(otherManga), mapOf(second[0] to true))
		}
		ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
			val container = AtomicReference<FavouritesContainerFragment?>()
			await { scenario.onActivity { activity -> container.set(descendants(activity.supportFragmentManager.fragments).filterIsInstance<FavouritesContainerFragment>().firstOrNull()) }; container.get()?.view != null }
			val model = AtomicReference<FavouritesContainerViewModel>()
			scenario.onActivity { model.set(ViewModelProvider(container.get()!!)[FavouritesContainerViewModel::class.java]) }
			val before = runBlocking { withTimeout(30_000) { model.get().categories.first { it.count { row -> row.id > 0 } == 80 && it.firstOrNull { row -> row.id == 0L }?.count == 1 } } }
			assertTrue(before.any { it.id == DOWNLOADED_FAVOURITES_CATEGORY_ID })
			assertTrue(before.any { it.id == LOCAL_FAVOURITES_CATEGORY_ID })
			scenario.onActivity { assertNavigationGeometry(it, tabsMode = true) }
			capture("normal-manga-tabs")
			scenario.onActivity { it.findViewById<android.view.View>(R.id.button_category_set).performClick() }
			tapNativeMenu("Second")
			val after = runBlocking { withTimeout(30_000) { model.get().categories.first { it.count { row -> row.id > 0 } == 15 && it.firstOrNull { row -> row.id == 0L }?.count == 1 } } }
			assertEquals(second.toSet(), after.filter { it.id > 0 }.map { it.id }.toSet())
			assertTrue(after.any { it.id == DOWNLOADED_FAVOURITES_CATEGORY_ID })
			assertTrue(after.any { it.id == LOCAL_FAVOURITES_CATEGORY_ID })
			val allModel = AtomicReference<FavouritesListViewModel?>()
			await { scenario.onActivity {
				allModel.set(descendants(container.get()!!.childFragmentManager.fragments).filterIsInstance<FavouritesListFragment>().map { ViewModelProvider(it)[FavouritesListViewModel::class.java] }.firstOrNull { it.categoryId == 0L })
			}; allModel.get() != null }
			assertEquals(setOf(otherManga.id), runBlocking { allModel.get()!!.getAllSelectableIds() })
			instrumentation.runOnMainSync { display.setShowCategoryTabs(FavouriteContentType.MANGA, false) }
			await { var ready = false; scenario.onActivity { ready = it.findViewById<android.view.View>(R.id.button_category_picker).isShown }; ready }
			scenario.onActivity { assertNavigationGeometry(it, tabsMode = false) }
			capture("normal-manga-picker")
			scenario.onActivity { activity ->
				val mode = checkNotNull(activity.startSupportActionMode(object : androidx.appcompat.view.ActionMode.Callback {
					override fun onCreateActionMode(mode: androidx.appcompat.view.ActionMode, menu: android.view.Menu) = true
					override fun onPrepareActionMode(mode: androidx.appcompat.view.ActionMode, menu: android.view.Menu) = false
					override fun onActionItemClicked(mode: androidx.appcompat.view.ActionMode, item: android.view.MenuItem) = false
					override fun onDestroyActionMode(mode: androidx.appcompat.view.ActionMode) = Unit
				}))
				container.get()!!.onActionModeStarted(mode)
				assertFalse(activity.findViewById<android.view.View>(R.id.button_category_set).isEnabled)
				assertFalse(activity.findViewById<android.view.View>(R.id.button_category_picker).isEnabled)
				container.get()!!.onActionModeFinished(mode)
				mode.finish()
				assertTrue(activity.findViewById<android.view.View>(R.id.button_category_set).isEnabled)
			}
			assertEquals(setOf(first[0], first[1]), memberships())
			scenario.onActivity { it.findViewById<android.view.View>(R.id.button_category_set).performClick() }
			tapNativeMenu("Utama")
			await { model.get().categories.value.count { it.id > 0 } == 80 }
			await { runBlocking { allModel.get()!!.getAllSelectableIds() == setOf(manga.id) } }
		}
	}
	@Test fun managementContextCountsPrivateIsolationAndCategoryRoute() {
		val space = FavouriteSpace.PRIVATE
		val normal = sets.getValue(FavouriteSpace.NORMAL)[0]
		val first = sets.getValue(space)[0]
		val second = sets.getValue(space)[1]
		runBlocking { repository.renameSet(normal.setId, "NORMAL_ONLY_SECRET", FavouriteSpace.NORMAL) }
		types.setCategoryType(categoryIds.getValue(space)[1][0], FavouriteContentType.NOVEL)
		selection.select(first, space, FavouriteContentType.MANGA)
		selection.select(second, space, FavouriteContentType.NOVEL)
		types.setSelectedType(FavouriteContentType.NOVEL, space)
		launch(space).use { scenario ->
			scenario.onActivity { org.koitharu.kotatsu.favourites.ui.sets.showCategorySets(it.supportFragmentManager, space) }
			await { compose.onAllNodesWithTag("set-row:${second.setId}").fetchSemanticsNodes().isNotEmpty() }
			compose.onNodeWithTag("set-row:${second.setId}").assertIsSelected()
			compose.onNodeWithTag("set-row:${first.setId}").assertIsNotSelected()
			compose.onNodeWithText(context.resources.getQuantityString(R.plurals.category_set_category_count, 15, 15)).assertIsDisplayed()
			compose.onNodeWithText("NORMAL_ONLY_SECRET").assertDoesNotExist()
			scenario.onActivity { activity ->
				val sheet = activity.supportFragmentManager.findFragmentByTag("category_sets") as org.koitharu.kotatsu.favourites.ui.sets.CategorySetsDialog
				assertTrue(sheet.dialog is com.google.android.material.bottomsheet.BottomSheetDialog)
				assertTrue(sheet.dialog!!.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
				assertEquals(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED, (sheet.dialog as com.google.android.material.bottomsheet.BottomSheetDialog).behavior.state)
			}
			compose.onNodeWithTag("set-overflow:${first.setId}").performClick()
			compose.onNodeWithTag("set-categories:${first.setId}").performClick()
			await {
				var routed = false
				instrumentation.runOnMainSync {
					val manager = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<FavouriteCategoriesActivity>().firstOrNull()
					if (manager != null && manager.intent.getLongExtra(EXTRA_FAVOURITE_SET_ID, -1) == first.setId) {
						val model = ViewModelProvider(manager)[org.koitharu.kotatsu.favourites.ui.categories.FavouritesCategoriesViewModel::class.java]
						routed = model.activeSet.value?.setId == first.setId && model.favouriteSpace == space && model.selectedContentType == FavouriteContentType.NOVEL
					}
				}
				routed
			}
			assertEquals(normal.setId, runBlocking { selection.observe(FavouriteSpace.NORMAL, kotlinx.coroutines.flow.flowOf(FavouriteContentType.MANGA)).first().setId })
			assertEquals(first.setId, runBlocking { selection.observe(space, kotlinx.coroutines.flow.flowOf(FavouriteContentType.MANGA)).first().setId })
			instrumentation.runOnMainSync {
				ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<FavouriteCategoriesActivity>().filter { it.intent.hasExtra(EXTRA_FAVOURITE_SET_ID) }.forEach { it.finish() }
			}
		}
	}

	@Test fun manySetsScrollAndCloseDoneAndSwipeDismiss() {
		val space = FavouriteSpace.NORMAL
		val extra = runBlocking { (1..10).map { repository.createSet("Archive $it with a very long collection name", space) } }
		launch().use { scenario ->
			fun open() {
				scenario.onActivity { org.koitharu.kotatsu.favourites.ui.sets.showCategorySets(it.supportFragmentManager, space) }
				await { compose.onAllNodesWithTag("category-sets-sheet").fetchSemanticsNodes().isNotEmpty() }
			}
			fun closed() = await { var absent = false; scenario.onActivity { absent = it.supportFragmentManager.findFragmentByTag("category_sets") == null }; absent }
			open()
			val first = sets.getValue(space)[0]
			val viewport = compose.onNodeWithTag("set-list").fetchSemanticsNode().boundsInRoot
			val handleBounds = compose.onNodeWithTag("set-drag:${first.setId}").fetchSemanticsNode().boundsInRoot
			compose.onNodeWithTag("set-drag:${first.setId}").performTouchInput {
				swipe(center, center.copy(y = viewport.bottom - handleBounds.top - 4f), durationMillis = 2000)
			}
			await { runBlocking { repository.getSets(space).indexOfFirst { it.setId == first.setId } > 2 } }
			compose.onNodeWithTag("set-overflow:${extra.last().setId}").performScrollTo().assertIsDisplayed().performClick()
			compose.onNodeWithTag("set-rename:${extra.last().setId}").assertIsDisplayed()
			compose.onNodeWithTag("set-rename:${extra.last().setId}").performClick()
			compose.onNodeWithText(context.getString(android.R.string.cancel)).performClick()
			capture("manage-many-sets-long-names")
			compose.onNodeWithTag("set-done").assertIsDisplayed().performClick()
			closed()
			open()
			compose.onNodeWithTag("set-close").performClick()
			closed()
			open()
			compose.onNodeWithText(context.getString(R.string.category_set_manage_subtitle)).performTouchInput { swipeDown(startY = centerY, endY = centerY + 600f, durationMillis = 500) }
			closed()
		}
	}

	@Test fun longAndEmptySetRemainNavigableAcrossContentTypesAndWidths() {
		val space = FavouriteSpace.NORMAL
		val longName = "A very long category Set title that must leave categories usable"
		val empty = runBlocking { repository.createSet(longName, space) }
		ActivityScenario.launch<FavouritesActivity>(Intent(context, FavouritesActivity::class.java)).use { scenario ->
			val container = AtomicReference<FavouritesContainerFragment?>()
			await { scenario.onActivity { container.set(descendants(it.supportFragmentManager.fragments).filterIsInstance<FavouritesContainerFragment>().firstOrNull()) }; container.get()?.view != null }
			val model = AtomicReference<FavouritesContainerViewModel>()
			scenario.onActivity { model.set(ViewModelProvider(container.get()!!)[FavouritesContainerViewModel::class.java]) }
			await { model.get().activeSet.value != null }
			for (style in org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle.entries) {
				scenario.onActivity { settings.setMiyorareDesignStyle(style) }
				for (type in FavouriteContentType.entries) {
					instrumentation.runOnMainSync { types.setSelectedType(type, space); selection.select(empty, space, type) }
					await { var ready = false; scenario.onActivity { ready = it.findViewById<android.widget.TextView>(R.id.button_category_set)?.text?.toString() == longName }; ready }
					for (tabs in listOf(true, false)) {
						instrumentation.runOnMainSync { display.setShowCategoryTabs(type, tabs) }
						// Empty Sets keep the chip even if the existing empty state hides all category entries.
						scenario.onActivity { activity ->
							val chip = activity.findViewById<android.widget.TextView>(R.id.button_category_set)
							assertTrue(chip.isShown)
							assertEquals(longName, chip.text.toString())
							assertEquals(context.getString(R.string.category_set_accessibility, longName), chip.contentDescription)
							assertTrue(chip.performClick())
						}
						await { instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Utama")?.isNotEmpty() == true }
						tapNativeMenu("Utama")
						await { var ready = false; scenario.onActivity { ready = it.findViewById<android.widget.TextView>(R.id.button_category_set)?.text == "Utama" }; ready }
						instrumentation.runOnMainSync { selection.select(empty, space, type) }
						await { var ready = false; scenario.onActivity { ready = it.findViewById<android.widget.TextView>(R.id.button_category_set)?.text == longName }; ready }
					}
				}
			}
			runBlocking { repository.renameSet(sets.getValue(space)[0].setId, longName + " populated", space) }
			// Populated Set: measure the actual navigation row at narrow, reference and landscape widths.
			instrumentation.runOnMainSync { types.setSelectedType(FavouriteContentType.MANGA, space); selection.select(sets.getValue(space)[0], space, FavouriteContentType.MANGA) }
			await { model.get().categories.value.count { it.id > 0 } == 80 }
			for (widthDp in listOf(320, 432, 720)) {
				for (tabs in listOf(true, false)) {
					instrumentation.runOnMainSync { display.setShowCategoryTabs(FavouriteContentType.MANGA, tabs) }
					await { var ready = false; scenario.onActivity { ready = it.findViewById<android.view.View>(if (tabs) R.id.tabs else R.id.button_category_picker).isShown }; ready }
					scenario.onActivity { activity ->
						val row = activity.findViewById<android.view.ViewGroup>(R.id.category_navigation_row)
						val originalWidth = row.width
						val heightSpec = android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
						val width = (widthDp * activity.resources.displayMetrics.density).toInt()
						row.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY), heightSpec)
						row.layout(0, 0, width, row.measuredHeight)
						val chip = row.findViewById<android.widget.TextView>(R.id.button_category_set)
						val category = row.findViewById<android.view.View>(if (tabs) R.id.tabs else R.id.button_category_picker)
						assertTrue(chip.right <= category.left)
						assertTrue(category.width > chip.width)
						row.measure(android.view.View.MeasureSpec.makeMeasureSpec(originalWidth, android.view.View.MeasureSpec.EXACTLY), heightSpec)
						row.requestLayout()
					}
				}
			}
			capture("long-set-picker")
		}
	}

	@Test fun privateFavouritesUsesIntegratedRailForMangaAndNovelWithoutNormalNames() {
		val space = FavouriteSpace.PRIVATE
		val selected = sets.getValue(space)[1]
		runBlocking { repository.renameSet(sets.getValue(FavouriteSpace.NORMAL)[0].setId, "NORMAL_ONLY", FavouriteSpace.NORMAL) }
		types.setCategoryType(categoryIds.getValue(space)[1][0], FavouriteContentType.NOVEL)
		for (type in FavouriteContentType.entries) selection.select(selected, space, type)
		ActivityScenario.launch<FavouritesActivity>(Intent(context, FavouritesActivity::class.java).putExtra(EXTRA_FAVOURITE_SPACE, space.dbValue)).use { scenario ->
			for (type in FavouriteContentType.entries) {
				instrumentation.runOnMainSync { types.setSelectedType(type, space) }
				for (tabs in listOf(true, false)) {
					instrumentation.runOnMainSync { display.setShowCategoryTabs(type, tabs) }
					await {
						var ready = false
						scenario.onActivity { activity ->
							ready = activity.findViewById<android.widget.TextView>(R.id.button_category_set)?.text == "Second" && activity.findViewById<android.view.View>(if (tabs) R.id.tabs else R.id.button_category_picker)?.isShown == true
						}
						ready
					}
					scenario.onActivity { activity ->
						assertNavigationGeometry(activity, tabs)
						assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
						assertTrue(activity.findViewById<android.view.View>(R.id.private_search).isShown)
					}
				}
			}
		}
	}

	@Test fun renderedModernLightDarkAndNarrowPhoneEvidence() {
		val initialNight = androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode()
		runBlocking { repository.updateCategoryMemberships(listOf(manga), mapOf(categoryIds.getValue(FavouriteSpace.NORMAL)[0][0] to true)) }
		try {
			for (night in listOf(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO, androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)) {
				instrumentation.runOnMainSync { androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(night) }
				ActivityScenario.launch<FavouritesActivity>(Intent(context, FavouritesActivity::class.java)).use { scenario ->
					for (tabs in listOf(true, false)) {
						instrumentation.runOnMainSync { display.setShowCategoryTabs(FavouriteContentType.MANGA, tabs) }
						await { var ready = false; scenario.onActivity { ready = it.findViewById<android.view.View>(if (tabs) R.id.tabs else R.id.button_category_picker)?.isShown == true }; ready }
						scenario.onActivity { assertNavigationGeometry(it, tabs) }
						capture("modern-night-$night-tabs-$tabs")
					}
					scenario.onActivity { activity ->
						activity.window.setLayout((320 * activity.resources.displayMetrics.density).toInt(), android.view.ViewGroup.LayoutParams.MATCH_PARENT)
					}
					await { var narrow = false; scenario.onActivity { narrow = it.findViewById<android.view.View>(R.id.category_navigation_row).width / it.resources.displayMetrics.density < 320 }; narrow }
					scenario.onActivity { assertNavigationGeometry(it, tabsMode = false) }
					capture("modern-night-$night-narrow-picker")
					scenario.onActivity { org.koitharu.kotatsu.favourites.ui.sets.showCategorySets(it.supportFragmentManager, FavouriteSpace.NORMAL) }
					await { compose.onAllNodesWithTag("set-row:${sets.getValue(FavouriteSpace.NORMAL)[0].setId}").fetchSemanticsNodes().isNotEmpty() }
					capture("modern-night-$night-management")
					compose.onNodeWithTag("set-done").performClick()
					scenario.onActivity { activity ->
						activity.window.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
						activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
					}
					await {
						var landscape = false
						scenario.onActivity { landscape = it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE && it.findViewById<android.view.View>(R.id.button_category_picker)?.isShown == true }
						landscape
					}
					scenario.onActivity { assertNavigationGeometry(it, tabsMode = false) }
					capture("modern-night-$night-landscape")
				}
			}
		} finally {
			instrumentation.runOnMainSync { androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(initialNight) }
		}
	}

	private fun tapNativeMenu(title: String) {
		await { instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(title)?.any { it.text?.toString() == title } == true }
		val node = instrumentation.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText(title).first { it.text?.toString() == title }
		val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
		assertFalse("Native menu item must be on screen", bounds.isEmpty)
		val downTime = android.os.SystemClock.uptimeMillis()
		for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
			val event = android.view.MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
			event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
			try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
		}
		instrumentation.waitForIdleSync()
	}

	private fun assertNavigationGeometry(activity: android.app.Activity, tabsMode: Boolean) {
		val chip = activity.findViewById<android.widget.TextView>(R.id.button_category_set)
		val category = activity.findViewById<android.view.View>(if (tabsMode) R.id.tabs else R.id.button_category_picker)
		assertTrue(chip.isShown)
		assertTrue(category.isShown)
		assertSame(chip.parent, category.parent)
		val chipBounds = android.graphics.Rect().also { assertTrue(chip.getGlobalVisibleRect(it)) }
		val categoryBounds = android.graphics.Rect().also { assertTrue(category.getGlobalVisibleRect(it)) }
		assertTrue("Set and categories share one vertical navigation band", chipBounds.top < categoryBounds.bottom && categoryBounds.top < chipBounds.bottom)
		assertTrue("Pinned Set precedes categories without overlap", chipBounds.right <= categoryBounds.left)
		assertTrue("Categories retain most of the rail width", categoryBounds.width() > chipBounds.width())
		assertEquals(1, chip.maxLines)
		assertEquals(android.text.TextUtils.TruncateAt.END, chip.ellipsize)
		if (tabsMode) assertEquals(com.google.android.material.tabs.TabLayout.MODE_SCROLLABLE, (category as com.google.android.material.tabs.TabLayout).tabMode)
	}

	private fun capture(name: String) {
		instrumentation.waitForIdleSync()
		val directory = java.io.File(context.filesDir, "category-sets-ui-evidence").apply { mkdirs() }
		val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
		java.io.File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
		bitmap.recycle()
	}

	private fun descendants(fragments: List<Fragment>): Sequence<Fragment> = fragments.asSequence().flatMap { sequenceOf(it) + descendants(it.childFragmentManager.fragments) }
}
