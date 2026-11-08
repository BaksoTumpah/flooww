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
 * Pembaruan di dalam aplikasi. Alamat berkas info (BuildConfig.UPDATE_URL) berisi JSON:
 * {"version":10,"url":"https://.../flooww-10.apk","notes":"...","sha256":"(opsional)"}.
 * Versi hanya angka bulat. Lisensi membatasi versi tertinggi yang boleh dipasang.
 */
object Updater {
    class Info(val version: Int, val url: String, val notes: String, val sha256: String)

    private fun open(u: String): HttpURLConnection {
        val h = URL(u).openConnection() as HttpURLConnection
        h.connectTimeout = 10000
        h.readTimeout = 20000
        h.useCaches = false
        h.instanceFollowRedirects = true
        return h
    }

    /** Ambil info pembaruan (thread latar). Melempar Exception bila gagal. */
    fun fetch(): Info {
        val u = BuildConfig.UPDATE_URL.trim()
        require(u.startsWith("https://")) { "Alamat pembaruan belum diatur." }
        val h = open(u)
        val body = try {
            if (h.responseCode != 200) error("Server pembaruan menjawab ${h.responseCode}.")
            h.inputStream.bufferedReader().use { it.readText() }
        } finally {
            h.disconnect()
        }
        val o = JSONObject(body)
        return Info(o.getInt("version"), o.getString("url"), o.optString("notes"), o.optString("sha256").lowercase())
    }

    /** Unduh APK ke cache (thread latar). onProgress menerima persen 0..100. */
    fun download(c: Context, info: Info, onProgress: (Int) -> Unit): File {
        require(info.url.startsWith("https://")) { "Alamat unduhan harus https." }
        val dir = File(c.cacheDir, "shared").apply { mkdirs() }
        val f = File(dir, "update.apk")
        val h = open(info.url)
        try {
            if (h.responseCode != 200) error("Unduhan gagal (${h.responseCode}).")
            val total = h.contentLengthLong
            val md = MessageDigest.getInstance("SHA-256")
            h.inputStream.use { ins ->
                f.outputStream().use { out ->
                    val buf = ByteArray(16 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
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
            if (info.sha256.isNotEmpty()) {
                val hex = md.digest().joinToString("") { "%02x".format(it) }
                if (hex != info.sha256) {
                    f.delete()
                    error("Berkas unduhan rusak (sidik jari tidak cocok).")
                }
            }
        } finally {
            h.disconnect()
        }
        return f
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
        c.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }
}
