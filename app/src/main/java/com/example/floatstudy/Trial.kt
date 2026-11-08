package com.example.floatstudy

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Trial 10 menit, dimulai saat bubble pertama kali dihidupkan.
 *
 * Data trial disimpan di Firebase Realtime Database per kode perangkat (/trial/<kode>), jadi tidak hilang walau data
 * aplikasi dihapus atau aplikasi dicopot. Aturan database (firebase/database.rules.json) hanya mengizinkan data itu
 * dibuat sekali, dengan waktu server, dan tidak bisa diubah atau dihapus lagi.
 *
 * Sisa waktu dihitung dari waktu server (header Date HTTPS) dan jam monotonik perangkat (elapsedRealtime), yang tidak
 * terpengaruh pengubahan jam/tanggal HP. Trial butuh internet; tanpa sinkron server fitur tidak jalan.
 */
object Trial {
    const val DURATION = 10 * 60 * 1000L

    enum class Kind { ACTIVE, EXPIRED, ERROR }

    class Result(val kind: Kind, val message: String = "")

    @Volatile private var startMs = 0L
    @Volatile private var offset = Long.MIN_VALUE // waktu server - elapsedRealtime

    /** Kunci data di server: kode perangkat tanpa tanda hubung. */
    private fun key(c: Context) = License.deviceCode(c).filter { it != '-' }

    /** Sisa waktu (ms) menurut jam server. Nilai null bila belum pernah sinkron. */
    fun remaining(): Long? {
        if (offset == Long.MIN_VALUE || startMs == 0L) return null
        return startMs + DURATION - (SystemClock.elapsedRealtime() + offset)
    }

    fun reset() {
        startMs = 0L
        offset = Long.MIN_VALUE
    }

    private class Resp(val code: Int, val body: String, val serverNow: Long)

    private fun call(method: String, url: String, body: String? = null): Resp {
        val u = URL(url)
        val h = u.openConnection() as HttpURLConnection
        h.requestMethod = method
        h.connectTimeout = 8000
        h.readTimeout = 8000
        h.useCaches = false
        h.setRequestProperty("Cache-Control", "no-cache")
        if (body != null) {
            h.doOutput = true
            h.setRequestProperty("Content-Type", "application/json")
            h.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = h.responseCode
        val text = (if (code in 200..299) h.inputStream else h.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        val date = h.getHeaderField("Date")
        h.disconnect()
        var now = 0L
        if (date != null) {
            try {
                val f = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
                f.timeZone = TimeZone.getTimeZone("GMT")
                now = f.parse(date)?.time ?: 0L
            } catch (e: Exception) {
            }
        }
        return Resp(code, text, now)
    }

    /**
     * Sinkron dengan server (jalankan di thread latar). Pertama kali: membuat catatan trial dengan waktu server.
     * Selanjutnya: membaca waktu mulai yang tersimpan.
     */
    fun sync(c: Context): Result {
        // pernah berlisensi di perangkat ini: tidak ada trial baru walau catatan trial di awan sudah dihapus
        if (License.hadLicense(c)) return Result(Kind.EXPIRED)
        val base = BuildConfig.TRIAL_DB_URL.trim().trimEnd('/')
        if (base.isEmpty() || !base.startsWith("https://")) {
            return Result(Kind.ERROR, "Server trial belum diatur. Hubungi admin.")
        }
        val url = "$base/trial/${key(c)}.json"
        return try {
            var r = call("GET", url)
            if (r.code != 200) return Result(Kind.ERROR, "Server trial menolak (${r.code}). Coba lagi nanti.")
            if (r.body.trim() == "null") {
                // belum pernah trial: catat sekali dengan waktu server
                val w = call("PUT", url, "{\"start\":{\".sv\":\"timestamp\"}}")
                if (w.code !in 200..299 && w.code != 401 && w.code != 403) {
                    return Result(Kind.ERROR, "Gagal memulai trial (${w.code}).")
                }
                r = call("GET", url)
                if (r.code != 200 || r.body.trim() == "null") return Result(Kind.ERROR, "Gagal memulai trial.")
            }
            val start = JSONObject(r.body).optLong("start", 0L)
            if (start <= 0L) return Result(Kind.ERROR, "Data trial rusak.")
            if (r.serverNow <= 0L) return Result(Kind.ERROR, "Waktu server tidak terbaca.")
            License.noteServerTime(r.serverNow)
            startMs = start
            offset = r.serverNow - SystemClock.elapsedRealtime()
            val rem = remaining() ?: 0L
            if (rem > 0) Result(Kind.ACTIVE) else {
                markExpired(c)
                Result(Kind.EXPIRED)
            }
        } catch (e: Exception) {
            Result(Kind.ERROR, "Trial butuh internet untuk diverifikasi.")
        }
    }

    /**
     * Baca status trial tanpa memulai trial baru (hanya GET). Thread latar. Untuk menampilkan hitung mundur setelah
     * aplikasi dibuka ulang. Mengembalikan ACTIVE atau EXPIRED bila catatan trial ada, null bila belum mulai atau gagal.
     */
    fun peek(c: Context): Kind? {
        val base = BuildConfig.TRIAL_DB_URL.trim().trimEnd('/')
        if (!base.startsWith("https://")) return null
        return try {
            val r = call("GET", "$base/trial/${key(c)}.json")
            if (r.code != 200 || r.body.trim() == "null") return null
            val start = JSONObject(r.body).optLong("start", 0L)
            if (start <= 0L || r.serverNow <= 0L) return null
            License.noteServerTime(r.serverNow)
            startMs = start
            offset = r.serverNow - SystemClock.elapsedRealtime()
            if ((remaining() ?: 0L) > 0) Kind.ACTIVE else {
                markExpired(c)
                Kind.EXPIRED
            }
        } catch (e: Exception) {
            null
        }
    }

    fun markExpired(c: Context) {
        Prefs.sp(c).edit().putBoolean("trial_expired", true).apply()
    }

    /** Aplikasi harus terkunci di halaman lisensi: trial habis, atau ada lisensi tersimpan yang tidak sah (habis, salah perangkat, dll). */
    fun mustLock(c: Context): Boolean =
        !License.ok(c) && (expiredHint(c) || License.state(c) != License.State.NONE || License.hadLicense(c))

    /** Ambil waktu server saja (untuk lisensi berkedaluwarsa). Thread latar. */
    fun syncTime() {
        val base = BuildConfig.TRIAL_DB_URL.trim().trimEnd('/')
        if (!base.startsWith("https://")) return
        try {
            License.noteServerTime(call("GET", "$base/trial/ping.json").serverNow)
        } catch (e: Exception) {
        }
    }

    /** Petunjuk lokal (bisa terhapus bersama data aplikasi; kebenaran tetap di server). */
    fun expiredHint(c: Context) = Prefs.sp(c).getBoolean("trial_expired", false)
}
