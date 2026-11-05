# FloatStudy

Aplikasi Android dengan bubble mengambang untuk belajar.

## Cara kerja

Ketuk bubble, muncul **menu lingkar**. Pilih alat, jendela baru muncul setelah dipilih.

Alat yang mengambil soal dari layar (Tanya AI, Salin, Terjemah, Gambar) langsung membuka lingkaran pilih area (seperti circle to search). Jendela alat baru terbuka setelah area dilingkari. Ketuk sekali tanpa menggambar untuk batal.

| Menu | Fungsi |
| --- | --- |
| Tanya AI | Lingkari area atau layar penuh, kirim ke AI, tampilkan penjelasan dan jawaban |
| Salin | Lingkari area, baca teks (OCR di perangkat), otomatis tersalin, bisa dijawab AI |
| Terjemah | Terjemahkan teks ketikan atau area layar |
| Gambar | Lingkari area lalu cari dengan Google Lens |
| Browser | Browser mini di jendela mengambang |
| Chat | Tanya AI lewat teks |
| Hitung | Kalkulator: + - × ÷ ^ % √ π, serta sin cos tan ln log (derajat) |
| Klik | Auto clicker: pasang target, atur jeda dan jumlah ketukan |
| Apps | Tambah aplikasi dari HP, buka sebagai jendela mengambang |
| Atur | Ukuran, warna, opasitas bubble, menu lingkar, dan jendela |

Tombol ✕ di jendela hanya menyembunyikan. Riwayat browser, hasil AI, dan isian tetap ada sampai bubble dihentikan. Jendela bisa digeser lewat ≡ dan diubah ukurannya lewat ◢ di pojok kanan bawah.

## Cara build

1. Upload folder ini ke repo GitHub (branch `main`).
2. Tab Actions, jalankan workflow **Build APK** (otomatis jalan saat push).
3. Unduh artifact `FloatStudy-debug`, install APK-nya.

## API key tetap

Key tidak ditulis di source code. Simpan sebagai secret GitHub, lalu ikut tertanam ke APK saat build:

1. Repo > Settings > Secrets and variables > Actions > New repository secret.
2. Nama: `ANTHROPIC_API_KEY`, isi: key Anthropic Anda.
3. Jalankan ulang workflow **Build APK**.

Build lokal: `ANTHROPIC_API_KEY=sk-ant-... gradle assembleDebug`. Bila key tidak ada saat build, aplikasi menampilkan kolom isian manual sebagai cadangan.

Key di dalam APK bisa diekstrak oleh siapa pun yang memegang APK. Jangan bagikan APK-nya, dan pakai key khusus dengan batas biaya.

## Cara pakai

1. API key sudah tertanam saat build (lihat bagian API key). Tidak perlu diisi di aplikasi. Model bawaan: `claude-sonnet-5-5`.
2. Tekan **Mulai bubble**, izinkan "tampil di atas aplikasi lain" dan tangkap layar.
3. Untuk auto clicker, tekan **Aktifkan Auto Clicker**, pilih FloatStudy Auto Clicker, aktifkan.
4. Ketuk bubble **AI**, pilih alat dari menu lingkar.
5. Menu **Apps** membuka aplikasi sebagai jendela mengambang hanya bila HP mendukung mode jendela bebas (Opsi pengembang: aktifkan jendela bentuk bebas dan paksa aktivitas dapat diubah ukuran). Tanpa itu, aplikasi terbuka layar penuh.

## Catatan

- Layar hanya diambil saat Anda menekan tombol Lingkari atau Layar penuh. Gambar yang dipilih dikirim ke API AI (atau Google Lens bila Anda memilihnya).
- Android 14+ meminta izin tangkap layar setiap kali bubble dimulai.
- Notifikasi foreground selalu tampil selama bubble aktif.
- Layanan aksesibilitas hanya dipakai untuk mengetuk titik target. Tidak membaca layar.
- OCR di perangkat hanya huruf Latin. Untuk aksara lain pakai tab Terjemah atau Tanya AI.
- Aplikasi ini tidak menyembunyikan dirinya. Hormati aturan sekolah, kampus, dan situs saat memakainya.
