## Miyorare v1.4.21

This maintenance update improves the stability of reading and download indicators on Favourites cards. Previously verified information can remain visible while Miyorare refreshes card metadata after returning from Details, switching tabs, or resuming the library.

### 🚀 Improvements

- **More Stable Favourites Cards** — Retain last-known, verified reading status, unread information, and download badges during background revalidation instead of briefly replacing them with unknown or empty values.
- **Smarter Metadata Refresh** — Reuse verified information only for matching Favourites context, including Normal/Private space, category, Manga/Novel type, visible items, and relevant display options. Newly encountered cards still wait for their own results.
- **Clearer Loading State** — On a genuine cold start, unresolved reading information is shown as pending rather than incorrectly appearing unread or untouched. An unverified download is not presented as a confirmed downloaded file.

### 🐞 Fixes

- **Rapid Navigation and Refresh** — Prevent outdated or cancelled metadata requests from replacing newer results, including rapid A → B → A navigation.
- **Download Badge Flicker** — Preserve verified download indicators while background checks run, including when other downloads or local files change.
- **Stale Metadata After Empty Views** — Revalidate cards correctly when an empty visible list later contains items again.

This update focuses on Favourites card status presentation and its asynchronous refresh. It does not change cover artwork, on-device files, saved manga data, or persistent caching.

<details>
<summary><strong>🇮🇩 Bahasa Indonesia</strong></summary>

Pembaruan pemeliharaan ini meningkatkan kestabilan indikator status membaca dan download pada kartu Favourites. Informasi yang sebelumnya sudah terverifikasi dapat tetap ditampilkan ketika Miyorare memperbarui metadata setelah kembali dari Details, berpindah tab, atau membuka kembali library.

### 🚀 Peningkatan

- **Kartu Favourites Lebih Stabil** — Mempertahankan status membaca, informasi chapter belum dibaca, dan badge download yang telah terverifikasi selama proses pembaruan di background, tanpa sementara menggantinya dengan nilai kosong atau belum diketahui.
- **Pembaruan Metadata Lebih Tepat** — Menggunakan kembali informasi yang valid hanya untuk konteks Favourites yang sesuai, termasuk ruang Normal/Private, kategori, jenis Manga/Novel, item yang ditampilkan, dan opsi tampilan. Kartu yang baru muncul tetap menunggu hasil pemeriksaannya sendiri.
- **Status Loading Lebih Jelas** — Saat aplikasi benar-benar dibuka dari kondisi awal, informasi membaca yang belum tersedia ditampilkan sebagai pending, bukan disalahartikan sebagai belum pernah dibaca. File yang belum terverifikasi juga tidak ditampilkan sebagai download yang sudah pasti tersedia.

### 🐞 Perbaikan

- **Navigasi dan Refresh Cepat** — Mencegah permintaan metadata lama atau yang sudah dibatalkan menimpa hasil yang lebih baru, termasuk perpindahan cepat A → B → A.
- **Badge Download Berkedip** — Mempertahankan indikator download yang sudah terverifikasi selama pemeriksaan ulang di background, termasuk ketika download atau file lokal lain berubah.
- **Metadata Stale Setelah Daftar Kosong** — Memastikan kartu diperiksa ulang ketika daftar yang sebelumnya kosong kembali menampilkan item.

Pembaruan ini berfokus pada tampilan status kartu Favourites dan proses refresh asynchronous. Tidak ada perubahan pada artwork cover, file on-device, data manga tersimpan, maupun mekanisme persistent cache.

</details>
