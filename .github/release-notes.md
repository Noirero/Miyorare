## Miyorare v1.4.14

This release expands how you discover, organize, and preserve your library. Miyorare introduces a new Discovery Hub backed by tracker catalogs, Category Sets for organizing Favourites, stronger backup and synchronization support, and improved migration compatibility.

### ✨ What's New

- **Discovery Hub** — a new discovery experience brings catalog browsing into Miyorare with dedicated navigation, responsive layouts, search integration, and status handling.
- **AniList, Kitsu & MyAnimeList catalogs** — browse discovery catalogs backed by multiple tracker ecosystems from one integrated experience.
- **Tracker catalog browsing** — dedicated catalog screens provide a richer way to explore titles and move from discovery into Miyorare's reading flow.
- **Category Sets for Favourites** — Favourite categories can now be organized into separate sets, giving larger libraries another layer of organization.
- **Category Set management** — create, select, reorder, and manage category sets through dedicated controls and dialogs.
- **Category navigation** — Favourites now includes dedicated category/set navigation to make moving through organized collections easier.
- **New source additions** — the Miyorare source packs have been expanded with additional sources, including ComicAsura, NewXToon, RawDex, and MGKomik.

### 🚀 Improvements

- **Discovery-to-reading integration** — catalog results can resolve into available Miyorare sources and continue into the normal reading experience.
- **Discovery resilience** — catalog data includes snapshot/cache handling and dedicated status presentation for more graceful behavior when remote catalog services are unavailable.
- **Responsive Discovery UI** — Discovery screens have dedicated behavior and validation for different available screen sizes.
- **Shared search ownership** — navigation and Discovery now coordinate ownership of the shared search interface more reliably.
- **Favourites organization** — Favourite repositories, category selection, editing, unread counters, and list presentation now understand Category Sets.
- **Private Favourites compatibility** — transfers between regular and Private Favourites have been updated to work with the expanded category organization model.
- **Category Sets in backup** — local backup now preserves Category Set information alongside the rest of the library state.
- **Category Sets in Google Drive sync** — synchronization and merge logic now understand Category Sets so organization can survive cross-device sync.
- **Migration compatibility** — source migration now includes additional handling for chapter-level personal data when moving titles between compatible sources.
- **Navigation preferences** — main navigation configuration has been simplified and moved toward a dedicated preference model.
- **Favourite cover information** — modern Favourite covers received additional reading-state and overlay handling.
- **Cloudflare browser handling** — browser challenge handling received lifecycle and navigation improvements.

### 🐞 Fixes

- Improved preservation of chapter-level personal data during compatible source migrations.
- Added database migration support for Category Sets when upgrading existing installations.
- Improved backup compatibility for Private Favourites and the expanded Favourite organization model.
- Improved Google Drive merge behavior for synchronized Category Sets.
- Fixed additional Favourite unread-count and downloaded/local-state edge cases.
- Improved Favourite category selection and editing behavior with the new set model.
- Improved navigation behavior around Discovery and shared search.
- Strengthened downloaded-novel round-trip validation and runtime diagnostics.
- Added broad regression coverage for Discovery navigation, catalog browsing, Category Sets, backup/sync, migration compatibility, responsive UI, and Favourite organization.

<details>
<summary><strong>🇮🇩 Bahasa Indonesia</strong></summary>

Rilis ini memperluas cara kamu menemukan, mengatur, dan mempertahankan koleksi. Miyorare menghadirkan Discovery Hub baru berbasis katalog tracker, Category Sets untuk mengatur Favourites, dukungan backup dan sinkronisasi yang lebih kuat, serta compatibility migration yang lebih baik.

### ✨ Yang Baru

- **Discovery Hub** — pengalaman discovery baru menghadirkan catalog browsing langsung di Miyorare dengan navigasi khusus, layout responsif, integrasi search, dan status handling.
- **Katalog AniList, Kitsu & MyAnimeList** — jelajahi katalog discovery dari beberapa ekosistem tracker melalui satu pengalaman yang terintegrasi.
- **Tracker catalog browsing** — layar katalog khusus memberikan cara yang lebih kaya untuk menemukan judul dan melanjutkannya ke alur membaca Miyorare.
- **Category Sets untuk Favourites** — kategori Favourite sekarang dapat diatur ke dalam beberapa set terpisah, memberikan lapisan organisasi tambahan untuk library yang besar.
- **Pengelolaan Category Set** — buat, pilih, ubah urutan, dan kelola category set melalui kontrol dan dialog khusus.
- **Navigasi kategori** — Favourites sekarang memiliki navigasi category/set khusus agar perpindahan antar koleksi yang sudah diatur menjadi lebih mudah.
- **Source baru** — Miyorare source packs diperluas dengan source tambahan, termasuk ComicAsura, NewXToon, RawDex, dan MGKomik.

### 🚀 Peningkatan

- **Integrasi Discovery ke reader** — hasil katalog dapat dicocokkan dengan source Miyorare yang tersedia dan dilanjutkan ke pengalaman membaca normal.
- **Discovery lebih tangguh** — data katalog memiliki snapshot/cache handling dan status presentation khusus agar kegagalan layanan katalog remote dapat ditangani dengan lebih baik.
- **Discovery UI responsif** — layar Discovery memiliki behavior dan validasi khusus untuk berbagai ukuran layar.
- **Shared search ownership** — navigasi dan Discovery sekarang mengoordinasikan penggunaan shared search interface dengan lebih andal.
- **Organisasi Favourites** — repository Favourite, category selection, editing, unread counter, dan tampilan list sekarang memahami Category Sets.
- **Kompatibilitas Private Favourites** — transfer antara Favourites biasa dan Private Favourites diperbarui agar bekerja dengan model organisasi kategori yang baru.
- **Category Sets dalam backup** — local backup sekarang mempertahankan informasi Category Set bersama state library lainnya.
- **Category Sets dalam Google Drive sync** — synchronization dan merge logic sekarang memahami Category Sets sehingga struktur organisasi dapat dipertahankan antar perangkat.
- **Kompatibilitas migration** — source migration mendapat penanganan tambahan untuk data personal tingkat chapter ketika judul dipindahkan antar source yang kompatibel.
- **Navigation preferences** — konfigurasi navigasi utama disederhanakan dan dipindahkan menuju preference model khusus.
- **Informasi cover Favourite** — modern Favourite cover mendapat penanganan tambahan untuk reading state dan overlay.
- **Cloudflare browser handling** — penanganan browser challenge mendapat peningkatan lifecycle dan navigasi.

### 🐞 Perbaikan

- Meningkatkan pemeliharaan data personal tingkat chapter ketika melakukan migration antar source yang kompatibel.
- Menambahkan database migration untuk Category Sets ketika pengguna memperbarui instalasi lama.
- Meningkatkan kompatibilitas backup untuk Private Favourites dan model organisasi Favourite yang diperluas.
- Meningkatkan Google Drive merge behavior untuk Category Sets yang disinkronkan.
- Memperbaiki edge case tambahan pada Favourite unread count dan downloaded/local state.
- Meningkatkan category selection dan editing Favourite dengan model set baru.
- Meningkatkan perilaku navigasi di sekitar Discovery dan shared search.
- Memperkuat validasi round-trip downloaded novel dan runtime diagnostics.
- Menambahkan regression coverage yang luas untuk Discovery navigation, catalog browsing, Category Sets, backup/sync, migration compatibility, responsive UI, dan organisasi Favourite.

</details>
