# Keamanan Miyorare & Peringatan Play Protect

Dokumen ini menjelaskan alasan Miyorare dapat memunculkan peringatan Play Protect, bagaimana APK resmi dapat diverifikasi, serta batasan yang tetap perlu dipahami pengguna.

## Apakah Miyorare berbahaya?

Peringatan Play Protect **tidak otomatis berarti sebuah aplikasi adalah virus**. Miyorare memiliki beberapa kemampuan tingkat lanjut yang dapat dianggap sensitif oleh sistem keamanan Android, seperti pemasangan extension APK, pemeriksaan aplikasi terpasang, akses penyimpanan, dan dukungan Shizuku.

Kemampuan tersebut digunakan untuk fungsi extension, sumber manga/novel, download, local library, backup, dan pengelolaan file. Tetap tidak tepat untuk menjamin bahwa setiap APK yang memakai nama Miyorare pasti aman: pengguna sebaiknya hanya memasang APK dari repository dan GitHub Releases resmi `Noirero/Miyorare`.

## 🔐 Verifikasi rilis terbaru

Bagian di bawah diperbarui otomatis oleh workflow rilis `main` setelah APK stable berhasil dibuat dan dipublikasikan.

<!-- RELEASE_SECURITY_START -->
- **Versi:** [v0.9.600131](https://github.com/Noirero/Miyorare/releases/tag/v0.9.600131)
- **Commit sumber:** [`db0b5b109d178f4e03576f443d0e4011b9eabd0d`](https://github.com/Noirero/Miyorare/commit/db0b5b109d178f4e03576f443d0e4011b9eabd0d)
- **GitHub Actions:** [run 34048174625](https://github.com/Noirero/Miyorare/actions/runs/34048174625)
- **APK dan SHA-256:**
  - `Miyorare-v0.9.600131.apk` — SHA-256 `ab5fbad59dad359a9cd68d84fa2f008078c87393db3571b56f916f97306318e0`
- **Fingerprint sertifikat SHA-256:** `tidak tersedia`
- **Fingerprint sertifikat SHA-1:** `tidak tersedia`
- **Waktu pembaruan:** 2026-09-06 17:28:47 UTC
<!-- RELEASE_SECURITY_END -->

SHA-256 dapat digunakan untuk memastikan file yang diunduh sama dengan APK yang dipublikasikan. Tanda tangan digital Android juga membantu mendeteksi perubahan pada APK setelah proses signing.

## Mengapa Play Protect dapat memberi peringatan?

Miyorare mempunyai fungsi yang lebih luas dibanding aplikasi pembaca sederhana. Beberapa kemampuan yang dapat meningkatkan tingkat kehati-hatian Play Protect antara lain:

- memasang dan memperbarui extension APK;
- mendukung Package Installer dan Shizuku untuk pengelolaan extension;
- melihat paket/aplikasi yang terpasang untuk menemukan extension yang kompatibel;
- akses penyimpanan untuk local manga/novel, backup, import, dan download;
- menjalankan kode extension pihak ketiga yang kompatibel dengan ekosistem Mihon/Tachiyomi;
- melakukan pekerjaan latar belakang untuk download, update, dan sinkronisasi tertentu.

Kemampuan seperti ini juga dapat disalahgunakan oleh malware pada aplikasi lain. Karena itu, **yang menentukan bukan hanya nama permission, tetapi bagaimana kemampuan tersebut digunakan dan dari mana APK berasal**.

## Perbedaan Miyorare resmi dan pola malware umum

| Miyorare resmi | Pola yang sering ditemukan pada aplikasi berbahaya |
| --- | --- |
| Source code tersedia dan dapat diperiksa | Perilaku penting sering disembunyikan atau sulit diaudit |
| APK stable dipublikasikan melalui GitHub Releases resmi | APK dapat berasal dari situs, pesan, atau sumber yang tidak jelas |
| APK menggunakan tanda tangan digital | APK palsu dapat memakai sertifikat berbeda atau berubah-ubah |
| Tidak ditemukan kebutuhan fitur untuk membaca SMS/OTP | Malware tertentu mengincar SMS dan OTP |
| Tidak bergantung pada Accessibility Service untuk mengambil alih perangkat | Malware sering menyalahgunakan Accessibility untuk membaca layar atau menekan tombol |
| Tidak membutuhkan overlay login palsu | Malware perbankan dapat memakai overlay untuk mencuri kredensial |
| Pemasangan APK digunakan untuk sistem extension | Malware dapat memasang paket lain secara tersembunyi atau tanpa tujuan yang jelas |
| Shizuku merupakan fitur tingkat lanjut yang digunakan secara eksplisit | Malware biasanya berusaha mendapatkan hak tinggi tanpa penjelasan yang wajar |

Dengan kata lain, **memiliki permission sensitif tidak otomatis berarti aplikasi adalah virus**.

## Risiko extension pihak ketiga

Miyorare mendukung extension dan repository pihak ketiga. Keamanan aplikasi inti Miyorare dan keamanan sebuah extension adalah dua hal yang berbeda.

Gunakan hanya extension dan repository yang Anda percaya. Jangan memasang extension hanya karena memiliki nama yang mirip dengan extension populer.

## Cara memastikan APK yang Anda pasang benar

1. Unduh APK stable dari halaman [GitHub Releases](https://github.com/Noirero/Miyorare/releases).
2. Cocokkan versi dan nama file dengan bagian **Verifikasi rilis terbaru** di atas.
3. Bila perlu, hitung SHA-256 file APK dan cocokkan dengan nilai yang tercantum.
4. Hindari APK yang dikirim ulang melalui situs atau akun yang tidak terkait dengan repository resmi.
5. Gunakan extension dari sumber yang Anda percaya.

## Batasan pemeriksaan

Tidak ada audit statis yang dapat memberikan jaminan keamanan absolut untuk seluruh kondisi runtime, seluruh extension, atau APK yang didistribusikan ulang oleh pihak lain. Informasi di dokumen ini dimaksudkan untuk membuat proses build dan verifikasi Miyorare lebih transparan, bukan untuk menggantikan kehati-hatian pengguna.

Jika menemukan perilaku keamanan yang mencurigakan, laporkan melalui [GitHub Issues](https://github.com/Noirero/Miyorare/issues) dengan informasi versi, langkah reproduksi, dan sumber APK yang digunakan.
