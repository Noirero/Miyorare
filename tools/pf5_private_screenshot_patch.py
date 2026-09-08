from pathlib import Path

def rep(path, old, new):
    p = Path(path)
    s = p.read_text()
    if old not in s:
        raise SystemExit(f"missing block in {path}: {old[:100]!r}")
    p.write_text(s.replace(old, new, 1))

p='app/src/main/kotlin/org/koitharu/kotatsu/favourites/vault/PrivateFavouritesSecurityStore.kt'
rep(p,'import dagger.hilt.android.qualifiers.ApplicationContext\n',
    'import dagger.hilt.android.qualifiers.ApplicationContext\nimport kotlinx.coroutines.flow.MutableStateFlow\nimport kotlinx.coroutines.flow.StateFlow\nimport kotlinx.coroutines.flow.asStateFlow\n')
rep(p,'\tprivate val file = File(context.noBackupFilesDir, FILE_NAME)\n\tprivate val lock = Any()\n',
    '\tprivate val file = File(context.noBackupFilesDir, FILE_NAME)\n\tprivate val lock = Any()\n\tprivate val allowPrivateScreenshotsState = MutableStateFlow(\n\t\tsynchronized(lock) {\n\t\t\tread().getProperty(KEY_ALLOW_SCREENSHOTS)?.toBooleanStrictOrNull() ?: false\n\t\t},\n\t)\n\tval allowPrivateScreenshotsFlow: StateFlow<Boolean> = allowPrivateScreenshotsState.asStateFlow()\n')
old='''\tvar includePrivateInBackup: Boolean
\t\tget() = synchronized(lock) { read().getProperty(KEY_INCLUDE_BACKUP)?.toBooleanStrictOrNull() ?: false }
\t\tset(value) = synchronized(lock) {
\t\t\twrite(read().apply { setProperty(KEY_INCLUDE_BACKUP, value.toString()) })
\t\t}
'''
new=old+'''\n\t/** Independent from the general screenshot policy and false on every existing install. */
\tvar allowPrivateScreenshots: Boolean
\t\tget() = allowPrivateScreenshotsState.value
\t\tset(value) = synchronized(lock) {
\t\t\twrite(read().apply { setProperty(KEY_ALLOW_SCREENSHOTS, value.toString()) })
\t\t\tallowPrivateScreenshotsState.value = value
\t\t}

\tvar privateScreenshotWarningAcknowledged: Boolean
\t\tget() = synchronized(lock) {
\t\t\tread().getProperty(KEY_SCREENSHOT_WARNING_ACK)?.toBooleanStrictOrNull() ?: false
\t\t}
\t\tset(value) = synchronized(lock) {
\t\t\twrite(read().apply { setProperty(KEY_SCREENSHOT_WARNING_ACK, value.toString()) })
\t\t}
'''
rep(p,old,new)
rep(p,'\t\tconst val KEY_INCLUDE_BACKUP = "include_private_backup"\n',
    '\t\tconst val KEY_INCLUDE_BACKUP = "include_private_backup"\n\t\tconst val KEY_ALLOW_SCREENSHOTS = "allow_private_screenshots"\n\t\tconst val KEY_SCREENSHOT_WARNING_ACK = "private_screenshot_warning_ack"\n')

p='app/src/main/kotlin/org/koitharu/kotatsu/settings/PrivateFavouritesSettingsFragment.kt'
rep(p,'\tprivate val backupState = MutableStateFlow(false)\n',
    '\tprivate val backupState = MutableStateFlow(false)\n\tprivate val screenshotsState = MutableStateFlow(false)\n')
rep(p,'\t\t\t\tval includeBackup by backupState.collectAsState()\n',
    '\t\t\t\tval includeBackup by backupState.collectAsState()\n\t\t\t\tval allowScreenshots by screenshotsState.collectAsState()\n')
rep(p,'\t\t\t\t\tincludeBackup = includeBackup,\n',
    '\t\t\t\t\tincludeBackup = includeBackup,\n\t\t\t\t\tallowScreenshots = allowScreenshots,\n')
rep(p,'\t\t\t\t\tonIncludeBackupChange = ::changeBackupInclusion,\n',
    '\t\t\t\t\tonIncludeBackupChange = ::changeBackupInclusion,\n\t\t\t\t\tonAllowScreenshotsChange = ::changePrivateScreenshots,\n')
rep(p,'\t\tbackupState.value = security.includePrivateInBackup\n',
    '\t\tbackupState.value = security.includePrivateInBackup\n\t\tscreenshotsState.value = security.allowPrivateScreenshots\n')
marker='\n\tprivate fun changeBackupInclusion(include: Boolean) {\n'
insert='''\tprivate fun changePrivateScreenshots(allow: Boolean) {
\t\tif (!allow) {
\t\t\tsecurity.allowPrivateScreenshots = false
\t\t\trefreshState()
\t\t\treturn
\t\t}
\t\tif (security.privateScreenshotWarningAcknowledged) {
\t\t\tsecurity.allowPrivateScreenshots = true
\t\t\trefreshState()
\t\t\treturn
\t\t}
\t\tbuildAlertDialog(requireContext(), isCentered = true) {
\t\t\tsetTitle(R.string.private_favourites_screenshot_warning_title)
\t\t\tsetMessage(R.string.private_favourites_screenshot_warning_message)
\t\t\tsetPositiveButton(R.string.private_favourites_screenshot_allow_button) { _, _ ->
\t\t\t\tsecurity.privateScreenshotWarningAcknowledged = true
\t\t\t\tsecurity.allowPrivateScreenshots = true
\t\t\t\trefreshState()
\t\t\t}
\t\t\tsetNegativeButton(android.R.string.cancel, null)
\t\t}.show()
\t}

'''
pp=Path(p); s=pp.read_text()
if marker not in s: raise SystemExit('missing backup marker')
pp.write_text(s.replace(marker,'\n'+insert+marker,1))
rep(p,'\tincludeBackup: Boolean,\n','\tincludeBackup: Boolean,\n\tallowScreenshots: Boolean,\n')
rep(p,'\tonIncludeBackupChange: (Boolean) -> Unit,\n',
    '\tonIncludeBackupChange: (Boolean) -> Unit,\n\tonAllowScreenshotsChange: (Boolean) -> Unit,\n')
old='''\t\titem { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
\t\titem {
\t\t\tSettingsGroup(title = stringResource(R.string.private_favourites_backup_group)) {
'''
new='''\t\titem { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
\t\titem {
\t\t\tSettingsGroup(title = stringResource(R.string.private_favourites_privacy_group)) {
\t\t\t\titem { pos ->
\t\t\t\t\tSwitchSettingsItem(
\t\t\t\t\t\ttitle = stringResource(R.string.private_favourites_allow_screenshots),
\t\t\t\t\t\tsubtitle = stringResource(R.string.private_favourites_allow_screenshots_summary),
\t\t\t\t\t\tchecked = allowScreenshots,
\t\t\t\t\t\tonCheckedChange = onAllowScreenshotsChange,
\t\t\t\t\t\ticon = R.drawable.ic_lock,
\t\t\t\t\t\tshape = pos.shape,
\t\t\t\t\t)
\t\t\t\t}
\t\t\t}
\t\t}
\t\titem { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
\t\titem {
\t\t\tSettingsGroup(title = stringResource(R.string.private_favourites_backup_group)) {
'''
rep(p,old,new)

p='app/src/main/kotlin/org/koitharu/kotatsu/main/ui/protect/ScreenshotPolicyHelper.kt'
rep(p,'import kotlinx.coroutines.flow.Flow\n','import kotlinx.coroutines.flow.Flow\nimport kotlinx.coroutines.flow.MutableStateFlow\n')
rep(p,'import org.koitharu.kotatsu.favourites.vault.PrivateFavouritesSession\n',
    'import org.koitharu.kotatsu.favourites.vault.PrivateFavouritesSecurityStore\nimport org.koitharu.kotatsu.favourites.vault.PrivateFavouritesSession\n')
rep(p,'\tprivate val favouritesRepository: FavouritesRepository,\n\tprivate val privateSession: PrivateFavouritesSession,\n',
    '\tprivate val favouritesRepository: FavouritesRepository,\n\tprivate val privateSecurity: PrivateFavouritesSecurityStore,\n\tprivate val privateSession: PrivateFavouritesSession,\n')
rep(p,'\tprivate val privateContentState = WeakHashMap<Activity, Boolean>()\n',
    '\tprivate val privateContentState = WeakHashMap<Activity, Boolean>()\n\tprivate val activityResumedState = WeakHashMap<Activity, MutableStateFlow<Boolean>>()\n')
rep(p,'\t\tval container = activity as? ContentContainer ?: return\n',
    '\t\tval container = activity as? ContentContainer ?: return\n\t\tactivityResumedState[activity] = MutableStateFlow(false)\n')
rep(p,'\toverride fun onActivityResumed(activity: Activity) {\n\t\tif (activity is FavouritesActivity) return\n',
    '\toverride fun onActivityResumed(activity: Activity) {\n\t\tactivityResumedState[activity]?.value = true\n\t\tif (activity is FavouritesActivity) return\n')
rep(p,'\tprivate fun ContentContainer.setupScreenshotPolicy(activity: Activity) =\n',
'''\toverride fun onActivityPaused(activity: Activity) {
\t\tactivityResumedState[activity]?.value = false
\t\t// Android may capture the task snapshot before ProcessLifecycleOwner reaches onStop.
\t\tif (privateContentState[activity] == true) {
\t\t\tactivity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
\t\t}
\t}

\toverride fun onActivityDestroyed(activity: Activity) {
\t\tprivateContentState.remove(activity)
\t\tactivityResumedState.remove(activity)
\t}

\tprivate fun ContentContainer.setupScreenshotPolicy(activity: Activity) =
''')
rep(p,'\t\t\tval protectAppFlow = settings.observeAsFlow(AppSettings.KEY_PROTECT_APP) { isAppProtectionEnabled }\n\t\t\tval privateMembershipState = observePrivateContent(activity)\n',
'''\t\t\tval protectAppFlow = settings.observeAsFlow(AppSettings.KEY_PROTECT_APP) { isAppProtectionEnabled }
\t\t\tval appProtectionSecureFlow = combine(
\t\t\t\tprotectAppFlow,
\t\t\t\tprotectHelper.isUnlockedFlow,
\t\t\t) { enabled, unlocked -> enabled && !unlocked }.distinctUntilChanged()
\t\t\tval resumedFlow = activityResumedState.getOrPut(activity) { MutableStateFlow(false) }
\t\t\tval privateScreenshotsAllowedFlow = combine(
\t\t\t\tprivateSecurity.allowPrivateScreenshotsFlow,
\t\t\t\tprivateSession.isUnlocked,
\t\t\t\tresumedFlow,
\t\t\t) { allowed, unlocked, resumed -> allowed && unlocked && resumed }.distinctUntilChanged()
\t\t\tval privateMembershipState = observePrivateContent(activity)
''')
old='''\t\t\tcombine(
\t\t\t\tscreenshotPolicyFlow,
\t\t\t\tprotectAppFlow,
\t\t\t\tprotectHelper.isUnlockedFlow,
\t\t\t\tprivateVaultFlow,
\t\t\t\tsensitiveScreenFlow,
\t\t\t) { screenshotSecure, protectEnabled, isUnlocked, privateVault, sensitiveScreen ->
\t\t\t\tSecurityState(
\t\t\t\t\tisSecure = screenshotSecure || privateVault || sensitiveScreen || (protectEnabled && !isUnlocked),
\t\t\t\t\tisPrivateVault = privateVault,
\t\t\t\t)
\t\t\t}.collect { state ->
'''
new='''\t\t\tcombine(
\t\t\t\tscreenshotPolicyFlow,
\t\t\t\tprivateVaultFlow,
\t\t\t\tsensitiveScreenFlow,
\t\t\t\tprivateScreenshotsAllowedFlow,
\t\t\t\tappProtectionSecureFlow,
\t\t\t) { screenshotSecure, privateVault, sensitiveScreen, privateScreenshotsAllowed, appProtectionSecure ->
\t\t\t\tSecurityState(
\t\t\t\t\tisSecure = shouldSecureWindow(
\t\t\t\t\t\tscreenshotSecure = screenshotSecure,
\t\t\t\t\t\tprivateVault = privateVault,
\t\t\t\t\t\tsensitiveScreen = sensitiveScreen,
\t\t\t\t\t\tprivateScreenshotsAllowed = privateScreenshotsAllowed,
\t\t\t\t\t\tappProtectionSecure = appProtectionSecure,
\t\t\t\t\t),
\t\t\t\t\tisPrivateVault = privateVault,
\t\t\t\t)
\t\t\t}.collect { state ->
'''
rep(p,old,new)
pp=Path(p); s=pp.read_text()
if 'internal fun shouldSecureWindow(' in s: raise SystemExit('policy helper already exists')
pp.write_text(s+'''\n\n/**
 * Private screenshot permission is intentionally independent from the general screenshot policy.
 * Authentication and foreground state are folded into [privateScreenshotsAllowed] by the caller.
 */
internal fun shouldSecureWindow(
\tscreenshotSecure: Boolean,
\tprivateVault: Boolean,
\tsensitiveScreen: Boolean,
\tprivateScreenshotsAllowed: Boolean,
\tappProtectionSecure: Boolean,
): Boolean = if (privateVault) {
\t!privateScreenshotsAllowed || appProtectionSecure
} else {
\tscreenshotSecure || sensitiveScreen || appProtectionSecure
}
''')

p='app/src/main/kotlin/org/koitharu/kotatsu/details/ui/DetailsExpressiveActivity.kt'
rep(p,'\t\tif (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) return\n',
    '\t\tif (privateContentStateFlow.value != PrivateContentState.NORMAL ||\n\t\t\twindow.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0\n\t\t) return\n')

test=Path('app/src/test/kotlin/org/koitharu/kotatsu/main/ui/protect/ScreenshotPolicyHelperTest.kt')
test.parent.mkdir(parents=True,exist_ok=True)
test.write_text('''package org.koitharu.kotatsu.main.ui.protect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotPolicyHelperTest {
\t@Test fun `private is secure by default`() = assertTrue(
\t\tshouldSecureWindow(false, true, false, false, false),
\t)
\t@Test fun `private permission ignores general screenshot block`() = assertFalse(
\t\tshouldSecureWindow(true, true, true, true, false),
\t)
\t@Test fun `app protection still secures private`() = assertTrue(
\t\tshouldSecureWindow(false, true, false, true, true),
\t)
\t@Test fun `normal follows general screenshot policy`() = assertTrue(
\t\tshouldSecureWindow(true, false, false, true, false),
\t)
\t@Test fun `unknown sensitive classification is fail closed`() = assertTrue(
\t\tshouldSecureWindow(false, false, true, true, false),
\t)
}
''')

p='.github/workflows/pf5-regression-audit.yml'
rep(p,
'            --tests org.koitharu.kotatsu.mihon.MihonWebViewTokenErrorTest\n',
'            --tests org.koitharu.kotatsu.mihon.MihonWebViewTokenErrorTest \\\n            --tests org.koitharu.kotatsu.main.ui.protect.ScreenshotPolicyHelperTest\n')

Path('app/src/main/res/values/private_favourites_screenshot.xml').write_text('''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="private_favourites_privacy_group">Privacy</string>
    <string name="private_favourites_allow_screenshots">Allow screenshots in Private</string>
    <string name="private_favourites_allow_screenshots_summary">Allow device screenshots only while Private is unlocked and visible. This is independent from the general screenshot policy.</string>
    <string name="private_favourites_screenshot_warning_title">Allow screenshots in Private?</string>
    <string name="private_favourites_screenshot_warning_message">Private screenshots may be saved to your gallery or accessed by other apps. On some devices, allowing screenshots may also allow screen recording or casting. Private remains protected while locked, loading, or in the background.</string>
    <string name="private_favourites_screenshot_allow_button">Allow</string>
</resources>
''')
Path('app/src/main/res/values-in/private_favourites_screenshot.xml').write_text('''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="private_favourites_privacy_group">Privasi</string>
    <string name="private_favourites_allow_screenshots">Izinkan tangkapan layar di Private</string>
    <string name="private_favourites_allow_screenshots_summary">Izinkan tangkapan layar perangkat hanya saat Private sudah terbuka dan terlihat. Pengaturan ini terpisah dari kebijakan tangkapan layar umum.</string>
    <string name="private_favourites_screenshot_warning_title">Izinkan tangkapan layar di Private?</string>
    <string name="private_favourites_screenshot_warning_message">Tangkapan layar Private dapat tersimpan di Galeri atau diakses aplikasi lain. Pada sebagian perangkat, mengizinkan tangkapan layar juga dapat memungkinkan perekaman layar atau casting. Private tetap terlindungi saat terkunci, loading, atau aplikasi berada di latar belakang.</string>
    <string name="private_favourites_screenshot_allow_button">Izinkan</string>
</resources>
''')
