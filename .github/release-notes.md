## Miyorare v1.4.13

This release focuses on reliability, recovery, and visual consistency. Miyorare now provides a browser-based Google Drive sign-in flow, dedicated maintenance tools for downloaded content, a stronger theme and semantic color foundation, and more reliable chapter release predictions.

### ✨ What's New

- **Google Drive browser sign-in** — Google Drive sync now has a dedicated browser-based authentication flow with callback handling and session recovery.
- **Download Maintenance** — a new maintenance system can inspect and repair downloaded-content metadata and local download indexes.
- **Dedicated maintenance controls** — download repair and maintenance actions are now available from Settings with their own status and progress handling.
- **New Miyorare theme picker** — appearance settings now include a dedicated theme-selection experience built specifically for Miyorare.
- **Semantic color system** — Miyorare now has a broader semantic palette foundation that keeps colors and contrast more consistent across modern and classic UI surfaces.
- **Release prediction lineage** — chapter release predictions can now retain clearer evidence and lineage about how an estimate was produced.

### 🚀 Improvements

- **More reliable chapter predictions** — prediction evidence, source-based estimates, favourite predictions, and next-chapter estimation have been refined for more dependable results.
- **Prediction grace handling** — chapter prediction logic now handles release timing and grace periods more carefully.
- **Stronger light-mode contrast** — the modern Miyorare palette includes dedicated contrast handling to keep content readable on light backgrounds.
- **Classic/modern palette consistency** — classic views can now derive their colors through a dedicated bridge so they remain visually aligned with Miyorare's newer theme system.
- **Explore color propagation** — Explore cards and related surfaces now follow the active Miyorare palette more consistently.
- **Settings organization** — root, services, storage/network, downloads, appearance, and source settings have been reorganized and cleaned up for easier navigation.
- **Settings search** — settings discovery has been expanded and reworked to better match the reorganized settings structure.
- **Google Drive authentication recovery** — sync settings can retain and recover authentication state more safely when browser login is interrupted or the app lifecycle changes.
- **Sync authentication policy** — Drive synchronization now has clearer authentication-state handling before background sync work proceeds.
- **Migration review reliability** — the migration review flow received additional state and UI handling improvements.
- **Local download scanning** — downloaded/local content discovery now uses a more dedicated scanner and cleaner compatibility path.

### 🐞 Fixes

- Fixed several cases where downloaded-content metadata or indexes could become inconsistent with files that still exist on device.
- Added safer repair handling for downloaded covers and local download indexes.
- Improved compatibility with legacy chapter download layouts while simplifying the old fallback path.
- Improved local manga parsing and output handling for downloaded/local content.
- Fixed authentication-state edge cases that could cause Google Drive sync to behave incorrectly after browser login or process recovery.
- Improved callback routing for Google Drive browser authentication.
- Improved migration review state handling after candidate changes.
- Fixed additional light-theme contrast and custom neutral-accent edge cases.
- Added extensive regression coverage for download maintenance, Drive authentication, semantic palettes, release predictions, local content, migration review, and Explore theme propagation.

<details>
<summary><strong>🇮🇩 Bahasa Indonesia</strong></summary>

Rilis ini berfokus pada keandalan, pemulihan, dan konsistensi visual. Miyorare sekarang memiliki alur login Google Drive melalui browser, alat maintenance khusus untuk konten hasil download, fondasi theme dan semantic color yang lebih kuat, serta prediksi rilis chapter yang lebih andal.

### ✨ Yang Baru

- **Login Google Drive melalui browser** — Google Drive sync sekarang memiliki alur autentikasi berbasis browser dengan callback handling dan pemulihan sesi.
- **Download Maintenance** — sistem maintenance baru dapat memeriksa dan memperbaiki metadata konten hasil download serta local download index.
- **Kontrol maintenance khusus** — tindakan repair dan maintenance download sekarang tersedia melalui Settings dengan status dan progress tersendiri.
- **Miyorare theme picker baru** — Appearance Settings sekarang memiliki pengalaman pemilihan theme khusus yang dibuat untuk Miyorare.
- **Semantic color system** — Miyorare sekarang memiliki fondasi semantic palette yang lebih luas agar warna dan kontras lebih konsisten pada UI modern maupun classic.
- **Release prediction lineage** — prediksi rilis chapter sekarang dapat menyimpan evidence dan lineage yang lebih jelas mengenai bagaimana sebuah perkiraan dihasilkan.

### 🚀 Peningkatan

- **Prediksi chapter lebih andal** — prediction evidence, perkiraan berbasis source, favourite predictions, dan estimasi chapter berikutnya telah disempurnakan.
- **Prediction grace handling** — logika prediksi sekarang menangani waktu rilis dan grace period dengan lebih hati-hati.
- **Kontras light mode lebih kuat** — palette modern Miyorare memiliki penanganan kontras khusus agar konten tetap mudah dibaca pada background terang.
- **Konsistensi palette classic/modern** — view classic sekarang dapat memperoleh warna melalui bridge khusus agar tetap selaras dengan sistem theme Miyorare yang lebih baru.
- **Propagasi warna Explore** — card dan surface terkait di Explore sekarang mengikuti palette aktif Miyorare dengan lebih konsisten.
- **Organisasi Settings** — root, services, storage/network, downloads, appearance, dan source settings ditata ulang dan dibersihkan agar lebih mudah dinavigasi.
- **Settings search** — pencarian pengaturan diperluas dan disesuaikan dengan struktur Settings yang baru.
- **Pemulihan autentikasi Google Drive** — Sync Settings dapat mempertahankan dan memulihkan state autentikasi dengan lebih aman ketika browser login terputus atau lifecycle aplikasi berubah.
- **Sync authentication policy** — Drive sync sekarang memiliki penanganan authentication state yang lebih jelas sebelum background sync dijalankan.
- **Migration review lebih andal** — alur migration review mendapat peningkatan tambahan pada state dan UI.
- **Local download scanning** — discovery konten downloaded/local sekarang menggunakan scanner yang lebih khusus dengan compatibility path yang lebih bersih.

### 🐞 Perbaikan

- Memperbaiki beberapa kondisi ketika metadata atau index download dapat tidak sinkron dengan file yang sebenarnya masih tersedia di perangkat.
- Menambahkan repair yang lebih aman untuk cover hasil download dan local download index.
- Meningkatkan kompatibilitas dengan layout chapter download lama sekaligus menyederhanakan fallback lama.
- Meningkatkan parsing dan output local manga untuk konten downloaded/local.
- Memperbaiki edge case authentication state yang dapat menyebabkan Google Drive sync berperilaku salah setelah browser login atau process recovery.
- Meningkatkan callback routing untuk autentikasi Google Drive melalui browser.
- Meningkatkan state handling migration review setelah kandidat berubah.
- Memperbaiki edge case tambahan pada kontras light theme dan custom neutral accent.
- Menambahkan regression coverage yang lebih luas untuk download maintenance, autentikasi Drive, semantic palette, release prediction, local content, migration review, dan propagasi theme Explore.

</details>
