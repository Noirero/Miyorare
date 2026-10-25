from __future__ import annotations

from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
READER = ROOT / "app/src/main/kotlin/org/koitharu/kotatsu/reader/ui/epub/EpubReaderFragment.kt"
CONTROLLER = ROOT / "app/src/main/kotlin/org/koitharu/kotatsu/reader/ui/epub/translation/NovelTranslationDialogController.kt"
SETTINGS = ROOT / "app/src/main/kotlin/org/koitharu/kotatsu/reader/ui/epub/translation/NovelTranslationSettings.kt"
POLICY = ROOT / "app/src/main/kotlin/org/koitharu/kotatsu/reader/ui/epub/translation/NovelAutoTranslationPolicy.kt"
POLICY_TEST = ROOT / "app/src/test/kotlin/org/koitharu/kotatsu/reader/ui/epub/translation/NovelAutoTranslationPolicyTest.kt"
STRINGS = ROOT / "app/src/main/res/values/strings_novel_translation_hub.xml"
STRINGS_ID = ROOT / "app/src/main/res/values-in/strings_novel_translation_hub.xml"


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text()
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected one exact match, found {count}")
    path.write_text(text.replace(old, new, 1))


def regex_once(path: Path, pattern: str, replacement: str) -> None:
    text = path.read_text()
    if replacement in text:
        return
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise RuntimeError(f"{path}: expected one regex match, found {count}")
    path.write_text(updated)


# --- Translation settings: per-manga opt-in bound to the current provider boundary. ---
replace_once(
    SETTINGS,
    '''\t/** Presentation preference only; it deliberately does not participate in semantic cache identity. */\n\tvar bilingual: Boolean\n\t\tget() = prefs.getBoolean(KEY_BILINGUAL, true)\n\t\tset(value) = prefs.edit().putBoolean(KEY_BILINGUAL, value).apply()\n''',
    '''\t/** Presentation preference only; it deliberately does not participate in semantic cache identity. */\n\tvar bilingual: Boolean\n\t\tget() = prefs.getBoolean(KEY_BILINGUAL, true)\n\t\tset(value) = prefs.edit().putBoolean(KEY_BILINGUAL, value).apply()\n\n\tfun isAutoTranslateEnabled(mangaId: Long): Boolean =\n\t\tmangaId > 0L && prefs.getBoolean(autoTranslateKey(mangaId), false)\n\n\t/**\n\t * Auto-translate is opt-in per novel and bound to the selected provider boundary. Changing\n\t * Online -> AI or switching AI providers therefore requires an explicit opt-in again.\n\t */\n\tfun isAutoTranslateActive(mangaId: Long): Boolean =\n\t\tisAutoTranslateEnabled(mangaId) &&\n\t\t\tprefs.getString(autoTranslateBindingKey(mangaId), null) == currentAutoProviderBinding()\n\n\tfun setAutoTranslateEnabled(mangaId: Long, enabled: Boolean) {\n\t\tif (mangaId <= 0L) return\n\t\tprefs.edit().apply {\n\t\t\tputBoolean(autoTranslateKey(mangaId), enabled)\n\t\t\tif (enabled) putString(autoTranslateBindingKey(mangaId), currentAutoProviderBinding())\n\t\t\telse remove(autoTranslateBindingKey(mangaId))\n\t\t}.apply()\n\t}\n\n\tfun currentSelection(): NovelTranslationSelection {\n\t\tval selectedEngine = engine\n\t\treturn NovelTranslationSelection(\n\t\t\tengine = selectedEngine,\n\t\t\tsourceLanguage = sourceLanguage,\n\t\t\ttargetLanguage = targetLanguage,\n\t\t\tstyle = style,\n\t\t\tcontextAware = selectedEngine == NovelTranslationEngineKind.AI && contextAware,\n\t\t\tdisplayMode = if (bilingual) NovelTranslationDisplayMode.BILINGUAL else NovelTranslationDisplayMode.TRANSLATED_ONLY,\n\t\t)\n\t}\n''',
)
replace_once(
    SETTINGS,
    '''\tprivate fun providerKey(prefix: String, provider: NovelAiProvider) = "$prefix${provider.id}"\n''',
    '''\tprivate fun providerKey(prefix: String, provider: NovelAiProvider) = "$prefix${provider.id}"\n\tprivate fun autoTranslateKey(mangaId: Long) = "$KEY_AUTO_TRANSLATE_PREFIX$mangaId"\n\tprivate fun autoTranslateBindingKey(mangaId: Long) = "$KEY_AUTO_TRANSLATE_BINDING_PREFIX$mangaId"\n\tprivate fun currentAutoProviderBinding() = NovelAutoTranslationPolicy.providerBinding(engine, provider)\n''',
)
replace_once(
    SETTINGS,
    '''\t\tprivate const val KEY_BILINGUAL = "bilingual"\n''',
    '''\t\tprivate const val KEY_BILINGUAL = "bilingual"\n\t\tprivate const val KEY_AUTO_TRANSLATE_PREFIX = "auto_translate_"\n\t\tprivate const val KEY_AUTO_TRANSLATE_BINDING_PREFIX = "auto_translate_binding_"\n''',
)

# --- Pure policy: deterministic eligibility + provider binding. ---
POLICY.write_text('''package org.koitharu.kotatsu.reader.ui.epub.translation\n\n/** Pure eligibility rules for per-novel automatic translation. */\ninternal object NovelAutoTranslationPolicy {\n\n\tfun shouldStart(\n\t\tenabled: Boolean,\n\t\tchapterReady: Boolean,\n\t\thasTranslation: Boolean,\n\t\ttranslationInFlight: Boolean,\n\t\tchapterId: Long,\n\t\tlastAttemptedChapterId: Long,\n\t): Boolean = enabled &&\n\t\tchapterReady &&\n\t\t!hasTranslation &&\n\t\t!translationInFlight &&\n\t\tchapterId > 0L &&\n\t\tchapterId != lastAttemptedChapterId\n\n\tfun providerBinding(engine: NovelTranslationEngineKind, provider: NovelAiProvider): String =\n\t\tif (engine == NovelTranslationEngineKind.AI) "ai:${provider.id}" else NovelTranslationEngineKind.ONLINE.id\n}\n''')

POLICY_TEST.parent.mkdir(parents=True, exist_ok=True)
POLICY_TEST.write_text('''package org.koitharu.kotatsu.reader.ui.epub.translation\n\nimport org.junit.Assert.assertFalse\nimport org.junit.Assert.assertNotEquals\nimport org.junit.Assert.assertTrue\nimport org.junit.Test\n\nclass NovelAutoTranslationPolicyTest {\n\n\t@Test\n\tfun `only a ready untranslated current chapter starts once`() {\n\t\tassertTrue(\n\t\t\tNovelAutoTranslationPolicy.shouldStart(\n\t\t\t\tenabled = true,\n\t\t\t\tchapterReady = true,\n\t\t\t\thasTranslation = false,\n\t\t\t\ttranslationInFlight = false,\n\t\t\t\tchapterId = 42L,\n\t\t\t\tlastAttemptedChapterId = 41L,\n\t\t\t),\n\t\t)\n\t\tassertFalse(\n\t\t\tNovelAutoTranslationPolicy.shouldStart(true, true, false, false, 42L, 42L),\n\t\t)\n\t}\n\n\t@Test\n\tfun `preload cached disabled and in flight states never start`() {\n\t\tassertFalse(NovelAutoTranslationPolicy.shouldStart(false, true, false, false, 42L, 0L))\n\t\tassertFalse(NovelAutoTranslationPolicy.shouldStart(true, false, false, false, 42L, 0L))\n\t\tassertFalse(NovelAutoTranslationPolicy.shouldStart(true, true, true, false, 42L, 0L))\n\t\tassertFalse(NovelAutoTranslationPolicy.shouldStart(true, true, false, true, 42L, 0L))\n\t}\n\n\t@Test\n\tfun `provider binding crosses cost boundary explicitly`() {\n\t\tval online = NovelAutoTranslationPolicy.providerBinding(NovelTranslationEngineKind.ONLINE, NovelAiProvider.GEMINI)\n\t\tval gemini = NovelAutoTranslationPolicy.providerBinding(NovelTranslationEngineKind.AI, NovelAiProvider.GEMINI)\n\t\tval openAi = NovelAutoTranslationPolicy.providerBinding(NovelTranslationEngineKind.AI, NovelAiProvider.OPENAI)\n\t\tassertNotEquals(online, gemini)\n\t\tassertNotEquals(gemini, openAi)\n\t}\n}\n''')

# --- Translation sheet: chapter-level per-novel toggle; selection sheets intentionally omit it. ---
replace_once(
    CONTROLLER,
    '''\tprivate val state: NovelTranslationSheetState = NovelTranslationSheetState(),\n\tprivate val onRestoreOriginal: () -> Unit,\n\tprivate val onShowTranslation: (NovelTranslationDisplayMode) -> Unit = {},\n\tprivate val onTranslate: (NovelTranslationSelection) -> Unit,\n''',
    '''\tprivate val state: NovelTranslationSheetState = NovelTranslationSheetState(),\n\tprivate val mangaId: Long? = null,\n\tprivate val onRestoreOriginal: () -> Unit,\n\tprivate val onShowTranslation: (NovelTranslationDisplayMode) -> Unit = {},\n\tprivate val onAutoTranslateEnabled: () -> Unit = {},\n\tprivate val onTranslate: (NovelTranslationSelection) -> Unit,\n''',
)
replace_once(
    CONTROLLER,
    '''\tfun show() {\n\t\tval projectionActionsEnabled = state.hasCachedTranslation && !isActiveTextSelectionInvocation()\n''',
    '''\tfun show() {\n\t\tval selectionInvocation = isActiveTextSelectionInvocation()\n\t\tval projectionActionsEnabled = state.hasCachedTranslation && !selectionInvocation\n\t\tval autoMangaId = mangaId?.takeIf { it > 0L && !selectionInvocation }\n''',
)
replace_once(
    CONTROLLER,
    '''\t\ttargetButton.setOnClickListener {\n\t\t\tchooseLanguage(source = false) {\n\t\t\t\tsettings.targetLanguage = it\n\t\t\t\trefreshLanguageButtons()\n\t\t\t}\n\t\t}\n\n\t\tval bilingualSwitch = MaterialSwitch(context).apply {\n''',
    '''\t\ttargetButton.setOnClickListener {\n\t\t\tchooseLanguage(source = false) {\n\t\t\t\tsettings.targetLanguage = it\n\t\t\t\trefreshLanguageButtons()\n\t\t\t}\n\t\t}\n\n\t\tval autoTranslateSwitch = autoMangaId?.let { id ->\n\t\t\tMaterialSwitch(context).apply {\n\t\t\t\tsetText(R.string.novel_translation_auto_translate_this_novel)\n\t\t\t\tisChecked = settings.isAutoTranslateActive(id)\n\t\t\t}\n\t\t}\n\t\tautoTranslateSwitch?.let { root.addView(it, matchWidth()) }\n\t\tval autoTranslateAiNote = autoMangaId?.let {\n\t\t\tTextView(context).apply {\n\t\t\t\tsetPadding(0, 0, 0, dp(8))\n\t\t\t\tvisibility = View.GONE\n\t\t\t}\n\t\t}\n\t\tautoTranslateAiNote?.let { root.addView(it, matchWidth()) }\n\n\t\tval bilingualSwitch = MaterialSwitch(context).apply {\n''',
)
regex_once(
    CONTROLLER,
    r'''\t\tfun selectedEngine\(\): NovelTranslationEngineKind =\n\t\t\tengines\[serviceSpinner\.selectedItemPosition\.coerceIn\(engines\.indices\)\]\n\n\t\tfun updateServiceUi\(\) \{.*?\n\t\tadvancedButton\.setOnClickListener \{ showAiSettings\(continueToTranslate = false\) \}\n''',
    '''\t\tfun selectedEngine(): NovelTranslationEngineKind =\n\t\t\tengines[serviceSpinner.selectedItemPosition.coerceIn(engines.indices)]\n\n\t\tvar updatingAutoSwitch = false\n\t\tfun updateAutoUi() {\n\t\t\tval id = autoMangaId ?: return\n\t\t\tupdatingAutoSwitch = true\n\t\t\tautoTranslateSwitch?.isChecked = settings.isAutoTranslateActive(id)\n\t\t\tupdatingAutoSwitch = false\n\t\t\tval ai = selectedEngine() == NovelTranslationEngineKind.AI\n\t\t\tautoTranslateAiNote?.visibility = if (ai) View.VISIBLE else View.GONE\n\t\t\tif (ai) {\n\t\t\t\tautoTranslateAiNote?.text = context.getString(\n\t\t\t\t\tR.string.novel_translation_auto_ai_note,\n\t\t\t\t\tsettings.provider.displayName,\n\t\t\t\t)\n\t\t\t}\n\t\t}\n\n\t\tfun updateServiceUi() {\n\t\t\tval ai = selectedEngine() == NovelTranslationEngineKind.AI\n\t\t\tadvancedButton.isEnabled = ai\n\t\t\tprivacyNote.visibility = if (ai) View.VISIBLE else View.GONE\n\t\t\tupdateAutoUi()\n\t\t}\n\t\tserviceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {\n\t\t\toverride fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {\n\t\t\t\tsettings.engine = engines[position.coerceIn(engines.indices)]\n\t\t\t\tupdateServiceUi()\n\t\t\t}\n\t\t\toverride fun onNothingSelected(parent: AdapterView<*>?) = Unit\n\t\t}\n\t\tupdateServiceUi()\n\n\t\tautoTranslateSwitch?.setOnCheckedChangeListener { button, checked ->\n\t\t\tif (updatingAutoSwitch) return@setOnCheckedChangeListener\n\t\t\tval id = autoMangaId ?: return@setOnCheckedChangeListener\n\t\t\tsettings.engine = selectedEngine()\n\t\t\tif (checked && settings.engine == NovelTranslationEngineKind.AI && !isAiConfigured()) {\n\t\t\t\tsettings.setAutoTranslateEnabled(id, false)\n\t\t\t\tupdatingAutoSwitch = true\n\t\t\t\tbutton.isChecked = false\n\t\t\t\tupdatingAutoSwitch = false\n\t\t\t\tToast.makeText(context, R.string.novel_translation_auto_requires_ai_setup, Toast.LENGTH_LONG).show()\n\t\t\t\tshowAiSettings(continueToTranslate = false)\n\t\t\t\treturn@setOnCheckedChangeListener\n\t\t\t}\n\t\t\tsettings.setAutoTranslateEnabled(id, checked)\n\t\t\tif (checked) onAutoTranslateEnabled()\n\t\t}\n\n\t\tadvancedButton.setOnClickListener {\n\t\t\tautoMangaId?.let { id ->\n\t\t\t\tsettings.setAutoTranslateEnabled(id, false)\n\t\t\t\tupdateAutoUi()\n\t\t\t}\n\t\t\tshowAiSettings(continueToTranslate = false)\n\t\t}\n''',
)

# --- Reader integration: current chapter only, never preload-driven. ---
replace_once(
    READER,
    '''import org.koitharu.kotatsu.reader.ui.epub.translation.NovelTranslationCoordinator\n''',
    '''import org.koitharu.kotatsu.reader.ui.epub.translation.NovelAutoTranslationPolicy\nimport org.koitharu.kotatsu.reader.ui.epub.translation.NovelTranslationCoordinator\n''',
)
replace_once(
    READER,
    '''\tprivate var novelTranslationCoordinator: NovelTranslationCoordinator? = null\n''',
    '''\tprivate var novelTranslationCoordinator: NovelTranslationCoordinator? = null\n\tprivate var autoTranslationLastChapterId = 0L\n''',
)
replace_once(
    READER,
    '''\tfun showTranslationDialog() {\n\t\tif (chapters.isEmpty()) return\n\t\tval chapter = chapters.getOrNull(currentLocator().chapter) ?: return\n\t\tNovelTranslationDialogController(\n\t\t\tfragment = this,\n\t\t\thttpClient = httpClient,\n\t\t\tstate = translationSheetState(chapter),\n\t\t\tonRestoreOriginal = ::restoreOriginalTranslation,\n\t\t\tonShowTranslation = ::showCachedTranslation,\n\t\t\tonTranslate = ::translateCurrentChapter,\n\t\t).show()\n\t}\n''',
    '''\tfun showTranslationDialog() {\n\t\tif (chapters.isEmpty()) return\n\t\tval chapter = chapters.getOrNull(currentLocator().chapter) ?: return\n\t\tval mangaId = bookSettingsMangaId.takeIf { it > 0L } ?: viewModel.getMangaOrNull()?.id\n\t\tNovelTranslationDialogController(\n\t\t\tfragment = this,\n\t\t\thttpClient = httpClient,\n\t\t\tstate = translationSheetState(chapter),\n\t\t\tmangaId = mangaId,\n\t\t\tonRestoreOriginal = ::restoreOriginalTranslation,\n\t\t\tonShowTranslation = ::showCachedTranslation,\n\t\t\tonAutoTranslateEnabled = { maybeAutoTranslateCurrentChapter(currentLocator().chapter) },\n\t\t\tonTranslate = ::translateCurrentChapter,\n\t\t).show()\n\t}\n''',
)
regex_once(
    READER,
    r'''\tprivate fun translateCurrentChapter\(selection: NovelTranslationSelection\) \{.*?\n\t\}\n\n\tprivate fun showInlineTranslationDialog''',
    '''\tprivate fun translateCurrentChapter(selection: NovelTranslationSelection) {\n\t\ttranslateChapter(currentLocator().clamped(), selection, automatic = false)\n\t}\n\n\tprivate fun maybeAutoTranslateCurrentChapter(chapterIndex: Int) {\n\t\tif (!isAdded || translationJob?.isActive == true) return\n\t\tval mangaId = bookSettingsMangaId.takeIf { it > 0L } ?: viewModel.getMangaOrNull()?.id ?: return\n\t\tval settings = NovelTranslationSettings(requireContext().applicationContext)\n\t\tval chapter = chapters.getOrNull(chapterIndex) ?: return\n\t\tif (!NovelAutoTranslationPolicy.shouldStart(\n\t\t\t\tenabled = settings.isAutoTranslateActive(mangaId),\n\t\t\t\tchapterReady = chapter.content != null,\n\t\t\t\thasTranslation = !inlineTranslations[chapter.id].isNullOrEmpty(),\n\t\t\t\ttranslationInFlight = translationJob?.isActive == true,\n\t\t\t\tchapterId = chapter.id,\n\t\t\t\tlastAttemptedChapterId = autoTranslationLastChapterId,\n\t\t\t)) return\n\t\tif (!isAutoTranslationProviderReady(settings)) {\n\t\t\tsettings.setAutoTranslateEnabled(mangaId, false)\n\t\t\tToast.makeText(requireContext(), R.string.novel_translation_auto_disabled_config, Toast.LENGTH_LONG).show()\n\t\t\treturn\n\t\t}\n\t\tautoTranslationLastChapterId = chapter.id\n\t\tval current = currentLocator().clamped()\n\t\tval locator = if (current.chapter == chapterIndex) current else Locator(chapterIndex, 0)\n\t\ttranslateChapter(locator, settings.currentSelection(), automatic = true)\n\t}\n\n\tprivate fun isAutoTranslationProviderReady(settings: NovelTranslationSettings): Boolean {\n\t\tif (settings.engine != NovelTranslationEngineKind.AI) return true\n\t\tval provider = settings.provider\n\t\tval secrets = NovelTranslationSecrets(requireContext().applicationContext)\n\t\treturn secrets.has(provider) &&\n\t\t\t(provider.protocol == NovelAiProtocol.DEEPL || settings.model(provider).isNotBlank()) &&\n\t\t\tsettings.resolvedBaseUrl(provider).isNotBlank()\n\t}\n\n\tprivate fun translateChapter(locator: Locator, selection: NovelTranslationSelection, automatic: Boolean) {\n\t\tval chapter = chapters.getOrNull(locator.chapter) ?: return\n\t\tval original = sourceText(chapter)\n\t\ttranslationOriginals.putIfAbsent(chapter.id, SpannedString(original))\n\t\tcancelActiveTranslation(incrementGeneration = false)\n\t\tval generation = ++translationGeneration\n\t\tval coordinator = translationCoordinator()\n\t\tif (!automatic) {\n\t\t\tsetChapterLoading(true)\n\t\t\tshowTranslationStatusDialog(\n\t\t\t\tgeneration = generation,\n\t\t\t\ttitleRes = R.string.epub_translate_current_chapter,\n\t\t\t\tmessage = getString(\n\t\t\t\t\tR.string.epub_translate_online_connecting,\n\t\t\t\t\tselection.sourceLanguage.uppercase(),\n\t\t\t\t\tselection.targetLanguage.uppercase(),\n\t\t\t\t),\n\t\t\t)\n\t\t}\n\t\ttranslationJob = viewLifecycleOwner.lifecycleScope.launch {\n\t\t\tval translated = runCatching {\n\t\t\t\twithContext(Dispatchers.IO) {\n\t\t\t\t\tcoordinator.translate(\n\t\t\t\t\t\tNovelTranslationOperationRequest(\n\t\t\t\t\t\t\ttext = original,\n\t\t\t\t\t\t\tselection = selection,\n\t\t\t\t\t\t),\n\t\t\t\t\t) { progress ->\n\t\t\t\t\t\tif (!automatic) {\n\t\t\t\t\t\t\twithContext(Dispatchers.Main) {\n\t\t\t\t\t\t\t\tif (generation == translationGeneration && isAdded) {\n\t\t\t\t\t\t\t\t\twhen (progress) {\n\t\t\t\t\t\t\t\t\t\tis NovelTranslationProgress.Translating -> updateTranslationStatus(\n\t\t\t\t\t\t\t\t\t\t\tgetString(\n\t\t\t\t\t\t\t\t\t\t\t\tR.string.epub_translate_translating_progress,\n\t\t\t\t\t\t\t\t\t\t\t\tprogress.completedSegments,\n\t\t\t\t\t\t\t\t\t\t\t\tprogress.totalSegments,\n\t\t\t\t\t\t\t\t\t\t\t),\n\t\t\t\t\t\t\t\t\t\t)\n\t\t\t\t\t\t\t\t\t\tis NovelTranslationProgress.WaitingToRetry -> updateTranslationStatus(\n\t\t\t\t\t\t\t\t\t\t\tgetString(\n\t\t\t\t\t\t\t\t\t\t\t\tR.string.epub_translate_translating_progress,\n\t\t\t\t\t\t\t\t\t\t\t\tprogress.completedSegments,\n\t\t\t\t\t\t\t\t\t\t\t\tprogress.totalSegments,\n\t\t\t\t\t\t\t\t\t\t\t),\n\t\t\t\t\t\t\t\t\t\t)\n\t\t\t\t\t\t\t\t\t}\n\t\t\t\t\t\t\t\t}\n\t\t\t\t\t\t\t}\n\t\t\t\t\t\t}\n\t\t\t\t\t}\n\t\t\t\t}\n\t\t\t}.getOrElse { error ->\n\t\t\t\tif (generation == translationGeneration && isAdded) {\n\t\t\t\t\tfinishTranslationUi()\n\t\t\t\t\tval detail = error.localizedMessage?.takeIf { it.isNotBlank() }\n\t\t\t\t\tToast.makeText(\n\t\t\t\t\t\trequireContext(),\n\t\t\t\t\t\tdetail ?: getString(if (automatic) R.string.novel_translation_auto_failed else R.string.epub_translate_failed),\n\t\t\t\t\t\tToast.LENGTH_LONG,\n\t\t\t\t\t).show()\n\t\t\t\t\tif (automatic) continueAutoTranslationFromCurrent(locator.chapter)\n\t\t\t\t}\n\t\t\t\treturn@launch\n\t\t\t}\n\t\t\tif (!isAdded || generation != translationGeneration) return@launch\n\t\t\tfinishTranslationUi()\n\t\t\tval items = translated.segments\n\t\t\t\t.filter { it.translatedText.isNotBlank() }\n\t\t\t\t.map { InlineTranslation(it.source.start, it.source.end, it.translatedText) }\n\t\t\tinlineTranslations[chapter.id] = items\n\t\t\tapplyTranslationProjection(chapter, selection.displayMode)\n\t\t\tval current = currentLocator().clamped()\n\t\t\tif (!automatic || current.chapter == locator.chapter) {\n\t\t\t\trefreshReader(if (automatic) current else locator)\n\t\t\t}\n\t\t\tif (automatic) continueAutoTranslationFromCurrent(locator.chapter)\n\t\t\telse Toast.makeText(requireContext(), R.string.epub_translate_inline_done, Toast.LENGTH_SHORT).show()\n\t\t}\n\t}\n\n\tprivate fun continueAutoTranslationFromCurrent(completedChapter: Int) {\n\t\tif (!isAdded || chapters.isEmpty()) return\n\t\tval current = currentLocator().clamped()\n\t\tif (current.chapter != completedChapter) maybeAutoTranslateCurrentChapter(current.chapter)\n\t}\n\n\tprivate fun showInlineTranslationDialog''',
)
replace_once(
    READER,
    '''\t\ttrimRemoteChapterCache(locator.chapter)\n\t\tval chapter = chapters[locator.chapter]\n''',
    '''\t\ttrimRemoteChapterCache(locator.chapter)\n\t\tval chapter = chapters[locator.chapter]\n\t\tmaybeAutoTranslateCurrentChapter(locator.chapter)\n''',
)
replace_once(
    READER,
    '''\t\tnovelTranslationCoordinator = null\n\t\ttranslationOriginals.clear()\n''',
    '''\t\tnovelTranslationCoordinator = null\n\t\tautoTranslationLastChapterId = 0L\n\t\ttranslationOriginals.clear()\n''',
)

# The reader now references these AI protocol/engine types directly in the auto readiness gate.
replace_once(
    READER,
    '''import org.koitharu.kotatsu.reader.ui.epub.translation.NovelTranslationDisplaySegment\n''',
    '''import org.koitharu.kotatsu.reader.ui.epub.translation.NovelTranslationDisplaySegment\nimport org.koitharu.kotatsu.reader.ui.epub.translation.NovelTranslationEngineKind\nimport org.koitharu.kotatsu.reader.ui.epub.translation.NovelAiProtocol\n''',
)

# --- Localized UX copy. ---
def append_strings(path: Path, additions: str) -> None:
    text = path.read_text()
    if 'name="novel_translation_auto_translate_this_novel"' in text:
        return
    if "</resources>" not in text:
        raise RuntimeError(f"{path}: missing resources close")
    text = text.replace("</resources>", additions + "\n</resources>", 1)
    path.write_text(text)

append_strings(
    STRINGS,
    '''    <string name="novel_translation_auto_translate_this_novel">Auto translate this novel</string>\n    <string name="novel_translation_auto_ai_note">Automatic translation sends each chapter you open to %1$s and may use your provider quota or billing.</string>\n    <string name="novel_translation_auto_requires_ai_setup">Configure the selected AI provider before enabling automatic translation.</string>\n    <string name="novel_translation_auto_disabled_config">Automatic translation was turned off because the selected AI provider is no longer configured.</string>\n    <string name="novel_translation_auto_failed">Automatic translation failed; showing the original text.</string>''',
)
append_strings(
    STRINGS_ID,
    '''    <string name="novel_translation_auto_translate_this_novel">Terjemahkan novel ini secara otomatis</string>\n    <string name="novel_translation_auto_ai_note">Terjemahan otomatis mengirim setiap bab yang kamu buka ke %1$s dan dapat memakai kuota atau penagihan penyedia.</string>\n    <string name="novel_translation_auto_requires_ai_setup">Konfigurasikan penyedia AI yang dipilih sebelum mengaktifkan terjemahan otomatis.</string>\n    <string name="novel_translation_auto_disabled_config">Terjemahan otomatis dimatikan karena penyedia AI yang dipilih tidak lagi terkonfigurasi.</string>\n    <string name="novel_translation_auto_failed">Terjemahan otomatis gagal; teks asli tetap ditampilkan.</string>''',
)

print("Batch 5 patch applied successfully")
