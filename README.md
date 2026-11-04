# FloatStudy

Aplikasi Android dengan bubble mengambang untuk belajar.

## Fitur

| Tab | Fungsi |
| --- | --- |
| Tanya AI | Lingkari area di layar (seperti circle to search) atau layar penuh, kirim ke AI, tampilkan penjelasan dan jawaban |
| Salin | Lingkari area, baca teks (OCR di perangkat), otomatis tersalin ke clipboard, bisa dijawab AI |
| Terjemah | Terjemahkan teks ketikan atau area layar (AI vision, mendukung semua aksara) |
| Gambar | Lingkari area lalu cari dengan Google Lens, atau cari teks hasil OCR di Google |
| Browser | Browser mini di dalam panel mengambang |
| Chat | Tanya AI lewat teks |
| Hitung | Kalkulator: + - × ÷ ^ % √ π, serta sin cos tan ln log (derajat) |
| Klik | Auto clicker: pasang target, atur jeda dan jumlah ketukan |

Panel bisa digeser lewat ikon ≡. Bubble bisa digeser. Saat auto clicker berjalan bubble jadi merah, ketuk untuk berhenti.

## Cara build

1. Upload folder ini ke repo GitHub (branch `main`).
2. Tab Actions, jalankan workflow **Build APK** (otomatis jalan saat push).
3. Unduh artifact `FloatStudy-debug`, install APK-nya.

## Cara pakai

1. Buka aplikasi, isi API key Anthropic. Model bawaan: `claude-sonnet-5-5`.
2. Tekan **Mulai bubble**, izinkan "tampil di atas aplikasi lain" dan tangkap layar.
3. Untuk auto clicker, tekan **Aktifkan Auto Clicker**, pilih FloatStudy Auto Clicker, aktifkan.
4. Ketuk bubble **AI** untuk membuka panel.

## Catatan

- Layar hanya diambil saat Anda menekan tombol Lingkari atau Layar penuh. Gambar yang dipilih dikirim ke API AI (atau Google Lens bila Anda memilihnya).
- API key disimpan di SharedPreferences biasa pada perangkat. Pakai key khusus dengan batas biaya.
- Android 14+ meminta izin tangkap layar setiap kali bubble dimulai.
- Notifikasi foreground selalu tampil selama bubble aktif.
- Layanan aksesibilitas hanya dipakai untuk mengetuk titik target. Tidak membaca layar.
- OCR di perangkat hanya huruf Latin. Untuk aksara lain pakai tab Terjemah atau Tanya AI.
- Aplikasi ini tidak menyembunyikan dirinya. Hormati aturan sekolah, kampus, dan situs saat memakainya.
