from pathlib import Path


def replace(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    if old not in text:
        raise SystemExit(f"pattern not found in {path}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1))

# AppRouter: keep Normal as the default, but carry the library-space extra for Private category routes.
path = "app/src/main/kotlin/org/koitharu/kotatsu/core/nav/AppRouter.kt"
replace(
    path,
    "import org.koitharu.kotatsu.core.model.FavouriteCategory\n",
    "import org.koitharu.kotatsu.core.model.FavouriteCategory\n"
    "import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE\n"
    "import org.koitharu.kotatsu.favourites.data.FavouriteSpace\n",
)
replace(
    path,
    "    fun openFavoriteCategories() = startActivity(FavouriteCategoriesActivity::class.java)\n\n"
    "    fun openFavoriteCategoryEdit(categoryId: Long) {\n"
    "        startActivity(\n"
    "            Intent(contextOrNull() ?: return, FavouritesCategoryEditActivity::class.java)\n"
    "                .putExtra(KEY_ID, categoryId),\n"
    "        )\n"
    "    }\n\n"
    "    fun openFavoriteCategoryCreate() = openFavoriteCategoryEdit(FavouritesCategoryEditActivity.NO_ID)\n",
    "    fun openFavoriteCategories(space: FavouriteSpace = FavouriteSpace.NORMAL) {\n"
    "        startActivity(\n"
    "            Intent(contextOrNull() ?: return, FavouriteCategoriesActivity::class.java)\n"
    "                .putExtra(EXTRA_FAVOURITE_SPACE, space.dbValue),\n"
    "        )\n"
    "    }\n\n"
    "    fun openFavoriteCategoryEdit(\n"
    "        categoryId: Long,\n"
    "        space: FavouriteSpace = FavouriteSpace.NORMAL,\n"
    "    ) {\n"
    "        startActivity(\n"
    "            Intent(contextOrNull() ?: return, FavouritesCategoryEditActivity::class.java)\n"
    "                .putExtra(KEY_ID, categoryId)\n"
    "                .putExtra(EXTRA_FAVOURITE_SPACE, space.dbValue),\n"
    "        )\n"
    "    }\n\n"
    "    fun openFavoriteCategoryCreate(space: FavouriteSpace = FavouriteSpace.NORMAL) =\n"
    "        openFavoriteCategoryEdit(FavouritesCategoryEditActivity.NO_ID, space)\n",
)

# Container overflow menu: manage the category set belonging to the active space.
path = "app/src/main/kotlin/org/koitharu/kotatsu/favourites/ui/container/FavouritesContainerMenuProvider.kt"
replace(
    path,
    "import org.koitharu.kotatsu.core.nav.AppRouter\n",
    "import org.koitharu.kotatsu.core.nav.AppRouter\n"
    "import org.koitharu.kotatsu.favourites.data.FavouriteSpace\n",
)
replace(
    path,
    "\tprivate val router: AppRouter,\n\tprivate val isAllFavouritesSelected: () -> Boolean,\n",
    "\tprivate val router: AppRouter,\n\tprivate val favouriteSpace: FavouriteSpace,\n\tprivate val isAllFavouritesSelected: () -> Boolean,\n",
)
replace(
    path,
    "\t\t\tR.id.action_manage -> {\n\t\t\t\trouter.openFavoriteCategories()\n\t\t\t}\n",
    "\t\t\tR.id.action_manage -> {\n\t\t\t\trouter.openFavoriteCategories(favouriteSpace)\n\t\t\t}\n",
)

# Category tab popup: edit/manage must stay in the same FavouriteSpace.
path = "app/src/main/kotlin/org/koitharu/kotatsu/favourites/ui/container/FavouriteTabPopupMenuProvider.kt"
replace(
    path,
    "\t\t\tR.id.action_edit -> router.openFavoriteCategoryEdit(categoryId)\n"
    "\t\t\tR.id.action_delete -> confirmDelete()\n"
    "\t\t\tR.id.action_manage -> router.openFavoriteCategories()\n",
    "\t\t\tR.id.action_edit -> router.openFavoriteCategoryEdit(categoryId, viewModel.favouriteSpace)\n"
    "\t\t\tR.id.action_delete -> confirmDelete()\n"
    "\t\t\tR.id.action_manage -> router.openFavoriteCategories(viewModel.favouriteSpace)\n",
)

# Container: wire its active space into menu routes, and make Private core shelves explicit/visible.
path = "app/src/main/kotlin/org/koitharu/kotatsu/favourites/ui/container/FavouritesContainerFragment.kt"
replace(
    path,
    "import org.koitharu.kotatsu.favourites.domain.FavouriteCategoryNavigationMode\n",
    "import org.koitharu.kotatsu.favourites.data.FavouriteSpace\n"
    "import org.koitharu.kotatsu.favourites.domain.FavouriteCategoryNavigationMode\n",
)
replace(
    path,
    "\t\t\tFavouritesContainerMenuProvider(\n"
    "\t\t\t\trouter = router,\n"
    "\t\t\t\tisAllFavouritesSelected = { currentCategory()?.id == FavouritesListFragment.NO_ID },\n",
    "\t\t\tFavouritesContainerMenuProvider(\n"
    "\t\t\t\trouter = router,\n"
    "\t\t\t\tfavouriteSpace = viewModel.favouriteSpace,\n"
    "\t\t\t\tisAllFavouritesSelected = { currentCategory()?.id == FavouritesListFragment.NO_ID },\n",
)
replace(
    path,
    "\t\t\tR.id.button_retry -> router.openFavoriteCategories()\n",
    "\t\t\tR.id.button_retry -> router.openFavoriteCategories(viewModel.favouriteSpace)\n",
)
replace(
    path,
    "\t\tval hasCategories = categories.isNotEmpty()\n"
    "\t\tval hasMultipleCategories = categories.size > 1\n"
    "\t\tbinding.tabs.isVisible = !isEmptyState && hasMultipleCategories && options.showCategoryTabs\n"
    "\t\tbinding.buttonCategoryPicker.isVisible = !isEmptyState && hasCategories && !options.showCategoryTabs\n",
    "\t\tval hasCategories = categories.isNotEmpty()\n"
    "\t\tval hasMultipleCategories = categories.size > 1\n"
    "\t\t// Private must never look like a two-button Manga/Novel-only screen. Its core shelves\n"
    "\t\t// (All, Downloaded, Local and Private categories) stay explicitly visible regardless of\n"
    "\t\t// the Normal library's display preference. This is display-only and does not mix data.\n"
    "\t\tval forcePrivateTabs = viewModel.favouriteSpace == FavouriteSpace.PRIVATE\n"
    "\t\tbinding.tabs.isVisible = !isEmptyState && hasMultipleCategories &&\n"
    "\t\t\t(forcePrivateTabs || options.showCategoryTabs)\n"
    "\t\tbinding.buttonCategoryPicker.isVisible = !isEmptyState && hasCategories &&\n"
    "\t\t\t!forcePrivateTabs && !options.showCategoryTabs\n",
)

print("Private navigation patch applied")
