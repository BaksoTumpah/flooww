package com.example.floowwadmin

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Satu lisensi yang sudah diterbitkan. */
class Lic(
    val id: String,
    val name: String,
    val phone: String,
    val school: String,
    val cls: String,
    val device: String,
    val issued: Long,
    val maxV: Int,
    val token: String,
    /** Kedaluwarsa (ms sejak epoch). 0 = jangan pernah. */
    val expires: Long = 0L
)

/** Satu rilis flooww. Versi hanya angka bulat (1, 2, 3, ...), naik satu per rilis. */
class Rel(val code: Int, val notes: String, val date: Long, val url: String = "")

/** Penyimpanan data admin di HP ini, plus tanda tangan lisensi dan PIN. */
object Store {
    /** Kunci publik yang tertanam di flooww. Kunci rahasia yang diimpor harus berpasangan dengannya. */
    private const val PUB = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEl91fZh7A+IfYZ90DjP5+v23wtuVJx7xfpCQWBclEFNxYCH+0V35rAr7Kwtb/6wI9xRcdG+WLFPs19PhIFmWc0Q=="
    const val FIRST_RELEASE_CODE = 9

    private fun sp(c: Context) = c.getSharedPreferences("admin", Context.MODE_PRIVATE)
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)
    private fun b64u(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    // ---------- PIN ----------

    private fun hashPin(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)).encoded

    fun hasPin(c: Context) = sp(c).contains("pinHash")

    fun setPin(c: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        sp(c).edit().putString("pinSalt", b64(salt)).putString("pinHash", b64(hashPin(pin, salt))).apply()
    }

    fun checkPin(c: Context, pin: String): Boolean {
        val salt = unb64(sp(c).getString("pinSalt", "") ?: return false)
        val h = unb64(sp(c).getString("pinHash", "") ?: return false)
        return java.security.MessageDigest.isEqual(h, hashPin(pin, salt))
    }

    var failures: Int
        get() = lastCtx?.let { sp(it).getInt("fail", 0) } ?: 0
        set(v) { lastCtx?.let { sp(it).edit().putInt("fail", v).apply() } }
    var lockUntil: Long
        get() = lastCtx?.let { sp(it).getLong("lockUntil", 0) } ?: 0
        set(v) { lastCtx?.let { sp(it).edit().putLong("lockUntil", v).apply() } }
    private var lastCtx: Context? = null
    fun init(c: Context) { lastCtx = c.applicationContext }

    // ---------- kunci penandatangan ----------

    fun hasKey(c: Context) = sp(c).contains("priv")

    /** Impor kunci rahasia (PKCS8 base64). Ditolak bila tidak berpasangan dengan kunci publik di flooww. */
    fun importKey(c: Context, text: String): String? {
        val t = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("KUNCI") && !it.startsWith("(") }
            .joinToString("").filter { !it.isWhitespace() }
        return try {
            val priv = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.decode(t, Base64.DEFAULT)))
            val s = Signature.getInstance("SHA256withECDSA")
            s.initSign(priv)
            val msg = "tes".toByteArray()
            s.update(msg)
            val sig = s.sign()
            val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(PUB, Base64.DEFAULT)))
            val v = Signature.getInstance("SHA256withECDSA")
            v.initVerify(pub)
            v.update(msg)
            if (!v.verify(sig)) "Kunci ini tidak berpasangan dengan kunci publik di flooww." else {
                sp(c).edit().putString("priv", t).apply()
                null
            }
        } catch (e: Exception) {
            "Kunci tidak valid. Tempel seluruh teks kunci."
        }
    }

    // ---------- akun Google pemilik ----------

    fun owner(c: Context): String? = sp(c).getString("owner", null)
    fun setOwner(c: Context, email: String) = sp(c).edit().putString("owner", email.lowercase()).apply()

    // ---------- rilis ----------

    /** Isi berkas update.json yang dibaca flooww untuk fitur "Cek pembaruan". */
    fun updateJson(r: Rel): String = JSONObject().put("version", r.code).put("url", r.url).put("notes", r.notes).toString(2)

    /** Repo rilis publik (hanya berisi APK). Nomor versi diambil dari nama tag rilis terbaru, mis. v10. */
    private const val API_LATEST = "https://api.github.com/repos/BaksoTumpah/flooww-rilis/releases/latest"

    /** Baca rilis terbaru dari GitHub (thread latar). Melempar Exception bila gagal. */
    fun fetchLatest(): Rel {
        val h = java.net.URL(API_LATEST).openConnection() as java.net.HttpURLConnection
        h.connectTimeout = 10000
        h.readTimeout = 15000
        h.useCaches = false
        h.setRequestProperty("Accept", "application/json")
        try {
            if (h.responseCode != 200) error("GitHub menjawab ${h.responseCode}.")
            val o = JSONObject(h.inputStream.bufferedReader().use { it.readText() })
            val v = Regex("\\d+").find(o.optString("tag_name"))?.value?.toIntOrNull() ?: error("Nama rilis tidak berisi nomor versi.")
            var url = ""
            val assets = o.optJSONArray("assets")
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", true)) { url = a.optString("browser_download_url"); break }
            }
            return Rel(v, o.optString("body").trim().ifEmpty { o.optString("name") }, System.currentTimeMillis(), url)
        } finally {
            h.disconnect()
        }
    }

    /** Sinkron daftar rilis lokal dengan rilis terbaru di GitHub. Mengembalikan rilis terbaru, atau null bila sudah ada. */
    fun syncLatest(c: Context): Rel? {
        val r = fetchLatest()
        val cur = releases(c)
        if (cur.any { it.code == r.code }) return null
        setReleases(c, cur + r)
        return r
    }

    fun releases(c: Context): List<Rel> {
        val raw = sp(c).getString("releases", null)
        if (raw == null) {
            val l = listOf(Rel(FIRST_RELEASE_CODE, "Trial 10 menit, lisensi, update dalam aplikasi", System.currentTimeMillis()))
            setReleases(c, l)
            return l
        }
        val a = JSONArray(raw)
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Rel(o.getInt("c"), o.optString("x"), o.optLong("d"), o.optString("u"))
        }.sortedByDescending { it.code }
    }

    fun setReleases(c: Context, l: List<Rel>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("c", it.code).put("x", it.notes).put("d", it.date).put("u", it.url)) }
        sp(c).edit().putString("releases", a.toString()).apply()
    }

    fun freeUpdates(c: Context) = sp(c).getInt("free", 2)
    fun setFreeUpdates(c: Context, n: Int) = sp(c).edit().putInt("free", n.coerceIn(0, 50)).apply()

    // ---------- lisensi ----------

    fun licenses(c: Context): List<Lic> {
        val a = JSONArray(sp(c).getString("licenses", "[]"))
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Lic(
                o.getString("id"), o.optString("n"), o.optString("p"), o.optString("s"), o.optString("c"),
                o.getString("d"), o.getLong("t"), o.optInt("m"), o.getString("k"), o.optLong("e", 0L)
            )
        }.sortedByDescending { it.issued }
    }

    fun setLicenses(c: Context, l: List<Lic>) {
        val a = JSONArray()
        l.forEach {
            a.put(JSONObject().put("id", it.id).put("n", it.name).put("p", it.phone).put("s", it.school).put("c", it.cls)
                .put("d", it.device).put("t", it.issued).put("m", it.maxV).put("k", it.token).put("e", it.expires))
        }
        sp(c).edit().putString("licenses", a.toString()).apply()
    }

    /** Rapikan kode perangkat menjadi AAAA-BBBB-CCCC. null bila bukan 12 karakter heksadesimal. */
    fun normDevice(s: String): String? {
        val t = s.uppercase().filter { it.isLetterOrDigit() }
        if (t.length != 12 || !t.all { it in '0'..'9' || it in 'A'..'F' }) return null
        return t.chunked(4).joinToString("-")
    }

    private fun newId(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val r = SecureRandom()
        return (1..6).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }

    /** Buat dan tanda tangani lisensi. Berlaku sampai versi = rilis terbaru + jumlah update gratis. */
    fun issue(c: Context, name: String, phone: String, school: String, cls: String, device: String, free: Int, expires: Long = 0L): Lic {
        val priv = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.decode(sp(c).getString("priv", "")!!, Base64.DEFAULT)))
        val latest = releases(c).maxOfOrNull { it.code } ?: FIRST_RELEASE_CODE
        val maxV = latest + free
        val id = newId()
        val now = System.currentTimeMillis()
        val payload = JSONObject().put("v", 1).put("id", id).put("n", name).put("p", phone).put("s", school).put("c", cls)
            .put("d", device).put("t", now).put("m", maxV).put("e", expires).toString().toByteArray(Charsets.UTF_8)
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(priv)
        s.update(payload)
        val token = b64u(payload) + "." + b64u(s.sign())
        return Lic(id, name, phone, school, cls, device, now, maxV, token, expires)
    }

    // ---------- cadangan terenkripsi ----------

    private fun aesKey(password: String, salt: ByteArray) = SecretKeySpec(
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password.toCharArray(), salt, 120_000, 256)).encoded, "AES"
    )

    fun exportBackup(c: Context, password: String): String {
        val data = JSONObject()
            .put("priv", sp(c).getString("priv", ""))
            .put("licenses", JSONArray(sp(c).getString("licenses", "[]")))
            .put("releases", JSONArray(sp(c).getString("releases", "[]")))
            .put("free", freeUpdates(c)).toString().toByteArray(Charsets.UTF_8)
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val ci = Cipher.getInstance("AES/GCM/NoPadding")
        ci.init(Cipher.ENCRYPT_MODE, aesKey(password, salt), GCMParameterSpec(128, iv))
        return JSONObject().put("app", "flow-admin").put("v", 1).put("salt", b64(salt)).put("iv", b64(iv))
            .put("data", b64(ci.doFinal(data))).toString()
    }

    /** Pulihkan cadangan. Mengembalikan pesan galat, atau null bila berhasil. */
    fun importBackup(c: Context, text: String, password: String): String? {
        try {
            val o = JSONObject(text)
            if (o.optString("app") != "flow-admin") return "Berkas ini bukan cadangan flow admin."
            val ci = Cipher.getInstance("AES/GCM/NoPadding")
            ci.init(Cipher.DECRYPT_MODE, aesKey(password, unb64(o.getString("salt"))), GCMParameterSpec(128, unb64(o.getString("iv"))))
            val d = JSONObject(String(ci.doFinal(unb64(o.getString("data"))), Charsets.UTF_8))
            val priv = d.optString("priv")
            if (priv.isNotBlank()) {
                val err = importKey(c, priv)
                if (err != null) return err
            }
            sp(c).edit().putString("licenses", d.getJSONArray("licenses").toString())
                .putString("releases", d.getJSONArray("releases").toString())
                .putInt("free", d.optInt("free", 2)).apply()
            return null
        } catch (e: Exception) {
            return "Kata sandi salah atau berkas rusak."
        }
    }
}
