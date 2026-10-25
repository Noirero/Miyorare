from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
READER = ROOT / "app/src/main/kotlin/org/koitharu/kotatsu/reader/ui/epub/EpubReaderFragment.kt"
CONTROLLER = ROOT / "app/src/main/kotlin/org/koitharu/kotatsu/reader/ui/epub/translation/NovelTranslationDialogController.kt"
POLICY = ROOT / "app/src/main/kotlin/org/koitharu/kotatsu/reader/ui/epub/translation/NovelAutoTranslationPolicy.kt"
POLICY_TEST = ROOT / "app/src/test/kotlin/org/koitharu/kotatsu/reader/ui/epub/translation/NovelAutoTranslationPolicyTest.kt"


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text()
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected one exact match, found {count}")
    path.write_text(text.replace(old, new, 1))


# Provider/cost consent is one-way invalidated: after a provider boundary changes the old opt-in
# cannot silently come back merely because the user later switches back.
replace_once(
    CONTROLLER,
    '''\t\tfun updateAutoUi() {\n\t\t\tval id = autoMangaId ?: return\n\t\t\tupdatingAutoSwitch = true\n\t\t\tautoTranslateSwitch?.isChecked = settings.isAutoTranslateActive(id)\n''',
    '''\t\tfun updateAutoUi() {\n\t\t\tval id = autoMangaId ?: return\n\t\t\tif (settings.isAutoTranslateEnabled(id) && !settings.isAutoTranslateActive(id)) {\n\t\t\t\tsettings.setAutoTranslateEnabled(id, false)\n\t\t\t}\n\t\t\tupdatingAutoSwitch = true\n\t\t\tautoTranslateSwitch?.isChecked = settings.isAutoTranslateActive(id)\n''',
)
replace_once(
    CONTROLLER,
    '''\tprivate val onAutoTranslateEnabled: () -> Unit = {},\n''',
    '''\tprivate val onAutoTranslateChanged: (Boolean) -> Unit = {},\n''',
)
replace_once(
    CONTROLLER,
    '''\t\t\tsettings.setAutoTranslateEnabled(id, checked)\n\t\t\tif (checked) onAutoTranslateEnabled()\n''',
    '''\t\t\tsettings.setAutoTranslateEnabled(id, checked)\n\t\t\tonAutoTranslateChanged(checked)\n''',
)

# Keep auto-derived in-memory projection bounded. Persistent semantic cache remains the durable
# source for revisits, so evicting an old derived projection never forces a network request.
replace_once(
    POLICY,
    '''\tfun providerBinding(engine: NovelTranslationEngineKind, provider: NovelAiProvider): String =\n''',
    '''\tfun shouldRetainProjection(chapterIndex: Int, currentIndex: Int, radius: Int): Boolean =\n\t\tkotlin.math.abs(chapterIndex - currentIndex) <= radius.coerceAtLeast(0)\n\n\tfun providerBinding(engine: NovelTranslationEngineKind, provider: NovelAiProvider): String =\n''',
)
replace_once(
    POLICY_TEST,
    '''\t@Test\n\tfun `provider binding crosses cost boundary explicitly`() {\n''',
    '''\t@Test\n\tfun `auto projection retention stays bounded around current chapter`() {\n\t\tassertTrue(NovelAutoTranslationPolicy.shouldRetainProjection(10, 12, 2))\n\t\tassertTrue(NovelAutoTranslationPolicy.shouldRetainProjection(14, 12, 2))\n\t\tassertFalse(NovelAutoTranslationPolicy.shouldRetainProjection(9, 12, 2))\n\t}\n\n\t@Test\n\tfun `provider binding crosses cost boundary explicitly`() {\n''',
)

replace_once(
    READER,
    '''\tprivate var novelTranslationCoordinator: NovelTranslationCoordinator? = null\n\tprivate var autoTranslationLastChapterId = 0L\n''',
    '''\tprivate var novelTranslationCoordinator: NovelTranslationCoordinator? = null\n\tprivate var autoTranslationLastChapterId = 0L\n\tprivate val autoTranslatedChapterIds = ConcurrentHashMap.newKeySet<Long>()\n\tprivate var translationShowsProgressUi = false\n\tprivate var translationIsAutomatic = false\n''',
)
replace_once(
    READER,
    '''\t\t\tonAutoTranslateEnabled = { maybeAutoTranslateCurrentChapter(currentLocator().chapter) },\n''',
    '''\t\t\tonAutoTranslateChanged = { enabled ->\n\t\t\t\tif (enabled) {\n\t\t\t\t\tautoTranslationLastChapterId = 0L\n\t\t\t\t\tmaybeAutoTranslateCurrentChapter(currentLocator().chapter)\n\t\t\t\t} else if (translationIsAutomatic) {\n\t\t\t\t\tcancelActiveTranslation(incrementGeneration = true)\n\t\t\t\t}\n\t\t\t},\n''',
)
replace_once(
    READER,
    '''\t\tcancelActiveTranslation(incrementGeneration = false)\n\t\tval generation = ++translationGeneration\n\t\tval coordinator = translationCoordinator()\n''',
    '''\t\tcancelActiveTranslation(incrementGeneration = false)\n\t\ttranslationIsAutomatic = automatic\n\t\tval generation = ++translationGeneration\n\t\tval coordinator = translationCoordinator()\n''',
)
replace_once(
    READER,
    '''\t\t\tinlineTranslations[chapter.id] = items\n\t\t\tapplyTranslationProjection(chapter, selection.displayMode)\n''',
    '''\t\t\tinlineTranslations[chapter.id] = items\n\t\t\tif (automatic) autoTranslatedChapterIds += chapter.id\n\t\t\tapplyTranslationProjection(chapter, selection.displayMode)\n''',
)
replace_once(
    READER,
    '''\tprivate fun showTranslationStatusDialog(generation: Int, titleRes: Int, message: String) {\n\t\ttranslationStatusDialog?.dismiss()\n''',
    '''\tprivate fun showTranslationStatusDialog(generation: Int, titleRes: Int, message: String) {\n\t\ttranslationShowsProgressUi = true\n\t\ttranslationStatusDialog?.dismiss()\n''',
)
replace_once(
    READER,
    '''\tprivate fun finishTranslationUi() {\n\t\tsetChapterLoading(false)\n\t\ttranslationStatusDialog?.dismiss()\n\t\ttranslationStatusDialog = null\n\t\ttranslationJob = null\n\t}\n''',
    '''\tprivate fun finishTranslationUi() {\n\t\tif (translationShowsProgressUi) setChapterLoading(false)\n\t\ttranslationShowsProgressUi = false\n\t\ttranslationIsAutomatic = false\n\t\ttranslationStatusDialog?.dismiss()\n\t\ttranslationStatusDialog = null\n\t\ttranslationJob = null\n\t}\n''',
)
replace_once(
    READER,
    '''\t\tnovelTranslationCoordinator = null\n\t\tautoTranslationLastChapterId = 0L\n\t\ttranslationOriginals.clear()\n''',
    '''\t\tnovelTranslationCoordinator = null\n\t\tautoTranslationLastChapterId = 0L\n\t\tautoTranslatedChapterIds.clear()\n\t\ttranslationShowsProgressUi = false\n\t\ttranslationIsAutomatic = false\n\t\ttranslationOriginals.clear()\n''',
)
replace_once(
    READER,
    '''\t\tlastLocator = locator\n\t\ttrimRemoteChapterCache(locator.chapter)\n\t\tval chapter = chapters[locator.chapter]\n\t\tmaybeAutoTranslateCurrentChapter(locator.chapter)\n''',
    '''\t\tlastLocator = locator\n\t\ttrimAutoTranslationState(locator.chapter)\n\t\ttrimRemoteChapterCache(locator.chapter)\n\t\tval chapter = chapters[locator.chapter]\n\t\tmaybeAutoTranslateCurrentChapter(locator.chapter)\n''',
)
replace_once(
    READER,
    '''\tprivate fun trimRemoteChapterCache(center: Int) {\n''',
    '''\tprivate fun trimAutoTranslationState(center: Int) {\n\t\tif (autoTranslatedChapterIds.isEmpty()) return\n\t\tval idsToRelease = autoTranslatedChapterIds.filter { chapterId ->\n\t\t\tval index = chapters.indexOfFirst { it.id == chapterId }\n\t\t\tindex < 0 || !NovelAutoTranslationPolicy.shouldRetainProjection(\n\t\t\t\tchapterIndex = index,\n\t\t\t\tcurrentIndex = center,\n\t\t\t\tradius = AUTO_TRANSLATION_MEMORY_RADIUS,\n\t\t\t)\n\t\t}\n\t\tidsToRelease.forEach { chapterId ->\n\t\t\tval chapter = chapters.firstOrNull { it.id == chapterId }\n\t\t\tval original = translationOriginals.remove(chapterId)\n\t\t\tinlineTranslations.remove(chapterId)\n\t\t\ttranslationDisplayModes.remove(chapterId)\n\t\t\tautoTranslatedChapterIds.remove(chapterId)\n\t\t\tif (chapter != null && original != null) chapter.content = original\n\t\t}\n\t}\n\n\tprivate fun trimRemoteChapterCache(center: Int) {\n''',
)
# Add bounded retention constant near the existing remote cache constant.
text = READER.read_text()
if "AUTO_TRANSLATION_MEMORY_RADIUS" not in text:
    marker = "\t\tprivate const val REMOTE_CONTENT_CACHE_RADIUS = "
    idx = text.find(marker)
    if idx < 0:
        raise RuntimeError("Reader: REMOTE_CONTENT_CACHE_RADIUS marker not found")
    line_end = text.find("\n", idx)
    text = text[: line_end + 1] + "\t\tprivate const val AUTO_TRANSLATION_MEMORY_RADIUS = 2\n" + text[line_end + 1 :]
    READER.write_text(text)

print("Batch 5 hardening patch applied successfully")
