## 🇮🇩 Bahasa Indonesia

Rilis ini mempromosikan peningkatan terbaru yang telah dikembangkan di Beta ke branch Main, sambil tetap mempertahankan branding Main, metadata rilis, versioning, dan perbaikan khusus Main.

### ✨ Modern UI & Tema

- Menambahkan dan menyempurnakan tampilan **Miyorare Modern**.
- Modern sekarang menjadi tampilan default untuk instalasi baru.
- Pengguna lama tetap mempertahankan tampilan sebelumnya saat melakukan update.
- Classic tetap tersedia sepenuhnya dan masih dapat dipilih secara manual.
- Meningkatkan sistem warna dan konsistensi visual pada Modern.
- Setiap preset Modern kini memiliki identitas warna yang lebih berbeda.
- Meningkatkan dukungan Light, Dark, AMOLED, dan Custom Color.
- Meningkatkan konsistensi visual antara layar Compose dan layar berbasis View.

### 🎨 Peningkatan Antarmuka

- Menambahkan gaya header Modern yang disesuaikan berdasarkan fungsi layar.
- Favourites / Disukai menggunakan header yang lebih dekoratif.
- Manga Details menggunakan tampilan semi-dekoratif yang tetap berfokus pada keterbacaan.
- Explore / Jelajah menggunakan header Modern yang lebih bersih.
- Downloads dan Settings menggunakan tampilan yang lebih clean agar lebih mudah digunakan.
- Menyempurnakan spacing, card, chip, border, surface, dan bentuk berbagai komponen.

### ❤️ Disukai / Favourites

- Menyempurnakan tampilan Modern pada halaman Disukai.
- Memperbaiki tampilan kategori dan header.
- Meningkatkan konsistensi visual dengan preset Modern yang dipilih.
- Pencarian kontekstual kini mempertahankan state Favourites saat ini.

### 📖 Detail Manga

- Menyempurnakan layout Manga Details pada Modern.
- Memperbaiki spacing dan tampilan bagian atas halaman.
- Menyempurnakan tampilan genre dan tag.
- Menambahkan context action terpadu untuk **Judul Manga** dan **Author**.
- Menambahkan:
  - Cari di Library
  - Global Search
  - Salin ke clipboard

Fitur ini berkaitan dengan feature request:

**Issue #2 — Expand context menu for Manga Title to match Author search options**  
https://github.com/Noirero/Miyorare/issues/2

### 🔗 Tracker & Metadata

- Menambahkan context action / long-press pada hasil pencarian tracker.
- Menambahkan **Salin judul**.
- Menambahkan **Salin judul alternatif**.
- Menambahkan **Buka halaman tracker**.
- Menambahkan kemampuan mengambil metadata dari tracker yang telah ditautkan.
- Menambahkan preview metadata sebelum perubahan diterapkan.
- Metadata yang dapat digunakan meliputi:
  - Judul
  - Author
  - Artist
  - Deskripsi
  - Cover
- Menambahkan dukungan metadata AniList.
- Menambahkan dukungan metadata MyAnimeList.
- Pengguna dapat memilih field metadata mana yang ingin diterapkan, sehingga tidak harus mengganti semuanya sekaligus.

Fitur ini berkaitan dengan feature request:

**Issue #3 — Enhance Tracking Functions: Copy titles to clipboard and auto-fill metadata**  
https://github.com/Noirero/Miyorare/issues/3

### ⬇️ Downloads

- Menyempurnakan halaman Downloads untuk Modern UI.
- Memperbaiki tampilan status download.
- Meningkatkan tampilan state aktif, loading, dan error.
- Menambahkan quick controls pada tampilan Modern.
- Menambahkan penjelasan struktur folder penyimpanan download.

Struktur penyimpanan kini dijelaskan untuk:

- Manga
- Novel
- Local Manga / PDF / CBZ

### 🔎 Jelajah / Explore

- Menyempurnakan header Modern Explore.
- Kontrol Explore kini lebih menyesuaikan preset Modern yang dipilih.
- Meningkatkan perilaku filter SFW / NSFW.
- Meningkatkan identitas item dan refresh daftar source.
- Meningkatkan konsistensi antara filter source dan navigasi Explore.

### ⚙️ Pengaturan

- Menyempurnakan tampilan Modern Settings.
- Memperbaiki struktur dan pengelompokan pengaturan.
- Menyempurnakan halaman Downloads, Reader, Services, Storage & Network, Extensions, dan Tracker.
- Menyempurnakan segmented choice dan dialog pada Modern.
- Menambahkan dan memperbaiki berbagai teks Bahasa Indonesia dan Bahasa Inggris.

### 🛠 Perbaikan & Stabilitas

- Mempertahankan perbaikan ANR pada preference observer di Main.
- Memperbaiki penanganan warna error pada Downloads.
- Memperbaiki berbagai state UI dan layout.
- Meningkatkan kompatibilitas antara Classic dan Modern.
- Menambahkan berbagai perbaikan stabilitas dan penyempurnaan UI yang sebelumnya dikembangkan di Beta.

### 📌 Catatan

Miyorare Classic tetap tersedia.

Modern dikembangkan sebagai lapisan visual terpisah dan tidak menghapus tampilan Classic.

Rilis ini juga mencakup implementasi feature request dari:

- **#2 — Context action Judul Manga / Author**
- **#3 — Tracker title actions dan pengambilan metadata**

---

## 🇬🇧 English

This release promotes the latest improvements developed in Beta to the Main branch while preserving Main branding, release metadata, versioning, and Main-specific fixes.

### ✨ Modern UI & Themes

- Added and refined the **Miyorare Modern** interface.
- Modern is now the default appearance for fresh installations.
- Existing users keep their previous appearance when upgrading.
- Classic remains fully available and can still be selected manually.
- Improved Modern color handling and visual consistency.
- Each Modern preset now has a more distinct color identity.
- Improved Light, Dark, AMOLED, and Custom Color support.
- Improved visual consistency between Compose and View-based screens.

### 🎨 Interface Improvements

- Added screen-specific Modern header styles.
- Favourites now uses a more decorative header.
- Manga Details uses a semi-decorative layout focused on readability.
- Explore uses a cleaner Modern header.
- Downloads and Settings use cleaner layouts for easier navigation.
- Improved spacing, cards, chips, borders, surfaces, and component shapes.

### ❤️ Favourites

- Improved the Modern Favourites interface.
- Refined category and header presentation.
- Improved consistency with the selected Modern preset.
- Context searches now preserve the current Favourites state.

### 📖 Manga Details

- Improved the Modern Manga Details layout.
- Refined top spacing and content presentation.
- Improved genre and tag chips.
- Added unified context actions for **Manga Title** and **Author**.
- Added:
  - Search in Library
  - Global Search
  - Copy to clipboard

This functionality is related to the following feature request:

**Issue #2 — Expand context menu for Manga Title to match Author search options**  
https://github.com/Noirero/Miyorare/issues/2

### 🔗 Tracker & Metadata

- Added long-press/context actions to tracker search results.
- Added **Copy title**.
- Added **Copy alternative title**.
- Added **Open tracker page**.
- Added the ability to fetch metadata from linked trackers.
- Added metadata preview before applying changes.
- Supported metadata fields include:
  - Title
  - Author
  - Artist
  - Description
  - Cover
- Added AniList metadata support.
- Added MyAnimeList metadata support.
- Users can choose which metadata fields to apply instead of overwriting everything at once.

This functionality is related to the following feature request:

**Issue #3 — Enhance Tracking Functions: Copy titles to clipboard and auto-fill metadata**  
https://github.com/Noirero/Miyorare/issues/3

### ⬇️ Downloads

- Improved the Downloads screen for Modern UI.
- Improved download-state visuals.
- Improved active, loading, and error state presentation.
- Added Modern quick controls.
- Added explanations for download folder structures.

Storage structures are now documented for:

- Manga
- Novel
- Local Manga / PDF / CBZ

### 🔎 Explore

- Improved the Modern Explore header.
- Explore controls now better follow the selected Modern preset.
- Improved SFW / NSFW filtering behavior.
- Improved source item identity and refresh behavior.
- Improved consistency between source filtering and Explore navigation.

### ⚙️ Settings

- Improved the Modern Settings interface.
- Improved settings grouping and presentation.
- Refined Downloads, Reader, Services, Storage & Network, Extensions, and Tracker settings.
- Improved Modern segmented choices and dialogs.
- Added and refined Indonesian and English interface strings.

### 🛠 Fixes & Stability

- Preserved the Main preference-observer ANR fix.
- Fixed Downloads error-color handling.
- Improved several UI states and layouts.
- Improved compatibility between Classic and Modern.
- Added various stability fixes and UI refinements developed in Beta.

### 📌 Notes

Miyorare Classic remains available.

Modern is developed as a separate visual layer and does not remove the Classic interface.

This release also includes functionality related to:

- **#2 — Manga Title / Author context actions**
- **#3 — Tracker title actions and metadata fetching**
