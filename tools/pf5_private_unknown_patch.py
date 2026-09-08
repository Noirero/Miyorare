from pathlib import Path

def rep(path, old, new):
    p = Path(path)
    s = p.read_text()
    if old not in s:
        raise SystemExit(f"missing block in {path}: {old[:120]!r}")
    p.write_text(s.replace(old, new, 1))

p='app/src/main/kotlin/org/koitharu/kotatsu/main/ui/protect/ScreenshotPolicyHelper.kt'
old='''\t\t).transformLatest {
\t\t\temit(PrivateMembershipState.UNKNOWN)
\t\t\tval privateOnly = runCatchingCancellable { isPrivateOnly(mangaId) }.getOrDefault(true)
\t\t\temit(if (privateOnly) PrivateMembershipState.PRIVATE else PrivateMembershipState.NORMAL)
\t\t}.distinctUntilChanged()
'''
new='''\t\t).transformLatest {
\t\t\temit(PrivateMembershipState.UNKNOWN)
\t\t\tval privateOnly = runCatchingCancellable { isPrivateOnly(mangaId) }.getOrNull()
\t\t\t\t?: return@transformLatest
\t\t\temit(if (privateOnly) PrivateMembershipState.PRIVATE else PrivateMembershipState.NORMAL)
\t\t}.distinctUntilChanged()
'''
rep(p,old,new)

p='app/src/main/kotlin/org/koitharu/kotatsu/details/ui/DetailsExpressiveActivity.kt'
old='''\t\t\t.transformLatest { manga ->
\t\t\t\temit(PrivateContentState.UNKNOWN)
\t\t\t\tif (manga == null) return@transformLatest
\t\t\t\tval isPrivate = runCatchingCancellable {
\t\t\t\t\tdatabase.getPrivateFavouritesDao().isPrivateOnly(manga.id)
\t\t\t\t}.getOrDefault(true)
\t\t\t\temit(if (isPrivate) PrivateContentState.PRIVATE else PrivateContentState.NORMAL)
\t\t\t}
'''
new='''\t\t\t.transformLatest { manga ->
\t\t\t\temit(PrivateContentState.UNKNOWN)
\t\t\t\tif (manga == null) return@transformLatest
\t\t\t\tval isPrivate = runCatchingCancellable {
\t\t\t\t\tdatabase.getPrivateFavouritesDao().isPrivateOnly(manga.id)
\t\t\t\t}.getOrNull() ?: return@transformLatest
\t\t\t\temit(if (isPrivate) PrivateContentState.PRIVATE else PrivateContentState.NORMAL)
\t\t\t}
'''
rep(p,old,new)
