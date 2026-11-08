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

    /** Ambil info pembaruan (thread latar). Melempar Exception bila gagal. */
    fun fetch(): Info {
        val custom = BuildConfig.UPDATE_URL.trim()
        val first = if (custom.startsWith("https://")) custom else DEFAULT_INFO_URL
        try {
            val o = JSONObject(get(first))
            return Info(o.getInt("version"), o.getString("url"), o.optString("notes"), o.optString("sha256").lowercase())
        } catch (e: Exception) {
            // update.json belum ada di rilis: baca langsung dari API rilis GitHub
        }
        val o = JSONObject(get(API_LATEST))
        val tag = o.optString("tag_name")
        val ver = Regex("\\d+").find(tag)?.value?.toIntOrNull() ?: error("Nama rilis terbaru tidak berisi nomor versi.")
        val assets = o.optJSONArray("assets")
        var url = ""
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", true)) {
                    url = a.optString("browser_download_url")
                    break
                }
            }
        }
        if (url.isEmpty()) error("Rilis terbaru belum berisi berkas APK.")
        return Info(ver, url, o.optString("body").trim(), "")
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
