# flooww

Aplikasi Android dengan bubble mengambang untuk belajar.

## Cara kerja

Bubble polos mengambang di layar. Ketuk bubble, muncul **menu kotak** di sampingnya. Pilih alat, jendela baru muncul setelah dipilih.

Alat yang mengambil soal dari layar (Tanya AI, Salin, Terjemah, Gambar) langsung membuka lingkaran pilih area, seperti circle to search. Ketuk sekali tanpa menggambar untuk batal.

| Menu | Fungsi |
| --- | --- |
| Tanya AI | Lingkari soal, gambar dibuka di browser mengambang pada website Gemini, ChatGPT, atau Claude (pilih di aplikasi), gambar otomatis terlampir lalu perintah dikirim |
| Salin | Lingkari area, baca teks (OCR di HP), otomatis tersalin |
| Terjemah | Lingkari teks, terjemah di HP (Inggris atau Jepang ke Indonesia, bisa dibalik) |
| Screenshot | Ambil layar penuh, simpan ke galeri (Pictures/flooww) |
| Browser | Browser mini di jendela mengambang |
| Hitung | Kalkulator: + - × ÷ ^ % √ π |
| Klik | Auto clicker: pasang target, atur jeda dan jumlah ketukan |
| Apps | Tambah aplikasi dari HP, buka sebagai jendela mengambang |
| Matikan | Hentikan bubble |

Tombol ✕ di jendela hanya menyembunyikan. Riwayat browser dan isian tetap ada sampai bubble dihentikan. Jendela digeser lewat header dan diubah ukurannya lewat ◢ di pojok kanan bawah.

## Gestur bubble

Di aplikasi, bagian **Gestur bubble**, atur aksi untuk ketuk sekali (bawaan: buka menu), ketuk dua kali (bawaan: Tanya AI), tekan lama, dan sapu ke atas, bawah, atau ke arah tengah layar. Pilihannya: tidak ada, buka menu, atau salah satu alat. Saat auto clicker menyala, semua gestur mematikannya. Bila ketuk dua kali dipakai, ketuk sekali tertunda sekitar 0,3 detik.

## Pengaturan

Semua pengaturan ada di aplikasi (bukan di menu bubble): tujuan Tanya AI, bentuk, ukuran, dan warna bubble, menu, serta jendela. Perubahan langsung berlaku pada bubble yang sedang berjalan. Aplikasi tidak memakai API key apa pun.

## Keyboard dan tombol tempel

Di aplikasi, bagian **Keyboard di jendela mengambang**, pilih Diblokir (bawaan) atau Diizinkan. Diblokir berarti semua jendela mengambang memakai `FLAG_NOT_FOCUSABLE` dan `FLAG_NOT_TOUCH_MODAL`: tidak menerima fokus, keyboard tidak bisa muncul, dan sentuhan di luar jendela diteruskan ke aplikasi di bawahnya. Layar pengaturan aplikasi tidak terpengaruh. Untuk mengisi kolom di jendela (Browser), pakai tombol tempel di header jendela: menempel teks terakhir yang disalin dari gambar lewat menu Salin.

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

- Layar hanya diambil saat Anda melingkari area atau memakai Screenshot. Gambar Tanya AI dikirim ke website AI yang Anda pilih.
- Android 14+ meminta izin tangkap layar setiap kali bubble dimulai.
- Notifikasi foreground selalu tampil selama bubble aktif.
- Layanan aksesibilitas hanya dipakai untuk mengetuk titik target auto clicker. Tidak membaca layar.
- OCR di HP hanya huruf Latin.
- Aplikasi ini tidak menyembunyikan dirinya. Hormati aturan sekolah, kampus, dan ujian saat memakainya.

## Lisensi, trial, dan aplikasi admin

- **Lisensi**: flooww terikat kode perangkat. Admin (aplikasi `flow admin`, merah) menandatangani lisensi dengan kunci rahasia ECDSA; flooww hanya menyimpan kunci publik.
- **Versi**: hanya angka bulat (1, 2, 3, ...). `versionCode` = `versionName`. Lisensi mencakup rilis terbaru + update gratis (bawaan 2).
- **Trial**: 10 menit sejak bubble pertama kali dihidupkan, disimpan di Firebase Realtime Database per kode perangkat (`/trial/<kode>`), tidak bisa diulang walau data dihapus atau aplikasi dicopot. Waktu dihitung dari waktu server + jam monotonik, bukan jam HP. Setelah habis semua fitur mati dan muncul popup yang hanya bisa ditutup dengan lisensi sah.
- **Pembaruan**: menu "Cek pembaruan" membaca `update.json` (`{"version":10,"url":"https://...apk","notes":"..."}`), mengunduh, dan memasang. Lisensi membatasi versi tertinggi.

### Pengaturan Firebase (sekali saja)
1. Firebase Console > Build > Realtime Database > Create database.
2. Tab Rules: tempel isi `firebase/database.rules.json`, Publish.
3. Di GitHub repo > Settings > Secrets and variables > Actions > Variables, tambah:
   - `FLOOWW_DB_URL` = alamat database, contoh `https://nama-proyek-default-rtdb.firebaseio.com`
   - `FLOOWW_UPDATE_URL` = alamat https berkas `update.json`
   - `FLOOWW_ADMIN_GOOGLE_CLIENT_ID` = ID klien OAuth jenis Web (opsional; kosong = admin hanya pakai PIN)

### Unduhan publik otomatis (repo rilis terpisah)
Setiap build di `main`, workflow mengunggah `flooww.apk` dan `update.json` sebagai GitHub Release di repo publik terpisah (hanya berisi APK, tanpa kode). Langkah sekali saja:
1. Buat repo publik baru, mis. `flooww-rilis`, centang "Add a README file".
2. GitHub > Settings > Developer settings > Personal access tokens > Fine-grained tokens: akses hanya ke repo `flooww-rilis`, izin Contents: Read and write.
3. Di repo flooww > Settings > Secrets and variables > Actions: secret `RELEASE_TOKEN` (token tadi); variables `RELEASE_REPO` (`NAMA/flooww-rilis`) dan `FLOOWW_UPDATE_URL` = `https://github.com/NAMA/flooww-rilis/releases/latest/download/update.json`.
Tombol download di web: `https://github.com/NAMA/flooww-rilis/releases/latest/download/flooww.apk`.
