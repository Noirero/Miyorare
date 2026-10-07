This release focuses on faster page loading, more reliable stale-page recovery, and persistent manga cover caching.

### ✨ Improvements

- Added a **Chapter/Page Metadata Cache** to reduce repeated page metadata requests and improve loading efficiency.
- Page metadata can be reused for up to **48 hours**, with bounded storage and automatic cleanup of older entries.
- Added **Persistent Cover Cache** so manga covers can remain available even after Android's regular app cache is cleared.
- Added bounded persistent cover storage to prevent cover cache growth without limits.
- Added a dedicated **Clear cover cache** option with cache size information.
- Improved safe migration from the previous temporary cover cache.

### 🐞 Fixed

- Improved recovery when cached page metadata becomes stale and a page returns **404**, including Mihon-based sources.
- Stale chapter page metadata can now be refreshed without unnecessarily affecting Reader progress or reading history.
- Various cache reliability and stability improvements.
