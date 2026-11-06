# flooww

Aplikasi Android dengan bubble mengambang untuk belajar.

## Cara kerja

Bubble polos mengambang di layar. Ketuk bubble, muncul **menu kotak** di sampingnya. Pilih alat, jendela baru muncul setelah dipilih.

Alat yang mengambil soal dari layar (Tanya AI, Salin, Terjemah, Gambar) langsung membuka lingkaran pilih area, seperti circle to search. Ketuk sekali tanpa menggambar untuk batal.

| Menu | Fungsi |
| --- | --- |
| Tanya AI | Lingkari soal, gambar langsung dikirim ke Gemini, ChatGPT, atau Claude (pilih di aplikasi) |
| Salin | Lingkari area, baca teks (OCR di HP), otomatis tersalin |
| Terjemah | Lingkari teks, dikirim ke AI pilihan dengan perintah terjemah |
| Gambar | Lingkari area lalu cari dengan Google Lens |
| Browser | Browser mini di jendela mengambang |
| Chat | Tanya jawab dengan Gemini (memakai API key) |
| Hitung | Kalkulator: + - × ÷ ^ % √ π |
| Klik | Auto clicker: pasang target, atur jeda dan jumlah ketukan |
| Apps | Tambah aplikasi dari HP, buka sebagai jendela mengambang |
| Auto Jawab | Membaca layar, menandai jawaban benar. Lihat bagian di bawah |

Tombol ✕ di jendela hanya menyembunyikan. Riwayat browser, obrolan, dan isian tetap ada sampai bubble dihentikan. Jendela digeser lewat header dan diubah ukurannya lewat ◢ di pojok kanan bawah.

### Auto Jawab

Saat dinyalakan, aplikasi membaca seluruh layar lewat Gemini lalu menggambar tanda di atas layar:

- Pilihan ganda dan pilihan ganda kompleks: kotak hijau dengan centang pada opsi yang benar.
- Esai atau isian: kartu kecil berisi jawaban singkat di dekat soalnya.

Bubble berubah menjadi ✕ merah. Ketuk untuk mematikan, bubble kembali normal. Saat layar berubah (geser atau pindah soal), tanda lama dihapus dan layar dibaca ulang setelah tenang. Jarak antar pembacaan minimal 8 detik, dan makin lama bila tidak ada soal, supaya kuota gratis awet. Auto Jawab memakai API key Gemini.

## Gestur bubble

Di aplikasi, bagian **Gestur bubble**, atur aksi untuk ketuk sekali (bawaan: buka menu), ketuk dua kali (bawaan: Tanya AI), dan tekan lama (bawaan: Auto Jawab). Pilihannya: tidak ada, buka menu, atau salah satu alat. Lama tekan bisa diatur 0,4 sampai 3 detik. Saat Auto Jawab atau auto clicker menyala, semua gestur mematikannya. Bila ketuk dua kali dipakai, ketuk sekali tertunda sekitar 0,3 detik.

## Pengaturan

Semua pengaturan ada di aplikasi (bukan di menu bubble): AI tujuan Tanya AI, API key Gemini, ukuran, warna, dan opasitas bubble, menu, serta jendela. Perubahan langsung berlaku pada bubble yang sedang berjalan.

## API key Gemini

Dipakai hanya oleh **Chat** dan **Auto Jawab**. Tanya AI dan Terjemah tidak memakainya.

Cara terbaik untuk APK yang disebar: tiap pengguna membuat key gratis sendiri di <https://aistudio.google.com/apikey> lalu menempelnya di aplikasi. Jatah gratis jadi per orang.

Untuk APK pribadi, key bisa ditanam saat build lewat secret GitHub `GEMINI_API_KEY` (Settings > Secrets and variables > Actions). Build lokal: `GEMINI_API_KEY=... gradle assembleDebug`. Key di dalam APK bisa diekstrak siapa pun yang memegang APK, jadi jangan sebar APK yang berisi key Anda. Model bawaan: `gemini-2.5-flash`, bisa diganti di aplikasi.

## Keyboard dan tombol tempel

Di aplikasi, bagian **Keyboard di jendela mengambang**, pilih Diblokir (bawaan) atau Diizinkan. Diblokir berarti semua jendela mengambang memakai `FLAG_NOT_FOCUSABLE` dan `FLAG_NOT_TOUCH_MODAL`: tidak menerima fokus, keyboard tidak bisa muncul, dan sentuhan di luar jendela diteruskan ke aplikasi di bawahnya. Layar pengaturan aplikasi tidak terpengaruh. Untuk mengisi kolom di jendela (Browser, Chat), pakai tombol tempel di header jendela: menempel teks terakhir yang disalin dari gambar lewat menu Salin.

## Aplikasi di menu Apps

Dipilih di aplikasi, bagian **Aplikasi di menu Apps**. Menu Apps di bubble hanya menampilkan dan membuka aplikasi itu.

## Tanda tangan APK

APK ditandatangani dengan key tetap (`app/flooww.keystore`), jadi versi baru langsung memperbarui yang lama selama `applicationId` sama. Key bawaan ini ada di repo dan passwordnya tertulis di `app/build.gradle.kts`, cocok untuk APK pribadi. Untuk key rahasia sendiri, buat keystore, lalu isi secret GitHub `FLOOWW_KEYSTORE_B64` (base64 keystore), `FLOOWW_STORE_PASSWORD`, dan bila perlu `FLOOWW_KEY_ALIAS` serta `FLOOWW_KEY_PASSWORD`. Catatan: APK lama yang bertanda tangan debug acak harus dicopot sekali, setelah itu update berjalan mulus.

## Cara build

1. Upload folder ini ke repo GitHub (branch `main`).
2. Tab Actions, jalankan workflow **Build APK** (otomatis jalan saat push).
3. Unduh artifact, install APK-nya.

## Menu Apps dan jendela bebas

Aplikasi terbuka sebagai jendela mengambang hanya bila HP mendukung mode jendela bebas. Di Opsi pengembang aktifkan "Jendela bentuk bebas" dan "Paksa aktivitas dapat diubah ukuran", lalu **restart HP**. Tab Apps menampilkan status keduanya. Beberapa merek (Samsung, Xiaomi, dll.) membatasi ini. Tanpa mode bebas, aplikasi terbuka layar penuh.

## Catatan

- Layar hanya diambil saat Anda melingkari area atau menyalakan Auto Jawab. Gambarnya dikirim ke AI yang Anda pilih.
- Android 14+ meminta izin tangkap layar setiap kali bubble dimulai.
- Notifikasi foreground selalu tampil selama bubble aktif.
- Layanan aksesibilitas hanya dipakai untuk mengetuk titik target auto clicker. Tidak membaca layar.
- OCR di HP hanya huruf Latin.
- Aplikasi ini tidak menyembunyikan dirinya. Hormati aturan sekolah, kampus, dan ujian saat memakainya.
