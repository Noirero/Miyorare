## Miyorare v1.4.9

This update improves chapter organization, release predictions, personal notes, recommendations, Favourites management, Source Pack performance, and overall stability.

### ✨ What's New

- **Chapter Range Grouping** — long chapter lists can now be organized into expandable numeric ranges. Choose **Automatic, 25, 50, 100, or Off** while keeping existing search, filters, sorting, grid/list views, and chapter actions.
- **Next Chapter Prediction Calendar** — tap a chapter release estimate in Details to open a calendar with the predicted date and available release-history information. Predictions can also be added to your calendar.
- **Prediction Calendar Backgrounds** — customize the prediction calendar using the default theme, manga cover, or your own image. Cover backgrounds also support an optional blur effect.
- **Improved Personal Notes** — chapter notes can now be opened in a selectable read-only viewer, with clickable HTTP(S) links.
- **Search Notes Viewer** — open an individual matching note or browse all matching note results without losing your current Search Notes session.
- **Redesigned Favourite Category Picker** — category selection now uses a wider and more adaptive layout, including compact rows, two-column layouts where appropriate, paging for large category collections, and an optional manga cover/title header.

### 🚀 Improvements

- **Faster Source Pack → Details navigation** — opening manga Details from Source Pack is now more responsive, with less unnecessary background work competing during navigation.
- **Faster Source Pack chapter loading** — available chapter information can appear sooner when opening Details, especially during a cold open.
- **More Accurate Similar Genres** — recommendations now prioritize titles with actual matching genres and handle sources with limited genre information more reliably.
- **Improved Chapter Search** — Chapter Search and Search Notes now behave more consistently. Only one search mode can be active at a time, the active search field receives focus automatically, **X** clears the query, and **Back** exits search mode.
- **Reader Personal Actions** — chapter ratings and Personal Notes in the Reader toolbox now behave consistently with the same actions available from Details.
- **Chapter Sort Persistence** — your ascending or descending chapter order is preserved more consistently when reopening Details.
- **Clickable Manga Notes Links** — HTTP(S) links inside read-only Manga Notes can now be opened while normal text remains selectable and copyable.
- **Performance Diagnostics** — additional optional diagnostics were added to help investigate navigation, rendering, image loading, network activity, Details, and Favourites performance. These diagnostics remain disabled by default.

### 🐞 Fixes

- Fixed Search Notes behavior so tapping a chapter opens the Reader while the dedicated note action opens note results.
- Fixed Chapter Search and Search Notes creating overlapping search sessions in the Reader.
- Fixed Reader chapter rating and Personal Note actions that could fail to open correctly.
- Fixed chapter sorting sometimes reverting after reopening Details.
- Fixed Similar Genres showing titles without an actual shared genre.
- Improved Similar Genres behavior for sources that provide incomplete genre information.
- Fixed a Fragment state-related crash that could occur when a source refresh finished after leaving a manga list screen.
- Fixed several Source Pack navigation and chapter-loading timing issues.
- Improved Favourite category picker spacing, sizing, paging, and behavior on compact or short screens.

<details>
<summary><strong>🇮🇩 Bahasa Indonesia</strong></summary>

Pembaruan ini meningkatkan pengelolaan chapter, prediksi rilis, catatan pribadi, rekomendasi, pengelolaan Favourites, performa Source Pack, dan stabilitas aplikasi secara keseluruhan.

### ✨ Yang Baru

- **Pengelompokan Rentang Chapter** — daftar chapter yang panjang sekarang dapat dikelompokkan menjadi rentang angka yang bisa dibuka dan ditutup. Pilih **Automatic, 25, 50, 100, atau Off** tanpa menghilangkan pencarian, filter, sorting, tampilan grid/list, dan aksi chapter yang sudah ada.
- **Kalender Prediksi Chapter Berikutnya** — tekan perkiraan rilis chapter di Details untuk membuka kalender yang menampilkan tanggal prediksi dan informasi riwayat rilis yang tersedia. Prediksi juga dapat ditambahkan ke kalender perangkat.
- **Background Kalender Prediksi** — sesuaikan tampilan kalender prediksi menggunakan tema default, cover manga, atau gambar milikmu sendiri. Background cover juga mendukung efek blur opsional.
- **Personal Notes yang Lebih Baik** — catatan chapter sekarang dapat dibuka melalui viewer read-only dengan teks yang dapat dipilih serta tautan HTTP(S) yang dapat ditekan.
- **Viewer Search Notes** — buka satu catatan yang ditemukan atau lihat seluruh hasil catatan yang cocok tanpa kehilangan sesi Search Notes yang sedang aktif.
- **Pemilih Kategori Favourite Baru** — pemilihan kategori sekarang menggunakan layout yang lebih lebar dan adaptif, termasuk baris yang lebih ringkas, dua kolom ketika sesuai, paging untuk kategori dalam jumlah besar, serta header cover/judul manga opsional.

### 🚀 Peningkatan

- **Navigasi Source Pack → Details lebih cepat** — membuka Details manga dari Source Pack sekarang lebih responsif dengan mengurangi pekerjaan background yang tidak diperlukan saat navigasi.
- **Chapter Source Pack tampil lebih cepat** — informasi chapter yang sudah tersedia dapat muncul lebih awal ketika membuka Details, terutama pada cold open.
- **Similar Genres lebih akurat** — rekomendasi sekarang lebih memprioritaskan manga yang benar-benar memiliki genre yang sama dan menangani source dengan informasi genre terbatas secara lebih baik.
- **Chapter Search lebih konsisten** — Chapter Search dan Search Notes tidak lagi aktif secara bersamaan. Field pencarian aktif otomatis mendapatkan fokus, tombol **X** membersihkan query, dan **Back** keluar dari mode pencarian.
- **Personal Action di Reader** — rating chapter dan Personal Notes pada Reader toolbox sekarang bekerja secara konsisten dengan aksi yang sama pada Details.
- **Sorting Chapter lebih konsisten** — pilihan urutan ascending atau descending lebih baik dipertahankan ketika Details dibuka kembali.
- **Link pada Manga Notes** — tautan HTTP(S) pada Manga Notes read-only sekarang dapat ditekan sementara teks biasa tetap dapat dipilih dan disalin.
- **Performance Diagnostics** — diagnostics opsional ditambahkan untuk membantu pemeriksaan performa navigasi, rendering, pemuatan gambar, network, Details, dan Favourites. Diagnostics tersebut tetap nonaktif secara default.

### 🐞 Perbaikan

- Memperbaiki Search Notes sehingga menekan chapter membuka Reader sementara aksi catatan khusus membuka hasil catatan.
- Memperbaiki Chapter Search dan Search Notes yang dapat membuat sesi pencarian bertumpuk di Reader.
- Memperbaiki aksi rating chapter dan Personal Note di Reader yang terkadang gagal terbuka.
- Memperbaiki sorting chapter yang terkadang kembali ke urutan sebelumnya setelah Details dibuka ulang.
- Memperbaiki Similar Genres yang dapat menampilkan manga tanpa genre yang benar-benar sama.
- Meningkatkan Similar Genres untuk source dengan informasi genre yang tidak lengkap.
- Memperbaiki crash terkait state Fragment yang dapat terjadi ketika refresh source selesai setelah pengguna meninggalkan layar daftar manga.
- Memperbaiki beberapa masalah timing pada navigasi dan pemuatan chapter Source Pack.
- Meningkatkan spacing, ukuran, paging, dan perilaku pemilih kategori Favourite pada layar compact atau pendek.

</details>
