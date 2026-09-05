from pathlib import Path


epub = Path('app/src/main/kotlin/org/koitharu/kotatsu/reader/ui/epub/EpubReaderFragment.kt')
text = epub.read_text()

for line in [
    'import com.google.mlkit.common.model.DownloadConditions\n',
    'import com.google.mlkit.nl.translate.TranslateLanguage\n',
    'import com.google.mlkit.nl.translate.Translation\n',
    'import com.google.mlkit.nl.translate.Translator\n',
    'import com.google.mlkit.nl.translate.TranslatorOptions\n',
]:
    text = text.replace(line, '')
if 'import java.io.IOException\n' not in text:
    text = text.replace('import java.io.File\n', 'import java.io.File\nimport java.io.IOException\n')

old_fields = '''\tprivate val translationOriginals = HashMap<Long, Spanned>()
\tprivate var activeTranslator: Translator? = null
\tprivate var translationGeneration = 0
\tprivate var translationStatusDialog: androidx.appcompat.app.AlertDialog? = null
\tprivate var translationTimeoutRunnable: Runnable? = null
'''
new_fields = '''\tprivate val translationOriginals = HashMap<Long, Spanned>()
\tprivate var translationJob: Job? = null
\tprivate var translationGeneration = 0
\tprivate var translationStatusDialog: androidx.appcompat.app.AlertDialog? = null
'''
if old_fields not in text:
    raise SystemExit('Translation fields block not found')
text = text.replace(old_fields, new_fields, 1)

start = text.find('\tfun showTranslationDialog() {')
end = text.find('\n\tprivate data class EpubTranslationChunk(', start)
if start < 0 or end < 0:
    raise SystemExit('Translation UI block not found')
replacement = '''\tfun showTranslationDialog() {
\t\tif (chapters.isEmpty()) return
\t\tval labels = arrayOf(
\t\t\tgetString(R.string.epub_translate_show_original),
\t\t\tgetString(R.string.epub_translate_online_en_id),
\t\t\tgetString(R.string.epub_translate_online_ja_id),
\t\t\tgetString(R.string.epub_translate_online_ja_en),
\t\t\tgetString(R.string.epub_translate_online_ko_id),
\t\t\tgetString(R.string.epub_translate_online_ko_en),
\t\t\tgetString(R.string.epub_translate_online_zh_id),
\t\t\tgetString(R.string.epub_translate_online_zh_en),
\t\t\tgetString(R.string.epub_translate_offline_plugin),
\t\t)
\t\tMaterialAlertDialogBuilder(requireContext())
\t\t\t.setTitle(R.string.epub_translate_current_chapter)
\t\t\t.setMessage(R.string.epub_translate_online_note)
\t\t\t.setItems(labels) { _, which ->
\t\t\t\twhen {
\t\t\t\t\twhich == 0 -> restoreOriginalTranslation()
\t\t\t\t\twhich == labels.lastIndex -> showOfflineTranslationPluginInfo()
\t\t\t\t\telse -> {
\t\t\t\t\t\tval pair = TRANSLATION_PAIRS[which - 1]
\t\t\t\t\t\ttranslateCurrentChapter(pair.first, pair.second)
\t\t\t\t\t}
\t\t\t\t}
\t\t\t}
\t\t\t.setNegativeButton(android.R.string.cancel, null)
\t\t\t.show()
\t}

\tprivate fun showOfflineTranslationPluginInfo() {
\t\tMaterialAlertDialogBuilder(requireContext())
\t\t\t.setTitle(R.string.epub_translate_offline_plugin)
\t\t\t.setMessage(R.string.epub_translate_offline_plugin_not_installed)
\t\t\t.setPositiveButton(android.R.string.ok, null)
\t\t\t.show()
\t}

\tprivate fun translateCurrentChapter(sourceLanguage: String, targetLanguage: String) {
\t\tval locator = currentLocator()
\t\tval chapter = chapters.getOrNull(locator.chapter) ?: return
\t\tval original = translationOriginals[chapter.id] ?: chapter.content ?: return
\t\ttranslationOriginals.putIfAbsent(chapter.id, SpannedString(original))

\t\tcancelActiveTranslation(incrementGeneration = false)
\t\tval generation = ++translationGeneration
\t\tsetChapterLoading(true)
\t\tshowTranslationStatusDialog(generation, sourceLanguage, targetLanguage)
\t\tval chunks = splitTranslationText(original)
\t\tupdateTranslationStatus(getString(R.string.epub_translate_translating_progress, 0, chunks.size))

\t\ttranslationJob = viewLifecycleOwner.lifecycleScope.launch {
\t\t\tval translated = runCatching {
\t\t\t\twithContext(Dispatchers.IO) {
\t\t\t\t\tval result = ArrayList<EpubTranslatedChunk>(chunks.size)
\t\t\t\t\tchunks.forEachIndexed { index, chunk ->
\t\t\t\t\t\tif (generation != translationGeneration) return@withContext null
\t\t\t\t\t\tval translatedText = if (chunk.text.isBlank()) {
\t\t\t\t\t\t\tchunk.text
\t\t\t\t\t\t} else {
\t\t\t\t\t\t\ttranslateOnlineText(sourceLanguage, targetLanguage, chunk.text)
\t\t\t\t\t\t}
\t\t\t\t\t\tresult += EpubTranslatedChunk(chunk, translatedText)
\t\t\t\t\t\twithContext(Dispatchers.Main) {
\t\t\t\t\t\t\tif (generation == translationGeneration && isAdded) {
\t\t\t\t\t\t\t\tupdateTranslationStatus(
\t\t\t\t\t\t\t\t\tgetString(R.string.epub_translate_translating_progress, index + 1, chunks.size),
\t\t\t\t\t\t\t\t)
\t\t\t\t\t\t\t}
\t\t\t\t\t\t}
\t\t\t\t\t}
\t\t\t\t\tbuildTranslatedSpanned(original, result)
\t\t\t\t}
\t\t\t}.getOrElse { error ->
\t\t\t\tif (generation == translationGeneration && isAdded) {
\t\t\t\t\tfinishTranslationUi()
\t\t\t\t\tval detail = error.localizedMessage?.takeIf { it.isNotBlank() }
\t\t\t\t\tToast.makeText(
\t\t\t\t\t\trequireContext(),
\t\t\t\t\t\tdetail ?: getString(R.string.epub_translate_failed),
\t\t\t\t\t\tToast.LENGTH_LONG,
\t\t\t\t\t).show()
\t\t\t\t}
\t\t\t\treturn@launch
\t\t\t}

\t\t\tif (!isAdded || generation != translationGeneration || translated == null) return@launch
\t\t\tfinishTranslationUi()
\t\t\tval beforeLength = chapter.text.length.coerceAtLeast(1)
\t\t\tchapter.content = translated
\t\t\tval mappedOffset = (translated.length * (locator.offset.toDouble() / beforeLength))
\t\t\t\t.toInt().coerceIn(0, translated.length)
\t\t\trefreshReader(Locator(locator.chapter, mappedOffset))
\t\t\tToast.makeText(requireContext(), R.string.epub_translate_done, Toast.LENGTH_SHORT).show()
\t\t}
\t}

\tprivate fun translateOnlineText(sourceLanguage: String, targetLanguage: String, sourceText: String): String {
\t\tval url = ONLINE_TRANSLATE_URL.toHttpUrl().newBuilder()
\t\t\t.addQueryParameter("client", "gtx")
\t\t\t.addQueryParameter("sl", sourceLanguage)
\t\t\t.addQueryParameter("tl", targetLanguage)
\t\t\t.addQueryParameter("dt", "t")
\t\t\t.addQueryParameter("q", sourceText)
\t\t\t.build()
\t\tval request = Request.Builder()
\t\t\t.url(url)
\t\t\t.header("User-Agent", "Mozilla/5.0 (Android) Miyorare")
\t\t\t.get()
\t\t\t.build()
\t\thttpClient.newCall(request).execute().use { response ->
\t\t\tif (!response.isSuccessful) throw IOException("Online translation failed: HTTP ${response.code}")
\t\t\tval body = response.body.string()
\t\t\tval root = Json.parseToJsonElement(body).jsonArray
\t\t\tval segments = root.getOrNull(0)?.jsonArray
\t\t\t\t?: throw IOException("Online translation returned an invalid response")
\t\t\tval translated = segments.joinToString("") { segment ->
\t\t\t\tsegment.jsonArray.getOrNull(0)?.jsonPrimitive?.contentOrNull.orEmpty()
\t\t\t}
\t\t\tif (translated.isBlank() && sourceText.isNotBlank()) {
\t\t\t\tthrow IOException("Online translation returned an empty result")
\t\t\t}
\t\t\treturn translated
\t\t}
\t}

\tprivate fun showTranslationStatusDialog(generation: Int, sourceLanguage: String, targetLanguage: String) {
\t\ttranslationStatusDialog?.dismiss()
\t\ttranslationStatusDialog = MaterialAlertDialogBuilder(requireContext())
\t\t\t.setTitle(R.string.epub_translate_current_chapter)
\t\t\t.setMessage(getString(R.string.epub_translate_online_connecting, sourceLanguage.uppercase(), targetLanguage.uppercase()))
\t\t\t.setNegativeButton(android.R.string.cancel) { _, _ ->
\t\t\t\tif (generation == translationGeneration) cancelActiveTranslation(incrementGeneration = true)
\t\t\t}
\t\t\t.setCancelable(false)
\t\t\t.show()
\t}

\tprivate fun updateTranslationStatus(message: String) {
\t\ttranslationStatusDialog?.setMessage(message)
\t}

\tprivate fun finishTranslationUi() {
\t\tsetChapterLoading(false)
\t\ttranslationStatusDialog?.dismiss()
\t\ttranslationStatusDialog = null
\t\ttranslationJob = null
\t}

\tprivate fun cancelActiveTranslation(incrementGeneration: Boolean) {
\t\tif (incrementGeneration) translationGeneration++
\t\ttranslationJob?.cancel()
\t\ttranslationJob = null
\t\tfinishTranslationUi()
\t}

\tprivate fun restoreOriginalTranslation() {
\t\tval locator = currentLocator()
\t\tval chapter = chapters.getOrNull(locator.chapter) ?: return
\t\tval original = translationOriginals.remove(chapter.id) ?: run {
\t\t\tToast.makeText(requireContext(), R.string.epub_translate_already_original, Toast.LENGTH_SHORT).show()
\t\t\treturn
\t\t}
\t\ttranslationGeneration++
\t\ttranslationJob?.cancel()
\t\ttranslationJob = null
\t\tval beforeLength = chapter.text.length.coerceAtLeast(1)
\t\tchapter.content = original
\t\tval mappedOffset = (original.length * (locator.offset.toDouble() / beforeLength))
\t\t\t.toInt().coerceIn(0, original.length)
\t\trefreshReader(Locator(locator.chapter, mappedOffset))
\t}
'''
text = text[:start] + replacement + text[end:]

trans_start = text.find('\tprivate fun translateChunks(')
build_start = text.find('\n\t/**\n\t * Rebuilds the translated chapter', trans_start)
if trans_start >= 0 and build_start >= 0:
    text = text[:trans_start] + text[build_start + 1:]

old_destroy = '''\t\ttranslationGeneration++
\t\tclearTranslationTimeout()
\t\ttranslationStatusDialog?.dismiss()
\t\ttranslationStatusDialog = null
\t\tactiveTranslator?.close()
\t\tactiveTranslator = null
\t\ttranslationOriginals.clear()
'''
new_destroy = '''\t\ttranslationGeneration++
\t\ttranslationJob?.cancel()
\t\ttranslationJob = null
\t\ttranslationStatusDialog?.dismiss()
\t\ttranslationStatusDialog = null
\t\ttranslationOriginals.clear()
'''
if old_destroy not in text:
    raise SystemExit('onDestroy translation cleanup block not found')
text = text.replace(old_destroy, new_destroy, 1)

old_companion = '''\tcompanion object {
\t\tprivate const val TRANSLATION_MODEL_TIMEOUT_MS = 120_000L
\t\tprivate val TRANSLATION_PAIRS = listOf(
\t\t\tTranslateLanguage.ENGLISH to TranslateLanguage.INDONESIAN,
\t\t\tTranslateLanguage.JAPANESE to TranslateLanguage.INDONESIAN,
\t\t\tTranslateLanguage.JAPANESE to TranslateLanguage.ENGLISH,
\t\t\tTranslateLanguage.KOREAN to TranslateLanguage.INDONESIAN,
\t\t\tTranslateLanguage.KOREAN to TranslateLanguage.ENGLISH,
\t\t\tTranslateLanguage.CHINESE to TranslateLanguage.INDONESIAN,
\t\t\tTranslateLanguage.CHINESE to TranslateLanguage.ENGLISH,
\t\t)
'''
new_companion = '''\tcompanion object {
\t\tprivate const val ONLINE_TRANSLATE_URL = "https://translate.googleapis.com/translate_a/single"
\t\tprivate val TRANSLATION_PAIRS = listOf(
\t\t\t"en" to "id",
\t\t\t"ja" to "id",
\t\t\t"ja" to "en",
\t\t\t"ko" to "id",
\t\t\t"ko" to "en",
\t\t\t"zh-CN" to "id",
\t\t\t"zh-CN" to "en",
\t\t)
'''
if old_companion not in text:
    raise SystemExit('Translation companion block not found')
text = text.replace(old_companion, new_companion, 1)
epub.write_text(text)

gradle = Path('app/build.gradle')
gradle_text = gradle.read_text()
mlkit = "\timplementation 'com.google.mlkit:translate:17.0.3'\n"
if mlkit not in gradle_text:
    raise SystemExit('ML Kit dependency not found')
gradle.write_text(gradle_text.replace(mlkit, '', 1))

strings = Path('app/src/main/res/values/strings_noirero_translate.xml')
strings.write_text('''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="epub_translate">Translate</string>
    <string name="epub_translate_current_chapter">Translate current chapter</string>
    <string name="epub_translate_show_original">Original / Restore original</string>
    <string name="epub_translate_online_en_id">Online • English → Indonesia</string>
    <string name="epub_translate_online_ja_id">Online • Japanese → Indonesia</string>
    <string name="epub_translate_online_ja_en">Online • Japanese → English</string>
    <string name="epub_translate_online_ko_id">Online • Korean → Indonesia</string>
    <string name="epub_translate_online_ko_en">Online • Korean → English</string>
    <string name="epub_translate_online_zh_id">Online • Chinese → Indonesia</string>
    <string name="epub_translate_online_zh_en">Online • Chinese → English</string>
    <string name="epub_translate_online_note">Online translation is the lightweight default and requires an internet connection. Paragraph layout and EPUB formatting are preserved.</string>
    <string name="epub_translate_online_connecting">Online translation %1$s → %2$s…</string>
    <string name="epub_translate_translating_progress">Translating chapter… %1$d / %2$d</string>
    <string name="epub_translate_offline_plugin">Offline translation (plugin)</string>
    <string name="epub_translate_offline_plugin_not_installed">The offline translation engine is an optional plugin and is not installed yet. The main Miyorare APK does not include ML Kit, keeping the app lightweight.</string>
    <string name="epub_translate_done">Chapter translated</string>
    <string name="epub_translate_failed">Could not translate this chapter. Check your internet connection and try again.</string>
    <string name="epub_translate_already_original">This chapter is already showing the original text.</string>
</resources>
''')
