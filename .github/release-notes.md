## Miyorare v1.4.11

This update focuses on visual consistency and release reliability. Custom neutral accent colors now keep their intended identity across Favourites glass and bottom navigation, light-mode Favourites titles are easier to read, and the stable release/README flow is more reliable.

### ✨ What's New

- **Faithful neutral custom accents** — explicitly selected neutral accents such as black and gray now keep their authored identity in Miyorare's glass styling instead of being transformed into a luminous color.
- **Stable release README synchronization** — published stable releases can now automatically prepare a small metadata PR that keeps the README release verification block aligned with the released version, source commit, Actions run, APK name, and SHA-256.

### 🚀 Improvements

- **Custom background color identity** — when custom-background color synchronization is active, the background's primary identity can flow consistently into the Miyorare view palette.
- **Bottom navigation theming** — modern and legacy bottom navigation now respect an explicitly selected black custom identity, including container, border, glow, selected state, and content contrast.
- **Release-note maintenance** — stable release metadata handling now normalizes repeated release headings and preserves machine-readable release provenance.
- **Promotion validation** — the main promotion gate can publish an exact-head APK for owner device testing, with an explicit low-risk `skip-device-test` override while retaining the non-device promotion checks.

### 🐞 Fixes

- Fixed light-mode Favourites titles below modern covers so text uses the appropriate glass content color instead of losing contrast.
- Fixed black/gray/white custom accents being relit or tinted by neon-glass color processing.
- Fixed black custom accent styling in modern bottom navigation so it remains black instead of inheriting a luminous theme color.
- Fixed legacy bottom navigation so a black custom identity remains black across its glass, selected state, and glow treatment.
- Added regression coverage for neutral custom accent identity and modern-cover title contrast.

<details>
<summary><strong>🇮🇩 Bahasa Indonesia</strong></summary>

Pembaruan ini berfokus pada konsistensi visual dan keandalan proses rilis. Warna accent netral kustom sekarang mempertahankan identitas warna yang dipilih pada glass Favourites dan navigasi bawah, judul Favourites di mode terang lebih mudah dibaca, dan alur stable release/README menjadi lebih andal.

### ✨ Yang Baru

- **Accent netral kustom yang lebih akurat** — accent netral yang dipilih secara eksplisit seperti hitam dan abu-abu sekarang mempertahankan identitas warnanya pada gaya glass Miyorare, bukan diubah menjadi warna luminous.
- **Sinkronisasi README stable release** — setelah stable release dipublikasikan, sistem sekarang dapat menyiapkan PR metadata kecil untuk menyelaraskan blok verifikasi README dengan versi rilis, source commit, Actions run, nama APK, dan SHA-256.

### 🚀 Peningkatan

- **Identitas warna custom background** — ketika sinkronisasi warna custom background aktif, warna primary background dapat diteruskan secara konsisten ke view palette Miyorare.
- **Tema bottom navigation** — bottom navigation modern dan legacy sekarang menghormati identitas custom hitam yang dipilih secara eksplisit, termasuk container, border, glow, selected state, dan kontras konten.
- **Pemeliharaan release notes** — penanganan metadata stable release sekarang menormalkan heading rilis yang berulang dan mempertahankan provenance rilis yang dapat dibaca mesin.
- **Validasi promotion** — promotion gate menuju main dapat menyediakan APK exact-head untuk pengujian perangkat oleh owner, dengan override `skip-device-test` untuk promotion berisiko rendah tanpa melewati pengecekan promotion non-device.

### 🐞 Perbaikan

- Memperbaiki judul Favourites mode terang di bawah cover modern agar menggunakan warna konten glass yang sesuai dan tetap mudah dibaca.
- Memperbaiki custom accent hitam/abu-abu/putih yang sebelumnya dapat dibuat lebih terang atau berubah tint oleh pemrosesan warna neon-glass.
- Memperbaiki custom accent hitam pada bottom navigation modern agar tetap hitam dan tidak berubah mengikuti warna luminous theme.
- Memperbaiki bottom navigation legacy agar identitas custom hitam tetap hitam pada glass, selected state, dan efek glow.
- Menambahkan regression coverage untuk identitas custom accent netral dan kontras judul modern cover.

</details>
