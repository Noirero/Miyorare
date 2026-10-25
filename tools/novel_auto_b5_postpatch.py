from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
READER = ROOT / "app/src/main/kotlin/org/koitharu/kotatsu/reader/ui/epub/EpubReaderFragment.kt"
CONTROLLER = ROOT / "app/src/main/kotlin/org/koitharu/kotatsu/reader/ui/epub/translation/NovelTranslationDialogController.kt"


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text()
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected one exact match, found {count}")
    path.write_text(text.replace(old, new, 1))


# Crossing a provider/cost boundary revokes the old consent and cancels any unattended request that
# was already in flight under that consent.
replace_once(
    CONTROLLER,
    '''\t\t\tif (settings.isAutoTranslateEnabled(id) && !settings.isAutoTranslateActive(id)) {\n\t\t\t\tsettings.setAutoTranslateEnabled(id, false)\n\t\t\t}\n''',
    '''\t\t\tif (settings.isAutoTranslateEnabled(id) && !settings.isAutoTranslateActive(id)) {\n\t\t\t\tsettings.setAutoTranslateEnabled(id, false)\n\t\t\t\tonAutoTranslateChanged(false)\n\t\t\t}\n''',
)
replace_once(
    CONTROLLER,
    '''\t\tadvancedButton.setOnClickListener {\n\t\t\tautoMangaId?.let { id ->\n\t\t\t\tsettings.setAutoTranslateEnabled(id, false)\n\t\t\t\tupdateAutoUi()\n\t\t\t}\n\t\t\tshowAiSettings(continueToTranslate = false)\n\t\t}\n''',
    '''\t\tadvancedButton.setOnClickListener {\n\t\t\tautoMangaId?.let { id ->\n\t\t\t\tval wasEnabled = settings.isAutoTranslateEnabled(id)\n\t\t\t\tsettings.setAutoTranslateEnabled(id, false)\n\t\t\t\tif (wasEnabled) onAutoTranslateChanged(false)\n\t\t\t\tupdateAutoUi()\n\t\t\t}\n\t\t\tshowAiSettings(continueToTranslate = false)\n\t\t}\n''',
)

# A failed/cancelled auto request must not leave translationOriginals pinning a remote chapter.
replace_once(
    READER,
    '''\tprivate var translationShowsProgressUi = false\n\tprivate var translationIsAutomatic = false\n''',
    '''\tprivate var translationShowsProgressUi = false\n\tprivate var translationIsAutomatic = false\n\tprivate var autoTranslationInFlightChapterId = 0L\n''',
)
replace_once(
    READER,
    '''\t\ttranslationIsAutomatic = automatic\n\t\tval generation = ++translationGeneration\n''',
    '''\t\ttranslationIsAutomatic = automatic\n\t\tautoTranslationInFlightChapterId = if (automatic) chapter.id else 0L\n\t\tval generation = ++translationGeneration\n''',
)
replace_once(
    READER,
    '''\t\t\t}.getOrElse { error ->\n\t\t\t\tif (generation == translationGeneration && isAdded) {\n\t\t\t\t\tfinishTranslationUi()\n''',
    '''\t\t\t}.getOrElse { error ->\n\t\t\t\tif (generation == translationGeneration && isAdded) {\n\t\t\t\t\tif (automatic) releaseUntranslatedAutoSource(chapter.id)\n\t\t\t\t\tfinishTranslationUi()\n''',
)
replace_once(
    READER,
    '''\tprivate fun finishTranslationUi() {\n\t\tif (translationShowsProgressUi) setChapterLoading(false)\n\t\ttranslationShowsProgressUi = false\n\t\ttranslationIsAutomatic = false\n\t\ttranslationStatusDialog?.dismiss()\n''',
    '''\tprivate fun finishTranslationUi() {\n\t\tif (translationShowsProgressUi) setChapterLoading(false)\n\t\ttranslationShowsProgressUi = false\n\t\ttranslationIsAutomatic = false\n\t\tautoTranslationInFlightChapterId = 0L\n\t\ttranslationStatusDialog?.dismiss()\n''',
)
replace_once(
    READER,
    '''\tprivate fun cancelActiveTranslation(incrementGeneration: Boolean) {\n\t\tif (incrementGeneration) translationGeneration++\n\t\ttranslationJob?.cancel()\n\t\ttranslationJob = null\n\t\tfinishTranslationUi()\n\t}\n''',
    '''\tprivate fun cancelActiveTranslation(incrementGeneration: Boolean) {\n\t\tval abandonedAutoChapterId = if (translationIsAutomatic) autoTranslationInFlightChapterId else 0L\n\t\tif (incrementGeneration) translationGeneration++\n\t\ttranslationJob?.cancel()\n\t\ttranslationJob = null\n\t\tfinishTranslationUi()\n\t\treleaseUntranslatedAutoSource(abandonedAutoChapterId)\n\t}\n\n\tprivate fun releaseUntranslatedAutoSource(chapterId: Long) {\n\t\tif (chapterId <= 0L || !inlineTranslations[chapterId].isNullOrEmpty()) return\n\t\ttranslationOriginals.remove(chapterId)\n\t\tautoTranslatedChapterIds.remove(chapterId)\n\t}\n''',
)
replace_once(
    READER,
    '''\t\ttranslationShowsProgressUi = false\n\t\ttranslationIsAutomatic = false\n\t\ttranslationOriginals.clear()\n''',
    '''\t\ttranslationShowsProgressUi = false\n\t\ttranslationIsAutomatic = false\n\t\tautoTranslationInFlightChapterId = 0L\n\t\ttranslationOriginals.clear()\n''',
)

print("Batch 5 post-patch lifecycle hardening applied successfully")
