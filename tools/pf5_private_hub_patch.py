from pathlib import Path


def replace_once(path, old, new):
    p = Path(path)
    text = p.read_text()
    if old not in text:
        raise SystemExit(f"anchor not found in {path}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1))

# 1) System shelves: make the previously agreed progress shelves real virtual categories.
path = "app/src/main/kotlin/org/koitharu/kotatsu/favourites/domain/SystemFavouriteCategories.kt"
p = Path(path)
text = p.read_text()
if "PRIVATE_IN_PROGRESS_CATEGORY_ID" not in text:
    text += "\nconst val PRIVATE_IN_PROGRESS_CATEGORY_ID = Long.MIN_VALUE + 2L\nconst val PRIVATE_IN_PROGRESS_CATEGORY_TITLE = \"Belum Selesai\"\nconst val PRIVATE_COMPLETED_CATEGORY_ID = Long.MIN_VALUE + 3L\nconst val PRIVATE_COMPLETED_CATEGORY_TITLE = \"Selesai\"\n"
    p.write_text(text)

# 2) Container model: expose progress shelves only inside Private, always before Downloaded/Local.
path = "app/src/main/kotlin/org/koitharu/kotatsu/favourites/ui/container/FavouritesContainerViewModel.kt"
replace_once(path,
    "import org.koitharu.kotatsu.favourites.domain.LOCAL_FAVOURITES_CATEGORY_ID\nimport org.koitharu.kotatsu.favourites.domain.LOCAL_FAVOURITES_CATEGORY_TITLE\n",
    "import org.koitharu.kotatsu.favourites.domain.LOCAL_FAVOURITES_CATEGORY_ID\nimport org.koitharu.kotatsu.favourites.domain.LOCAL_FAVOURITES_CATEGORY_TITLE\nimport org.koitharu.kotatsu.favourites.domain.PRIVATE_COMPLETED_CATEGORY_ID\nimport org.koitharu.kotatsu.favourites.domain.PRIVATE_COMPLETED_CATEGORY_TITLE\nimport org.koitharu.kotatsu.favourites.domain.PRIVATE_IN_PROGRESS_CATEGORY_ID\nimport org.koitharu.kotatsu.favourites.domain.PRIVATE_IN_PROGRESS_CATEGORY_TITLE\n")
replace_once(path,
    "\t\t\tincludeLocal = structure.includeLocal,\n\t\t\tlocalCount = counts?.localCount ?: 0,\n\t\t\tdownloadedCount = counts?.downloadedCount ?: 0,\n",
    "\t\t\tincludeLocal = structure.includeLocal,\n\t\t\tincludePrivateProgress = favouriteSpace == FavouriteSpace.PRIVATE,\n\t\t\tlocalCount = counts?.localCount ?: 0,\n\t\t\tdownloadedCount = counts?.downloadedCount ?: 0,\n")
replace_once(path,
    "\t\tincludeLocal: Boolean,\n\t\tlocalCount: Int,\n\t\tdownloadedCount: Int,\n\t): List<FavouriteTabModel> {\n\t\tval result = ArrayList<FavouriteTabModel>(\n\t\t\tsize + (if (showAll) 1 else 0) + (if (includeLocal) 1 else 0) + 1,\n\t\t)\n\t\tif (showAll) result.add(FavouriteTabModel(NO_ID, null, allCount))\n",
    "\t\tincludeLocal: Boolean,\n\t\tincludePrivateProgress: Boolean,\n\t\tlocalCount: Int,\n\t\tdownloadedCount: Int,\n\t): List<FavouriteTabModel> {\n\t\tval result = ArrayList<FavouriteTabModel>(\n\t\t\tsize + (if (showAll) 1 else 0) + (if (includeLocal) 1 else 0) +\n\t\t\t\t(if (includePrivateProgress) 2 else 0) + 1,\n\t\t)\n\t\tif (showAll) result.add(FavouriteTabModel(NO_ID, null, allCount))\n\t\tif (includePrivateProgress) {\n\t\t\tresult.add(FavouriteTabModel(PRIVATE_IN_PROGRESS_CATEGORY_ID, PRIVATE_IN_PROGRESS_CATEGORY_TITLE, 0))\n\t\t\tresult.add(FavouriteTabModel(PRIVATE_COMPLETED_CATEGORY_ID, PRIVATE_COMPLETED_CATEGORY_TITLE, 0))\n\t\t}\n")
replace_once(path,
    "\tfun hide(categoryId: Long) {\n\t\tif (categoryId == LOCAL_FAVOURITES_CATEGORY_ID || categoryId == DOWNLOADED_FAVOURITES_CATEGORY_ID) return\n",
    "\tfun hide(categoryId: Long) {\n\t\tif (categoryId == LOCAL_FAVOURITES_CATEGORY_ID ||\n\t\t\tcategoryId == DOWNLOADED_FAVOURITES_CATEGORY_ID ||\n\t\t\tcategoryId == PRIVATE_IN_PROGRESS_CATEGORY_ID ||\n\t\t\tcategoryId == PRIVATE_COMPLETED_CATEGORY_ID\n\t\t) return\n")
replace_once(path,
    "\tfun deleteCategory(categoryId: Long) {\n\t\tif (categoryId == LOCAL_FAVOURITES_CATEGORY_ID || categoryId == DOWNLOADED_FAVOURITES_CATEGORY_ID) return\n",
    "\tfun deleteCategory(categoryId: Long) {\n\t\tif (categoryId == LOCAL_FAVOURITES_CATEGORY_ID ||\n\t\t\tcategoryId == DOWNLOADED_FAVOURITES_CATEGORY_ID ||\n\t\t\tcategoryId == PRIVATE_IN_PROGRESS_CATEGORY_ID ||\n\t\t\tcategoryId == PRIVATE_COMPLETED_CATEGORY_ID\n\t\t) return\n")

# 3) List VM: status shelves are backed by the existing local ReadingProgress SQL filters.
path = "app/src/main/kotlin/org/koitharu/kotatsu/favourites/ui/list/FavouritesListViewModel.kt"
replace_once(path,
    "import org.koitharu.kotatsu.favourites.domain.LOCAL_FAVOURITES_CATEGORY_ID\nimport org.koitharu.kotatsu.favourites.domain.debounceFavouritesSearch\n",
    "import org.koitharu.kotatsu.favourites.domain.LOCAL_FAVOURITES_CATEGORY_ID\nimport org.koitharu.kotatsu.favourites.domain.PRIVATE_COMPLETED_CATEGORY_ID\nimport org.koitharu.kotatsu.favourites.domain.PRIVATE_IN_PROGRESS_CATEGORY_ID\nimport org.koitharu.kotatsu.favourites.domain.debounceFavouritesSearch\n")
replace_once(path,
    "\tval sortOrder: StateFlow<ListSortOrder?> = when (categoryId) {\n\t\tDOWNLOADED_FAVOURITES_CATEGORY_ID,\n\t\tLOCAL_FAVOURITES_CATEGORY_ID,\n\t\t-> downloadedSortPreferences.state\n\t\tNO_ID -> settings.observeAsFlow(AppSettings.KEY_FAVORITES_ORDER) { allFavoritesSortOrder }\n",
    "\tval sortOrder: StateFlow<ListSortOrder?> = when (categoryId) {\n\t\tDOWNLOADED_FAVOURITES_CATEGORY_ID,\n\t\tLOCAL_FAVOURITES_CATEGORY_ID,\n\t\t-> downloadedSortPreferences.state\n\t\tNO_ID, PRIVATE_IN_PROGRESS_CATEGORY_ID, PRIVATE_COMPLETED_CATEGORY_ID ->\n\t\t\tsettings.observeAsFlow(AppSettings.KEY_FAVORITES_ORDER) { allFavoritesSortOrder }\n")
replace_once(path,
    "\t\tval filters = effectiveFilters.combineWithSettings().first()\n\t\tval allItems = when (categoryId) {\n",
    "\t\tval filters = systemShelfFilters(effectiveFilters.combineWithSettings().first())\n\t\tval allItems = when (categoryId) {\n")
replace_once(path,
    "\t\t\tNO_ID -> repository.observeAll(\n\t\t\t\torder = order,\n\t\t\t\tfilterOptions = filters,\n\t\t\t\tlimit = Int.MAX_VALUE,\n\t\t\t\tspace = favouriteSpace,\n\t\t\t).first()\n",
    "\t\t\tNO_ID, PRIVATE_IN_PROGRESS_CATEGORY_ID, PRIVATE_COMPLETED_CATEGORY_ID -> repository.observeAll(\n\t\t\t\torder = order,\n\t\t\t\tfilterOptions = filters,\n\t\t\t\tlimit = Int.MAX_VALUE,\n\t\t\t\tspace = favouriteSpace,\n\t\t\t).first()\n")
replace_once(path,
    "\t\t\t\tcategoryId == DOWNLOADED_FAVOURITES_CATEGORY_ID ||\n\t\t\t\tcategoryId == LOCAL_FAVOURITES_CATEGORY_ID\n",
    "\t\t\t\tcategoryId == DOWNLOADED_FAVOURITES_CATEGORY_ID ||\n\t\t\t\tcategoryId == LOCAL_FAVOURITES_CATEGORY_ID ||\n\t\t\t\tcategoryId == PRIVATE_IN_PROGRESS_CATEGORY_ID ||\n\t\t\t\tcategoryId == PRIVATE_COMPLETED_CATEGORY_ID\n")
replace_once(path,
    "\t\tval categoryFilters = filters\n",
    "\t\tval categoryFilters = systemShelfFilters(filters)\n")
replace_once(path,
    "\t\t\tNO_ID -> repository.observeAll(\n\t\t\t\tqueryOrder,\n\t\t\t\tcategoryFilters,\n\t\t\t\teffectiveLimit,\n\t\t\t\teffectivePinned,\n\t\t\t\tfavouriteSpace,\n\t\t\t)\n",
    "\t\t\tNO_ID, PRIVATE_IN_PROGRESS_CATEGORY_ID, PRIVATE_COMPLETED_CATEGORY_ID -> repository.observeAll(\n\t\t\t\tqueryOrder,\n\t\t\t\tcategoryFilters,\n\t\t\t\teffectiveLimit,\n\t\t\t\teffectivePinned,\n\t\t\t\tfavouriteSpace,\n\t\t\t)\n")
replace_once(path,
    "\tprivate fun localShelfFilters(filters: Set<ListFilterOption>): Set<ListFilterOption> = buildSet {\n",
    "\tprivate fun systemShelfFilters(filters: Set<ListFilterOption>): Set<ListFilterOption> = when (categoryId) {\n\t\tPRIVATE_IN_PROGRESS_CATEGORY_ID -> buildSet {\n\t\t\taddAll(filters.filterNot { it is ListFilterOption.ReadingProgress })\n\t\t\tadd(ListFilterOption.ReadingProgress.IN_PROGRESS)\n\t\t}\n\t\tPRIVATE_COMPLETED_CATEGORY_ID -> buildSet {\n\t\t\taddAll(filters.filterNot { it is ListFilterOption.ReadingProgress })\n\t\t\tadd(ListFilterOption.ReadingProgress.COMPLETED)\n\t\t}\n\t\telse -> filters\n\t}\n\n\tprivate fun localShelfFilters(filters: Set<ListFilterOption>): Set<ListFilterOption> = buildSet {\n")

# 4) Tab rendering: treat progress shelves as immutable system shelves, never editable categories.
path = "app/src/main/kotlin/org/koitharu/kotatsu/favourites/ui/container/FavouritesTabConfigurationStrategy.kt"
replace_once(path,
    "import org.koitharu.kotatsu.favourites.domain.LOCAL_FAVOURITES_CATEGORY_ID\n",
    "import org.koitharu.kotatsu.favourites.domain.LOCAL_FAVOURITES_CATEGORY_ID\nimport org.koitharu.kotatsu.favourites.domain.PRIVATE_COMPLETED_CATEGORY_ID\nimport org.koitharu.kotatsu.favourites.domain.PRIVATE_IN_PROGRESS_CATEGORY_ID\n")
replace_once(path,
    "\t\tif (item.id != LOCAL_FAVOURITES_CATEGORY_ID && item.id != DOWNLOADED_FAVOURITES_CATEGORY_ID) {\n",
    "\t\tif (!item.id.isSystemCategory()) {\n")
replace_once(path,
    "\t\tLOCAL_FAVOURITES_CATEGORY_ID ->\n\t\t\tSystemStyle(R.drawable.ic_folder_file, materialR.attr.colorTertiaryContainer, materialR.attr.colorTertiary)\n\t\telse -> null\n",
    "\t\tLOCAL_FAVOURITES_CATEGORY_ID ->\n\t\t\tSystemStyle(R.drawable.ic_folder_file, materialR.attr.colorTertiaryContainer, materialR.attr.colorTertiary)\n\t\tPRIVATE_IN_PROGRESS_CATEGORY_ID ->\n\t\t\tSystemStyle(R.drawable.ic_book_page, materialR.attr.colorPrimaryContainer, appcompatR.attr.colorPrimary)\n\t\tPRIVATE_COMPLETED_CATEGORY_ID ->\n\t\t\tSystemStyle(R.drawable.ic_state_finished, materialR.attr.colorSecondaryContainer, materialR.attr.colorSecondary)\n\t\telse -> null\n")
replace_once(path,
    "\tprivate fun Long.isSystemCategory() =\n\t\tthis == NO_ID || this == DOWNLOADED_FAVOURITES_CATEGORY_ID || this == LOCAL_FAVOURITES_CATEGORY_ID\n",
    "\tprivate fun Long.isSystemCategory() =\n\t\tthis == NO_ID || this == DOWNLOADED_FAVOURITES_CATEGORY_ID ||\n\t\t\tthis == LOCAL_FAVOURITES_CATEGORY_ID || this == PRIVATE_IN_PROGRESS_CATEGORY_ID ||\n\t\t\tthis == PRIVATE_COMPLETED_CATEGORY_ID\n")

# 5) Layout: an always-visible Private Hub and a Private-local search input.
path = "app/src/main/res/layout/fragment_favourites_container.xml"
p = Path(path)
text = p.read_text()
if "private_hub_container" not in text:
    anchor = "\n\t\t\t<com.google.android.material.tabs.TabLayout\n\t\t\t\tandroid:id=\"@+id/tabs\""
    if anchor not in text:
        raise SystemExit("layout tabs anchor not found")
    hub = '''\n\t\t\t<LinearLayout\n\t\t\t\tandroid:id="@+id/private_hub_container"\n\t\t\t\tandroid:layout_width="match_parent"\n\t\t\t\tandroid:layout_height="wrap_content"\n\t\t\t\tandroid:orientation="vertical"\n\t\t\t\tandroid:visibility="gone"\n\t\t\t\ttools:visibility="visible">\n\n\t\t\t\t<androidx.appcompat.widget.AppCompatEditText\n\t\t\t\t\tandroid:id="@+id/private_search"\n\t\t\t\t\tandroid:layout_width="match_parent"\n\t\t\t\t\tandroid:layout_height="48dp"\n\t\t\t\t\tandroid:layout_marginStart="16dp"\n\t\t\t\t\tandroid:layout_marginTop="4dp"\n\t\t\t\t\tandroid:layout_marginEnd="16dp"\n\t\t\t\t\tandroid:background="@drawable/bg_favourites_segment_group"\n\t\t\t\t\tandroid:drawableStart="@drawable/ic_search"\n\t\t\t\t\tandroid:drawablePadding="10dp"\n\t\t\t\t\tandroid:hint="@string/search_manga"\n\t\t\t\t\tandroid:imeOptions="actionSearch"\n\t\t\t\t\tandroid:inputType="text"\n\t\t\t\t\tandroid:maxLines="1"\n\t\t\t\t\tandroid:paddingStart="16dp"\n\t\t\t\t\tandroid:paddingEnd="16dp"\n\t\t\t\t\tandroid:singleLine="true"\n\t\t\t\t\tandroid:textColor="?attr/colorOnSurface"\n\t\t\t\t\tandroid:textColorHint="?attr/colorOnSurfaceVariant" />\n\n\t\t\t\t<HorizontalScrollView\n\t\t\t\t\tandroid:layout_width="match_parent"\n\t\t\t\t\tandroid:layout_height="wrap_content"\n\t\t\t\t\tandroid:layout_marginTop="4dp"\n\t\t\t\t\tandroid:fillViewport="false"\n\t\t\t\t\tandroid:overScrollMode="never"\n\t\t\t\t\tandroid:scrollbars="none">\n\n\t\t\t\t\t<LinearLayout\n\t\t\t\t\t\tandroid:layout_width="wrap_content"\n\t\t\t\t\t\tandroid:layout_height="wrap_content"\n\t\t\t\t\t\tandroid:orientation="horizontal"\n\t\t\t\t\t\tandroid:paddingStart="12dp"\n\t\t\t\t\t\tandroid:paddingEnd="12dp">\n\n\t\t\t\t\t\t<com.google.android.material.button.MaterialButton\n\t\t\t\t\t\t\tandroid:id="@+id/private_action_all"\n\t\t\t\t\t\t\tstyle="?attr/materialButtonOutlinedStyle"\n\t\t\t\t\t\t\tandroid:layout_width="wrap_content"\n\t\t\t\t\t\t\tandroid:layout_height="40dp"\n\t\t\t\t\t\t\tandroid:text="@string/all_favourites"\n\t\t\t\t\t\t\tandroid:textAllCaps="false"\n\t\t\t\t\t\t\tapp:icon="@drawable/ic_heart_outline" />\n\n\t\t\t\t\t\t<com.google.android.material.button.MaterialButton\n\t\t\t\t\t\t\tandroid:id="@+id/private_action_downloaded"\n\t\t\t\t\t\t\tstyle="?attr/materialButtonOutlinedStyle"\n\t\t\t\t\t\t\tandroid:layout_width="wrap_content"\n\t\t\t\t\t\t\tandroid:layout_height="40dp"\n\t\t\t\t\t\t\tandroid:layout_marginStart="6dp"\n\t\t\t\t\t\t\tandroid:text="@string/downloads"\n\t\t\t\t\t\t\tandroid:textAllCaps="false"\n\t\t\t\t\t\t\tapp:icon="@drawable/ic_storage" />\n\n\t\t\t\t\t\t<com.google.android.material.button.MaterialButton\n\t\t\t\t\t\t\tandroid:id="@+id/private_action_local"\n\t\t\t\t\t\t\tstyle="?attr/materialButtonOutlinedStyle"\n\t\t\t\t\t\t\tandroid:layout_width="wrap_content"\n\t\t\t\t\t\t\tandroid:layout_height="40dp"\n\t\t\t\t\t\t\tandroid:layout_marginStart="6dp"\n\t\t\t\t\t\t\tandroid:text="@string/local_storage"\n\t\t\t\t\t\t\tandroid:textAllCaps="false"\n\t\t\t\t\t\t\tapp:icon="@drawable/ic_folder_file" />\n\n\t\t\t\t\t\t<com.google.android.material.button.MaterialButton\n\t\t\t\t\t\t\tandroid:id="@+id/private_action_extensions"\n\t\t\t\t\t\t\tstyle="?attr/materialButtonOutlinedStyle"\n\t\t\t\t\t\t\tandroid:layout_width="wrap_content"\n\t\t\t\t\t\t\tandroid:layout_height="40dp"\n\t\t\t\t\t\t\tandroid:layout_marginStart="6dp"\n\t\t\t\t\t\t\tandroid:text="@string/extensions"\n\t\t\t\t\t\t\tandroid:textAllCaps="false"\n\t\t\t\t\t\t\tapp:icon="@drawable/ic_manga_source" />\n\n\t\t\t\t\t\t<com.google.android.material.button.MaterialButton\n\t\t\t\t\t\t\tandroid:id="@+id/private_action_categories"\n\t\t\t\t\t\t\tstyle="?attr/materialButtonOutlinedStyle"\n\t\t\t\t\t\t\tandroid:layout_width="wrap_content"\n\t\t\t\t\t\t\tandroid:layout_height="40dp"\n\t\t\t\t\t\t\tandroid:layout_marginStart="6dp"\n\t\t\t\t\t\t\tandroid:text="@string/categories"\n\t\t\t\t\t\t\tandroid:textAllCaps="false"\n\t\t\t\t\t\t\tapp:icon="@drawable/ic_tag" />\n\n\t\t\t\t\t\t<com.google.android.material.button.MaterialButton\n\t\t\t\t\t\t\tandroid:id="@+id/private_action_settings"\n\t\t\t\t\t\t\tstyle="?attr/materialButtonOutlinedStyle"\n\t\t\t\t\t\t\tandroid:layout_width="wrap_content"\n\t\t\t\t\t\t\tandroid:layout_height="40dp"\n\t\t\t\t\t\t\tandroid:layout_marginStart="6dp"\n\t\t\t\t\t\t\tandroid:text="@string/settings"\n\t\t\t\t\t\t\tandroid:textAllCaps="false"\n\t\t\t\t\t\t\tapp:icon="@drawable/ic_settings" />\n\n\t\t\t\t\t</LinearLayout>\n\t\t\t\t</HorizontalScrollView>\n\t\t\t</LinearLayout>\n'''
    text = text.replace(anchor, hub + anchor, 1)
    p.write_text(text)

# 6) Fragment wiring: always show the Private hub/pager and expose direct actions.
path = "app/src/main/kotlin/org/koitharu/kotatsu/favourites/ui/container/FavouritesContainerFragment.kt"
replace_once(path,
    "import org.koitharu.kotatsu.favourites.domain.LOCAL_FAVOURITES_CATEGORY_ID\n",
    "import org.koitharu.kotatsu.favourites.domain.DOWNLOADED_FAVOURITES_CATEGORY_ID\nimport org.koitharu.kotatsu.favourites.domain.LOCAL_FAVOURITES_CATEGORY_ID\n")
replace_once(path,
    "\tprivate var pendingCategoryRestore: FavouriteContentType? = null\n",
    "\tprivate var pendingCategoryRestore: FavouriteContentType? = null\n\tprivate var pendingPrivateShelfId: Long? = null\n")
replace_once(path,
    "\t\tbinding.buttonCategoryPicker.setOnClickListener { showCategoryPicker() }\n",
    "\t\tbinding.buttonCategoryPicker.setOnClickListener { showCategoryPicker() }\n\t\tsetupPrivateHub(binding)\n")
replace_once(path,
    "\t\tinlineSearchEdit?.hint = hint\n",
    "\t\tinlineSearchEdit?.hint = hint\n\t\tviewBinding?.privateSearch?.hint = hint\n")
replace_once(path,
    "\t\tcategories = value\n\t\tactivity?.invalidateOptionsMenu()\n",
    "\t\tcategories = value\n\t\tactivity?.invalidateOptionsMenu()\n\t\tselectPendingPrivateShelf()\n")
replace_once(path,
    "\tprivate fun onEmptyStateChanged(isEmpty: Boolean) {\n\t\tisEmptyState = isEmpty\n\t\tviewBinding?.run {\n\t\t\tpager.isGone = isEmpty\n\t\t\tstubEmpty.isVisible = isEmpty\n\t\t\ttoggleContentType.isVisible = true\n\t\t}\n",
    "\tprivate fun onEmptyStateChanged(isEmpty: Boolean) {\n\t\tisEmptyState = isEmpty\n\t\tviewBinding?.run {\n\t\t\tval isPrivate = viewModel.favouriteSpace == FavouriteSpace.PRIVATE\n\t\t\t// Private system shelves must remain navigable even when every shelf currently has zero items.\n\t\t\tpager.isGone = isEmpty && !isPrivate\n\t\t\tstubEmpty.isVisible = isEmpty && !isPrivate\n\t\t\ttoggleContentType.isVisible = true\n\t\t\tprivateHubContainer.isVisible = isPrivate\n\t\t}\n")
replace_once(path,
    "\t\tbinding.tabs.isVisible = !isEmptyState && hasMultipleCategories &&\n\t\t\t(forcePrivateTabs || options.showCategoryTabs)\n",
    "\t\tbinding.tabs.isVisible = hasMultipleCategories &&\n\t\t\t(forcePrivateTabs || (!isEmptyState && options.showCategoryTabs))\n")
# Insert helper methods before onSaveInstanceState.
replace_once(path,
    "\n\toverride fun onSaveInstanceState(outState: Bundle) {\n",
    '''\n\tprivate fun setupPrivateHub(binding: FragmentFavouritesContainerBinding) {\n\t\tval isPrivate = viewModel.favouriteSpace == FavouriteSpace.PRIVATE\n\t\tbinding.privateHubContainer.isVisible = isPrivate\n\t\tif (!isPrivate) return\n\n\t\tbinding.privateSearch.apply {\n\t\t\tsetText(searchQuery.value)\n\t\t\tsetSelection(text?.length ?: 0)\n\t\t\tdoAfterTextChanged { value -> searchQuery.value = value?.toString().orEmpty() }\n\t\t}\n\t\tbinding.privateActionAll.setOnClickListener { openPrivateShelf(FavouritesListFragment.NO_ID) }\n\t\tbinding.privateActionDownloaded.setOnClickListener { openPrivateShelf(DOWNLOADED_FAVOURITES_CATEGORY_ID) }\n\t\tbinding.privateActionLocal.setOnClickListener { openPrivateShelf(LOCAL_FAVOURITES_CATEGORY_ID) }\n\t\tbinding.privateActionCategories.setOnClickListener { router.openFavoriteCategories(FavouriteSpace.PRIVATE) }\n\t\tbinding.privateActionExtensions.setOnClickListener { router.openPrivateExtensionsSettings() }\n\t\tbinding.privateActionSettings.setOnClickListener { router.openPrivateFavouritesSettings() }\n\t}\n\n\tprivate fun openPrivateShelf(categoryId: Long) {\n\t\tif (viewModel.favouriteSpace != FavouriteSpace.PRIVATE) return\n\t\tpendingPrivateShelfId = categoryId\n\t\tif (categoryId == LOCAL_FAVOURITES_CATEGORY_ID &&\n\t\t\tcontentTypeStore.selectedType.value == FavouriteContentType.NOVEL\n\t\t) {\n\t\t\tcontentTypeStore.setSelectedType(FavouriteContentType.MANGA)\n\t\t}\n\t\tselectPendingPrivateShelf()\n\t}\n\n\tprivate fun selectPendingPrivateShelf() {\n\t\tval targetId = pendingPrivateShelfId ?: return\n\t\tval index = categories.indexOfFirst { it.id == targetId }\n\t\tif (index < 0) return\n\t\tviewBinding?.pager?.setCurrentItem(index, false)\n\t\tpendingPrivateShelfId = null\n\t}\n\n\toverride fun onSaveInstanceState(outState: Bundle) {\n''')

# 7) Router + Settings: direct secure entry points so Private settings/extensions are visible from the vault.
path = "app/src/main/kotlin/org/koitharu/kotatsu/core/nav/AppRouter.kt"
replace_once(path,
    "    fun openSettings() = startActivity(SettingsActivity::class.java)\n\n    fun openReaderSettings() {\n",
    "    fun openSettings() = startActivity(SettingsActivity::class.java)\n\n    fun openPrivateFavouritesSettings() {\n        startActivity(privateFavouritesSettingsIntent(contextOrNull() ?: return))\n    }\n\n    fun openPrivateExtensionsSettings() {\n        startActivity(privateExtensionsSettingsIntent(contextOrNull() ?: return))\n    }\n\n    fun openReaderSettings() {\n")
replace_once(path,
    "        fun readerSettingsIntent(context: Context) =\n            Intent(context, SettingsActivity::class.java)\n                .setAction(ACTION_READER)\n",
    "        fun privateFavouritesSettingsIntent(context: Context) =\n            Intent(context, SettingsActivity::class.java)\n                .setAction(ACTION_PRIVATE_FAVOURITES_SETTINGS)\n\n        fun privateExtensionsSettingsIntent(context: Context) =\n            Intent(context, SettingsActivity::class.java)\n                .setAction(ACTION_PRIVATE_EXTENSIONS_SETTINGS)\n\n        fun readerSettingsIntent(context: Context) =\n            Intent(context, SettingsActivity::class.java)\n                .setAction(ACTION_READER)\n")
replace_once(path,
    "        const val ACTION_MANAGE_DOWNLOADS = \"${BuildConfig.APPLICATION_ID}.action.MANAGE_DOWNLOADS\"\n",
    "        const val ACTION_PRIVATE_FAVOURITES_SETTINGS = \"${BuildConfig.APPLICATION_ID}.action.MANAGE_PRIVATE_FAVOURITES\"\n        const val ACTION_PRIVATE_EXTENSIONS_SETTINGS = \"${BuildConfig.APPLICATION_ID}.action.MANAGE_PRIVATE_EXTENSIONS\"\n        const val ACTION_MANAGE_DOWNLOADS = \"${BuildConfig.APPLICATION_ID}.action.MANAGE_DOWNLOADS\"\n")

path = "app/src/main/kotlin/org/koitharu/kotatsu/settings/SettingsActivity.kt"
replace_once(path,
    "import android.view.View\n",
    "import android.view.View\nimport android.view.WindowManager\n")
replace_once(path,
    "\toverride fun onCreate(savedInstanceState: Bundle?) {\n\t\tsuper.onCreate(savedInstanceState)\n",
    "\toverride fun onCreate(savedInstanceState: Bundle?) {\n\t\tif (intent?.action == AppRouter.ACTION_PRIVATE_FAVOURITES_SETTINGS ||\n\t\t\tintent?.action == AppRouter.ACTION_PRIVATE_EXTENSIONS_SETTINGS\n\t\t) {\n\t\t\twindow.addFlags(WindowManager.LayoutParams.FLAG_SECURE)\n\t\t}\n\t\tsuper.onCreate(savedInstanceState)\n")
replace_once(path,
    "\t\tval fragment = when (intent?.action) {\n\t\t\tAppRouter.ACTION_READER -> ReaderSettingsFragment()\n",
    "\t\tval fragment = when (intent?.action) {\n\t\t\tAppRouter.ACTION_PRIVATE_FAVOURITES_SETTINGS -> PrivateFavouritesSettingsFragment()\n\t\t\tAppRouter.ACTION_PRIVATE_EXTENSIONS_SETTINGS -> ExtensionsSettingsFragment()\n\t\t\tAppRouter.ACTION_READER -> ReaderSettingsFragment()\n")

print("Private Hub patch applied")
