from pathlib import Path

def rep(path, old, new):
    p = Path(path)
    s = p.read_text()
    if old not in s:
        raise SystemExit(f"missing block in {path}: {old[:100]!r}")
    p.write_text(s.replace(old, new, 1))

p='app/src/main/kotlin/org/koitharu/kotatsu/main/ui/protect/ScreenshotPolicyHelper.kt'
rep(p,'import javax.inject.Inject\n','import javax.inject.Inject\nimport javax.inject.Singleton\n')
rep(p,'class ScreenshotPolicyHelper @Inject constructor(\n','@Singleton\nclass ScreenshotPolicyHelper @Inject constructor(\n')
rep(p,'\tprivate fun enforcePrivateSession(activity: Activity, owner: LifecycleOwner, isPrivate: Boolean) {\n',
'''\t/**
\t * True only after this Activity has been classified as an actual Private vault surface.
\t * This stays true even when FLAG_SECURE is deliberately relaxed for an authenticated screenshot.
\t */
\t@MainThread
\tfun isPrivateContent(activity: Activity): Boolean = privateContentState[activity] == true

\tprivate fun enforcePrivateSession(activity: Activity, owner: LifecycleOwner, isPrivate: Boolean) {
''')

p='app/src/main/kotlin/org/koitharu/kotatsu/core/ui/BaseActivityEntryPoint.kt'
rep(p,'import org.koitharu.kotatsu.core.prefs.AppSettings\n',
    'import org.koitharu.kotatsu.core.prefs.AppSettings\nimport org.koitharu.kotatsu.main.ui.protect.ScreenshotPolicyHelper\n')
rep(p,'\tval settings: AppSettings\n\n',
    '\tval settings: AppSettings\n\n\tval screenshotPolicyHelper: ScreenshotPolicyHelper\n\n')

p='app/src/main/kotlin/org/koitharu/kotatsu/core/ui/BaseActivity.kt'
old='''\t/**
\t * FLAG_SECURE is also a privacy boundary for OS assistant/recents integrations, not only pixels.
\t * Clear data populated by Activity's default implementation so a secure Details/Reader/Image
\t * window cannot leak its intent URL or structured page metadata through AssistContent.
\t */
\toverride fun onProvideAssistContent(outContent: AssistContent) {
\t\tsuper.onProvideAssistContent(outContent)
\t\tif (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) {
\t\t\toutContent.webUri = null
\t\t\toutContent.structuredData = null
\t\t}
\t}
'''
new='''\t/**
\t * Screenshot permission and OS metadata disclosure are separate boundaries. Private may
\t * deliberately relax FLAG_SECURE while authenticated, but AssistContent must remain scrubbed.
\t */
\toverride fun onProvideAssistContent(outContent: AssistContent) {
\t\tsuper.onProvideAssistContent(outContent)
\t\tif (
\t\t\twindow.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0 ||
\t\t\tentryPoint.screenshotPolicyHelper.isPrivateContent(this)
\t\t) {
\t\t\toutContent.webUri = null
\t\t\toutContent.structuredData = null
\t\t}
\t}
'''
rep(p,old,new)
