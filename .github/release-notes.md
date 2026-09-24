Miyorare tetap berpegang pada **Kestabilan · Kecepatan · Kelancaran**.

Rilis Main ini membawa snapshot Beta yang telah lolos promotion gate, deep unit test, Source Pack compatibility, Identity Guard, serta Android 15 runtime acceptance.

### ✨ Baru

- **Reader Journey** — perjalanan membaca jangka panjang dengan Lifetime XP berbasis penyelesaian yang terverifikasi, level/rank, achievement, Reader Profile, Reader Title, dan showcase achievement yang dibatasi.
- **Kosmetik dan celebration Reader Journey** — frame, glow, background, progress treatment, dan celebration milestone opsional tanpa mengunci fitur inti membaca.
- **Year in Review yang aman untuk privasi** — ringkasan tahunan berbasis statistik agregat tanpa membawa identitas manga/source ke jalur share.
- **Reader Novel / EPUB yang lebih lengkap** — kontrol font, ukuran, weight, line height, paragraph spacing, margin, alignment, serta preset Light, Sepia, Dark, dan OLED.
- **Progress novel lebih presisi** — posisi EPUB dapat dipulihkan jauh lebih dekat ke paragraf/offset terakhir.
- **Tool terjemahan novel** — mendukung terjemahan selection, paragraf, atau chapter penuh sambil mempertahankan teks asli dan layout chapter; APK utama tetap ringan tanpa model terjemahan on-device besar.
- **Tampilan Disukai Miyorare baru** — background adaptif, neon/glass surface, Manga/Novel switch, category treatment, quick filter ringkas, serta visual kartu dan navigasi yang diperbarui.

### ⚡ Ditingkatkan

- **Aturan XP dan privasi Reader Journey** — Incognito/Peek dikecualikan, import/mark-as-read tidak memberi XP retroaktif, dan reread reward tetap kecil serta dibatasi.
- **Performa Reader Journey** — persistence stats/journey dibuat terserialisasi dan dapat dibatalkan untuk mengurangi write storm, race condition, dan recompute yang tidak perlu.
- **Navigasi dan konsistensi visual** — floating/legacy navigation, mode gelap/terang, Details, Jelajah, Settings, kategori, overlay, serta surface Miyorare lain mendapat polishing tambahan.
- **Performa Disukai** — filtering, category handling, interaksi library besar, background rendering, dan visual regression coverage diperkuat.
- **Stabilitas reader novel** — perubahan chapter/posisi EPUB dan prepend asynchronous diperkuat agar tidak terjadi stale-position jump atau regression saat melanjutkan bacaan.
- **Backup, Local, dan Source Pack** — restore/local indexing, source compatibility, parser wiring, dan promotion validation diperkuat.
- **Download UI** — interaksi daftar download dan scrolling mendapat stabilisasi tambahan.
- **ExHentai / E-Hentai** — akses, WebView URL handling, search/pagination diagnostics, dan source compatibility diperkuat tanpa mengubah jalur download yang sudah ada.

### 🐞 Diperbaiki

- **Race condition privasi Reader** — saat Journey dimatikan atau Incognito/Peek aktif, pending persistence sesi ikut dibatalkan agar aktivitas yang dikecualikan tidak masuk kembali ke stats/journey.
- **Reader Profile privacy guard** — data profil tetap lokal di perangkat, showcase tetap dibatasi, dan achievement/share tetap aman untuk konten mature.
- **Regresi visual Disukai** — geometri, spacing, proporsi kartu, quick actions, dan bottom navigation reference diselaraskan dengan layout final yang diterima.
- **Runtime/performance hardening** — jalur stats, reader, local, backup, source compatibility, dan beberapa hot path diperkuat untuk mengurangi lag dan regression.
- **Promotion dan Android 15 acceptance** — dependency resolution, shell execution, instrumentation invocation, deep test, dan Android 15 acceptance diperbaiki sebelum promotion ke Main.

### 🛡️ Stabilitas & kompatibilitas

- Promotion ke Main menggunakan frozen release branch agar fitur Beta baru tidak ikut terseret ke rilis stabil.
- Source Pack Compatibility Contract dan Miyorare Identity Guard berhasil dilewati pada kandidat promotion.
- Deep promotion gate berhasil dilewati.
- Build APK Main dan kandidat release berhasil diverifikasi.
- Android 15 consolidated runtime acceptance berhasil dilewati.
- Regression coverage tambahan kini melindungi Reader Journey, Year in Review, Disukai, backup/restore, local storage, chapter persistence, source compatibility, dan runtime performance.
