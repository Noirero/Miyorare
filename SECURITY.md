# Keamanan Miyorare & Peringatan Play Protect

Dokumen ini menjelaskan alasan Miyorare dapat memunculkan peringatan Play Protect, apa yang sudah diperiksa pada APK resmi, serta batasan yang tetap perlu dipahami pengguna.

## Apakah Miyorare berbahaya?

Berdasarkan pemeriksaan terhadap APK resmi Miyorare Beta yang dibuat melalui GitHub Actions, **tidak ditemukan indikasi bahwa APK tersebut adalah malware atau telah disusupi setelah proses build**.

Peringatan Play Protect **tidak otomatis berarti sebuah aplikasi adalah virus**. Miyorare memiliki beberapa kemampuan tingkat lanjut yang memang dapat dianggap sensitif oleh sistem keamanan Android, seperti pemasangan extension APK, pemeriksaan aplikasi terpasang, akses penyimpanan, dan dukungan Shizuku. Kemampuan tersebut digunakan untuk fungsi extension, sumber manga/novel, serta pengelolaan file di Miyorare.

Tetap tidak tepat untuk menjamin bahwa setiap APK yang memakai nama Miyorare pasti aman. Hasil pemeriksaan hanya berlaku pada APK resmi yang berasal dari repository dan workflow yang disebutkan di bawah.

## Hasil verifikasi APK resmi

APK yang diperiksa berasal dari:

- Repository: `Noirero/Miyorare`
- Branch: `beta`
- GitHub Actions run: `34020611087`
- Commit: `ce0b35a345fb1117c0ea0b9984dafa08dc9207e9`
- Artifact: `Miyorare-Beta-beta-ce0b35a.apk`

Hasil pemeriksaan:

- Workflow GitHub Actions selesai dengan status **success**.
- Identitas aplikasi Beta terverifikasi sebagai `org.noirero.miyorare.beta`.
- Artifact berasal langsung dari workflow resmi repository.
- SHA-256 APK: `2d08852ce1dbf66ee9c6b457042ed1bfcbd5d01d2304be046f2db218a367d44d`
- SHA-256 artifact ZIP GitHub: `b01c22ec90c71c705667a2b444aa255c696c1f96d0ccbd9c57df62bb1941fc72`
- APK Signature Scheme v2 terverifikasi valid pada APK yang diperiksa.
- Fingerprint SHA-256 sertifikat signing APK yang diperiksa:
  `A4:41:D2:4E:96:19:AC:85:53:F1:D4:71:81:C1:93:F7:13:D8:49:4E:41:44:78:A1:3D:1E:40:FE:55:62:F4:65`

Validnya tanda tangan digital berarti perubahan pada isi APK setelah ditandatangani akan merusak verifikasi signature. Pada APK yang diperiksa, tidak ditemukan tanda bahwa artifact telah diubah setelah proses signing.

## Mengapa Play Protect dapat memberi peringatan?

Miyorare mempunyai fungsi yang lebih luas dibanding aplikasi pembaca sederhana. Beberapa kemampuan yang dapat meningkatkan tingkat kehati-hatian Play Protect antara lain:

- memasang dan memperbarui extension APK;
- mendukung Package Installer dan Shizuku untuk pengelolaan extension;
- melihat paket/aplikasi yang terpasang untuk menemukan extension yang kompatibel;
- akses penyimpanan yang luas untuk local manga/novel, backup, import, dan download;
- menjalankan kode extension pihak ketiga yang kompatibel dengan ekosistem Mihon/Tachiyomi;
- melakukan pekerjaan latar belakang untuk download, update, dan sinkronisasi tertentu.

Kemampuan tersebut juga dapat disalahgunakan oleh malware pada aplikasi lain. Karena itu, **yang menentukan bukan hanya nama permission, tetapi bagaimana kemampuan tersebut digunakan dan dari mana APK berasal**.

## Perbedaan Miyorare yang diperiksa dan pola malware umum

| Miyorare resmi yang diperiksa | Pola yang sering ditemukan pada aplikasi berbahaya |
| --- | --- |
| Source code tersedia dan dapat diperiksa | Perilaku penting sering disembunyikan atau sulit diaudit |
| APK berasal dari build GitHub Actions resmi | APK dapat berasal dari situs, pesan, atau sumber yang tidak jelas |
| Signature APK valid | APK palsu dapat memakai sertifikat berbeda atau berubah-ubah |
| Tidak ditemukan izin SMS pada manifest yang diperiksa | Malware tertentu mengincar SMS dan OTP |
| Tidak ditemukan Accessibility Service untuk mengambil alih perangkat | Malware sering menyalahgunakan Accessibility untuk membaca layar atau menekan tombol |
| Tidak ditemukan izin overlay `SYSTEM_ALERT_WINDOW` | Malware perbankan dapat memakai overlay untuk membuat tampilan login palsu |
| Pemasangan APK digunakan untuk sistem extension | Malware dapat memasang paket lain secara tersembunyi atau tanpa tujuan yang jelas |
| Shizuku merupakan fitur tingkat lanjut yang digunakan secara eksplisit | Malware biasanya berusaha mendapatkan hak tinggi tanpa penjelasan yang wajar |

Dengan kata lain, **memiliki permission sensitif tidak otomatis berarti aplikasi adalah virus**.

## Risiko extension pihak ketiga

Miyorare mendukung extension dan repository pihak ketiga. Ini berarti keamanan aplikasi inti Miyorare dan keamanan sebuah extension adalah dua hal yang berbeda.

Miyorare melakukan pemeriksaan package/signature tertentu pada extension dan menolak beberapa kondisi yang tidak valid. Namun extension tetap merupakan kode dari pihak lain yang dapat terhubung ke situs dan layanan di luar Miyorare.

Gunakan hanya extension dan repository yang Anda percaya. Jangan memasang extension hanya karena memiliki nama yang mirip dengan extension populer.

## Catatan keamanan yang tetap perlu diperhatikan

Miyorare mempertahankan kompatibilitas dengan banyak sumber dan extension. Beberapa keputusan teknis memperluas permukaan risiko dibanding aplikasi yang hanya menggunakan HTTPS dan tidak mendukung plugin, antara lain dukungan cleartext HTTP untuk sumber tertentu, kepercayaan terhadap CA yang ditambahkan pengguna, serta pemuatan kode extension pihak ketiga.

Build Beta juga merupakan build pengujian. Konfigurasi Beta dapat berbeda dari release final dan dapat menerima perubahan lebih cepat. Gunakan Beta jika Anda memahami bahwa stabilitas dan hardening-nya masih dapat terus diperbaiki.

Hal-hal tersebut **bukan bukti malware**, tetapi merupakan alasan mengapa pengguna tetap harus memperoleh APK dan extension dari sumber yang dipercaya.

## Cara memastikan APK yang Anda pasang benar

1. Unduh Miyorare hanya dari repository, GitHub Releases, atau GitHub Actions resmi `Noirero/Miyorare`.
2. Hindari APK yang dikirim ulang melalui situs/file hosting yang tidak dapat diverifikasi.
3. Untuk build yang memiliki hash publik, bandingkan SHA-256 file yang Anda unduh dengan hash yang dipublikasikan.
4. Perhatikan apabila Android menyatakan signature aplikasi berbeda saat melakukan update. Jangan lanjutkan apabila sumber APK tidak jelas.
5. Gunakan repository extension yang dipercaya dan tinjau sumbernya bila tersedia.

## Arti peringatan Play Protect

Peringatan Play Protect dapat muncul karena beberapa alasan dan tidak semuanya memiliki arti yang sama. Peringatan terhadap aplikasi yang belum dikenal atau aplikasi sideload tidak sama dengan temuan malware yang sudah terklasifikasi.

Jika Play Protect menampilkan pesan yang secara eksplisit menyebut aplikasi sebagai **berbahaya**, **malware**, atau memblokirnya karena ancaman tertentu, jangan abaikan pesan tersebut secara otomatis. Periksa terlebih dahulu apakah APK berasal dari sumber resmi, cocokkan hash/signature bila tersedia, dan laporkan pesan lengkap agar dapat diaudit.

## Batasan pemeriksaan

Pemeriksaan keamanan tidak dapat menjadi jaminan absolut bahwa tidak pernah ada bug atau kerentanan. Kesimpulan di dokumen ini terutama didasarkan pada source code yang tersedia dan APK resmi pada build yang disebutkan di atas.

APK Miyorare dari pihak ketiga, APK yang dimodifikasi, build lain dengan signature berbeda, atau extension pihak ketiga **tidak otomatis mendapatkan status yang sama** hanya karena menggunakan nama atau ikon Miyorare.

Transparansi dan verifikasi build lebih penting daripada sekadar mempercayai nama aplikasi.