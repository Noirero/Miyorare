## Miyorare v1.4.10

This update refreshes the modern Miyorare experience with a redesigned Details presentation, more flexible Favourites layouts, improved cover information, deeper appearance customization, and important fixes for chapter data and personal metadata.

### ✨ What's New

- **Modern Details experience** — Details now uses the latest Miyorare presentation with refined header and expansion behavior for a cleaner, more consistent manga information screen.
- **Favourites layout size controls** — Modern Favourites gains dedicated layout sizing controls so the library presentation can better match your preferred cover and content density.
- **Modern cover information system** — manga covers can now present reusable status, metadata, reading-progress, and Favourite information with a more consistent modern layout.
- **Custom accent colors** — Appearance now includes a dedicated custom accent picker for more control over Miyorare's visual identity.
- **Improved custom backgrounds** — custom backgrounds now integrate more consistently with Miyorare's surrounding colors and surfaces.

### 🚀 Improvements

- **Favourite category picker** — category selection received another layout and interaction pass for a cleaner experience across different screen sizes.
- **Reading progress presentation** — reading progress visuals were refreshed and integrated with the modern cover/status presentation.
- **Chapter personal indicators** — chapter rating, notes, and related personal information are displayed more reliably and consistently in Details.
- **Modern cover consistency** — cover presentation was aligned across Favourites and other supported manga lists while preserving screens that intentionally keep the classic layout.
- **Appearance color handling** — custom accent and background colors now produce more balanced surrounding palettes and neutral surfaces.
- **Release validation** — additional runtime and regression coverage now protects modern covers, Favourite overlays, chapter persistence, personal metadata identity, appearance colors, and promotion to main.

### 🐞 Fixes

- Fixed chapter reconciliation so freshly loaded chapter data can be matched with existing local chapter records earlier and more reliably.
- Fixed cases where chapter personal metadata could become associated with the wrong chapter identity after chapter data changed or refreshed.
- Fixed chapter rating and Personal Note indicators so their state stays aligned with the correct chapter.
- Fixed live chapter download/delete state updates in Details.
- Fixed several modern Details header and expansion geometry issues.
- Fixed custom accent/background combinations that could produce unintended tinted neutral surfaces.
- Fixed Favourites and cover overlay geometry across compact and supported modern layouts.
- Improved Downloaded and Local Favourites state handling and regression coverage.

<details>
<summary><strong>🇮🇩 Bahasa Indonesia</strong></summary>

Pembaruan ini menyegarkan pengalaman modern Miyorare dengan tampilan Details yang diperbarui, layout Favourites yang lebih fleksibel, informasi cover yang lebih lengkap, kustomisasi tampilan yang lebih dalam, serta perbaikan penting pada data chapter dan metadata pribadi.

### ✨ Yang Baru

- **Tampilan Details modern** — Details sekarang menggunakan presentasi Miyorare terbaru dengan perilaku header dan expansion yang disempurnakan agar informasi manga tampil lebih bersih dan konsisten.
- **Kontrol ukuran layout Favourites** — Modern Favourites mendapatkan kontrol ukuran layout khusus sehingga tampilan library dapat disesuaikan dengan ukuran cover dan kepadatan konten yang diinginkan.
- **Sistem informasi cover modern** — cover manga sekarang dapat menampilkan status, metadata, progres membaca, dan informasi Favourite melalui layout modern yang lebih konsisten.
- **Custom accent color** — Appearance sekarang memiliki pemilih warna accent khusus untuk memberikan kontrol lebih besar terhadap identitas visual Miyorare.
- **Custom background yang lebih baik** — background kustom sekarang menyatu lebih konsisten dengan warna dan surface di sekitarnya.

### 🚀 Peningkatan

- **Pemilih kategori Favourite** — pemilihan kategori mendapatkan penyempurnaan layout dan interaksi agar lebih nyaman pada berbagai ukuran layar.
- **Tampilan progres membaca** — visual progres membaca diperbarui dan diintegrasikan dengan tampilan cover/status modern.
- **Indikator personal chapter** — rating chapter, catatan, dan informasi pribadi terkait sekarang ditampilkan dengan lebih andal dan konsisten di Details.
- **Konsistensi cover modern** — tampilan cover diselaraskan pada Favourites dan daftar manga lain yang didukung, tanpa mengubah layar yang memang sengaja mempertahankan layout klasik.
- **Penanganan warna Appearance** — custom accent dan background sekarang menghasilkan palette sekitar dan neutral surface yang lebih seimbang.
- **Validasi rilis** — coverage runtime dan regression tambahan sekarang melindungi modern cover, Favourite overlay, chapter persistence, identitas metadata pribadi, warna Appearance, dan proses promotion ke main.

### 🐞 Perbaikan

- Memperbaiki rekonsiliasi chapter agar data chapter yang baru dimuat dapat dicocokkan lebih awal dan lebih andal dengan record chapter lokal yang sudah ada.
- Memperbaiki kasus ketika metadata pribadi chapter dapat terhubung ke identitas chapter yang salah setelah data chapter berubah atau di-refresh.
- Memperbaiki indikator rating chapter dan Personal Note agar state tetap terhubung dengan chapter yang benar.
- Memperbaiki pembaruan langsung status download/delete chapter di Details.
- Memperbaiki beberapa masalah geometri header dan expansion pada Details modern.
- Memperbaiki kombinasi custom accent/background yang dapat menghasilkan neutral surface dengan tint yang tidak diinginkan.
- Memperbaiki geometri Favourites dan cover overlay pada layout compact serta layout modern yang didukung.
- Meningkatkan penanganan state dan regression coverage untuk Downloaded dan Local Favourites.

</details>
