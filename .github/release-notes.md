## Miyorare — Reading Gets Bigger 📚

Miyorare terus berkembang, tetapi satu prinsip tetap dijaga: **Kestabilan · Kecepatan · Kelancaran.**

Karena pembaruan Main kali ini berdekatan dengan rilis sebelumnya, catatan berikut **digabungkan** agar perubahan sebelumnya tetap tercantum dan tambahan terbaru tidak terpisah menjadi changelog yang terlalu pendek.

### 📖 Novel & EPUB

- Novel kini dapat menyimpan profil pengaturan reader khusus per judul, atau tetap mengikuti pengaturan Novel global.
- Dukungan Novel/EPUB diperluas, termasuk download EPUB, highlight dengan catatan, dan penanganan reader yang lebih tepat untuk source Novel.
- Status pemuatan chapter dibuat lebih jelas saat data masih diproses.
- Memperbaiki crash pada dialog highlight/catatan EPUB sehingga input highlight dapat dibuka dengan lebih aman.

### 📦 Download berdasarkan volume

- Pilih dan download seluruh volume tanpa memilih chapter satu per satu.
- Lihat jumlah chapter yang siap didownload, sudah didownload, atau sudah berada dalam antrean.
- Hapus hasil download berdasarkan volume.
- State Jeda/Lanjut download dibuat lebih konsisten, termasuk setelah proses aplikasi dibuat ulang.

### 🌙 Tsuki / Usagi Plugin Support

- Menambahkan dukungan plugin opsional Tsuki / Usagi untuk UMA dan Gekkoushi.
- Plugin dapat di-install/update, diimpor dari JAR lokal, diaktifkan/nonaktifkan, dan source dapat dipilih satu per satu.
- Gekkoushi kini dapat dipasang melalui jalur instalasi resmi Tsuki/Usagi dan tidak lagi ditahan sebagai tahap deferred.
- Source plugin baru tetap nonaktif secara default sampai diaktifkan pengguna.
- Plugin tidak dibundel ke APK Miyorare dan hanya diunduh atas tindakan pengguna.

### 📚 Chikari

- Menambahkan dan menyempurnakan kompatibilitas Chikari untuk Novel.
- Memperbaiki penanganan instalasi yang sudah ada dan chapter/oneshot.

### 🗂️ Library Groups

- Group dapat ditempatkan pada kategori Favourites tertentu tanpa mengubah kategori manga aslinya.
- Menambahkan pencarian dan import metadata dari tracking service.
- Menambahkan linking tracking pada Group dan sinkronisasi progress berdasarkan Reading Timeline.
- Pengelolaan Group, urutan, metadata, dan navigasi kembali disempurnakan.

### ❤️ Favourites / Disukai

- Menambahkan pilihan perilaku header: **Header pinned** atau **Header scrolls away**.
- Mode header diperbaiki agar pilihan pinned/scroll-away diterapkan kembali dengan benar saat kembali ke halaman Disukai atau setelah keluar dari pencarian.
- Kontrol tampil/sembunyikan kategori dibuat lebih jelas.
- Berbagai state kategori, tampilan, dan interaksi Disukai diperbaiki.

### 🩺 Smart Reader Error Guidance

- Reader kini menampilkan tombol **Bantuan** saat halaman gagal dimuat.
- Miyorare mencoba memberi petunjuk penyebab yang paling mungkin, seperti DNS/adblock, jaringan, source/server, plugin/parser, atau compatibility layer Miyorare.
- Pesan bantuan dibuat bersifat diagnostik dan tidak mengubah DNS, routing, request, atau perilaku plugin secara otomatis.
- Untuk error tertentu, pengguna dapat diarahkan ke sumber/plugin terkait atau halaman issue Miyorare agar pelaporan lebih tepat.

### ⚙️ Settings

- Halaman Settings disusun menjadi kelompok yang lebih jelas agar fitur yang semakin banyak tetap mudah ditemukan.

### 🎨 Branding & Release

- Identitas launcher Main dikunci ke artwork Miyorare agar branding DropSauce tidak terbawa saat promotion.
- Adaptive launcher Main diperketat agar tidak kembali menggunakan asset monochrome lama yang belum diverifikasi.
- README Main dipertahankan terpisah dari README Beta saat promotion.
- Workflow build Main dipisahkan menjadi **Miyorare Main Build** yang dijalankan manual, sehingga build stable tidak otomatis berjalan hanya karena promotion/commit.

### 🔧 Stability & Performance

- Penyempurnaan pada Novel reader, chapter loading, download queue, Favourites, Library Groups, source handling, navigation, search, Explore, dan reader state.
- Perbaikan tambahan dilakukan pada lifecycle AppBar/Header Disukai dan jalur error Reader tanpa menambah pemeriksaan jaringan di background.
- Berbagai perbaikan internal dilakukan untuk menjaga Miyorare tetap stabil, cepat, dan lancar saat fitur baru ditambahkan.
