from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
READER = ROOT / "app/src/main/kotlin/org/koitharu/kotatsu/reader/ui/epub/EpubReaderFragment.kt"
POLICY = ROOT / "app/src/main/kotlin/org/koitharu/kotatsu/reader/ui/epub/translation/NovelAutoTranslationPolicy.kt"
TEST = ROOT / "app/src/test/kotlin/org/koitharu/kotatsu/reader/ui/epub/translation/NovelAutoTranslationPolicyTest.kt"


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text()
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected one exact match, found {count}")
    path.write_text(text.replace(old, new, 1))

replace_once(
    READER,
    '''import androidx.lifecycle.lifecycleScope\n''',
    '''import androidx.lifecycle.Lifecycle\nimport androidx.lifecycle.lifecycleScope\n''',
)
replace_once(
    READER,
    '''\tprivate fun maybeAutoTranslateCurrentChapter(chapterIndex: Int) {\n\t\tif (!isAdded || translationJob?.isActive == true) return\n''',
    '''\tprivate fun maybeAutoTranslateCurrentChapter(chapterIndex: Int) {\n\t\tval readerActive = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)\n\t\tif (!isAdded || translationJob?.isActive == true) return\n''',
)
replace_once(
    READER,
    '''\t\tif (!NovelAutoTranslationPolicy.shouldStart(\n\t\t\t\tenabled = settings.isAutoTranslateActive(mangaId),\n''',
    '''\t\tif (!NovelAutoTranslationPolicy.shouldStart(\n\t\t\t\tenabled = settings.isAutoTranslateActive(mangaId),\n\t\t\t\treaderActive = readerActive,\n''',
)
replace_once(
    POLICY,
    '''\tfun shouldStart(\n\t\tenabled: Boolean,\n\t\tchapterReady: Boolean,\n''',
    '''\tfun shouldStart(\n\t\tenabled: Boolean,\n\t\treaderActive: Boolean,\n\t\tchapterReady: Boolean,\n''',
)
replace_once(
    POLICY,
    '''\t): Boolean = enabled &&\n\t\tchapterReady &&\n''',
    '''\t): Boolean = enabled &&\n\t\treaderActive &&\n\t\tchapterReady &&\n''',
)

# Update named-argument test call and positional test calls; fail closed if their expected shape moves.
replace_once(
    TEST,
    '''\t\t\t\tenabled = true,\n\t\t\t\tchapterReady = true,\n''',
    '''\t\t\t\tenabled = true,\n\t\t\t\treaderActive = true,\n\t\t\t\tchapterReady = true,\n''',
)
replace_once(
    TEST,
    '''NovelAutoTranslationPolicy.shouldStart(true, true, false, false, 42L, 42L)''',
    '''NovelAutoTranslationPolicy.shouldStart(true, true, true, false, false, 42L, 42L)''',
)
replace_once(
    TEST,
    '''\t\tassertFalse(NovelAutoTranslationPolicy.shouldStart(false, true, false, false, 42L, 0L))\n\t\tassertFalse(NovelAutoTranslationPolicy.shouldStart(true, false, false, false, 42L, 0L))\n\t\tassertFalse(NovelAutoTranslationPolicy.shouldStart(true, true, true, false, 42L, 0L))\n\t\tassertFalse(NovelAutoTranslationPolicy.shouldStart(true, true, false, true, 42L, 0L))\n''',
    '''\t\tassertFalse(NovelAutoTranslationPolicy.shouldStart(false, true, true, false, false, 42L, 0L))\n\t\tassertFalse(NovelAutoTranslationPolicy.shouldStart(true, false, true, false, false, 42L, 0L))\n\t\tassertFalse(NovelAutoTranslationPolicy.shouldStart(true, true, false, false, false, 42L, 0L))\n\t\tassertFalse(NovelAutoTranslationPolicy.shouldStart(true, true, true, true, false, 42L, 0L))\n\t\tassertFalse(NovelAutoTranslationPolicy.shouldStart(true, true, true, false, true, 42L, 0L))\n''',
)

print("Reader-active auto translation guard applied")
