## Changelog v1.2.0

Miyorare tetap berpegang pada **Kestabilan · Kecepatan · Kelancaran**. Rilis ini memusatkan perubahan Beta terbaru menjadi satu peningkatan stabil dengan fokus pada ekosistem source, kontinuitas library/download, dan respons UI.

### ✨ Baru

- **Miyorare Source Packs resmi** — Miyorare-ID dan Miyorare-EN kini memiliki halaman pengelolaan khusus. Pack tetap terpisah dari APK inti dan dapat di-install/update sesuai kebutuhan, termasuk shard sumber berbasis UMA dan Gekkoushi.
- **Kontinuitas source dan download** — fondasi Canonical Source Identity, Source Alias, pencocokan konten terunduh, dan reconnect planner membantu konten lama tetap dikenali ketika provider/source yang setara berubah tanpa memindahkan atau menghapus file fisik secara otomatis.
- **Tujuan penyimpanan yang lebih jelas** — download manga/novel dan Save Page memiliki pengaturan tujuan yang lebih terpisah sehingga struktur file lebih mudah dikelola tanpa mencampur ruang konten.
- **Pencarian extension di Jelajah** — extension dapat dicari dari daftar yang sedang tampil tanpa memicu reload atau request jaringan baru.
- **Pencarian global dapat diedit ulang** — kata pencarian pada halaman hasil dapat diketuk dan diedit untuk langsung melakukan pencarian lain.

### 🔧 Ditingkatkan

- **Jelajah lebih ringan saat scroll** — kepemilikan scroll disederhanakan agar tidak ada dua jalur yang berebut menggerakkan daftar; Saran juga dibuat lebih ringkas dan dapat disembunyikan.
- **Detail dan Related lebih responsif** — pemuatan data utama diprioritaskan, Related menunggu data utama siap, dan pencarian fallback yang sama tidak dijalankan dua kali.
- **Disukai dan Didownload untuk library besar** — mode Paged/Berkelanjutan memakai batch adaptif dengan penggunaan memori tetap dibatasi, disertai penyempurnaan state dan query agar daftar besar lebih stabil.
- **Backup/restore dan Local lebih aman** — alur restore, pemetaan ruang library, indexing Local, serta alias path download diperkuat untuk mengurangi data stale dan salah pencocokan.
- **WebView dan jaringan extension** — source kompatibel Mihon mendapat akses WebView dengan konteks sesi serta profil jaringan Adaptive, Standard, Aggressive, dan Custom.

### 🐞 Diperbaiki

- **Crash/ANR dialog Disukai pada koleksi besar** — query membership dipersempit ke manga yang sedang dipilih dan pembaruan state dibuat lebih terisolasi.
- **Respons Download Queue** — state Jeda/Lanjutkan/Batal, empty-state, retry hydration worker, dan query privasi notifikasi dibuat lebih cepat dan lebih jelas.
- **Duplikasi kerja dan state stale** — sejumlah jalur Explore, Details, Favourites, Local, dan source resolution dikurangi dari query/reload berulang yang tidak diperlukan.

### ℹ️ Source Packs

Source Pack resmi tidak dibundel ke APK. Setelah memasang Miyorare v1.2.0, buka **Pengaturan → Sources/Extensions → Miyorare Source Packs**, install Miyorare-ID atau Miyorare-EN, lalu aktifkan source yang ingin digunakan.
