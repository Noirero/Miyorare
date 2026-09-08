from pathlib import Path

path = Path('app/src/main/kotlin/org/koitharu/kotatsu/download/ui/worker/DownloadWorker.kt')
s = path.read_text()


def rep(old: str, new: str) -> None:
    global s
    if old not in s:
        raise SystemExit(f'missing patch anchor: {old[:160]!r}')
    s = s.replace(old, new, 1)

rep(
    'import kotlinx.coroutines.CancellationException\nimport kotlinx.coroutines.Dispatchers\n',
    'import kotlinx.coroutines.CancellationException\nimport kotlinx.coroutines.CoroutineScope\nimport kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.currentCoroutineContext\n',
)
rep(
    'import kotlinx.coroutines.flow.channelFlow\nimport kotlinx.coroutines.flow.drop\nimport kotlinx.coroutines.flow.map\n',
    'import kotlinx.coroutines.flow.channelFlow\nimport kotlinx.coroutines.flow.distinctUntilChanged\nimport kotlinx.coroutines.flow.drop\nimport kotlinx.coroutines.flow.map\n',
)
rep(
    'import org.koitharu.kotatsu.R\nimport org.koitharu.kotatsu.core.image.BitmapDecoderCompat\n',
    'import org.koitharu.kotatsu.R\nimport org.koitharu.kotatsu.core.db.MangaDatabase\nimport org.koitharu.kotatsu.core.image.BitmapDecoderCompat\n',
)
rep(
    '\t@PageCache private val cache: LocalStorageCache,\n\tprivate val localMangaRepository: LocalMangaRepository,\n',
    '\t@PageCache private val cache: LocalStorageCache,\n\tprivate val database: MangaDatabase,\n\tprivate val localMangaRepository: LocalMangaRepository,\n',
)
rep(
    '\t\tval manga = mangaDataRepository.findMangaById(task.mangaId, withChapters = true) ?: return Result.failure()\n\t\tpublishState(DownloadState(manga = manga, isIndeterminate = true).also { lastPublishedState = it })\n',
    '\t\tval manga = mangaDataRepository.findMangaById(task.mangaId, withChapters = true) ?: return Result.failure()\n'
    '\t\t// Membership can change while a download is stalled on network I/O. Observe the atomic\n'
    '\t\t// Normal/Private classification so an already-posted public notification is scrubbed\n'
    '\t\t// immediately instead of waiting for the next page/progress update.\n'
    '\t\tval privacyRefreshJob = CoroutineScope(currentCoroutineContext()).launch {\n'
    '\t\t\tdatabase.getPrivateFavouritesDao()\n'
    '\t\t\t\t.observePrivateOnly(manga.id)\n'
    '\t\t\t\t.distinctUntilChanged()\n'
    '\t\t\t\t.collect { refreshNotificationForPrivacy() }\n'
    '\t\t}\n'
    '\t\tpublishState(DownloadState(manga = manga, isIndeterminate = true).also { lastPublishedState = it })\n',
)
rep(
    '\t\t} finally {\n\t\t\trunCatching { applicationContext.unregisterReceiver(pausingReceiver) }\n',
    '\t\t} finally {\n\t\t\tprivacyRefreshJob.cancel()\n\t\t\trunCatching { applicationContext.unregisterReceiver(pausingReceiver) }\n',
)
anchor = '\tprivate suspend fun publishState(state: DownloadState) = statePublishMutex.withLock {\n'
insert = '''\t/**
\t * Membership invalidation bypasses the progress throttler. This is a disclosure boundary, not a
\t * progress update: a Normal -> Private transition must replace an existing public notification
\t * even when the download is paused or waiting on a slow source.
\t */
\tprivate suspend fun refreshNotificationForPrivacy() = statePublishMutex.withLock {
\t\tval state = lastPublishedState ?: return@withLock
\t\tval notification = notificationFactory.create(state)
\t\tif (state.isFinalState) {
\t\t\tif (!notificationFactory.isSilent) {
\t\t\t\tnotificationManager.notify(id.toString(), id.hashCode(), notification)
\t\t\t}
\t\t} else {
\t\t\tnotificationManager.notify(id.hashCode(), notification)
\t\t}
\t}

'''
if anchor not in s:
    raise SystemExit('publishState anchor missing')
s = s.replace(anchor, insert + anchor, 1)

path.write_text(s)
