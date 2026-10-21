## Miyorare v1.4.12

This release is a major reliability and source-management update. Miyorare now has a more capable migration system for broken or changing sources, a dedicated Kotatsu runtime foundation, clearer source provenance in Explore, and stronger handling of local and downloaded content.

### ✨ What's New

- **Redesigned source migration** — broken or unavailable sources can now be handled through a more complete migration flow with candidate discovery, matching, review, and controlled execution.
- **Migration review screen** — review migration candidates before applying changes instead of relying on a blind replacement flow.
- **Batch migration support** — migration work can be grouped and scheduled with dedicated batch controls and background execution.
- **Lossless migration transfer** — migration now has dedicated transfer logic designed to preserve user state and related manga data while moving an entry to its replacement source.
- **Kotatsu runtime foundation** — Miyorare now includes dedicated infrastructure for loading, managing, verifying, and updating its Kotatsu parser runtime separately.
- **Kotatsu source settings** — new settings infrastructure provides dedicated management and configuration for Kotatsu sources and parsers.
- **Source provenance in Explore** — source entries can expose clearer information about where a source comes from and which ecosystem provides it.

### 🚀 Improvements

- **Smarter migration matching** — migration candidate selection now uses dedicated discovery and matching logic instead of relying on the older simplified path.
- **Background migration reliability** — batch migration work is integrated with WorkManager and retains its state more reliably across process restarts.
- **Migration data preservation** — additional persistence handling protects reading-related state that is not represented solely by the main Room records.
- **Explore source presentation** — source cards and source filtering have been expanded to support provenance information and the newer source architecture.
- **Kotatsu runtime verification** — runtime packs have an explicit contract, storage layer, verifier, updater, preferences, and update worker.
- **Local library resolution** — local/downloaded manga and novel lookup has been strengthened, including download-path alias handling.
- **Novel source routing** — source capability and routing behavior for novels has been refined to better distinguish supported source behavior.
- **README showcase** — the project README is now fully available in English and includes a categorized, collapsible visual showcase of Miyorare.

### 🐞 Fixes

- Fixed migration background state being vulnerable to loss when the application process is restarted.
- Fixed several migration paths that could fail to preserve associated user data when changing sources.
- Improved durability for migration state stored outside the primary Room-backed records.
- Improved downloaded novel resolution and local index handling.
- Fixed handling of reserved characters in ZIP/local URI paths.
- Improved download path alias resolution for local content.
- Added broader regression and runtime coverage for migration matching, lossless transfer, batch execution, process persistence, novel routing, local downloads, and Kotatsu runtime isolation.

<details>
<summary><strong>🇮🇩 Bahasa Indonesia</strong></summary>

Rilis ini merupakan pembaruan besar untuk keandalan dan pengelolaan source. Miyorare sekarang memiliki sistem migrasi yang lebih lengkap untuk menangani source yang rusak atau berubah, fondasi runtime Kotatsu khusus, informasi asal source yang lebih jelas di Explore, serta penanganan konten lokal dan hasil download yang lebih kuat.

### ✨ Yang Baru

- **Redesign migrasi source** — source yang rusak atau tidak lagi tersedia sekarang dapat ditangani melalui alur migrasi yang lebih lengkap, mulai dari pencarian kandidat, pencocokan, review, hingga eksekusi terkontrol.
- **Layar review migrasi** — kandidat pengganti dapat diperiksa sebelum migrasi diterapkan, sehingga perpindahan source tidak lagi bergantung pada penggantian secara buta.
- **Batch migration** — pekerjaan migrasi dapat dikelompokkan dan dijadwalkan dengan kontrol batch serta eksekusi background khusus.
- **Lossless migration transfer** — tersedia mekanisme transfer khusus yang dirancang untuk mempertahankan state pengguna dan data manga terkait ketika sebuah entri dipindahkan ke source pengganti.
- **Fondasi Kotatsu runtime** — Miyorare sekarang memiliki infrastruktur khusus untuk memuat, mengelola, memverifikasi, dan memperbarui runtime parser Kotatsu secara terpisah.
- **Pengaturan source Kotatsu** — tersedia infrastruktur settings khusus untuk pengelolaan dan konfigurasi source serta parser Kotatsu.
- **Source provenance di Explore** — source dapat menampilkan informasi yang lebih jelas mengenai asal dan ekosistem penyedianya.

### 🚀 Peningkatan

- **Pencocokan migrasi lebih pintar** — pemilihan kandidat migrasi sekarang menggunakan discovery dan matching khusus dibanding alur sederhana sebelumnya.
- **Background migration lebih andal** — batch migration terintegrasi dengan WorkManager dan state pekerjaannya lebih tahan terhadap restart process aplikasi.
- **Perlindungan data saat migrasi** — persistence tambahan membantu mempertahankan state membaca yang tidak hanya tersimpan di record utama Room.
- **Tampilan source di Explore** — kartu source dan filtering diperluas untuk mendukung provenance dan arsitektur source yang baru.
- **Verifikasi Kotatsu runtime** — runtime pack memiliki contract, storage, verifier, updater, preferences, dan update worker khusus.
- **Resolusi local library** — pencarian manga/novel lokal dan hasil download diperkuat, termasuk penanganan alias path download.
- **Routing source novel** — capability dan routing source novel diperbaiki agar perilaku source yang didukung dapat dibedakan dengan lebih tepat.
- **Showcase README** — README proyek sekarang tersedia penuh dalam bahasa Inggris dan memiliki showcase visual Miyorare yang dikelompokkan serta dapat dibuka/tutup.

### 🐞 Perbaikan

- Memperbaiki state background migration yang sebelumnya dapat hilang ketika process aplikasi dimulai ulang.
- Memperbaiki beberapa jalur migrasi yang berisiko tidak mempertahankan seluruh data pengguna terkait ketika berpindah source.
- Meningkatkan durability state migrasi yang disimpan di luar record utama berbasis Room.
- Meningkatkan resolusi downloaded novel dan penanganan local index.
- Memperbaiki penanganan karakter khusus/reserved pada URI ZIP dan file lokal.
- Meningkatkan resolusi alias path download untuk konten lokal.
- Menambahkan regression dan runtime coverage yang lebih luas untuk migration matching, lossless transfer, batch execution, process persistence, novel routing, local downloads, dan isolasi Kotatsu runtime.

</details>
