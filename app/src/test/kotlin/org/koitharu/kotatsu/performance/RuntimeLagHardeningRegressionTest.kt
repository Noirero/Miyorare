package org.koitharu.kotatsu.performance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RuntimeLagHardeningRegressionTest {

	@Test
	fun `reader prefetch cannot occupy every foreground page-load slot`() {
		val source = source("kotlin/org/koitharu/kotatsu/reader/domain/PageLoader.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(source.contains("privatevalsemaphore=Semaphore(4)"))
		assertTrue(source.contains("privatevalprefetchSemaphore=Semaphore(PREFETCH_MAX_PARALLELISM)"))
		assertTrue(source.contains("privateconstvalPREFETCH_MAX_PARALLELISM=2"))
		assertTrue(source.contains("if(isPrefetch){prefetchSemaphore.withPermit{"))
		assertTrue(source.contains("loadPageWithPermit(page,progress,isPrefetch=true,skipCache=skipCache)"))
	}

	@Test
	fun `adaptive update reuses one source-health snapshot per source in a selection window`() {
		val source = source("kotlin/org/koitharu/kotatsu/tracker/domain/SmartUpdatePolicy.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(source.contains("valsourceHealth=HashMap<String,SourceHealthRepository.State>()"))
		assertTrue(source.contains("sourceHealth.getOrPut(tracking.manga.source.name)"))
		assertTrue(source.contains("sourceHealthRepository.snapshotForScheduling(tracking.manga.source).state"))
	}


	@Test
	fun `per manga reader profile owns the first ReaderSettings emission`() {
		val settings = source("kotlin/org/koitharu/kotatsu/reader/ui/config/ReaderSettings.kt")
			.replace(Regex("\\s+"), "")
		val reader = source("kotlin/org/koitharu/kotatsu/reader/ui/ReaderViewModel.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(settings.contains("@AssistedinitialMangaId:Long"))
		assertTrue(settings.contains("profileStore.get(initialMangaId)"))
		assertTrue(settings.contains("funcreate(mangaId:Flow<Long>,initialMangaId:Long):Producer"))
		assertFalse(
			"Reader must not publish global settings first and apply the manga profile afterwards",
			settings.contains("MediatorStateFlow<ReaderSettings>(ReaderSettings(settings,null,null))"),
		)
		assertTrue(reader.contains("initialMangaId=intent.mangaId"))
	}

	@Test
	fun `manual tracker refresh semantics do not depend on foreground promotion`() {
		val worker = source("kotlin/org/koitharu/kotatsu/tracker/work/TrackWorker.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(worker.contains("trySetForeground()"))
		assertTrue(worker.contains("doWorkImpl(isFullRun=TAG_ONESHOTintags)"))
		assertFalse(
			"Foreground-service promotion is not the source of truth for a manual full refresh",
			worker.contains("isForeground&&TAG_ONESHOTintags"),
		)
	}

	@Test
	fun `source migration prepares durable metadata before Room and cleans it after commit`() {
		val migration = source("kotlin/org/koitharu/kotatsu/alternatives/domain/MigrateUseCase.kt")
			.replace(Regex("\\s+"), "")
		val profiles = source("kotlin/org/koitharu/kotatsu/reader/ui/config/MangaReaderProfileStore.kt")
			.replace(Regex("\\s+"), "")
		val notes = source("kotlin/org/koitharu/kotatsu/details/data/MangaNotesRepository.kt")
			.replace(Regex("\\s+"), "")

		val profilePrepare = migration.indexOf("mangaReaderProfileStore.prepareMove(oldDetails.id,newDetails.id)")
		val notePrepare = migration.indexOf("mangaNotesRepository.prepareMove(oldDetails.id,newDetails.id)")
		val room = migration.indexOf("database.withTransaction{")
		val profileFinish = migration.indexOf("mangaReaderProfileStore.finishPreparedMove(oldDetails.id,newDetails.id)")
		val noteFinish = migration.indexOf("mangaNotesRepository.finishPreparedMove(oldDetails.id,newDetails.id)")

		assertTrue(profilePrepare >= 0 && profilePrepare < room)
		assertTrue(notePrepare >= 0 && notePrepare < room)
		assertTrue(profileFinish > room)
		assertTrue(noteFinish > room)
		assertTrue(migration.contains("mangaReaderProfileStore.rollbackPreparedMove(newDetails.id)"))
		assertTrue(migration.contains("mangaNotesRepository.rollbackPreparedMove(newDetails.id)"))
		assertFalse(migration.contains("mangaReaderProfileStore.move("))
		assertFalse(migration.contains("mangaNotesRepository.move("))

		assertTrue(profiles.contains("funprepareMove(oldMangaId:Long,newMangaId:Long):Boolean"))
		assertTrue(profiles.contains(".commit()"))
		assertTrue(notes.contains("funprepareMove(oldMangaId:Long,newMangaId:Long):Boolean"))
		assertTrue(notes.contains(".commit()"))
	}

	@Test
	fun `User Agent imports legacy preference once and removes the old key`() {
		val manager = source("kotlin/org/koitharu/kotatsu/core/network/UserAgentManager.kt")
			.replace(Regex("\\s+"), "")
		val settings = source("kotlin/org/koitharu/kotatsu/core/prefs/AppSettings.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(manager.contains("vallegacyOverride=prefs.getString(AppSettings.KEY_MIHON_USER_AGENT"))
		assertFalse(manager.contains("putString(AppSettings.KEY_MIHON_USER_AGENT"))
		assertTrue(manager.contains("remove(AppSettings.KEY_MIHON_USER_AGENT)"))
		assertFalse(settings.contains("valmihonUserAgentOverride:"))
	}



	@Test
	fun `startup update schedulers remain unique across repeated cold starts`() {
		val extension = source("kotlin/org/koitharu/kotatsu/extensions/install/ExtensionUpdateWorker.kt")
			.replace(Regex("\\s+"), "")
		val sourcePack = source("kotlin/org/koitharu/kotatsu/tsuki/MiyorareSourcePackUpdateWorker.kt")
			.replace(Regex("\\s+"), "")

		for (worker in listOf(extension, sourcePack)) {
			assertTrue(worker.contains("enqueueUniquePeriodicWork("))
			assertTrue(worker.contains("ExistingPeriodicWorkPolicy.UPDATE"))
			assertTrue(worker.contains("enqueueUniqueWork("))
			assertTrue(worker.contains("ExistingWorkPolicy.KEEP"))
			assertTrue(worker.contains("IMMEDIATE_WORK_NAME"))
			assertTrue(worker.contains("PERIODIC_WORK_NAME"))
		}
		assertTrue(extension.contains("constvalIMMEDIATE_WORK_NAME=\"extension_auto_updates_now\""))
		assertTrue(sourcePack.contains("constvalIMMEDIATE_WORK_NAME=\"miyorare_source_pack_auto_updates_now\""))
	}

	@Test
	fun `cold start favourites renders before optional card and cover enrichment`() {
		val viewModel = source("kotlin/org/koitharu/kotatsu/favourites/ui/list/FavouritesListViewModel.kt")
			.replace(Regex("\\s+"), "")
		val fragment = source("kotlin/org/koitharu/kotatsu/favourites/ui/list/FavouritesListFragment.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(viewModel.contains("privateconstvalDATABASE_WINDOW_INITIAL=PAGE_SIZE"))
		assertTrue(viewModel.contains("settings.allFavoritesSortOrder"))
		assertTrue(viewModel.contains("scheduleCardEnrichment(enrichmentKey)"))
		assertTrue(viewModel.contains("matchingEnrichment?.snapshot?:emptyCardSnapshot"))
		val mapList = viewModel.substringAfter("privatesuspendfunList<Manga>.mapList(")
			.substringBefore("privatefunsearchWithLibraryGroups")
		assertFalse(
			"Visible favourites must not wait for Room unread/history enrichment before the first frame",
			mapList.contains("unreadCounter.getSnapshot("),
		)

		assertTrue(fragment.contains("Semaphore(2)"))
		assertTrue(fragment.contains("RecyclerView.SCROLL_STATE_IDLE"))
		assertTrue(fragment.contains("postDelayed(coverPrefetchRunnable,COVER_PREFETCH_IDLE_DELAY_MS)"))
		assertTrue(fragment.contains("privateconstvalCOVER_PREFETCH_BATCH=12"))
	}

	@Test
	fun `favourites scrolling never starts filesystem download reconciliation`() {
		val classifier = source("kotlin/org/koitharu/kotatsu/favourites/domain/DownloadedContentClassifier.kt")
			.replace(Regex("\\s+"), "")
		val viewModel = source("kotlin/org/koitharu/kotatsu/favourites/ui/list/FavouritesListViewModel.kt")
			.replace(Regex("\\s+"), "")
		val destinationStore = source("kotlin/org/koitharu/kotatsu/download/domain/DownloadDestinationStore.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(classifier.contains("suspendfungetKnownDownloadedIds("))
		assertFalse(classifier.contains("getDownloadedIdsExact"))
		assertFalse(classifier.contains("findSavedMangaInRoot"))
		assertFalse(classifier.contains("findMangaById"))
		assertFalse(classifier.contains("listFiles("))
		assertFalse(classifier.contains("canonicalPath"))
		assertFalse(classifier.contains("CoroutineScope("))
		assertFalse(destinationStore.contains("isLegacyIndexMigrationRequired"))
		assertFalse(destinationStore.contains("markLegacyIndexMigrationComplete"))
		assertFalse(viewModel.contains("getDownloadedIdsExact"))
		assertTrue(viewModel.contains("valdeltaIds=key.ids.drop(reusedCount)"))
		assertTrue(viewModel.contains("previous?.snapshot?.merge(deltaSnapshot)"))
	}

	@Test
	fun `library cover retention is large and image work is concurrency bounded`() {
		val appModule = source("kotlin/org/koitharu/kotatsu/core/AppModule.kt")
			.replace(Regex("\\s+"), "")
		val coil = source("kotlin/org/koitharu/kotatsu/core/util/ext/Coil.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(coil.contains("diskCacheKey(mangaCoverDiskCacheKey(manga.id))"))
		assertTrue(appModule.contains(".maxSizePercent(0.10)"))
		assertTrue(appModule.contains(".minimumMaxSizeBytes(256L*1024L*1024L)"))
		assertTrue(appModule.contains(".maximumMaxSizeBytes(2L*1024L*1024L*1024L)"))
		assertTrue(appModule.contains(".fetcherCoroutineContext(Dispatchers.IO.limitedParallelism(8))"))
		assertTrue(appModule.contains(".decoderCoroutineContext(Dispatchers.IO.limitedParallelism(3))"))
	}

	@Test
	fun `Normal Favourites glass has one visual owner and no patch stack`() {
		val container = source("kotlin/org/koitharu/kotatsu/favourites/ui/container/FavouritesContainerFragment.kt")
			.replace(Regex("\\s+"), "")
		val header = source("kotlin/org/koitharu/kotatsu/core/ui/MiyorareHeaderLayouts.kt")
			.replace(Regex("\\s+"), "")
		val tabs = source("kotlin/org/koitharu/kotatsu/favourites/ui/container/FavouritesTabConfigurationStrategy.kt")
			.replace(Regex("\\s+"), "")
		val list = source("kotlin/org/koitharu/kotatsu/favourites/ui/list/FavouritesListFragment.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(container.contains("layoutCategoryHeader?.refreshModernPresentation()"))
		assertFalse(
			"Fragment must not rebuild Normal Favourites glass drawables alongside the header owner",
			container.contains("applyNormalNeonVisualFoundation"),
		)
		assertTrue(header.contains("funrefreshModernPresentation()=scheduleModernPresentation()"))
		assertTrue(header.contains("applyNormalHeaderGeometry("))
		assertTrue(header.contains("showDividers=LinearLayout.SHOW_DIVIDER_MIDDLE"))
		assertTrue(
			"Normal tab configuration may style each tab, but must not restyle the whole header",
			tabs.contains("if(privateFavourites)applyPrivateModernHeaderDensity(view)"),
		)
		assertFalse(tabs.contains("applyModernHeaderDensity(view)"))
		assertFalse(tabs.contains("baseBackgrounds"))
		assertTrue(
			"Normal Modern tabs must return a state-only overlay instead of owning a second rail material",
			tabs.contains("if(modern&&!privateFavourites){returncreateNormalModernSelectedOverlay(context)}"),
		)
		assertTrue(tabs.contains("valseparator=isLastSystemTab(position)&&(!modern||privateFavourites)"))
		val normalCategoryOverlay = tabs
			.substringAfter("privatefuncreateNormalModernSelectedOverlay")
			.substringBefore("privatefuncreateSystemTitle")
		assertTrue(normalCategoryOverlay.contains("intArrayOf(selectedFillColor,Color.TRANSPARENT)"))
		assertFalse(normalCategoryOverlay.contains("setStroke("))
		assertFalse(normalCategoryOverlay.contains("LayerDrawable("))
		assertTrue(list.contains("shouldDrawFill=false"))
		assertTrue(list.contains("shouldDrawStroke=false"))
		assertTrue(list.contains("shouldDrawGlow=true"))
	}

	@Test
	fun `Normal Favourites selected controls do not stack legacy and modern chrome`() {
		val header = source("kotlin/org/koitharu/kotatsu/core/ui/MiyorareHeaderLayouts.kt")
			.replace(Regex("\\s+"), "")
		val actions = source("kotlin/org/koitharu/kotatsu/list/ui/adapter/QuickFilterAD.kt")
			.replace(Regex("\\s+"), "")
		val legacyNav = source("kotlin/org/koitharu/kotatsu/main/ui/nav/LegacyGlowNavBar.kt")
			.replace(Regex("\\s+"), "")
		val floatingNav = source("kotlin/org/koitharu/kotatsu/main/ui/nav/FloatingNavBar.kt")
			.replace(Regex("\\s+"), "")

		assertFalse(header.contains("createCheckedGlassBloom("))
		assertTrue(header.contains("foreground=null"))
		assertTrue(header.contains("stateListAnimator=null"))
		assertTrue(actions.contains("chip.chipStrokeWidth=0f"))
		assertTrue(actions.contains("chip.foreground=createMiyorareFavouritesActionChrome("))
		assertTrue(actions.contains("returnLayerDrawable("))
		assertFalse(actions.contains("chip.chipStrokeWidth=density*if(normalNeon)"))
		assertFalse(legacyNav.contains("BOTTOM_NAV_INNER_HIGHLIGHT_ALPHA"))
		assertFalse(legacyNav.contains("BOTTOM_NAV_SELECTED_GLOW_ALPHA"))
		assertFalse(floatingNav.contains("BOTTOM_NAV_INNER_HIGHLIGHT_ALPHA"))
		assertFalse(floatingNav.contains("BOTTOM_NAV_SELECTED_GLOW_ALPHA"))
	}

	@Test
	fun `Normal Favourites reference geometry stays centralized responsive and icon complete`() {
		val spec = source("kotlin/org/koitharu/kotatsu/core/ui/MiyorareFavouritesVisualSpec.kt")
			.replace(Regex("\\s+"), "")
		val header = source("kotlin/org/koitharu/kotatsu/core/ui/MiyorareHeaderLayouts.kt")
			.replace(Regex("\\s+"), "")
		val actions = source("kotlin/org/koitharu/kotatsu/list/ui/adapter/QuickFilterAD.kt")
			.replace(Regex("\\s+"), "")
		val cards = source("kotlin/org/koitharu/kotatsu/list/ui/adapter/MangaGridItemAD.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(spec.contains("constvalSCREEN_HORIZONTAL_MARGIN_DP=18f"))
		assertTrue(spec.contains("constvalGRID_ITEM_MARGIN_DP=4f"))
		assertTrue(spec.contains("constvalMANGA_CARD_ASPECT_RATIO=0.845f"))
		assertTrue(spec.contains("constvalBOTTOM_NAV_HEIGHT_DP=70f"))
		assertTrue(header.contains("R.drawable.ic_book_pageelseR.drawable.ic_novel_book"))
		assertTrue(header.contains("MiyorareFavouritesVisualSpec.CONTENT_TOGGLE_ICON_DP"))
		assertTrue(header.contains("MiyorareFavouritesVisualSpec.SEARCH_VISUAL_HEIGHT_DP"))
		assertTrue(actions.contains("valactionCount=chipsTags.childCount.coerceAtLeast(1)"))
		assertTrue(actions.contains("(contentWidth-gap*(actionCount-1))/actionCount.toFloat()"))
		assertTrue(actions.contains("chipsTags.setFixedChildWidth(actionWidth)"))
		assertTrue(cards.contains("coverWidth/MiyorareFavouritesVisualSpec.MANGA_CARD_ASPECT_RATIO"))
		assertFalse(header.contains("setPadding(0,dp(14f),0,dp(2f))"))
		assertFalse(actions.contains("favorites_continue_reading->(108f*density).toInt()"))
		assertFalse(actions.contains("favorites_new_chapters->(104f*density).toInt()"))
		assertFalse(actions.contains("favorites_filter->(96f*density).toInt()"))
	}

	@Test
	fun `Normal main navigation owns the Favourites glass emphasis across destinations`() {
		val host = source("kotlin/org/koitharu/kotatsu/core/ui/widgets/FloatingBottomNavigationView.kt")
			.replace(Regex("\\s+"), "")
		val legacy = source("kotlin/org/koitharu/kotatsu/main/ui/nav/LegacyGlowNavBar.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(host.contains("valemphasizeFavourites=!privateFavouritesHost"))
		assertFalse(host.contains("emphasizeFavourites=!privateFavouritesHost&&selectedId==R.id.nav_favorites"))
		assertTrue(legacy.contains("emphasizeFavourites:Boolean=false"))
		assertTrue(legacy.contains("valfavouritesGlass=if(emphasizeFavourites)"))
		assertFalse(
			"Normal legacy bar must not fall back to an always-opaque single-color container",
			legacy.contains("color=barContainer,contentColor="),
		)
	}

	@Test
	fun `Normal Favourites uses exact full height portrait wallpaper per theme without recrop`() {
		val drawable = source("kotlin/org/koitharu/kotatsu/core/ui/MiyorareHeaderShapeDrawable.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(drawable.contains("drawFullPortraitBackground(canvas,bitmap,width)"))
		assertTrue(drawable.contains("MIYORARE_BACKGROUND_CHUNK_COUNT=42"))
		assertTrue(drawable.contains("FAVOURITES_PORTRAIT_WIDTH_PX=1080"))
		assertTrue(drawable.contains("FAVOURITES_PORTRAIT_HEIGHT_PX=2408"))
		assertTrue(drawable.contains("privatefunusesFullPortraitArtwork():Boolean=!privateStyle"))
		assertTrue(drawable.contains("MIYORARE_GOLDEN_CHUNK_COUNT=8"))
		assertTrue(drawable.contains("\"miyorare-hi\""))
		assertTrue(
			drawable.contains("constvalFAVOURITES_ASSET_DIR=\"miyorare/header-full/favourites\""),
		)
		for (theme in listOf("sakura", "violet", "cyan", "emerald", "amber")) {
			assertTrue(
				drawable.contains("\"\$FAVOURITES_ASSET_DIR/theme-full/miyorare_favourites_${theme}.webp\""),
			)
		}
		assertTrue(drawable.contains("valscale=width/bitmap.width.toFloat()"))
		assertTrue(drawable.contains("vallocalTop=-favouritesArtworkTopOffset()"))
		val portraitRenderer = drawable
			.substringAfter("privatefundrawFullPortraitBackground")
			.substringBefore("privatefundrawFavouritesArtworkContinuation")
		assertFalse(portraitRenderer.contains("maxOf("))
		assertFalse(portraitRenderer.contains("canvas.scale(1f,-1f)"))
		assertFalse(
			"Normal Favourites portrait wallpapers must not repeat rectangular artwork strips",
			portraitRenderer.contains("while("),
		)
	}


	@Test
	fun `main destinations share one wallpaper owner without live scroll blur`() {
		val drawable = source("kotlin/org/koitharu/kotatsu/core/ui/MiyorareHeaderShapeDrawable.kt")
			.replace(Regex("\\s+"), "")
		val main = source("kotlin/org/koitharu/kotatsu/main/ui/MainActivity.kt")
			.replace(Regex("\\s+"), "")
		val explore = source("kotlin/org/koitharu/kotatsu/explore/ui/ExploreFragment.kt")
			.replace(Regex("\\s+"), "")
		val layout = source("res/layout/activity_main.xml")

		assertTrue(drawable.contains("APP_BACKGROUND"))
		assertTrue(drawable.contains("blurredFavouritesArtworkCache=HashMap<String,Bitmap>()"))
		assertTrue(drawable.contains("APP_BACKGROUND_BLUR_WIDTH_PX=135"))
		assertTrue(drawable.contains("APP_BACKGROUND_BLUR_HEIGHT_PX=301"))
		assertTrue(drawable.contains("repeat(APP_BACKGROUND_BLUR_PASSES)"))
		assertFalse("App wallpaper blur must not become a per-frame RenderEffect", drawable.contains("RenderEffect"))
		assertTrue(main.contains("valisFavourites=fragmentisFavouritesContainerFragment"))
		assertTrue(main.contains("if(isFavourites){MiyorareHeaderShapeDrawable.Variant.FAVOURITES_TOP}else{MiyorareHeaderShapeDrawable.Variant.APP_BACKGROUND}"))
		assertTrue(main.contains("append(if(isFavourites)\"favourites-sharp\"else\"shared-blur\")"))
		assertTrue(layout.contains("android:id=\"@+id/app_background\""))
		assertTrue(explore.contains("binding.root.setBackgroundColor(Color.TRANSPARENT)"))
	}






	@Test
	fun `Normal Favourites in MainActivity has one sharp wallpaper owner while Private keeps dedicated renderer`() {
		val main = source("kotlin/org/koitharu/kotatsu/main/ui/MainActivity.kt")
			.replace(Regex("\\s+"), "")
		val header = source("kotlin/org/koitharu/kotatsu/core/ui/MiyorareHeaderLayouts.kt")
			.replace(Regex("\\s+"), "")
		val list = source("kotlin/org/koitharu/kotatsu/favourites/ui/list/FavouritesListFragment.kt")
			.replace(Regex("\\s+"), "")
		val privateDrawable = source("kotlin/org/koitharu/kotatsu/core/ui/MiyorarePrivateFavouritesHeaderDrawable.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(main.contains("MiyorareHeaderShapeDrawable.Variant.FAVOURITES_TOP"))
		assertTrue(header.contains("valsharedNormalBackdrop=usesSharedNormalFavouritesBackdrop(privateFavourites)"))
		assertTrue(header.contains("!privateFavourites&&rootView.findViewById<View>(R.id.app_background)!=null"))
		assertTrue(header.contains("appBar.background=if(sharedNormalBackdrop){null}else{"))
		assertTrue(header.contains("if(sharedNormalBackdrop){null}else{createFavouritesHeaderDrawable("))
		assertTrue(list.contains("valsharedNormalBackdrop=activity?.findViewById<View>(R.id.app_background)!=null"))
		assertTrue(list.contains("binding.root.setBackgroundColor(Color.TRANSPARENT)"))
		assertTrue("Standalone Normal fallback must remain available", list.contains("extendFavouritesArtwork=true"))
		assertTrue("Private must keep its dedicated stable renderer", privateDrawable.contains("classMiyorarePrivateFavouritesHeaderDrawable"))
	}



	@Test
	fun `private workspace owns toolbar title so settings label cannot leak across tabs`() {
		val workspace = source("kotlin/org/koitharu/kotatsu/favourites/ui/PrivateWorkspaceFragment.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(workspace.contains("updateTitle(itemIdFor(f))"))
		assertTrue(workspace.contains("if(current!=null)updateTitle(itemIdFor(current))"))
		assertTrue(workspace.contains("valtitleText=if(itemId==R.id.private_nav_favourites){\"\"}else{"))
		assertTrue(workspace.contains("findViewById<MaterialToolbar>(R.id.toolbar)?.title=titleText"))
		assertTrue(workspace.contains("findViewById<CollapsingToolbarLayout>(R.id.collapsingToolbarLayout)?.title=titleText"))
		assertTrue(workspace.contains("R.id.private_nav_settings->R.string.private_workspace_settings"))
	}



	@Test
	fun `custom adaptive background preprocesses once and never leaks into Private`() {
		val store = source("kotlin/org/koitharu/kotatsu/core/ui/MiyorareCustomBackgroundStore.kt")
			.replace(Regex("\\s+"), "")
		val appearance = source("kotlin/org/koitharu/kotatsu/settings/AppearanceSettingsFragment.kt")
			.replace(Regex("\\s+"), "")
		val palette = source("kotlin/org/koitharu/kotatsu/core/ui/MiyorareViewPalette.kt")
			.replace(Regex("\\s+"), "")
		val colors = source("kotlin/org/koitharu/kotatsu/core/ui/MiyorareColorScheme.kt")
			.replace(Regex("\\s+"), "")
		val drawable = source("kotlin/org/koitharu/kotatsu/core/ui/MiyorareHeaderShapeDrawable.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(store.contains("SHARP_WIDTH=1080"))
		assertTrue(store.contains("SHARP_HEIGHT=2408"))
		assertTrue(store.contains("BLUR_WIDTH=180"))
		assertTrue(store.contains("BLUR_HEIGHT=401"))
		assertTrue(store.contains("extractDominantPalette(portrait)"))
		assertTrue(store.contains("putString(MiyorareAppearance.KEY_CUSTOM_BACKGROUND_PRIMARY"))
		assertTrue(store.contains("putString(MiyorareAppearance.KEY_CUSTOM_BACKGROUND_SECONDARY"))
		assertTrue(store.contains("putString(MiyorareAppearance.KEY_CUSTOM_BACKGROUND_TERTIARY"))
		assertFalse("Custom wallpaper preprocessing must not use live RenderEffect", store.contains("RenderEffect"))

		assertTrue(appearance.contains("ActivityResultContracts.PickVisualMedia()"))
		assertTrue(appearance.contains("withContext(Dispatchers.IO)"))
		assertTrue(appearance.contains("miyorare_custom_background_use_colors"))
		assertTrue(appearance.contains("miyorare_custom_background_intensity"))

		assertTrue(palette.contains("valcustomBackgroundActive=!privateFavourites&&preset==MiyorareThemePreset.CUSTOM"))
		assertTrue(palette.contains("customBackgroundPath=if(customBackgroundActive)"))
		assertTrue(colors.contains("adaptivePalette?.let{palette->PaletteSeeds("))
		assertTrue(drawable.contains("palette.customBackgroundBlurPath?.let(::loadCustomBlurredArtwork)"))
		assertTrue(drawable.contains("custom-user-full-\${palette.customBackgroundRevision}"))
	}



	@Test
	fun `modern main chrome stays consistent across Explore Updates and History`() {
		val main = source("kotlin/org/koitharu/kotatsu/main/ui/MainActivity.kt")
			.replace(Regex("\\s+"), "")
		val chrome = source("kotlin/org/koitharu/kotatsu/core/ui/MiyorareMainChrome.kt")
			.replace(Regex("\\s+"), "")
		val quickFilters = source("kotlin/org/koitharu/kotatsu/list/ui/adapter/QuickFilterAD.kt")
			.replace(Regex("\\s+"), "")
		val explore = source("kotlin/org/koitharu/kotatsu/explore/ui/MiyorareExploreHeaderLayout.kt")
			.replace(Regex("\\s+"), "")
		val exploreLayout = source("res/layout/layout_explore_header.xml")
			.replace(Regex("\\s+"), "")

		assertTrue(main.contains("viewBinding.root.post{viewBinding.root.applyMiyorareSharedMainChrome()}"))
		assertTrue(chrome.contains("MiyorareFavouritesVisualSpec.SEARCH_VISUAL_HEIGHT_DP"))
		assertTrue(chrome.contains("MiyorareFavouritesVisualSpec.SEARCH_RADIUS_DP"))
		assertTrue(chrome.contains("createSharedMainGlassOutline(glass,radius,density)"))

		assertTrue(quickFilters.contains("applyMiyorareModernQuickFilterStyle(item)"))
		assertTrue(quickFilters.contains("if(isPrivate&&!isFavouritesQuickFilter)return"))
		assertTrue(quickFilters.contains("chipsTags.applyMiyorareFavouritesQuickFilterStyle("))
		assertTrue(
			"Normal Modern quick filters must use the subtle adaptive glass fill",
			quickFilters.contains("subtleGlassFill=!isPrivate"),
		)
		assertFalse(
			"Normal Updates/History must not be rejected before Modern glass styling",
			quickFilters.contains("if(!isFavouritesQuickFilter)return"),
		)

		assertTrue(explore.contains("setBackgroundColor(Color.TRANSPARENT)"))
		assertFalse("Explore must not paint an opaque secondary wallpaper header", explore.contains("Variant.EXPLORE"))
		assertTrue(explore.contains("valglass=palette.neonGlass()"))
		assertTrue(exploreLayout.contains("android:background=\"@android:color/transparent\""))
	}



	@Test
	fun `Modern overlays and Group details share adaptive glass in light and dark`() {
		val overlay = source("kotlin/org/koitharu/kotatsu/core/ui/MiyorareOverlayPopup.kt")
			.replace(Regex("\\s+"), "")
		val main = source("kotlin/org/koitharu/kotatsu/main/ui/MainActivity.kt")
			.replace(Regex("\\s+"), "")
		val favourites = source("kotlin/org/koitharu/kotatsu/favourites/ui/list/FavouritesListFragment.kt")
			.replace(Regex("\\s+"), "")
		val filter = source("kotlin/org/koitharu/kotatsu/list/ui/adapter/ExtensionFilterPopup.kt")
			.replace(Regex("\\s+"), "")
		val activity = source("kotlin/org/koitharu/kotatsu/favourites/ui/FavouritesActivity.kt")
			.replace(Regex("\\s+"), "")
		val group = source("kotlin/org/koitharu/kotatsu/favourites/groups/ui/LibraryGroupDetailsScreen.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(overlay.contains("ColorUtils.calculateLuminance(palette.background)>=0.5"))
		assertTrue(overlay.contains("createMiyorareOverlayBackground"))
		assertTrue(main.contains("anchor.showMiyorareGlassMenu(entries)"))
		assertTrue(favourites.contains("MODERN_SELECTION_MORE_ID"))
		assertTrue(favourites.contains("showMiyorareGlassMenu(entries,MiyorarePopupPlacement.TOP_END)"))
		assertTrue(filter.contains("background=context.createMiyorareOverlayBackground(radiusDp=28f)"))
		assertTrue(filter.contains("if(modernPalette!=null)ColorDrawable(Color.TRANSPARENT)"))
		assertTrue(activity.contains("Variant.APP_BACKGROUND"))
		assertTrue(activity.contains("configureModernLibraryGroupChrome()"))
		assertTrue(group.contains("modifier.miyorareSurface(palette=palette,shape=shape)"))
		assertTrue(group.contains("GroupGlassSurface(modifier=Modifier.fillMaxWidth(),radius=26.dp)"))
	}



	@Test
	fun `Details follows adaptive custom wallpaper colors in both light and dark modes`() {
		val colors = source("kotlin/org/koitharu/kotatsu/core/ui/MiyorareColorScheme.kt")
			.replace(Regex("\\s+"), "")
		val screen = source("kotlin/org/koitharu/kotatsu/details/ui/DetailsExpressiveScreen.kt")
			.replace(Regex("\\s+"), "")
		val common = source("kotlin/org/koitharu/kotatsu/details/ui/DetailsCommonComponents.kt")
			.replace(Regex("\\s+"), "")
		val content = source("kotlin/org/koitharu/kotatsu/details/ui/DetailsContentComponents.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(colors.contains("adaptiveCustomBackground=adaptivePalette!=null"))
		assertTrue(screen.contains("palette.isModern&&palette.adaptiveCustomBackground"))
		assertTrue(screen.contains("vallightMode=scheme.background.luminance()>=0.5f"))
		assertTrue(screen.contains("valadaptiveCustom=palette.isModern&&palette.adaptiveCustomBackground"))
		assertTrue(screen.contains("vallightSurface=surface.luminance()>=0.5f"))
		assertTrue(screen.contains("VisualEffectLevel.FULL->0.18f"))
		assertTrue(screen.contains("VisualEffectLevel.FULL->0.14f"))
		assertTrue(common.contains("valadaptiveCustom=palette.isModern&&palette.adaptiveCustomBackground"))
		assertTrue(common.contains("valmodernCardColor=if(adaptiveCustom)"))
		assertTrue(content.contains("valadaptiveCustom=palette.adaptiveCustomBackground"))
		assertTrue(content.contains("if(adaptiveCustom)9.dpelse7.dp"))
	}



	@Test
	fun `Group details tracking and secondary Favourites UI stay Modern and actionable`() {
		val groupScreen = source("kotlin/org/koitharu/kotatsu/favourites/groups/ui/LibraryGroupDetailsScreen.kt")
			.replace(Regex("\\s+"), "")
		val groupFragment = source("kotlin/org/koitharu/kotatsu/favourites/groups/ui/LibraryGroupDetailsFragment.kt")
			.replace(Regex("\\s+"), "")
		val categories = source("kotlin/org/koitharu/kotatsu/favourites/ui/categories/FavouriteCategoriesActivity.kt")
			.replace(Regex("\\s+"), "")
		val categoryStyle = source("kotlin/org/koitharu/kotatsu/favourites/ui/categories/adapter/MiyorareCategoryGlass.kt")
			.replace(Regex("\\s+"), "")
		val listConfig = source("kotlin/org/koitharu/kotatsu/list/ui/config/ListConfigBottomSheet.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(groupScreen.contains("contentColor=MaterialTheme.colorScheme.onSurface"))
		assertTrue(groupFragment.contains("AppRouter.trackerSettingsIntent(requireContext())"))
		assertTrue(groupFragment.contains("resumeTrackingSetupAfterSettings=true"))
		assertTrue(groupFragment.contains("if(viewModel.availableTrackingServices().isNotEmpty())"))
		assertFalse(
			"Tracking Manage must not dead-end at the old no-service snackbar",
			groupFragment.contains("showMessage(R.string.library_group_tracking_no_service)"),
		)

		assertTrue(categories.contains("Variant.APP_BACKGROUND"))
		assertTrue(categories.contains("configureModernCategoriesChrome()"))
		assertTrue(categoryStyle.contains("createMiyorareOverlayBackground(radiusDp=24f)"))
		assertTrue(categoryStyle.contains("title.setTextColor(palette.onSurface)"))
		assertTrue(listConfig.contains("createMiyorareOverlayBackground(radiusDp=30f)"))
		assertTrue(listConfig.contains("materialR.id.design_bottom_sheet"))
	}


	@Test
	fun `normal main tabs keep favourites navigation container while active item still moves`() {
		val navigation = source("kotlin/org/koitharu/kotatsu/core/ui/widgets/FloatingBottomNavigationView.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(navigation.contains("valemphasizeFavourites=!privateFavouritesHost"))
		assertFalse(navigation.contains("emphasizeFavourites=!privateFavouritesHost&&selectedId==R.id.nav_favorites"))
		assertTrue(navigation.split("selectedId=selectedId").size - 1 >= 2)
		assertTrue(navigation.contains("emphasizeFavourites=emphasizeFavourites"))
	}


	@Test
	fun `light mode uses milky wallpaper and glass treatment while dark path stays intact`() {
		val drawable = source("kotlin/org/koitharu/kotatsu/core/ui/MiyorareHeaderShapeDrawable.kt")
			.replace(Regex("\\s+"), "")
		val surfaces = source("kotlin/org/koitharu/kotatsu/core/ui/MiyorareSurface.kt")
			.replace(Regex("\\s+"), "")
		val legacyNav = source("kotlin/org/koitharu/kotatsu/main/ui/nav/LegacyGlowNavBar.kt")
			.replace(Regex("\\s+"), "")
		val floatingNav = source("kotlin/org/koitharu/kotatsu/main/ui/nav/FloatingNavBar.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(drawable.contains("LIGHT_BACKGROUND_WHITE_BASE_MIX=0.30f"))
		assertTrue(drawable.contains("LIGHT_BACKGROUND_ARTWORK_ALPHA=0.62f"))
		assertTrue(drawable.contains("LIGHT_BACKGROUND_WASH_TOP_ALPHA=0.32f"))
		assertTrue(drawable.contains("LIGHT_BACKGROUND_WASH_MIDDLE_ALPHA=0.22f"))
		assertTrue(drawable.contains("LIGHT_BACKGROUND_WASH_BOTTOM_ALPHA=0.30f"))
		assertTrue("Dark wallpaper wash must remain unchanged", drawable.contains("else0.46f"))
		assertTrue("Dark wallpaper wash must remain unchanged", drawable.contains("else0.38f"))
		assertTrue("Dark wallpaper wash must remain unchanged", drawable.contains("else0.50f"))
		assertTrue(surfaces.contains("LIGHT_GLASS_START_ALPHA=0.84f"))
		assertTrue(surfaces.contains("LIGHT_GLASS_MIDDLE_ALPHA=0.76f"))
		assertTrue(legacyNav.contains("val lightMode=MaterialTheme.colorScheme.background.luminance()>=0.5f".replace(" ", "")))
		assertTrue(floatingNav.contains("val lightMode=cs.background.luminance()>=0.5f".replace(" ", "")))
		assertTrue(legacyNav.contains("LIGHT_NAV_BASE_ACCENT_MIX=0.055f"))
		assertTrue(floatingNav.contains("LIGHT_NAV_BASE_ACCENT_MIX=0.055f"))
	}



	@Test
	fun `root settings exposes Google Drive login and keeps existing sync implementation wired`() {
		val root = source("kotlin/org/koitharu/kotatsu/settings/RootSettingsFragment.kt")
			.replace(Regex("\\s+"), "")
		val activity = source("kotlin/org/koitharu/kotatsu/settings/SettingsActivity.kt")
			.replace(Regex("\\s+"), "")
		val sync = source("kotlin/org/koitharu/kotatsu/sync/ui/SyncSettingsFragment.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(root.contains("SYNC(R.string.google_drive_sync,R.drawable.ic_cloud_sync,\"sync\""))
		assertTrue(root.contains("SyncSettingsFragment::class.java"))
		assertTrue(root.contains("sections=listOf(SettingsSection.SYNC,SettingsSection.STORAGE,SettingsSection.BACKUP)"))
		assertTrue(activity.contains("AppRouter.ACTION_SYNC->SyncSettingsFragment()"))
		assertTrue(sync.contains("title=stringResource(R.string.sync_sign_in)"))
		assertTrue(sync.contains("onClick=onSignIn"))
	}


	@Test
	fun `secondary modern screens inherit blurred favourites backdrop without leaking into private surfaces`() {
		val settings = source("kotlin/org/koitharu/kotatsu/settings/SettingsActivity.kt")
			.replace(Regex("\\s+"), "")
		val settingsScaffold = source("kotlin/org/koitharu/kotatsu/settings/compose/SettingsScaffold.kt")
			.replace(Regex("\\s+"), "")
		val downloads = source("kotlin/org/koitharu/kotatsu/download/ui/list/DownloadsActivity.kt")
			.replace(Regex("\\s+"), "")
		val stats = source("kotlin/org/koitharu/kotatsu/stats/ui/StatsActivity.kt")
			.replace(Regex("\\s+"), "")
		val detailsActivity = source("kotlin/org/koitharu/kotatsu/details/ui/DetailsExpressiveActivity.kt")
			.replace(Regex("\\s+"), "")
		val detailsScreen = source("kotlin/org/koitharu/kotatsu/details/ui/DetailsExpressiveScreen.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(settings.contains("if(isPrivateSettings){viewBinding.root.setBackgroundColor(palette.background)}else{"))
		assertTrue(settings.contains("Variant.APP_BACKGROUND"))
		assertTrue(settingsScaffold.contains("valmodernBackground=Color.Transparent"))
		assertTrue(downloads.contains("if(isPrivateDownloads){viewBinding.root.setBackgroundColor(palette.background)}else{"))
		assertTrue(downloads.contains("Variant.APP_BACKGROUND"))
		assertTrue(stats.contains("Variant.APP_BACKGROUND"))
		assertTrue(detailsActivity.contains("if(viewModel.favouriteSpace==FavouriteSpace.PRIVATE){"))
		assertTrue(detailsActivity.contains("Variant.APP_BACKGROUND"))
		assertTrue(detailsScreen.contains(".background(if(palette.isModern)Color.TransparentelsescreenSurface)"))
		assertTrue("Manga-specific details backdrop must remain available above the shared fallback", detailsScreen.contains("ExpressiveBackdrop("))
	}


	@Test
	fun `downloads scrolling stays off chapter hydration and app bar bounce paths`() {
		val item = source("kotlin/org/koitharu/kotatsu/download/ui/list/DownloadItemAD.kt")
			.replace(Regex("\\s+"), "")
		val activity = source("kotlin/org/koitharu/kotatsu/download/ui/list/DownloadsActivity.kt")
			.replace(Regex("\\s+"), "")
		val layout = source("res/layout/activity_downloads.xml")

		val expandedGate = item.indexOf("if(item.isExpanded){")
		val chapterCollect = item.indexOf("item.chapters.collect{chapters->")
		assertTrue(expandedGate >= 0 && chapterCollect > expandedGate)
		assertTrue(item.contains("else{chaptersJob?.cancel()chaptersJob=null}"))
		assertTrue(item.contains("binding.buttonExpand.isGone=false"))
		assertFalse(item.contains("binding.buttonExpand.isGone=chapters.isNullOrEmpty()"))

		assertTrue(activity.contains("itemAnimator=null"))
		assertFalse(layout.contains("scroll|enterAlways"))
		assertFalse(layout.contains("scroll|exitUntilCollapsed|snap"))
		assertTrue(layout.contains("app:layout_scrollFlags=\"scroll|exitUntilCollapsed\""))
		assertTrue(layout.contains("app:layout_scrollFlags=\"scroll\""))
	}

	@Test
	fun `explore startup keeps package metadata and plugin discovery off the main render path`() {
		val repository = source("kotlin/org/koitharu/kotatsu/explore/data/MangaSourcesRepository.kt")
			.replace(Regex("\\s+"), "")
		val viewModel = source("kotlin/org/koitharu/kotatsu/explore/ui/ExploreViewModel.kt")
			.replace(Regex("\\s+"), "")
		val adapter = source("kotlin/org/koitharu/kotatsu/explore/ui/adapter/ExploreAdapter.kt")
			.replace(Regex("\\s+"), "")
		val delegates = source("kotlin/org/koitharu/kotatsu/explore/ui/adapter/ExploreAdapterDelegates.kt")
			.replace(Regex("\\s+"), "")
		val activity = source("kotlin/org/koitharu/kotatsu/main/ui/MainActivity.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(repository.contains("returnflow{manager.initialize()"))
		assertTrue(repository.contains("emitAll("))
		assertFalse(viewModel.contains("sourcesRepository.reloadMihonSources()"))
		assertTrue(viewModel.contains("valsummary=source.getSummary(appContext)"))
		assertTrue(adapter.contains("sources.partition{it.isMiyorareSource}"))
		assertFalse(adapter.contains("getSummary(context)"))
		assertFalse(delegates.contains("getSummary(context)"))
		assertTrue(delegates.contains("item.summary.toCompactExploreSourceSummary()"))
		assertTrue(activity.contains("postDelayed(exploreWarmupRunnable,EXPLORE_WARMUP_IDLE_DELAY_MS)"))
		assertTrue(activity.contains("postDelayed(backgroundWarmupRunnable,BACKGROUND_WARMUP_IDLE_DELAY_MS)"))
	}

	private fun source(relativePath: String): String {
		return sequenceOf(
			File("src/main", relativePath),
			File("app/src/main", relativePath),
		)
			.firstOrNull(File::isFile)
			?.readText()
			?: error("Cannot find production source: $relativePath")
	}
}
