package com.example.floatstudy

import android.content.Context
import android.provider.Settings
import android.util.Base64
import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Lisensi flooww. Aplikasi admin (flow admin) menandatangani data pembeli dengan kunci rahasia (ECDSA P-256).
 * Aplikasi ini hanya menyimpan kunci publik, jadi hanya bisa memeriksa lisensi, tidak bisa membuatnya.
 * Lisensi terikat pada kode perangkat, sehingga tidak bisa dipakai di HP lain.
 */
object License {
    /** Kunci publik admin (X.509, base64). Pasangannya hanya ada di aplikasi flow admin. */
    private const val PUB = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEl91fZh7A+IfYZ90DjP5+v23wtuVJx7xfpCQWBclEFNxYCH+0V35rAr7Kwtb/6wI9xRcdG+WLFPs19PhIFmWc0Q=="

    class Info(
        val id: String,
        val name: String,
        val phone: String,
        val school: String,
        val cls: String,
        val device: String,
        val issued: Long,
        /** versionCode tertinggi yang boleh dipakai. 0 = tanpa batas. */
        val maxVersion: Int,
        /** Kedaluwarsa (ms sejak epoch). 0 = tanpa batas. */
        val expires: Long = 0L
    )

    enum class State { OK, NONE, BAD, WRONG_DEVICE, TOO_NEW, EXPIRED }

    /** Kode perangkat yang ditampilkan ke pembeli, contoh A1B2-C3D4-E5F6. */
    fun deviceCode(c: Context): String {
        val aid = Settings.Secure.getString(c.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        val h = MessageDigest.getInstance("SHA-256").digest(("flooww|" + aid).toByteArray())
        val hex = h.joinToString("") { "%02X".format(it) }.take(12)
        return hex.chunked(4).joinToString("-")
    }

    private fun norm(s: String) = s.uppercase().filter { it.isLetterOrDigit() }

    private fun b64d(s: String): ByteArray = Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    /** Periksa tanda tangan dan baca isinya. null bila bentuk atau tanda tangan salah. */
    fun parse(token: String): Info? = try {
        val t = token.trim().filter { !it.isWhitespace() }
        val i = t.indexOf('.')
        if (i <= 0) null else {
            val payload = b64d(t.substring(0, i))
            val sig = b64d(t.substring(i + 1))
            val pk = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(PUB, Base64.DEFAULT)))
            val v = Signature.getInstance("SHA256withECDSA")
            v.initVerify(pk)
            v.update(payload)
            if (!v.verify(sig)) null else {
                val o = JSONObject(String(payload, Charsets.UTF_8))
                Info(
                    o.optString("id"), o.optString("n"), o.optString("p"), o.optString("s"), o.optString("c"),
                    o.optString("d"), o.optLong("t"), o.optInt("m", 0), o.optLong("e", 0L)
                )
            }
        }
    } catch (e: Exception) {
        null
    }

    @Volatile private var srvOffset = Long.MIN_VALUE

    /** Catat waktu server (dari sinkron) agar jam HP yang dimundurkan tidak memperpanjang lisensi. */
    fun noteServerTime(serverNow: Long) {
        if (serverNow > 0) srvOffset = serverNow - android.os.SystemClock.elapsedRealtime()
    }

    /** Waktu tepercaya: waktu server bila sudah sinkron; selain itu jam HP yang tidak pernah lebih mundur dari yang pernah terlihat. */
    fun trustedNow(c: Context): Long {
        val sp = Prefs.sp(c)
        val seen = sp.getLong("seen_time", 0L)
        val t = if (srvOffset != Long.MIN_VALUE) srvOffset + android.os.SystemClock.elapsedRealtime()
        else maxOf(System.currentTimeMillis(), seen)
        if (t > seen) sp.edit().putLong("seen_time", t).apply()
        return t
    }

    fun state(c: Context): State {
        val tok = Prefs.sp(c).getString("license", "") ?: ""
        if (tok.isBlank()) return State.NONE
        val info = parse(tok) ?: return State.BAD
        if (norm(info.device) != norm(deviceCode(c))) return State.WRONG_DEVICE
        if (info.maxVersion in 1 until BuildConfig.VERSION_CODE) return State.TOO_NEW
        if (info.expires > 0 && trustedNow(c) > info.expires) return State.EXPIRED
        return State.OK
    }

    fun ok(c: Context): Boolean = state(c) == State.OK

    fun info(c: Context): Info? = parse(Prefs.sp(c).getString("license", "") ?: "")

    /** Simpan lisensi bila sah. Mengembalikan pesan galat, atau null bila berhasil. */
    fun activate(c: Context, token: String): String? {
        val info = parse(token) ?: return "Lisensi tidak valid. Salin ulang dari admin, jangan sampai terpotong."
        if (norm(info.device) != norm(deviceCode(c))) return "Lisensi ini untuk perangkat lain. Kirim kode perangkat ini ke admin."
        if (info.maxVersion in 1 until BuildConfig.VERSION_CODE) return "Lisensi tidak mencakup versi aplikasi ini. Hubungi admin."
        if (info.expires > 0 && trustedNow(c) > info.expires) return "Lisensi ini sudah kedaluwarsa. Hubungi admin untuk lisensi baru."
        Prefs.sp(c).edit().putString("license", token.trim().filter { !it.isWhitespace() }).apply()
        Prefs.setUserName(c, info.name)
        return null
    }

    fun message(s: State): String = when (s) {
        State.OK -> ""
        State.NONE -> "Aplikasi belum diaktifkan. Kirim kode perangkat ke admin, lalu tempel lisensi dari admin di sini."
        State.BAD -> "Lisensi tersimpan rusak. Tempel lisensi yang benar dari admin."
        State.WRONG_DEVICE -> "Lisensi ini bukan untuk perangkat ini. Kirim kode perangkat ke admin untuk lisensi baru."
        State.TOO_NEW -> "Lisensi tidak mencakup versi aplikasi ini. Hubungi admin untuk memperpanjang."
        State.EXPIRED -> "Lisensi sudah kedaluwarsa. Hubungi admin untuk memperpanjang."
    }
}
