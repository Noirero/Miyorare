## Miyorare v1.4.9

This update focuses on chapter organization, release predictions, personal notes, Source Pack responsiveness, recommendations, Favourites category management, and stability.

### ✨ What's New

- **Chapter range grouping** — long chapter lists can now be grouped into expandable numeric ranges. Choose Automatic, 25, 50, 100, or Off while keeping existing search, filters, sorting, grid/list display, and chapter actions.
- **Next chapter prediction calendar** — tap the release estimate in Details to open a calendar view with the predicted date, release-history evidence, and an option to add the estimate to your calendar. The sheet can also browse cached predictions for supported Favourites.
- **Prediction calendar backgrounds** — use the default theme, manga cover, or a custom image, with a dedicated blur option for cover backgrounds.
- **Better chapter Personal Notes** — chapter notes can be opened in a read-only selectable viewer, and explicit HTTP(S) links inside notes are clickable.
- **Search Notes result viewer** — while searching chapter notes, the note action can open the selected note or all matching note results without losing the active search session.
- **Redesigned Favourite category picker** — a wider adaptive category dialog supports compact rows, two-column layouts when appropriate, paging for large category sets, and an optional cover/title header.

### 🚀 Improvements

- **Faster Source Pack → Details handoff** — catalogue work is released before opening Details, reducing competing background work while preserving the published list state.
- **Faster Source Pack chapter first render** — completed chapter data can be presented before final Details persistence finishes, reducing unnecessary visible waiting on cold opens.
- **More accurate Similar Genres** — recommendations now require real shared genre evidence. Sources with sparse list metadata can selectively enrich a bounded number of candidates before ranking them.
- **Unified chapter search behavior** — Chapter Search and Search Notes are mutually exclusive, the active field receives focus automatically, X clears the query without leaving search mode, and Back exits the search session.
- **Reader toolbox personal actions** — chapter rating and Personal Note actions now use the same working data path as Details, including live personal metadata updates.
- **Chapter sort direction persistence** — the selected ascending/descending direction is preserved more consistently when reopening Details and across shared chapter controls.
- **Manga Notes links** — explicit HTTP(S) URLs in read-only Manga Notes are clickable while ordinary text remains selectable and copyable.
- **Performance diagnostics** — verbose diagnostics gained bounded timing and lifecycle instrumentation for navigation, rendering, images, network activity, Details, and Favourites while remaining disabled by default.

### 🐞 Fixes

- Fixed **Search Notes click routing** so tapping a chapter still opens Reader while the dedicated note action opens note results.
- Fixed Reader toolbox search ownership so Chapter Search and Search Notes cannot create nested competing search sessions.
- Fixed chapter personal actions in Reader that could previously appear interactive but fail to open the expected editor/viewer.
- Fixed chapter sort direction reverting after reopening Details.
- Fixed **Similar Genres** returning candidates without actual genre overlap and improved handling when source summaries omit genre metadata.
- Fixed a Fragment state-save crash that could occur when a source refresh completed after a manga list screen had already left the resumed state.
- Fixed several Source Pack handoff and first-render timing issues that could leave unnecessary work running while Details was opening.
- Refined the Favourite category picker geometry, spacing, paging height, and interaction handling for compact and short-screen layouts.

<details>
<summary><strong>🇮🇩 Bahasa Indonesia</strong></summary>

Pembaruan ini berfokus pada pengelolaan chapter, prediksi rilis, catatan pribadi, responsivitas Source Pack, rekomendasi, pengelolaan kategori Favourites, dan stabilitas.

### ✨ Yang Baru

- **Pengelompokan rentang chapter** — daftar chapter panjang sekarang dapat dikelompokkan menjadi rentang angka yang bisa dibuka/tutup. Tersedia Automatic, 25, 50, 100, atau Off tanpa menghilangkan search, filter, sorting, tampilan grid/list, dan aksi chapter yang sudah ada.
- **Kalender prediksi chapter berikutnya** — tekan perkiraan rilis di Details untuk membuka kalender berisi tanggal prediksi, bukti riwayat rilis, serta opsi menambahkan perkiraan tersebut ke kalender. Sheet juga dapat menampilkan prediksi tersimpan dari Favourites yang didukung.
- **Background kalender prediksi** — gunakan tema default, cover manga, atau gambar kustom, termasuk opsi blur khusus untuk background cover.
- **Personal Notes chapter yang lebih baik** — catatan chapter dapat dibuka dalam viewer read-only yang teksnya bisa dipilih, dan tautan HTTP(S) di dalam catatan dapat ditekan.
- **Viewer hasil Search Notes** — ketika mencari catatan chapter, aksi catatan dapat membuka catatan terpilih atau seluruh hasil yang cocok tanpa menghilangkan sesi pencarian aktif.
- **Pemilih kategori Favourite baru** — dialog kategori yang lebih lebar dan adaptif dengan baris ringkas, dua kolom ketika sesuai, paging untuk kategori dalam jumlah besar, serta header cover/judul opsional.

### 🚀 Peningkatan

- **Source Pack → Details lebih cepat** — pekerjaan katalog dilepas sebelum membuka Details sehingga pekerjaan background tidak bersaing dengan layar yang sedang dibuka, tanpa menghilangkan state daftar yang sudah tampil.
- **Chapter Source Pack tampil lebih cepat** — data chapter yang sudah selesai dapat ditampilkan sebelum persistence Details final selesai sehingga cold open tidak menunggu pekerjaan yang tidak perlu.
- **Similar Genres lebih akurat** — rekomendasi sekarang membutuhkan bukti genre yang benar-benar sama. Source dengan metadata daftar yang minim dapat memperkaya kandidat secara terbatas sebelum proses ranking.
- **Perilaku pencarian chapter lebih konsisten** — Chapter Search dan Search Notes tidak dapat aktif bersamaan, field aktif otomatis mendapat fokus, tombol X membersihkan query tanpa keluar dari mode pencarian, dan Back menutup sesi pencarian.
- **Personal action di Reader toolbox** — rating chapter dan Personal Note sekarang memakai jalur data yang sama dengan Details, termasuk pembaruan metadata pribadi secara langsung.
- **Arah sorting chapter lebih konsisten** — pilihan ascending/descending lebih baik dipertahankan ketika Details dibuka kembali dan pada kontrol chapter bersama.
- **Link pada Manga Notes** — URL HTTP(S) pada Manga Notes read-only dapat ditekan sementara teks biasa tetap dapat dipilih dan disalin.
- **Performance diagnostics** — verbose diagnostics mendapatkan timing dan lifecycle instrumentation yang terbatas untuk navigasi, rendering, image, network, Details, dan Favourites, serta tetap nonaktif secara default.

### 🐞 Perbaikan

- Memperbaiki **routing klik Search Notes** sehingga menekan chapter tetap membuka Reader, sedangkan aksi catatan khusus membuka hasil catatan.
- Memperbaiki ownership pencarian Reader toolbox agar Chapter Search dan Search Notes tidak membuat sesi pencarian bertumpuk.
- Memperbaiki personal action chapter di Reader yang sebelumnya dapat terlihat aktif tetapi tidak membuka editor/viewer yang seharusnya.
- Memperbaiki arah sorting chapter yang dapat kembali ke pilihan lama setelah Details dibuka ulang.
- Memperbaiki **Similar Genres** agar tidak menampilkan kandidat tanpa genre yang benar-benar sama dan meningkatkan penanganan source yang metadata ringkasannya tidak memiliki genre.
- Memperbaiki crash terkait Fragment state-save ketika refresh source selesai setelah layar daftar manga sudah tidak berada dalam kondisi resumed.
- Memperbaiki beberapa masalah timing handoff dan first render Source Pack yang dapat membiarkan pekerjaan tidak perlu berjalan ketika Details sedang dibuka.
- Menyempurnakan geometri, spacing, tinggi paging, dan interaksi pemilih kategori Favourite untuk layout compact dan layar pendek.

</details>
