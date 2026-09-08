from pathlib import Path

ROOT = Path('.')

def replace_once(path, old, new):
    p = ROOT / path
    text = p.read_text()
    if old not in text:
        raise SystemExit(f'Anchor not found in {path}: {old[:100]!r}')
    p.write_text(text.replace(old, new, 1))

def write(path, content):
    p = ROOT / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(content)

# --- Private workspace wrapper -------------------------------------------------
write('app/src/main/kotlin/org/koitharu/kotatsu/favourites/ui/PrivateWorkspaceFragment.kt', r'''package org.koitharu.kotatsu.favourites.ui

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.explore.ui.ExploreFragment
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.ui.container.FavouritesContainerFragment
import org.koitharu.kotatsu.history.ui.HistoryListFragment
import org.koitharu.kotatsu.settings.PrivateFavouritesSettingsFragment
import org.koitharu.kotatsu.tracker.ui.feed.FeedFragment

/**
 * Persistent, authenticated Private workspace. The five top-level destinations live inside the
 * same FLAG_SECURE FavouritesActivity and therefore never have to fall back to Normal navigation.
 */
class PrivateWorkspaceFragment : Fragment(R.layout.fragment_private_workspace) {

    private var selectedItemId: Int = R.id.private_nav_favourites
    private lateinit var navigation: BottomNavigationView

    private val backToLibrary = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            navigation.selectedItemId = R.id.private_nav_favourites
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        selectedItemId = savedInstanceState?.getInt(STATE_SELECTED, R.id.private_nav_favourites)
            ?: R.id.private_nav_favourites
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backToLibrary)
        navigation = view.findViewById(R.id.private_workspace_navigation)
        navigation.setOnItemSelectedListener { item ->
            showDestination(item.itemId)
            true
        }
        navigation.selectedItemId = selectedItemId
        if (childFragmentManager.findFragmentById(R.id.private_workspace_content) == null) {
            showDestination(selectedItemId)
        } else {
            updateTitle(selectedItemId)
            backToLibrary.isEnabled = selectedItemId != R.id.private_nav_favourites
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_SELECTED, selectedItemId)
        super.onSaveInstanceState(outState)
    }

    private fun showDestination(itemId: Int) {
        selectedItemId = itemId
        backToLibrary.isEnabled = itemId != R.id.private_nav_favourites
        updateTitle(itemId)
        val fragment = when (itemId) {
            R.id.private_nav_feed -> FeedFragment().withPrivateScope()
            R.id.private_nav_history -> HistoryListFragment().withPrivateScope()
            R.id.private_nav_explore -> ExploreFragment().withPrivateScope()
            R.id.private_nav_settings -> PrivateFavouritesSettingsFragment()
            else -> FavouritesContainerFragment().withPrivateScope()
        }
        childFragmentManager.commit {
            setReorderingAllowed(true)
            replace(R.id.private_workspace_content, fragment, "private-workspace-$itemId")
        }
    }

    private fun updateTitle(itemId: Int) {
        requireActivity().setTitle(
            when (itemId) {
                R.id.private_nav_feed -> R.string.private_workspace_feed
                R.id.private_nav_history -> R.string.private_workspace_history
                R.id.private_nav_explore -> R.string.private_workspace_explore
                R.id.private_nav_settings -> R.string.private_workspace_settings
                else -> R.string.private_favourites
            },
        )
    }

    private fun <T : Fragment> T.withPrivateScope(): T = apply {
        arguments = Bundle().apply {
            putInt(EXTRA_FAVOURITE_SPACE, FavouriteSpace.PRIVATE.dbValue)
        }
    }

    private companion object {
        const val STATE_SELECTED = "private_workspace_selected"
    }
}
''')

write('app/src/main/res/layout/fragment_private_workspace.xml', r'''<?xml version="1.0" encoding="utf-8"?>
<LinearLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical">

    <androidx.fragment.app.FragmentContainerView
        android:id="@+id/private_workspace_content"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1" />

    <com.google.android.material.bottomnavigation.BottomNavigationView
        android:id="@+id/private_workspace_navigation"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:background="?attr/colorSurfaceContainer"
        android:elevation="8dp"
        app:itemActiveIndicatorStyle="@style/Widget.Material3.NavigationBarView.ActiveIndicator"
        app:labelVisibilityMode="labeled"
        app:menu="@menu/menu_private_workspace" />

</LinearLayout>
''')

write('app/src/main/res/menu/menu_private_workspace.xml', r'''<?xml version="1.0" encoding="utf-8"?>
<menu xmlns:android="http://schemas.android.com/apk/res/android">
    <item
        android:id="@+id/private_nav_favourites"
        android:icon="@drawable/ic_favourites_selector"
        android:title="@string/private_workspace_favourites" />
    <item
        android:id="@+id/private_nav_feed"
        android:icon="@drawable/ic_feed_selector"
        android:title="@string/private_workspace_feed" />
    <item
        android:id="@+id/private_nav_history"
        android:icon="@drawable/ic_history_selector"
        android:title="@string/private_workspace_history" />
    <item
        android:id="@+id/private_nav_explore"
        android:icon="@drawable/ic_explore_selector"
        android:title="@string/private_workspace_explore" />
    <item
        android:id="@+id/private_nav_settings"
        android:icon="@drawable/ic_settings"
        android:title="@string/private_workspace_settings" />
</menu>
''')

write('app/src/main/res/values/private_workspace.xml', r'''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="private_workspace_favourites">Favorites</string>
    <string name="private_workspace_feed">Feed</string>
    <string name="private_workspace_history">History</string>
    <string name="private_workspace_explore">Explore</string>
    <string name="private_workspace_settings">Settings</string>
</resources>
''')
write('app/src/main/res/values-in/private_workspace.xml', r'''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="private_workspace_favourites">Disukai</string>
    <string name="private_workspace_feed">Umpan</string>
    <string name="private_workspace_history">Riwayat</string>
    <string name="private_workspace_explore">Jelajah</string>
    <string name="private_workspace_settings">Pengaturan</string>
</resources>
''')

# FavouritesActivity: use persistent workspace rather than only the library fragment.
replace_once(
    'app/src/main/kotlin/org/koitharu/kotatsu/favourites/ui/FavouritesActivity.kt',
    '\t\tisPrivateMode -> FavouritesContainerFragment::class.java\n',
    '\t\tisPrivateMode -> PrivateWorkspaceFragment::class.java\n',
)

# Remove the redundant horizontal action strip circled by the user. Keep only the local Private search.
layout_path = ROOT / 'app/src/main/res/layout/fragment_favourites_container.xml'
text = layout_path.read_text()
start = text.find('\n\t\t\t\t<HorizontalScrollView', text.find('android:id="@+id/private_hub_container"'))
if start < 0:
    raise SystemExit('Private action HorizontalScrollView not found')
end_tag = '\n\t\t\t\t</HorizontalScrollView>'
end = text.find(end_tag, start)
if end < 0:
    raise SystemExit('Private action HorizontalScrollView end not found')
text = text[:start] + text[end + len(end_tag):]
layout_path.write_text(text)

# FavouritesContainerFragment: Private search remains; destination actions moved to fixed bottom navigation.
replace_once(
    'app/src/main/kotlin/org/koitharu/kotatsu/favourites/ui/container/FavouritesContainerFragment.kt',
    '\tprivate var pendingPrivateShelfId: Long? = null\n',
    '',
)
frag_path = ROOT / 'app/src/main/kotlin/org/koitharu/kotatsu/favourites/ui/container/FavouritesContainerFragment.kt'
text = frag_path.read_text()
old = '''\tprivate fun setupPrivateHub(binding: FragmentFavouritesContainerBinding) {
\t\tval isPrivate = viewModel.favouriteSpace == FavouriteSpace.PRIVATE
\t\tbinding.privateHubContainer.isVisible = isPrivate
\t\tif (!isPrivate) return

\t\tbinding.privateSearch.apply {
\t\t\tsetText(searchQuery.value)
\t\t\tsetSelection(text?.length ?: 0)
\t\t\tdoAfterTextChanged { value -> searchQuery.value = value?.toString().orEmpty() }
\t\t}
\t\tbinding.privateActionAll.setOnClickListener { openPrivateShelf(FavouritesListFragment.NO_ID) }
\t\tbinding.privateActionDownloaded.setOnClickListener { openPrivateShelf(DOWNLOADED_FAVOURITES_CATEGORY_ID) }
\t\tbinding.privateActionLocal.setOnClickListener { openPrivateShelf(LOCAL_FAVOURITES_CATEGORY_ID) }
\t\tbinding.privateActionCategories.setOnClickListener { router.openFavoriteCategories(FavouriteSpace.PRIVATE) }
\t\tbinding.privateActionExtensions.setOnClickListener { router.openPrivateExtensionsSettings() }
\t\tbinding.privateActionSettings.setOnClickListener { router.openPrivateFavouritesSettings() }
\t}

\tprivate fun openPrivateShelf(categoryId: Long) {
\t\tif (viewModel.favouriteSpace != FavouriteSpace.PRIVATE) return
\t\tpendingPrivateShelfId = categoryId
\t\tif (categoryId == LOCAL_FAVOURITES_CATEGORY_ID &&
\t\t\tcontentTypeStore.selectedType.value == FavouriteContentType.NOVEL
\t\t) {
\t\t\tcontentTypeStore.setSelectedType(FavouriteContentType.MANGA)
\t\t}
\t\tselectPendingPrivateShelf()
\t}

\tprivate fun selectPendingPrivateShelf() {
\t\tval targetId = pendingPrivateShelfId ?: return
\t\tval index = categories.indexOfFirst { it.id == targetId }
\t\tif (index < 0) return
\t\tviewBinding?.pager?.setCurrentItem(index, false)
\t\tpendingPrivateShelfId = null
\t}
'''
new = '''\tprivate fun setupPrivateHub(binding: FragmentFavouritesContainerBinding) {
\t\tval isPrivate = viewModel.favouriteSpace == FavouriteSpace.PRIVATE
\t\tbinding.privateHubContainer.isVisible = isPrivate
\t\tif (!isPrivate) return

\t\tbinding.privateSearch.apply {
\t\t\tsetText(searchQuery.value)
\t\t\tsetSelection(text?.length ?: 0)
\t\t\tdoAfterTextChanged { value -> searchQuery.value = value?.toString().orEmpty() }
\t\t}
\t}
'''
if old not in text:
    raise SystemExit('Old Private hub methods not found')
text = text.replace(old, new, 1)
text = text.replace('\n\t\tselectPendingPrivateShelf()\n', '\n', 1)
frag_path.write_text(text)

# --- Private-scoped History ---------------------------------------------------
# HistoryDao gets a second raw-query builder using membership in Private (including Normal+Private).
history_dao = ROOT / 'app/src/main/kotlin/org/koitharu/kotatsu/history/data/HistoryDao.kt'
text = history_dao.read_text()
anchor = '''\tfun observeAll(
\t\torder: ListSortOrder,
\t\tfilterOptions: Set<ListFilterOption>,
\t\tlimit: Int,
\t\tminUpdatedAt: Long = 0L,
\t): Flow<List<HistoryWithManga>> = observeAllImpl(
'''
pos = text.find(anchor)
if pos < 0:
    raise SystemExit('History observeAll anchor missing')
# Insert private method after normal method by locating the next @Query following it.
next_query = text.find('\n\t@Query(', pos)
if next_query < 0:
    raise SystemExit('History next @Query missing')
private_method = '''
\n\tfun observeAllPrivate(
\t\torder: ListSortOrder,
\t\tfilterOptions: Set<ListFilterOption>,
\t\tlimit: Int,
\t\tminUpdatedAt: Long = 0L,
\t): Flow<List<HistoryWithManga>> = observeAllImpl(
\t\tMangaQueryBuilder(TABLE_HISTORY, this)
\t\t\t.join("LEFT JOIN manga ON history.manga_id = manga.manga_id")
\t\t\t.where("history.deleted_at = 0")
\t\t\t.where(
\t\t\t\t"EXISTS(SELECT 1 FROM private_favourites pf " +
\t\t\t\t\t"WHERE pf.manga_id = history.manga_id AND pf.deleted_at = 0)",
\t\t\t)
\t\t\t.where("history.updated_at >= $minUpdatedAt")
\t\t\t.filters(filterOptions)
\t\t\t.orderBy(
\t\t\t\torderBy = order.toOrderBy(
\t\t\t\t\tdateAdded = "history.created_at",
\t\t\t\t\tlastRead = "history.updated_at",
\t\t\t\t\tprogress = "history.percent",
\t\t\t\t),
\t\t\t)
\t\t\t.groupBy("history.manga_id")
\t\t\t.limit(limit)
\t\t\t.build(),
\t)
'''
if 'fun observeAllPrivate(' not in text:
    text = text[:next_query] + private_method + text[next_query:]
history_dao.write_text(text)

replace_once(
    'app/src/main/kotlin/org/koitharu/kotatsu/history/data/HistoryRepository.kt',
    'import org.koitharu.kotatsu.history.domain.model.MangaWithHistory\n',
    'import org.koitharu.kotatsu.history.domain.model.MangaWithHistory\nimport org.koitharu.kotatsu.favourites.data.FavouriteSpace\n',
)
replace_once(
    'app/src/main/kotlin/org/koitharu/kotatsu/history/data/HistoryRepository.kt',
    '''\tfun observeAllWithHistory(
\t\torder: ListSortOrder,
\t\tfilterOptions: Set<ListFilterOption>,
\t\tlimit: Int,
\t\tminUpdatedAt: Long = 0L,
\t): Flow<List<MangaWithHistory>> {
\t\tif (ListFilterOption.Downloaded in filterOptions) {
\t\t\treturn localObserver.observeAll(order, filterOptions, limit, minUpdatedAt)
\t\t}
\t\treturn db.getHistoryDao().observeAll(order, filterOptions, limit, minUpdatedAt).mapItems {
''',
    '''\tfun observeAllWithHistory(
\t\torder: ListSortOrder,
\t\tfilterOptions: Set<ListFilterOption>,
\t\tlimit: Int,
\t\tminUpdatedAt: Long = 0L,
\t\tspace: FavouriteSpace = FavouriteSpace.NORMAL,
\t): Flow<List<MangaWithHistory>> {
\t\tif (space == FavouriteSpace.NORMAL && ListFilterOption.Downloaded in filterOptions) {
\t\t\treturn localObserver.observeAll(order, filterOptions, limit, minUpdatedAt)
\t\t}
\t\tval flow = if (space == FavouriteSpace.PRIVATE) {
\t\t\tdb.getHistoryDao().observeAllPrivate(order, filterOptions, limit, minUpdatedAt)
\t\t} else {
\t\t\tdb.getHistoryDao().observeAll(order, filterOptions, limit, minUpdatedAt)
\t\t}
\t\treturn flow.mapItems {
''',
)

# History VM obtains scope from fragment arguments through SavedStateHandle.
replace_once(
    'app/src/main/kotlin/org/koitharu/kotatsu/history/ui/HistoryListViewModel.kt',
    'import androidx.lifecycle.viewModelScope\n',
    'import androidx.lifecycle.SavedStateHandle\nimport androidx.lifecycle.viewModelScope\n',
)
replace_once(
    'app/src/main/kotlin/org/koitharu/kotatsu/history/ui/HistoryListViewModel.kt',
    'import org.koitharu.kotatsu.history.data.HistoryRepository\n',
    'import org.koitharu.kotatsu.history.data.HistoryRepository\nimport org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE\nimport org.koitharu.kotatsu.favourites.data.FavouriteSpace\n',
)
replace_once(
    'app/src/main/kotlin/org/koitharu/kotatsu/history/ui/HistoryListViewModel.kt',
    '''\tprivate val database: MangaDatabase,
\tmangaDataRepository: MangaDataRepository,
''',
    '''\tprivate val database: MangaDatabase,
\tsavedStateHandle: SavedStateHandle,
\tmangaDataRepository: MangaDataRepository,
''',
)
replace_once(
    'app/src/main/kotlin/org/koitharu/kotatsu/history/ui/HistoryListViewModel.kt',
    ') : MangaListViewModel(settings, mangaDataRepository, localStorageChanges), QuickFilterListener by quickFilter {\n',
    ''') : MangaListViewModel(settings, mangaDataRepository, localStorageChanges), QuickFilterListener by quickFilter {

\tprivate val favouriteSpace = FavouriteSpace.fromArgument(
\t\tsavedStateHandle[EXTRA_FAVOURITE_SPACE] ?: FavouriteSpace.NORMAL.dbValue,
\t)
''',
)
replace_once(
    'app/src/main/kotlin/org/koitharu/kotatsu/history/ui/HistoryListViewModel.kt',
    '\t\trepository.observeAllWithHistory(order, filters, limit)\n',
    '\t\trepository.observeAllWithHistory(order, filters, limit, space = favouriteSpace)\n',
)

# Hide global History menu operations inside Private workspace.
replace_once(
    'app/src/main/kotlin/org/koitharu/kotatsu/history/ui/HistoryListFragment.kt',
    'import org.koitharu.kotatsu.databinding.FragmentListBinding\n',
    'import org.koitharu.kotatsu.databinding.FragmentListBinding\nimport org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE\nimport org.koitharu.kotatsu.favourites.data.FavouriteSpace\n',
)
replace_once(
    'app/src/main/kotlin/org/koitharu/kotatsu/history/ui/HistoryListFragment.kt',
    '''\t\tRecyclerScrollKeeper(binding.recyclerView).attach()
\t\taddMenuProvider(HistoryListMenuProvider(binding.root.context, router, viewModel))
\t\tviewModel.isStatsEnabled.observe(viewLifecycleOwner, MenuInvalidator(requireActivity()))
''',
    '''\t\tRecyclerScrollKeeper(binding.recyclerView).attach()
\t\tval space = FavouriteSpace.fromArgument(arguments?.getInt(EXTRA_FAVOURITE_SPACE) ?: FavouriteSpace.NORMAL.dbValue)
\t\tif (space == FavouriteSpace.NORMAL) {
\t\t\taddMenuProvider(HistoryListMenuProvider(binding.root.context, router, viewModel))
\t\t\tviewModel.isStatsEnabled.observe(viewLifecycleOwner, MenuInvalidator(requireActivity()))
\t\t}
''',
)

# --- Private-scoped Feed ------------------------------------------------------
# TrackLogsDao: second local flow scoped by Private membership.
track_logs = ROOT / 'app/src/main/kotlin/org/koitharu/kotatsu/core/db/dao/TrackLogsDao.kt'
text = track_logs.read_text()
needle = '''\tfun observeAll(
\t\tlimit: Int,
\t\tfilterOptions: Set<ListFilterOption>,
\t): Flow<List<TrackLogWithManga>> = observeAllImpl(
\t\tMangaQueryBuilder("track_logs", this)
\t\t\t.where(PRIVATE_SAFE_CONDITION)
\t\t\t.filters(filterOptions)
\t\t\t.limit(limit)
\t\t\t.orderBy("created_at DESC")
\t\t\t.build(),
\t)
'''
addition = needle + '''
\n\tfun observeAllPrivate(
\t\tlimit: Int,
\t\tfilterOptions: Set<ListFilterOption>,
\t): Flow<List<TrackLogWithManga>> = observeAllImpl(
\t\tMangaQueryBuilder("track_logs", this)
\t\t\t.where("EXISTS(SELECT 1 FROM private_favourites pf WHERE pf.manga_id = track_logs.manga_id AND pf.deleted_at = 0)")
\t\t\t.filters(filterOptions)
\t\t\t.limit(limit)
\t\t\t.orderBy("created_at DESC")
\t\t\t.build(),
\t)
'''
if 'fun observeAllPrivate(' not in text:
    if needle not in text:
        raise SystemExit('TrackLogs observeAll anchor missing')
    text = text.replace(needle, addition, 1)
track_logs.write_text(text)

replace_once(
    'app/src/main/kotlin/org/koitharu/kotatsu/tracker/domain/TrackingRepository.kt',
    'import org.koitharu.kotatsu.details.domain.ProgressUpdateUseCase\n',
    'import org.koitharu.kotatsu.details.domain.ProgressUpdateUseCase\nimport org.koitharu.kotatsu.favourites.data.FavouriteSpace\n',
)
replace_once(
    'app/src/main/kotlin/org/koitharu/kotatsu/tracker/domain/TrackingRepository.kt',
    '''\tfun observeTrackingLog(limit: Int, filterOptions: Set<ListFilterOption>): Flow<List<TrackingLogItem>> {
\t\treturn db.getTrackLogsDao().observeAll(limit, filterOptions)
\t\t\t.mapItems { it.toTrackingLogItem() }
\t\t\t.onStart { gcIfNotCalled() }
\t}
''',
    '''\tfun observeTrackingLog(
\t\tlimit: Int,
\t\tfilterOptions: Set<ListFilterOption>,
\t\tspace: FavouriteSpace = FavouriteSpace.NORMAL,
\t): Flow<List<TrackingLogItem>> {
\t\tval source = if (space == FavouriteSpace.PRIVATE) {
\t\t\tdb.getTrackLogsDao().observeAllPrivate(limit, filterOptions)
\t\t} else {
\t\t\tdb.getTrackLogsDao().observeAll(limit, filterOptions)
\t\t}
\t\treturn source.mapItems { it.toTrackingLogItem() }
\t\t\t.onStart { if (space == FavouriteSpace.NORMAL) gcIfNotCalled() }
\t}
''',
)

replace_once(
    'app/src/main/kotlin/org/koitharu/kotatsu/tracker/ui/feed/FeedViewModel.kt',
    'import androidx.lifecycle.viewModelScope\n',
    'import androidx.lifecycle.SavedStateHandle\nimport androidx.lifecycle.viewModelScope\n',
)
replace_once(
    'app/src/main/kotlin/org/koitharu/kotatsu/tracker/ui/feed/FeedViewModel.kt',
    'import kotlinx.coroutines.flow.update\n',
    'import kotlinx.coroutines.flow.update\nimport kotlinx.coroutines.flow.flowOf\n',
)
replace_once(
    'app/src/main/kotlin/org/koitharu/kotatsu/tracker/ui/feed/FeedViewModel.kt',
    'import org.koitharu.kotatsu.history.data.HistoryRepository\n',
    'import org.koitharu.kotatsu.history.data.HistoryRepository\nimport org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE\nimport org.koitharu.kotatsu.favourites.data.FavouriteSpace\n',
)
replace_once(
    'app/src/main/kotlin/org/koitharu/kotatsu/tracker/ui/feed/FeedViewModel.kt',
    '''\tprivate val db: MangaDatabase,
) : BaseViewModel(), QuickFilterListener by quickFilter {
''',
    '''\tprivate val db: MangaDatabase,
\tsavedStateHandle: SavedStateHandle,
) : BaseViewModel(), QuickFilterListener by quickFilter {

\tval favouriteSpace = FavouriteSpace.fromArgument(
\t\tsavedStateHandle[EXTRA_FAVOURITE_SPACE] ?: FavouriteSpace.NORMAL.dbValue,
\t)
''',
)
replace_once(
    'app/src/main/kotlin/org/koitharu/kotatsu/tracker/ui/feed/FeedViewModel.kt',
    '''\tval isRunning = scheduler.observeIsRunning()
\t\t.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, false)
''',
    '''\tval isRunning = (if (favouriteSpace == FavouriteSpace.PRIVATE) flowOf(false) else scheduler.observeIsRunning())
\t\t.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, false)
''',
)
replace_once(
    'app/src/main/kotlin/org/koitharu/kotatsu/tracker/ui/feed/FeedViewModel.kt',
    '\t\t\t.flatMapLatest { repository.observeTrackingLog(it.first, it.second) },\n',
    '\t\t\t.flatMapLatest { repository.observeTrackingLog(it.first, it.second, favouriteSpace) },\n',
)
replace_once(
    'app/src/main/kotlin/org/koitharu/kotatsu/tracker/ui/feed/FeedViewModel.kt',
    '''\tinit {
\t\tlaunchJob(Dispatchers.Default) {
\t\t\trepository.gc()
\t\t}
\t}
''',
    '''\tinit {
\t\tif (favouriteSpace == FavouriteSpace.NORMAL) {
\t\t\tlaunchJob(Dispatchers.Default) { repository.gc() }
\t\t}
\t}
''',
)
replace_once(
    'app/src/main/kotlin/org/koitharu/kotatsu/tracker/ui/feed/FeedViewModel.kt',
    '''\tfun update() {
\t\tscheduler.startNow()
\t}

\tfun stopUpdate() {
\t\tlaunchJob(Dispatchers.Default) {
\t\t\tscheduler.stopNow()
\t\t}
\t}
''',
    '''\tfun update() {
\t\tif (favouriteSpace == FavouriteSpace.NORMAL) scheduler.startNow()
\t}

\tfun stopUpdate() {
\t\tif (favouriteSpace != FavouriteSpace.NORMAL) return
\t\tlaunchJob(Dispatchers.Default) { scheduler.stopNow() }
\t}
''',
)

# FeedFragment suppresses Normal/global menus and external refresh in Private.
replace_once(
    'app/src/main/kotlin/org/koitharu/kotatsu/tracker/ui/feed/FeedFragment.kt',
    'import org.koitharu.kotatsu.databinding.FragmentListBinding\n',
    'import org.koitharu.kotatsu.databinding.FragmentListBinding\nimport org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE\nimport org.koitharu.kotatsu.favourites.data.FavouriteSpace\n',
)
replace_once(
    'app/src/main/kotlin/org/koitharu/kotatsu/tracker/ui/feed/FeedFragment.kt',
    '\t\taddMenuProvider(FeedMenuProvider(binding.recyclerView, viewModel, router))\n',
    '''\t\tif (!isPrivateWorkspace()) {
\t\t\taddMenuProvider(FeedMenuProvider(binding.recyclerView, viewModel, router))
\t\t}
''',
)
replace_once(
    'app/src/main/kotlin/org/koitharu/kotatsu/tracker/ui/feed/FeedFragment.kt',
    '''\toverride fun onRefresh() {
\t\tviewModel.update()
\t}
''',
    '''\toverride fun onRefresh() {
\t\tif (isPrivateWorkspace()) {
\t\t\tviewBinding?.swipeRefreshLayout?.isRefreshing = false
\t\t} else {
\t\t\tviewModel.update()
\t\t}
\t}

\tprivate fun isPrivateWorkspace(): Boolean = FavouriteSpace.fromArgument(
\t\targuments?.getInt(EXTRA_FAVOURITE_SPACE) ?: FavouriteSpace.NORMAL.dbValue,
\t) == FavouriteSpace.PRIVATE
''',
)

print('PF5 Private workspace batch applied')
