from pathlib import Path

def rep(path, old, new):
    p = Path(path)
    s = p.read_text()
    if old not in s:
        raise SystemExit(f"missing block in {path}: {old[:100]!r}")
    p.write_text(s.replace(old, new, 1))

p='app/src/main/kotlin/org/koitharu/kotatsu/main/ui/protect/ScreenshotPolicyHelper.kt'
rep(p,'import android.content.Intent\nimport android.os.Bundle\n',
    'import android.content.Intent\nimport android.os.Build\nimport android.os.Bundle\n')
old='''\t\tactivityResumedState[activity] = MutableStateFlow(false)
\t\tif (explicitPrivateSpace(activity) || mangaId(activity) != null) {
\t\t\tactivity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
\t\t}
'''
new='''\t\tactivityResumedState[activity] = MutableStateFlow(false)
\t\tif (explicitPrivateSpace(activity) || mangaId(activity) != null) {
\t\t\tactivity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
\t\t\tif (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
\t\t\t\t// Fail closed until membership is classified. This affects only Overview/Recents,
\t\t\t\t// not the user's foreground screenshot gesture.
\t\t\t\tactivity.setRecentsScreenshotEnabled(false)
\t\t\t}
\t\t}
'''
rep(p,old,new)
old='''\t\t\tval privateVaultFlow = combine(
\t\t\t\tprivateMembershipState,
\t\t\t\tisPrivateVaultContent().distinctUntilChanged(),
\t\t\t) { fromIntentOrMembership, fromScreen ->
\t\t\t\tfromIntentOrMembership == PrivateMembershipState.PRIVATE || fromScreen
\t\t\t}.distinctUntilChanged()
'''
new='''\t\t\tval screenPrivateVaultFlow = isPrivateVaultContent().distinctUntilChanged()
\t\t\tval privateVaultFlow = combine(
\t\t\t\tprivateMembershipState,
\t\t\t\tscreenPrivateVaultFlow,
\t\t\t) { fromIntentOrMembership, fromScreen ->
\t\t\t\tfromIntentOrMembership == PrivateMembershipState.PRIVATE || fromScreen
\t\t\t}.distinctUntilChanged()
\t\t\tif (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
\t\t\t\tlaunch {
\t\t\t\t\tcombine(privateMembershipState, screenPrivateVaultFlow) { membership, fromScreen ->
\t\t\t\t\t\tmembership != PrivateMembershipState.NORMAL || fromScreen
\t\t\t\t\t}.distinctUntilChanged().collect { protectRecents ->
\t\t\t\t\t\tactivity.setRecentsScreenshotEnabled(!protectRecents)
\t\t\t\t\t}
\t\t\t\t}
\t\t\t}
'''
rep(p,old,new)
