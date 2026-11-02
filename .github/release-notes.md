## Miyorare v1.4.17

This release brings a major refresh to On-device Files, introducing a redesigned local library, a visual chapter gallery, Virtual Groups, and new collection-management tools. Local reading is more efficient, download recovery is more reliable, and switching between Normal and Private Favourites is easier. **Source Migration is now stable**, featuring a redesigned workflow, improved source selection, and more reliable migration execution and recovery.

### ✨ What's New

- **On-device Files — Living Archive** — A redesigned local library with a cinematic ink-and-gold appearance, clearer folder/title/chapter statistics, improved search and filters, larger Continue Reading cards, and refreshed collection presentation.

- **On-device Chapter Gallery** — Local manga Details now includes an optional two-column gallery showing chapter previews, titles, and reading status. Tap a chapter cover to open it in Reader. The original Details view remains available.

- **Virtual Groups** — Organize local manga into custom groups without moving their physical files. Create, rename, manage, and remove groups, assign collections, and filter titles by group. Compact two-column group cards support See all and Show less.

- **Bulk Collection Management** — Select multiple local collections to edit authors, titles, and descriptions, reset manual text metadata, refresh selected collections, manage group membership, or perform confirmed deletion.

- **Local ZIP Export & File Moving** — Export selected local collections as ZIP archives through Android's sharing interface, or move collections between registered local library folders with verification before original files are removed.

- **Favourites Normal / Private Shortcut** — Tap the Favourites header title to switch between Normal and Private Favourites. Entering Private continues to use the existing authentication flow, while returning to Normal is more direct.

- **Source Migration — Now Stable** — Source Migration is now a stable feature with a redesigned experience for manga and novels. The updated workflow guides users through title selection, destination configuration, Search, Review, Confirmation, and Results. Broken-source migration also gains a more unified selection and replacement process.

### 🚀 Improvements

- **More Efficient On-device Reading** — Supported seekable PDF, CBZ, and ZIP documents can be read without first copying the entire file into app storage. Incompatible or non-seekable documents retain their existing fallback.

- **Reader Cache Management** — Temporary local-file backing is retained only while needed by active chapters and previews, with cleanup after use. Existing shared page-cache behavior for downloads and other supported reading paths is preserved.

- **Faster Chapter Gallery Previews** — Chapter thumbnails use more targeted archive and PDF cover extraction instead of opening complete chapters. A separate bounded thumbnail cache reduces unnecessary storage and decoding work.

- **Selective Local Refresh** — Refresh selected collections or individual files without unnecessarily rescanning unrelated collections. Local metadata changes and collection boundaries are handled more carefully.

- **Persistent Local Metadata** — Manually edited authors, titles, and descriptions are preserved across supported library rescans, with an option to restore scanner-derived text metadata.

- **Safer Local File Management** — Bulk deletion and physical moves include stronger ownership, destination, and file-integrity checks. Move operations verify copied content before removing originals, while deletion reports distinguish successful and failed items.

- **Download Maintenance Reliability** — Download Maintenance improves verification of physical files, stored identities, and download ownership. Missing paths are reconciled only when storage inspection is complete and confirms their absence, while uncertain storage states are preserved for further inspection.

- **Source Migration Experience** — Destination sources are organized more clearly by installed provider and language. Sorting is separated from search priority, candidate selection is more consistent, and completed migration results can display destination covers and open the corresponding manga Details.

### 🐞 Fixes

- Fixed legacy CBZ chapters incorrectly appearing as undownloaded.
- Fixed stale download records after verified file removal.
- Fixed temporary Reader backing-file and cache lifecycle issues.
- Fixed edge cases in bulk deletion and local file moving.
- Fixed blocked migration batches and unclear failure states.
- Fixed stale migration candidates, source selection issues, and UI inconsistencies.

<details>
<summary><strong>🇮🇩 Bahasa Indonesia</strong></summary>

Rilis ini membawa pembaruan besar pada On-device Files melalui tampilan pustaka lokal yang didesain ulang, galeri visual chapter, Virtual Groups, serta fitur pengelolaan koleksi baru. Pembacaan file lokal menjadi lebih efisien, pemulihan status download lebih andal, dan perpindahan antara Normal dan Private Favourites lebih mudah. **Source Migration sekarang sudah stabil**, dengan alur baru, pemilihan source yang lebih baik, serta peningkatan keandalan eksekusi dan pemulihan migrasi.

### ✨ Yang Baru

- **On-device Files — Living Archive** — Tampilan pustaka lokal didesain ulang dengan gaya sinematik bernuansa tinta dan emas, informasi jumlah folder/judul/chapter yang lebih jelas, pencarian dan filter yang diperbarui, kartu Continue Reading lebih besar, serta tampilan koleksi yang lebih modern.

- **On-device Chapter Gallery** — Details manga lokal sekarang memiliki galeri opsional dua kolom yang menampilkan pratinjau chapter, judul, dan status membaca. Ketuk cover chapter untuk membukanya di Reader. Tampilan Details asli tetap tersedia.

- **Virtual Groups** — Atur manga lokal ke dalam grup khusus tanpa memindahkan file fisiknya. Buat, ubah nama, kelola, dan hapus grup, masukkan koleksi, serta filter judul berdasarkan grup. Kartu grup dua kolom mendukung See all dan Show less.

- **Pengelolaan Koleksi Massal** — Pilih beberapa koleksi lokal untuk mengedit author, judul, dan deskripsi, mengembalikan metadata teks ke hasil pemindaian, me-refresh koleksi tertentu, mengatur keanggotaan grup, atau melakukan penghapusan dengan konfirmasi.

- **Ekspor ZIP & Pemindahan File Lokal** — Ekspor koleksi lokal terpilih sebagai arsip ZIP melalui fitur berbagi Android, atau pindahkan koleksi antar-folder pustaka lokal yang sudah didaftarkan dengan verifikasi sebelum file asli dihapus.

- **Shortcut Normal / Private Favourites** — Ketuk judul pada header Favourites untuk berpindah antara Normal dan Private Favourites. Masuk ke Private tetap menggunakan autentikasi yang sudah ada, sedangkan kembali ke Normal menjadi lebih langsung.

- **Source Migration — Sekarang Stabil** — Source Migration sekarang menjadi fitur stabil dengan pengalaman baru untuk manga dan novel. Alur yang diperbarui memandu pengguna melalui pemilihan judul, pengaturan source tujuan, Search, Review, Confirmation, dan Results. Migrasi dari source yang bermasalah juga mendapat proses pemilihan dan pencarian pengganti yang lebih terpadu.

### 🚀 Peningkatan

- **Pembacaan On-device Lebih Efisien** — File PDF, CBZ, dan ZIP yang mendukung akses seekable dapat dibaca tanpa harus menyalin seluruh file terlebih dahulu ke penyimpanan aplikasi. Dokumen yang tidak kompatibel atau tidak mendukung seekable tetap menggunakan fallback sebelumnya.

- **Pengelolaan Cache Reader** — File sementara untuk pembacaan lokal dipertahankan hanya selama diperlukan oleh chapter dan pratinjau aktif, lalu dibersihkan setelah digunakan. Perilaku shared page cache yang sudah ada untuk download dan jalur pembacaan lainnya tetap dipertahankan.

- **Pratinjau Chapter Gallery Lebih Ringan** — Thumbnail chapter menggunakan ekstraksi cover arsip dan PDF yang lebih terarah tanpa perlu membuka seluruh chapter. Cache thumbnail terpisah dengan batas penggunaan membantu mengurangi pekerjaan penyimpanan dan decoding yang tidak diperlukan.

- **Selective Local Refresh** — Refresh koleksi atau file tertentu tanpa memindai ulang koleksi lain yang tidak berkaitan. Perubahan metadata lokal dan batas koleksi ditangani dengan lebih hati-hati.

- **Metadata Lokal yang Persisten** — Perubahan manual pada author, judul, dan deskripsi dipertahankan selama pemindaian ulang pustaka yang didukung, dengan opsi untuk mengembalikan metadata teks berdasarkan hasil scanner.

- **Pengelolaan File Lokal Lebih Aman** — Penghapusan massal dan pemindahan file fisik memiliki pemeriksaan ownership, tujuan, dan integritas file yang lebih kuat. Proses pemindahan memverifikasi hasil salinan sebelum menghapus file asli, sedangkan laporan penghapusan membedakan operasi berhasil dan gagal.

- **Reliability Download Maintenance** — Download Maintenance meningkatkan verifikasi file fisik, identitas tersimpan, dan ownership download. Path yang hilang hanya direkonsiliasi ketika pemeriksaan storage berhasil sepenuhnya dan memastikan file memang tidak ada, sedangkan kondisi storage yang belum pasti tetap dipertahankan untuk pemeriksaan lebih lanjut.

- **Pengalaman Source Migration** — Source tujuan disusun lebih jelas berdasarkan provider terpasang dan bahasa. Pengurutan tampilan dipisahkan dari prioritas pencarian, pemilihan kandidat menjadi lebih konsisten, sementara hasil migrasi yang selesai dapat menampilkan cover tujuan dan membuka Details manga terkait.

### 🐞 Perbaikan

- Memperbaiki chapter CBZ lama yang salah ditampilkan sebagai belum di-download.
- Memperbaiki catatan download stale setelah file dipastikan terhapus.
- Memperbaiki lifecycle file sementara dan cache Reader.
- Memperbaiki beberapa masalah penghapusan massal dan pemindahan file lokal.
- Memperbaiki batch migrasi yang terblokir dan informasi kegagalan yang kurang jelas.
- Memperbaiki kandidat migrasi stale, pemilihan source, dan ketidakkonsistenan UI.

</details>
