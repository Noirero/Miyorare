from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected exactly one match, found {count}: {old[:100]!r}")
    path.write_text(text.replace(old, new, 1))


def replace_all_checked(path: Path, old: str, new: str, minimum: int = 1) -> None:
    text = path.read_text()
    count = text.count(old)
    if count < minimum:
        raise RuntimeError(f"{path}: expected at least {minimum} matches, found {count}: {old!r}")
    path.write_text(text.replace(old, new))


root = Path(__file__).resolve().parents[2]
epub = root / "app/src/main/kotlin/org/koitharu/kotatsu/reader/ui/epub/EpubReaderFragment.kt"
config = root / "app/src/main/kotlin/org/koitharu/kotatsu/reader/ui/config/ReaderConfigSheet.kt"

# ---- EpubReaderFragment: bind the active book profile and observe profile changes. ----
replace_once(
    epub,
    "\t@Inject\n\tlateinit var tts: ReaderTts\n\n\tprivate var chapters: List<NativeChapter> = emptyList()",
    "\t@Inject\n\tlateinit var tts: ReaderTts\n\n\t@Inject\n\tlateinit var epubBookSettingsStore: EpubBookSettingsStore\n\n\tprivate var chapters: List<NativeChapter> = emptyList()",
)
replace_once(
    epub,
    "\tprivate var translationStatusDialog: androidx.appcompat.app.AlertDialog? = null\n",
    "\tprivate var translationStatusDialog: androidx.appcompat.app.AlertDialog? = null\n"
    "\tprivate var bookSettings: EpubBookSettingsStore.BookSettings? = null\n"
    "\tprivate var bookSettingsJob: Job? = null\n"
    "\tprivate var bookSettingsMangaId = 0L\n",
)
replace_once(
    epub,
    "\toverride suspend fun onPagesChanged(pages: List<ReaderPage>, pendingState: ReaderState?) {\n"
    "\t\tval manga = viewModel.getMangaOrNull() ?: return\n"
    "\t\tval state = pendingState ?: viewModel.getCurrentState() ?: return\n"
    "\t\tobserveHighlights(manga)",
    "\toverride suspend fun onPagesChanged(pages: List<ReaderPage>, pendingState: ReaderState?) {\n"
    "\t\tval manga = viewModel.getMangaOrNull() ?: return\n"
    "\t\tattachBookSettings(manga.id)\n"
    "\t\tval state = pendingState ?: viewModel.getCurrentState() ?: return\n"
    "\t\tobserveHighlights(manga)",
)
replace_once(
    epub,
    "\tprivate fun setChapterLoading(value: Boolean) {\n\t\tviewBinding?.loadingIndicator?.isVisible = value\n\t}\n\n\tprivate fun observeHighlights(manga: Manga) {",
    "\tprivate fun setChapterLoading(value: Boolean) {\n\t\tviewBinding?.loadingIndicator?.isVisible = value\n\t}\n\n"
    "\tprivate fun attachBookSettings(mangaId: Long) {\n"
    "\t\tif (bookSettingsMangaId == mangaId && bookSettings != null) return\n"
    "\t\tbookSettingsMangaId = mangaId\n"
    "\t\tbookSettings = epubBookSettingsStore.forBook(mangaId)\n"
    "\t\ttts.attachBook(mangaId)\n"
    "\t\tbookSettingsJob?.cancel()\n"
    "\t\tbookSettingsJob = viewLifecycleOwner.lifecycleScope.launch {\n"
    "\t\t\tepubBookSettingsStore.observeReader(mangaId).collect {\n"
    "\t\t\t\tif (bookSettingsMangaId != mangaId) return@collect\n"
    "\t\t\t\tviewBinding?.root?.requestApplyInsets()\n"
    "\t\t\t\tanimateColors()\n"
    "\t\t\t\trefreshHighlightColors()\n"
    "\t\t\t\tswitchReadingMode()\n"
    "\t\t\t}\n"
    "\t\t}\n"
    "\t}\n\n"
    "\tprivate fun observeHighlights(manga: Manga) {",
)
replace_once(
    epub,
    "\t\ttranslationOriginals.clear()\n\t\tcachedCustomTypeface = null",
    "\t\ttranslationOriginals.clear()\n"
    "\t\tbookSettingsJob?.cancel()\n"
    "\t\tbookSettingsJob = null\n"
    "\t\tbookSettings = null\n"
    "\t\tbookSettingsMangaId = 0L\n"
    "\t\tcachedCustomTypeface = null",
)

# ---- EpubReaderFragment: use the active book profile for reader presentation. ----
replace_once(
    epub,
    '"${settings.epubCustomFontRevision}:" +\n\t\t\t"$effectiveLineHeight:$effectiveParagraphSpacing:$effectiveHorizontalPadding:$effectiveVerticalPadding:" +\n\t\t\t"$effectiveTextAlign:${settings.epubReadingMode}:${settings.isEpubPublisherStyleEnabled}:" +\n\t\t\t"${settings.isEpubBionicReadingEnabled}"',
    '"${settings.epubCustomFontRevision}:" +\n\t\t\t"$effectiveLineHeight:$effectiveParagraphSpacing:$effectiveHorizontalPadding:$effectiveVerticalPadding:" +\n\t\t\t"$effectiveTextAlign:$activeReadingMode:$activePublisherStyle:" +\n\t\t\t"$activeBionicReading"',
)
replace_all_checked(epub, "settings.isEpubPublisherStyleEnabled", "activePublisherStyle", minimum=5)
replace_all_checked(epub, "settings.isEpubBionicReadingEnabled", "activeBionicReading", minimum=2)

old_settings_block = '''\tprivate val isPagedMode get() = settings.epubReadingMode != EPUB_MODE_SCROLL
\tprivate val isRtlPagedMode get() = settings.epubReadingMode == EPUB_MODE_PAGED_RTL
\tprivate val effectiveTextAlign get() = when {
\t\tactivePublisherStyle -> if (isRtlPagedMode) "right" else "left"
\t\tisRtlPagedMode && settings.epubTextAlign == "left" -> "right"
\t\telse -> settings.epubTextAlign
\t}
\tprivate val effectiveFontSize get() = if (activePublisherStyle) 100 else settings.epubFontSize
\tprivate val effectiveLineHeight get() = if (activePublisherStyle) 120 else settings.epubLineHeight
\tprivate val effectiveParagraphSpacing get() =
\t\tif (activePublisherStyle) 0 else settings.epubParagraphSpacing
\tprivate val effectiveHorizontalPadding get() =
\t\tif (activePublisherStyle) PUBLISHER_HORIZONTAL_PADDING_DP else settings.epubHorizontalPadding
\tprivate val effectiveVerticalPadding get() =
\t\tif (activePublisherStyle) VERTICAL_MARGIN_MAX else settings.epubVerticalPadding
'''
new_settings_block = '''\tprivate val activeReadingMode get() = bookSettings?.readingMode ?: settings.epubReadingMode
\tprivate val activePublisherStyle get() = bookSettings?.publisherStyle ?: settings.isEpubPublisherStyleEnabled
\tprivate val activeBionicReading get() = bookSettings?.bionicReading ?: settings.isEpubBionicReadingEnabled
\tprivate val activeTextAlign get() = bookSettings?.textAlign ?: settings.epubTextAlign
\tprivate val activeFontSize get() = bookSettings?.fontSize ?: settings.epubFontSize
\tprivate val activeFontFamily get() = bookSettings?.fontFamily ?: settings.epubFontFamily
\tprivate val activeLineHeight get() = bookSettings?.lineHeight ?: settings.epubLineHeight
\tprivate val activeParagraphSpacing get() = bookSettings?.paragraphSpacing ?: settings.epubParagraphSpacing
\tprivate val activeHorizontalPadding get() = bookSettings?.horizontalPadding ?: settings.epubHorizontalPadding
\tprivate val activeVerticalPadding get() = bookSettings?.verticalPadding ?: settings.epubVerticalPadding
\tprivate val activeTheme get() = bookSettings?.theme ?: settings.epubTheme
\tprivate val activeCustomBackgroundColor get() = bookSettings?.customBackgroundColor ?: settings.epubCustomBackgroundColor
\tprivate val activeCustomTextColor get() = bookSettings?.customTextColor ?: settings.epubCustomTextColor
\tprivate val activeCustomHighlightColor get() = bookSettings?.customHighlightColor ?: settings.epubCustomHighlightColor

\tprivate val isPagedMode get() = activeReadingMode != EPUB_MODE_SCROLL
\tprivate val isRtlPagedMode get() = activeReadingMode == EPUB_MODE_PAGED_RTL
\tprivate val effectiveTextAlign get() = when {
\t\tactivePublisherStyle -> if (isRtlPagedMode) "right" else "left"
\t\tisRtlPagedMode && activeTextAlign == "left" -> "right"
\t\telse -> activeTextAlign
\t}
\tprivate val effectiveFontSize get() = if (activePublisherStyle) 100 else activeFontSize
\tprivate val effectiveLineHeight get() = if (activePublisherStyle) 120 else activeLineHeight
\tprivate val effectiveParagraphSpacing get() =
\t\tif (activePublisherStyle) 0 else activeParagraphSpacing
\tprivate val effectiveHorizontalPadding get() =
\t\tif (activePublisherStyle) PUBLISHER_HORIZONTAL_PADDING_DP else activeHorizontalPadding
\tprivate val effectiveVerticalPadding get() =
\t\tif (activePublisherStyle) VERTICAL_MARGIN_MAX else activeVerticalPadding
'''
replace_once(epub, old_settings_block, new_settings_block)

replace_once(
    epub,
    '''\tprivate val backgroundColor: Int get() {
\t\tif (settings.epubTheme == EPUB_THEME_CUSTOM) {
\t\t\treturn ColorUtils.setAlphaComponent(settings.epubCustomBackgroundColor, 255)
\t\t}
\t\tval dark = when (settings.epubTheme) {''',
    '''\tprivate val backgroundColor: Int get() {
\t\tif (activeTheme == EPUB_THEME_CUSTOM) {
\t\t\treturn ColorUtils.setAlphaComponent(activeCustomBackgroundColor, 255)
\t\t}
\t\tval dark = when (activeTheme) {''',
)
replace_once(
    epub,
    '''\tprivate val foregroundColor get() = if (settings.epubTheme == EPUB_THEME_CUSTOM) {
\t\tColorUtils.setAlphaComponent(settings.epubCustomTextColor, 255)''',
    '''\tprivate val foregroundColor get() = if (activeTheme == EPUB_THEME_CUSTOM) {
\t\tColorUtils.setAlphaComponent(activeCustomTextColor, 255)''',
)
replace_once(
    epub,
    '''\tprivate val highlightColor get() = if (settings.epubTheme == EPUB_THEME_CUSTOM) {
\t\tColorUtils.setAlphaComponent(settings.epubCustomHighlightColor, HIGHLIGHT_ALPHA)''',
    '''\tprivate val highlightColor get() = if (activeTheme == EPUB_THEME_CUSTOM) {
\t\tColorUtils.setAlphaComponent(activeCustomHighlightColor, HIGHLIGHT_ALPHA)''',
)
replace_once(
    epub,
    '''\tprivate val readerTypeface get() = when {
\t\tactivePublisherStyle -> Typeface.SERIF
\t\tsettings.epubFontFamily == EPUB_FONT_CUSTOM -> customReaderTypeface()
\t\telse -> Typeface.create(settings.epubFontFamily.substringBefore(',').trim().trim('\\'', '"'), Typeface.NORMAL)
\t}''',
    '''\tprivate val readerTypeface get() = when {
\t\tactivePublisherStyle -> Typeface.SERIF
\t\tactiveFontFamily == EPUB_FONT_CUSTOM -> customReaderTypeface()
\t\telse -> Typeface.create(activeFontFamily.substringBefore(',').trim().trim('\\'', '"'), Typeface.NORMAL)
\t}''',
)

# Global preference observers should not test the wrong profile when this book has an override.
replace_all_checked(epub, "if (settings.epubTheme == EPUB_THEME_CUSTOM)", "if (activeTheme == EPUB_THEME_CUSTOM)", minimum=3)
replace_once(
    epub,
    "\t\t\t\tif (settings.epubFontFamily == EPUB_FONT_CUSTOM) scheduleReflow()",
    "\t\t\t\tif (activeFontFamily == EPUB_FONT_CUSTOM) scheduleReflow()",
)

# ---- EpubReaderFragment: downsample embedded EPUB illustrations before allocating a bitmap. ----
old_image = '''\tprivate fun loadEpubImage(chapter: NativeChapter, source: String): Drawable? = runCatching {
\t\t// ByteArray for an embedded epub resource, absolute url for a remote one - coil takes either.
\t\tval data = chapterContent?.imageData(chapter.url, source) ?: return null
\t\tval drawable = (data as? ByteArray)
\t\t\t?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
\t\t\t?.let { BitmapDrawable(resources, it) }
\t\t\t?: runBlocking {
\t\t\t\t// Tag the request with the source so it goes out with that source's headers/client:
\t\t\t\t// illustrations on Referer-checking hosts 403 on a bare request.
\t\t\t\timageLoader.execute(
\t\t\t\t\tImageRequest.Builder(requireContext())
\t\t\t\t\t\t.data(data)
\t\t\t\t\t\t.mangaSourceExtra(viewModel.getMangaOrNull()?.source)
\t\t\t\t\t\t.build(),
\t\t\t\t).getDrawableOrThrow()
\t\t\t}
\t\tval width = drawable.intrinsicWidth.takeIf { it > 0 } ?: return null
\t\tval height = drawable.intrinsicHeight.takeIf { it > 0 } ?: return null
\t\tval metrics = resources.displayMetrics
\t\tval maxWidth = (metrics.widthPixels - 2 * effectiveHorizontalPadding * metrics.density).toInt().coerceAtLeast(1)
\t\tval maxHeight = (metrics.heightPixels * MAX_IMAGE_HEIGHT_FRACTION).toInt().coerceAtLeast(1)
\t\tval scale = minOf(1f, maxWidth / width.toFloat(), maxHeight / height.toFloat())
\t\tdrawable.setBounds(0, 0, (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
\t\tdrawable
\t}.getOrNull()
'''
new_image = '''\tprivate fun loadEpubImage(chapter: NativeChapter, source: String): Drawable? = runCatching {
\t\t// ByteArray for an embedded epub resource, absolute url for a remote one - coil takes either.
\t\tval data = chapterContent?.imageData(chapter.url, source) ?: return null
\t\tval metrics = resources.displayMetrics
\t\tval maxWidth = (metrics.widthPixels - 2 * effectiveHorizontalPadding * metrics.density).toInt().coerceAtLeast(1)
\t\tval maxHeight = (metrics.heightPixels * MAX_IMAGE_HEIGHT_FRACTION).toInt().coerceAtLeast(1)
\t\tval drawable = (data as? ByteArray)
\t\t\t?.let { decodeEmbeddedImage(it, maxWidth, maxHeight) }
\t\t\t?: runBlocking {
\t\t\t\t// Tag the request with the source so it goes out with that source's headers/client:
\t\t\t\t// illustrations on Referer-checking hosts 403 on a bare request.
\t\t\t\timageLoader.execute(
\t\t\t\t\tImageRequest.Builder(requireContext())
\t\t\t\t\t\t.data(data)
\t\t\t\t\t\t.mangaSourceExtra(viewModel.getMangaOrNull()?.source)
\t\t\t\t\t\t.build(),
\t\t\t\t).getDrawableOrThrow()
\t\t\t}
\t\tval width = drawable.intrinsicWidth.takeIf { it > 0 } ?: return null
\t\tval height = drawable.intrinsicHeight.takeIf { it > 0 } ?: return null
\t\tval scale = minOf(1f, maxWidth / width.toFloat(), maxHeight / height.toFloat())
\t\tdrawable.setBounds(0, 0, (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
\t\tdrawable
\t}.getOrNull()

\tprivate fun decodeEmbeddedImage(data: ByteArray, targetWidth: Int, targetHeight: Int): Drawable? {
\t\tval bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
\t\tBitmapFactory.decodeByteArray(data, 0, data.size, bounds)
\t\tval width = bounds.outWidth
\t\tval height = bounds.outHeight
\t\tif (width <= 0 || height <= 0) return null
\t\tvar sample = 1
\t\twhile (width / sample > targetWidth * 2 || height / sample > targetHeight * 2) {
\t\t\tsample *= 2
\t\t}
\t\tval bitmap = BitmapFactory.decodeByteArray(
\t\t\tdata,
\t\t\t0,
\t\t\tdata.size,
\t\t\tBitmapFactory.Options().apply { inSampleSize = sample },
\t\t) ?: return null
\t\treturn BitmapDrawable(resources, bitmap)
\t}
'''
replace_once(epub, old_image, new_image)

# ---- ReaderConfigSheet: expose and edit the active profile instead of always editing global EPUB settings. ----
replace_once(
    config,
    "import org.koitharu.kotatsu.reader.ui.ReaderViewModel\nimport org.koitharu.kotatsu.reader.ui.ScreenOrientationHelper",
    "import org.koitharu.kotatsu.reader.ui.ReaderViewModel\n"
    "import org.koitharu.kotatsu.reader.ui.ScreenOrientationHelper\n"
    "import org.koitharu.kotatsu.reader.ui.epub.EpubBookSettingsStore",
)
replace_once(
    config,
    "\t@Inject\n\tlateinit var settings: AppSettings\n\n\tprivate var customFontUiRevision by mutableIntStateOf(0)",
    "\t@Inject\n\tlateinit var settings: AppSettings\n"
    "\n\t@Inject\n\tlateinit var epubBookSettingsStore: EpubBookSettingsStore\n"
    "\n\tprivate var activeEpubBookSettings: EpubBookSettingsStore.BookSettings? = null\n"
    "\tprivate val epubSettings: EpubBookSettingsStore.BookSettings\n"
    "\t\tget() = checkNotNull(activeEpubBookSettings) { \"EPUB settings requested outside an EPUB reader\" }\n"
    "\n\tprivate var customFontUiRevision by mutableIntStateOf(0)",
)
replace_once(
    config,
    "\t\tsuper.onViewBindingCreated(binding, savedInstanceState)\n\t\tbinding.composeView.setViewCompositionStrategy(",
    "\t\tsuper.onViewBindingCreated(binding, savedInstanceState)\n"
    "\t\tactiveEpubBookSettings = viewModel.getMangaOrNull()\n"
    "\t\t\t?.takeIf { it.isEpub }\n"
    "\t\t\t?.let { epubBookSettingsStore.forBook(it.id) }\n"
    "\t\tbinding.composeView.setViewCompositionStrategy(",
)

# Custom-font metadata/file is intentionally global, but the choice of using it can be per novel.
replace_all_checked(config, "settings.epubFontFamily", "epubSettings.fontFamily", minimum=4)
replace_all_checked(config, "settings.epubFontSize", "epubSettings.fontSize", minimum=3)
replace_all_checked(config, "settings.epubLineHeight", "epubSettings.lineHeight", minimum=2)
replace_all_checked(config, "settings.epubParagraphSpacing", "epubSettings.paragraphSpacing", minimum=2)
replace_all_checked(config, "settings.epubHorizontalPadding", "epubSettings.horizontalPadding", minimum=2)
replace_all_checked(config, "settings.epubVerticalPadding", "epubSettings.verticalPadding", minimum=2)
replace_all_checked(config, "settings.epubTextAlign", "epubSettings.textAlign", minimum=5)
replace_all_checked(config, "settings.epubReadingMode", "epubSettings.readingMode", minimum=3)
replace_all_checked(config, "settings.isEpubPagedTapGesturesEnabled", "epubSettings.pagedTapGestures", minimum=2)
replace_all_checked(config, "settings.isEpubPublisherStyleEnabled", "epubSettings.publisherStyle", minimum=2)
replace_all_checked(config, "settings.isEpubBionicReadingEnabled", "epubSettings.bionicReading", minimum=2)
replace_all_checked(config, "settings.epubTheme", "epubSettings.theme", minimum=5)
replace_all_checked(config, "settings.epubCustomBackgroundColor", "epubSettings.customBackgroundColor", minimum=2)
replace_all_checked(config, "settings.epubCustomTextColor", "epubSettings.customTextColor", minimum=2)
replace_all_checked(config, "settings.epubCustomHighlightColor", "epubSettings.customHighlightColor", minimum=2)

replace_once(
    config,
    "    private fun EpubConfigContent() {\n        val customFontName = remember(customFontUiRevision) { settings.epubCustomFontName }",
    "    private fun EpubConfigContent() {\n"
    "        val customFontName = remember(customFontUiRevision) { settings.epubCustomFontName }\n"
    "        var perBookEnabled by remember { mutableStateOf(epubSettings.enabled) }",
)
replace_once(
    config,
    "                if (page == 0) {\n                    EpubTextSizeSection(",
    "                if (page == 0) {\n"
    "                    EpubProfileScopeSection(\n"
    "                        enabled = perBookEnabled,\n"
    "                        onEnabledChange = { enabled ->\n"
    "                            epubSettings.enabled = enabled\n"
    "                            perBookEnabled = enabled\n"
    "                            // Reopen so every remembered slider/choice is rebuilt from the newly active scope.\n"
    "                            dismissAllowingStateLoss()\n"
    "                        },\n"
    "                    )\n"
    "                    EpubTextSizeSection(",
)

insert_before = "    @Composable\n    private fun EpubSliderSection("
profile_ui = '''    @Composable
    private fun EpubProfileScopeSection(
        enabled: Boolean,
        onEnabledChange: (Boolean) -> Unit,
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.epub_per_book_settings),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.epub_per_book_settings_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
        }
    }

'''
replace_once(config, insert_before, profile_ui + insert_before)

# Add standalone resource files so the base strings.xml and translation catalogs stay untouched.
values = root / "app/src/main/res/values/strings_novel_reader_settings.xml"
values_in = root / "app/src/main/res/values-in/strings_novel_reader_settings.xml"
values.write_text('''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="epub_per_book_settings">Settings for this novel</string>
    <string name="epub_per_book_settings_summary">Off uses the global Novel reader settings. On keeps a separate profile for this novel.</string>
</resources>
''')
values_in.write_text('''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="epub_per_book_settings">Pengaturan khusus Novel ini</string>
    <string name="epub_per_book_settings_summary">Matikan untuk memakai pengaturan Novel global. Aktifkan untuk menyimpan profil terpisah untuk Novel ini.</string>
</resources>
''')

print("Novel reader profile patch applied successfully")
