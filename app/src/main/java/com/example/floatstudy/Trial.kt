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
 * Trial 10 menit pemakaian, mulai saat bubble pertama kali dihidupkan.
 *
 * Timer hanya berjalan selama bubble menyala. Bubble dihentikan: timer berhenti dan sisa waktu disimpan.
 * Bubble dinyalakan lagi: timer lanjut dari sisa waktu tadi.
 *
 * Data trial disimpan di Firebase Realtime Database per kode perangkat (/trial/<kode>), jadi tidak hilang walau data
 * aplikasi dihapus atau aplikasi dicopot. Isinya: "start" (waktu server, hanya dibuat sekali) dan "used" (lama
 * pemakaian dalam ms, hanya boleh naik, maksimal 10 menit). Lihat firebase/database.rules.json.
 *
 * Pemakaian dihitung dari jam monotonik perangkat (elapsedRealtime), yang tidak terpengaruh pengubahan jam/tanggal HP,
 * dan dikirim ke server tiap sinkron (sekitar semenit sekali) serta saat bubble berhenti. Bila pengiriman gagal, nilai
 * disimpan di HP dan dikirim pada kesempatan berikutnya. Trial butuh internet untuk dimulai.
 *
 * Catatan kompatibilitas: catatan lama tanpa "used" (atau server yang aturannya belum diperbarui) dihitung seperti
 * dulu, yaitu waktu berjalan sejak "start", sehingga jeda belum berlaku sampai aturan database diperbarui.
 */
object Trial {
    const val DURATION = 10 * 60 * 1000L

    enum class Kind { ACTIVE, EXPIRED, ERROR }

    class Result(val kind: Kind, val message: String = "")

    @Volatile private var known = false // pemakaian sudah diketahui dari server
    @Volatile private var running = false // timer sedang berjalan (bubble menyala)
    @Volatile private var usedBase = 0L // pemakaian (ms) sampai saat timer terakhir dimulai
    @Volatile private var runStart = 0L // elapsedRealtime saat timer terakhir dimulai
    private var lastLocalSave = 0L

    private const val PENDING = "trial_used_pending"

    /** Kunci data di server: kode perangkat tanpa tanda hubung. */
    private fun key(c: Context) = License.deviceCode(c).filter { it != '-' }

    private fun usedNow(): Long =
        (usedBase + if (running) SystemClock.elapsedRealtime() - runStart else 0L).coerceIn(0L, DURATION)

    /** Sisa waktu (ms). Nilai null bila belum pernah sinkron. Tidak berkurang saat bubble berhenti. */
    fun remaining(): Long? {
        if (!known) return null
        return DURATION - usedNow()
    }

    fun reset() {
        known = false
        running = false
        usedBase = 0L
        runStart = 0L
    }

    /** Pemakaian dari catatan server. Catatan lama tanpa "used": waktu berjalan sejak start (perilaku lama). */
    private fun usedFrom(o: JSONObject, serverNow: Long, start: Long): Long {
        val u = if (o.has("used")) o.optLong("used", 0L) else (serverNow - start)
        return u.coerceIn(0L, DURATION)
    }

    /** Berhenti menghitung waktu (bubble dihentikan). Sisa waktu disimpan di HP dan dikirim ke server. */
    fun pause(c: Context) {
        if (!running) return
        usedBase = usedNow()
        running = false
        Prefs.sp(c).edit().putLong(PENDING, usedBase).apply()
        Thread { saveUsed(c) }.start()
    }

    /** Simpan pemakaian di HP tiap beberapa detik selagi timer berjalan, supaya tidak hilang bila aplikasi dimatikan paksa. */
    fun tick(c: Context) {
        if (!running) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastLocalSave < 10_000L) return
        lastLocalSave = now
        Prefs.sp(c).edit().putLong(PENDING, usedNow()).apply()
    }

    /** Kirim pemakaian ke server (thread latar). Bila gagal, nilai tetap tersimpan di HP. */
    private fun saveUsed(c: Context) {
        val used = usedNow()
        Prefs.sp(c).edit().putLong(PENDING, used).apply()
        val base = BuildConfig.TRIAL_DB_URL.trim().trimEnd('/')
        if (!base.startsWith("https://")) return
        try {
            val r = call("PUT", "$base/trial/${key(c)}/used.json", used.toString())
            if (r.serverNow > 0L) License.noteServerTime(r.serverNow)
        } catch (e: Exception) {
        }
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
        if (running) {
            // timer sudah berjalan (sinkron berkala): kirim pemakaian terbaru, jangan mulai ulang
            saveUsed(c)
            return if ((remaining() ?: 0L) > 0L) Result(Kind.ACTIVE) else {
                markExpired(c)
                Result(Kind.EXPIRED)
            }
        }
        return try {
            var r = call("GET", url)
            if (r.code != 200) return Result(Kind.ERROR, "Server trial menolak (${r.code}). Coba lagi nanti.")
            if (r.body.trim() == "null") {
                // belum pernah trial: catat sekali dengan waktu server
                var w = call("PUT", url, "{\"start\":{\".sv\":\"timestamp\"},\"used\":0}")
                if (w.code == 401 || w.code == 403) {
                    // aturan database lama belum mengenal "used": buat catatan format lama
                    w = call("PUT", url, "{\"start\":{\".sv\":\"timestamp\"}}")
                }
                if (w.code !in 200..299 && w.code != 401 && w.code != 403) {
                    return Result(Kind.ERROR, "Gagal memulai trial (${w.code}).")
                }
                r = call("GET", url)
                if (r.code != 200 || r.body.trim() == "null") return Result(Kind.ERROR, "Gagal memulai trial.")
            }
            val o = JSONObject(r.body)
            val start = o.optLong("start", 0L)
            if (start <= 0L) return Result(Kind.ERROR, "Data trial rusak.")
            if (r.serverNow <= 0L) return Result(Kind.ERROR, "Waktu server tidak terbaca.")
            License.noteServerTime(r.serverNow)
            // pakai nilai terbesar antara server dan simpanan di HP (pengiriman terakhir mungkin gagal)
            usedBase = maxOf(usedFrom(o, r.serverNow, start), Prefs.sp(c).getLong(PENDING, 0L)).coerceIn(0L, DURATION)
            known = true
            runStart = SystemClock.elapsedRealtime()
            running = true
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
            val o = JSONObject(r.body)
            val start = o.optLong("start", 0L)
            if (start <= 0L || r.serverNow <= 0L) return null
            License.noteServerTime(r.serverNow)
            if (running) return if ((remaining() ?: 0L) > 0) Kind.ACTIVE else Kind.EXPIRED
            usedBase = maxOf(usedFrom(o, r.serverNow, start), Prefs.sp(c).getLong(PENDING, 0L)).coerceIn(0L, DURATION)
            known = true
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
