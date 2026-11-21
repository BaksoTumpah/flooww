package com.example.floatstudy

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Pembaruan di dalam aplikasi, seperti Magisk: aplikasi membaca rilis terbaru langsung dari GitHub Releases,
 * tanpa admin mengisi alamat atau nomor versi lagi.
 *
 * Urutan: (1) update.json di rilis terbaru (berisi sha256 untuk verifikasi), (2) bila tidak ada, API rilis GitHub
 * (nomor versi dari nama tag, mis. v10, dan berkas flooww.apk). Versi hanya angka bulat.
 * Lisensi membatasi versi tertinggi yang boleh dipasang.
 */
object Updater {
    class Info(val version: Int, val url: String, val notes: String, val sha256: String)

    /** Repo rilis publik (hanya berisi APK). */
    private const val RELEASE_REPO = "BaksoTumpah/flooww-rilis"
    private const val DEFAULT_INFO_URL = "https://github.com/$RELEASE_REPO/releases/latest/download/update.json"
    private const val API_LATEST = "https://api.github.com/repos/$RELEASE_REPO/releases/latest"

    private fun open(u: String): HttpURLConnection {
        val h = URL(u).openConnection() as HttpURLConnection
        h.connectTimeout = 10000
        h.readTimeout = 20000
        h.useCaches = false
        h.instanceFollowRedirects = true
        h.setRequestProperty("Accept", "application/json")
        h.setRequestProperty("Cache-Control", "no-cache")
        return h
    }

    private fun get(u: String): String {
        val h = open(u)
        try {
            if (h.responseCode != 200) error("Server pembaruan menjawab ${h.responseCode}.")
            return h.inputStream.bufferedReader().use { it.readText() }
        } finally {
            h.disconnect()
        }
    }

    /**
     * Changelog gabungan: semua perubahan dari versi setelah yang terpasang sampai versi terbaru.
     * Contoh: terpasang 15, terbaru 19 -> catatan v16, v17, v18, v19. Bila sudah terbaru, tampil catatan versi terpasang.
     * Rilis lama tanpa daftar changelog memakai catatan tunggal ("notes").
     */
    private fun composeNotes(o: JSONObject, latest: Int): String {
        val single = o.optString("notes").trim()
        val arr = o.optJSONArray("changelog") ?: return single
        val all = (0 until arr.length()).mapNotNull {
            val e = arr.optJSONObject(it) ?: return@mapNotNull null
            val n = e.optString("n").trim()
            if (n.isEmpty()) null else e.optInt("v") to n
        }.sortedBy { it.first }
        val installed = BuildConfig.VERSION_CODE
        val fresh = all.filter { it.first in (installed + 1)..latest }
        val pick = when {
            fresh.isNotEmpty() -> fresh
            else -> all.filter { it.first == latest }
        }
        if (pick.isEmpty()) return single
        return pick.joinToString("\n") { "v${it.first}: ${it.second}" }
    }

    /** Ambil info pembaruan (thread latar). Melempar Exception bila gagal. */
    fun fetch(): Info {
        val custom = BuildConfig.UPDATE_URL.trim()
        val first = if (custom.startsWith("https://")) custom else DEFAULT_INFO_URL
        try {
            val o = JSONObject(get(first))
            val u = o.getString("url")
            if (!u.endsWith("/flooww.apk")) error("Bukan berkas flooww.apk.")
            val ver = o.getInt("version")
            return Info(ver, u, composeNotes(o, ver), o.optString("sha256").lowercase())
        } catch (e: Exception) {
            // update.json belum ada di rilis: baca langsung dari API rilis GitHub
        }
        val o = JSONObject(get(API_LATEST))
        val tag = o.optString("tag_name")
        // hanya rilis flooww (tag v10, v11, ...) dan hanya berkas flooww.apk; rilis atau APK aplikasi admin diabaikan
        val ver = Regex("^v(\\d+)$").find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: error("Rilis terbaru bukan rilis flooww.")
        val assets = o.optJSONArray("assets")
        var url = ""
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name") == "flooww.apk") {
                    url = a.optString("browser_download_url")
                    break
                }
            }
        }
        if (url.isEmpty()) error("Rilis terbaru belum berisi flooww.apk.")
        return Info(ver, url, o.optString("body").trim(), "")
    }

    /**
     * Unduh APK ke cache (thread latar). onProgress menerima persen 0..100.
     * Unduhan bisa dilanjutkan: bila koneksi putus atau lambat sampai timeout, diulang otomatis (sampai 6 kali)
     * dari byte terakhir, tidak dari nol lagi.
     */
    fun download(c: Context, info: Info, onProgress: (Int) -> Unit): File {
        require(info.url.startsWith("https://")) { "Alamat unduhan harus https." }
        val dir = File(c.cacheDir, "shared").apply { mkdirs() }
        val f = File(dir, "update.apk")
        val part = File(dir, "update.apk.part")
        f.delete()
        var lastErr: Exception? = null
        var ok = false
        for (attempt in 1..6) {
            try {
                fetchInto(part, info.url, onProgress)
                ok = true
                break
            } catch (e: java.io.IOException) {
                lastErr = e
                Thread.sleep(1500L)
            }
        }
        if (!ok) error("Unduhan terputus: ${lastErr?.message ?: "koneksi lambat"}. Periksa koneksi lalu coba lagi.")
        if (info.sha256.isNotEmpty()) {
            val md = MessageDigest.getInstance("SHA-256")
            part.inputStream().use { ins ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (hex != info.sha256) {
                part.delete()
                error("Berkas unduhan rusak (sidik jari tidak cocok).")
            }
        }
        if (!part.renameTo(f)) error("Gagal menyimpan berkas unduhan.")
        return f
    }

    /** Satu percobaan unduh. Melanjutkan dari ukuran berkas .part bila server mendukung Range. */
    private fun fetchInto(part: File, url: String, onProgress: (Int) -> Unit) {
        val have = if (part.exists()) part.length() else 0L
        val h = open(url)
        h.readTimeout = 30000
        h.setRequestProperty("Accept", "application/octet-stream")
        h.setRequestProperty("Accept-Encoding", "identity")
        if (have > 0) h.setRequestProperty("Range", "bytes=$have-")
        try {
            val code = h.responseCode
            if (code == 416) {
                // berkas .part tidak cocok dengan server: mulai dari awal
                part.delete()
                throw java.io.IOException("ulang dari awal")
            }
            if (code != 200 && code != 206) error("Unduhan gagal ($code).")
            val resumed = code == 206 && have > 0
            val len = h.contentLengthLong
            val total = if (len > 0) (if (resumed) have + len else len) else -1L
            var done = if (resumed) have else 0L
            var last = -1
            h.inputStream.use { ins ->
                java.io.FileOutputStream(part, resumed).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != last) {
                                last = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
            if (total > 0 && done < total) throw java.io.IOException("terputus di ${done * 100 / total}%")
        } finally {
            h.disconnect()
        }
    }

    /**
     * Unduhan selesai saat flooww tidak sedang di layar: Android 10+ melarang aplikasi di latar membuka jendela pemasang,
     * jadi pemasang tidak pernah muncul dan klik otomatis tidak terjadi. Kirim notifikasi; ketukan pengguna membuka pemasang.
     */
    fun notifyReady(c: Context, f: File, version: Int): Boolean {
        return try {
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.createNotificationChannel(
                android.app.NotificationChannel("update", "Pembaruan flooww", android.app.NotificationManager.IMPORTANCE_HIGH)
            )
            val uri = FileProvider.getUriForFile(c, "${c.packageName}.files", f)
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = android.app.PendingIntent.getActivity(
                c, 7, i, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val n = android.app.Notification.Builder(c, "update")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("flooww versi $version siap dipasang")
                .setContentText("Ketuk untuk memasang. Tombol Pasang ditekan otomatis bila layanan aksesibilitas aktif.")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(7, n)
            // pengguna mungkin mengetuk beberapa menit kemudian: perpanjang jendela klik otomatis
            ClickService.armAutoInstall(10 * 60_000L, c)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Buka pemasang paket. Mengembalikan false bila izin "pasang aplikasi" belum diberikan (halaman izin dibuka). */
    fun install(c: Context, f: File): Boolean {
        if (!c.packageManager.canRequestPackageInstalls()) {
            c.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.files", f)
        // bila layanan aksesibilitas aktif, tombol Pasang di jendela pemasang ditekan otomatis (hanya untuk flooww)
        if (!ClickService.armAutoInstall(c = c)) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(
                    c, "Layanan aksesibilitas flooww mati, tombol Pasang tidak bisa ditekan otomatis. Nyalakan lagi di Pengaturan > Aksesibilitas.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
        c.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }
}
