package org.koitharu.kotatsu.favourites.ui

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.EditText
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
			compose.onNodeWithText(context.getString(R.string.category_set_create)).performClick()
			compose.onNodeWithText(context.getString(R.string.category_set_name)).performTextInput("Third")
			compose.onNodeWithText(context.getString(R.string.save)).performClick()
			await { runBlocking { repository.getSets(normal).size == 3 } }
			val third = runBlocking { repository.getSets(normal).first { it.title == "Third" } }
			compose.onNodeWithTag("set-rename:${third.setId}").performScrollTo().performClick()
			compose.onNodeWithText(context.getString(R.string.category_set_name)).performTextReplacement("Archive")
			compose.onNodeWithText(context.getString(R.string.save)).performClick()
			await { runBlocking { repository.getSets(normal).any { it.syncKey == third.syncKey && it.title == "Archive" } } }
			compose.onNodeWithTag("set-up:${third.setId}").performScrollTo().performClick()
			await { runBlocking { repository.getSets(normal)[1].syncKey == third.syncKey } }
			compose.onNodeWithTag("set-delete:${second.setId}").performScrollTo().performClick()
			await { compose.onAllNodesWithText(context.getString(R.string.category_set_delete_confirm, "Second", 15, 1)).fetchSemanticsNodes().isNotEmpty() }
			compose.onNodeWithText(context.getString(R.string.category_set_move_delete)).performClick()
			await { runBlocking { repository.getSets(normal).none { it.setId == second.setId } } }
			assertEquals(first.setId, runBlocking { repository.getCategorySet(category, normal).setId })
			assertEquals(setOf(category), memberships())
			compose.onNodeWithTag("set-delete:${third.setId}").performScrollTo().performClick()
			await { compose.onAllNodesWithText(context.getString(R.string.category_set_delete_confirm, "Archive", 0, 0)).fetchSemanticsNodes().isNotEmpty() }
			compose.onNodeWithText(context.getString(R.string.category_set_delete_categories)).performClick()
			assertTrue(runBlocking { repository.getSets(normal).any { it.setId == third.setId } })
			compose.onNodeWithText(context.getString(R.string.category_set_delete_categories)).performClick()
			await { runBlocking { repository.getSets(normal).size == 1 } }
			compose.onNodeWithTag("set-delete:${first.setId}").performScrollTo().assertIsNotEnabled()
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
			scenario.onActivity { it.findViewById<android.view.View>(R.id.button_category_set).performClick() }
			await { instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Second")?.any { it.text?.toString() == "Second" } == true }
			val node = instrumentation.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("Second").first { it.text?.toString() == "Second" }
			val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
			assertFalse("The native Set menu item must be on screen", bounds.isEmpty)
			val downTime = android.os.SystemClock.uptimeMillis()
			for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
				val event = android.view.MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
				event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
				try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
			}
			instrumentation.waitForIdleSync()
			val after = runBlocking { withTimeout(30_000) { model.get().categories.first { it.count { row -> row.id > 0 } == 15 && it.firstOrNull { row -> row.id == 0L }?.count == 1 } } }
			assertEquals(second.toSet(), after.filter { it.id > 0 }.map { it.id }.toSet())
			assertTrue(after.any { it.id == DOWNLOADED_FAVOURITES_CATEGORY_ID })
			assertTrue(after.any { it.id == LOCAL_FAVOURITES_CATEGORY_ID })
			val allModel = AtomicReference<FavouritesListViewModel?>()
			await { scenario.onActivity {
				allModel.set(descendants(container.get()!!.childFragmentManager.fragments).filterIsInstance<FavouritesListFragment>().map { ViewModelProvider(it)[FavouritesListViewModel::class.java] }.firstOrNull { it.categoryId == 0L })
			}; allModel.get() != null }
			assertEquals(setOf(otherManga.id), runBlocking { allModel.get()!!.getAllSelectableIds() })
		}
	}
	private fun descendants(fragments: List<Fragment>): Sequence<Fragment> = fragments.asSequence().flatMap { sequenceOf(it) + descendants(it.childFragmentManager.fragments) }
}
