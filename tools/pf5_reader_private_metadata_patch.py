from pathlib import Path


def rep(path: str, old: str, new: str) -> None:
    p = Path(path)
    s = p.read_text()
    if old not in s:
        raise SystemExit(f"missing block in {path}: {old[:140]!r}")
    p.write_text(s.replace(old, new, 1))


# Atomic Room observer for live NORMAL/PRIVATE changes while TTS is active.
path = 'app/src/main/kotlin/org/koitharu/kotatsu/favourites/data/PrivateFavouritesDao.kt'
old = '''\t@Query(
\t\t"SELECT EXISTS(SELECT 1 FROM private_favourites WHERE manga_id = :mangaId AND deleted_at = 0) " +
\t\t\t"AND NOT EXISTS(SELECT 1 FROM favourites WHERE manga_id = :mangaId AND deleted_at = 0)",
\t)
\tabstract suspend fun isPrivateOnly(mangaId: Long): Boolean
'''
new = old + '''
\t@Query(
\t\t"SELECT EXISTS(SELECT 1 FROM private_favourites WHERE manga_id = :mangaId AND deleted_at = 0) " +
\t\t\t"AND NOT EXISTS(SELECT 1 FROM favourites WHERE manga_id = :mangaId AND deleted_at = 0)",
\t)
\tabstract fun observePrivateOnly(mangaId: Long): Flow<Boolean>
'''
rep(path, old, new)

# Reader must not repopulate AssistContent after BaseActivity scrubbed a Private window.
path = 'app/src/main/kotlin/org/koitharu/kotatsu/reader/ui/ReaderActivity.kt'
rep(
    path,
    '        if (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) return\n',
    '        if (\n'
    '            window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0 ||\n'
    '            entryPoint.screenshotPolicyHelper.isPrivateContent(this)\n'
    '        ) return\n',
)
rep(
    path,
    '                ReaderTtsService.start(this, viewModel.getMangaOrNull()?.title.orEmpty())\n',
    '                val manga = viewModel.getMangaOrNull()\n'
    '                ReaderTtsService.start(this, manga?.title.orEmpty(), manga?.id ?: 0L)\n',
)

# TTS notification privacy is separate from FLAG_SECURE because authenticated Private screenshots
# deliberately relax that flag while Reader is foreground.
path = 'app/src/main/kotlin/org/koitharu/kotatsu/reader/ui/tts/ReaderTtsService.kt'
rep(
    path,
    'import dagger.hilt.android.AndroidEntryPoint\nimport kotlinx.coroutines.flow.launchIn\nimport kotlinx.coroutines.flow.onEach\n',
    'import dagger.hilt.android.AndroidEntryPoint\n'
    'import kotlinx.coroutines.Job\n'
    'import kotlinx.coroutines.flow.distinctUntilChanged\n'
    'import kotlinx.coroutines.flow.launchIn\n'
    'import kotlinx.coroutines.flow.onEach\n',
)
rep(
    path,
    'import org.koitharu.kotatsu.R\nimport org.koitharu.kotatsu.core.prefs.AppSettings\nimport org.koitharu.kotatsu.core.ui.util.ForegroundActivityHolder\n',
    'import org.koitharu.kotatsu.R\n'
    'import org.koitharu.kotatsu.core.db.MangaDatabase\n'
    'import org.koitharu.kotatsu.core.prefs.AppSettings\n'
    'import org.koitharu.kotatsu.core.ui.util.ForegroundActivityHolder\n'
    'import org.koitharu.kotatsu.main.ui.protect.ScreenshotPolicyHelper\n',
)
rep(
    path,
    '\t@Inject\n\tlateinit var foregroundActivityHolder: ForegroundActivityHolder\n\n\tprivate var title: String = ""\n\tprivate var hideSensitiveTitle: Boolean = false\n',
    '\t@Inject\n\tlateinit var foregroundActivityHolder: ForegroundActivityHolder\n\n'
    '\t@Inject\n\tlateinit var database: MangaDatabase\n\n'
    '\t@Inject\n\tlateinit var screenshotPolicyHelper: ScreenshotPolicyHelper\n\n'
    '\tprivate var title: String = ""\n'
    '\tprivate var hideSensitiveTitle: Boolean = false\n'
    '\tprivate var isPrivateOnly: Boolean = true\n'
    '\tprivate var observedMangaId: Long = Long.MIN_VALUE\n'
    '\tprivate var privacyJob: Job? = null\n',
)
rep(
    path,
    '\t\tsuper.onStartCommand(intent, flags, startId)\n\t\tintent?.getStringExtra(EXTRA_TITLE)?.let {\n',
    '\t\tsuper.onStartCommand(intent, flags, startId)\n'
    '\t\tif (intent?.hasExtra(EXTRA_MANGA_ID) == true) {\n'
    '\t\t\tval mangaId = intent.getLongExtra(EXTRA_MANGA_ID, 0L)\n'
    '\t\t\tif (mangaId != observedMangaId || privacyJob == null) observePrivateMembership(mangaId)\n'
    '\t\t}\n'
    '\t\tintent?.getStringExtra(EXTRA_TITLE)?.let {\n',
)
rep(
    path,
    '''\t\t\t// FLAG_SECURE is the single privacy boundary used by Private Favourites, app protection,
\t\t\t// NSFW screenshot policy and BLOCK_ALL. Remember it before the Reader goes to background so
\t\t\t// the media notification cannot reveal a title the window itself was forbidden to expose.
\t\t\thideSensitiveTitle = foregroundActivityHolder.current?.isSecureWindow() == true
''',
    '''\t\t\t// Screenshot permission and notification disclosure are separate boundaries. Remember
\t\t\t// secure/private state before the Reader backgrounds, while the Room observer below keeps
\t\t\t// live NORMAL -> PRIVATE membership changes reflected in the media notification.
\t\t\thideSensitiveTitle = foregroundActivityHolder.current?.isSensitiveWindow() == true
''',
)
marker = '\n\tprivate fun startForeground() {\n'
insert = '''
\tprivate fun observePrivateMembership(mangaId: Long) {
\t\tobservedMangaId = mangaId
\t\tprivacyJob?.cancel()
\t\t// Fail closed until Room emits the atomic dual-membership classification.
\t\tisPrivateOnly = true
\t\tprivacyJob = database.getPrivateFavouritesDao()
\t\t\t.observePrivateOnly(mangaId)
\t\t\t.distinctUntilChanged()
\t\t\t.onEach { privateOnly ->
\t\t\t\tisPrivateOnly = privateOnly
\t\t\t\tif (tts.isAttached) notify(tts.isPlaying.value)
\t\t\t}
\t\t\t.launchIn(lifecycleScope)
\t}
'''
p = Path(path)
s = p.read_text()
if marker not in s:
    raise SystemExit('startForeground marker missing')
p.write_text(s.replace(marker, '\n' + insert + marker, 1))
rep(
    path,
    '\tprivate fun notify(isPlaying: Boolean) {\n\t\tNotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(isPlaying))\n\t}\n',
    '\tprivate fun notify(isPlaying: Boolean) {\n'
    '\t\ttry {\n'
    '\t\t\tNotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(isPlaying))\n'
    '\t\t} catch (_: SecurityException) {\n'
    '\t\t\t// Notification permission may be revoked while the foreground service is alive.\n'
    '\t\t}\n'
    '\t}\n',
)
rep(
    path,
    '\t\tval sensitive = hideSensitiveTitle || foregroundActivityHolder.current?.isSecureWindow() == true\n',
    '\t\tval currentSensitive = foregroundActivityHolder.current?.isSensitiveWindow() == true\n'
    '\t\tval sensitive = shouldHideTtsTitle(isPrivateOnly, hideSensitiveTitle, currentSensitive)\n',
)
rep(
    path,
    '\tprivate fun android.app.Activity.isSecureWindow(): Boolean =\n\t\twindow.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0\n',
    '\tprivate fun android.app.Activity.isSensitiveWindow(): Boolean =\n'
    '\t\twindow.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0 ||\n'
    '\t\t\tscreenshotPolicyHelper.isPrivateContent(this)\n',
)
rep(
    path,
    '\t\tprivate const val EXTRA_TITLE = "title"\n',
    '\t\tprivate const val EXTRA_TITLE = "title"\n\t\tprivate const val EXTRA_MANGA_ID = "manga_id"\n',
)
rep(
    path,
    '''\t\tfun start(context: Context, title: String) {
\t\t\tval intent = Intent(context, ReaderTtsService::class.java).putExtra(EXTRA_TITLE, title)
\t\t\tContextCompat.startForegroundService(context, intent)
\t\t}
''',
    '''\t\tfun start(context: Context, title: String, mangaId: Long) {
\t\t\tval intent = Intent(context, ReaderTtsService::class.java)
\t\t\t\t.putExtra(EXTRA_TITLE, title)
\t\t\t\t.putExtra(EXTRA_MANGA_ID, mangaId)
\t\t\tContextCompat.startForegroundService(context, intent)
\t\t}
''',
)
p = Path(path)
s = p.read_text()
if 'internal fun shouldHideTtsTitle(' in s:
    raise SystemExit('shouldHideTtsTitle already exists')
p.write_text(s + '''

internal fun shouldHideTtsTitle(
\tisPrivateOnly: Boolean,
\trememberedSensitive: Boolean,
\tcurrentSensitive: Boolean,
): Boolean = isPrivateOnly || rememberedSensitive || currentSensitive
''')

test = Path('app/src/test/kotlin/org/koitharu/kotatsu/reader/ui/tts/ReaderTtsPrivacyPolicyTest.kt')
test.parent.mkdir(parents=True, exist_ok=True)
test.write_text('''package org.koitharu.kotatsu.reader.ui.tts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderTtsPrivacyPolicyTest {
\t@Test fun `private only always hides title`() =
\t\tassertTrue(shouldHideTtsTitle(isPrivateOnly = true, rememberedSensitive = false, currentSensitive = false))

\t@Test fun `normal unlocked reader may show title`() =
\t\tassertFalse(shouldHideTtsTitle(isPrivateOnly = false, rememberedSensitive = false, currentSensitive = false))

\t@Test fun `remembered secure state hides title in background`() =
\t\tassertTrue(shouldHideTtsTitle(isPrivateOnly = false, rememberedSensitive = true, currentSensitive = false))

\t@Test fun `current secure state hides title`() =
\t\tassertTrue(shouldHideTtsTitle(isPrivateOnly = false, rememberedSensitive = false, currentSensitive = true))
}
''')
