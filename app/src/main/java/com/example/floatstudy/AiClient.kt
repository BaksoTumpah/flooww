package com.example.floatstudy

import android.content.Context
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class AiError(val code: Int, message: String) : Exception(message)

/** Klien API kompatibel OpenAI (Gemini, OpenRouter, Groq, atau alamat sendiri). Hanya dipakai oleh Chat dan Auto Jawab. */
object AiClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    const val CHAT_SYSTEM =
        "Kamu tutor belajar yang sabar. Jawab dalam bahasa Indonesia. " +
            "Jelaskan konsep dan langkah penyelesaian secara runtut supaya siswa paham, " +
            "lalu beri jawaban akhir. Pakai teks biasa tanpa format markdown berat."

    /**
     * turns: pasangan (peran, teks) dengan peran "user" atau "model".
     * json = true meminta keluaran JSON.
     */
    fun generate(
        ctx: Context,
        turns: List<Pair<String, String>>,
        system: String,
        json: Boolean,
        imageB64: String? = null,
        cb: (Result<String>) -> Unit
    ) {
        val key = Prefs.key(ctx)
        if (key.isBlank()) {
            cb(Result.failure(AiError(401, "API key ${Prefs.providerName(ctx)} belum diisi. Buka aplikasi flooww, bagian API key.")))
            return
        }
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        turns.forEachIndexed { i, (role, text) ->
            val m = JSONObject().put("role", if (role == "model") "assistant" else "user")
            if (imageB64 != null && i == turns.lastIndex) {
                // gambar dilampirkan pada giliran terakhir (model gambar)
                m.put(
                    "content",
                    JSONArray()
                        .put(JSONObject().put("type", "text").put("text", text))
                        .put(
                            JSONObject().put("type", "image_url").put(
                                "image_url", JSONObject().put("url", "data:image/jpeg;base64,$imageB64")
                            )
                        )
                )
            } else m.put("content", text)
            msgs.put(m)
        }
        val body = JSONObject()
            .put("model", if (imageB64 != null) Prefs.visionModel(ctx) else Prefs.model(ctx))
            .put("messages", msgs)
            .put("temperature", if (json) 0.1 else 0.6)
            .put("max_tokens", if (imageB64 != null) 6000 else 2048)
        if (json) body.put("response_format", JSONObject().put("type", "json_object"))

        val req = Request.Builder()
            .url("${Prefs.base(ctx)}/chat/completions")
            .header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        send(req, 1, cb)
    }

    private fun send(req: Request, retries: Int, cb: (Result<String>) -> Unit) {
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (retries > 0 && !call.isCanceled()) {
                    send(req, retries - 1, cb)
                    return
                }
                val why = when (e) {
                    is java.net.UnknownHostException -> "alamat server tidak ketemu (DNS). Matikan Private DNS/VPN atau coba ganti jaringan"
                    is java.net.SocketTimeoutException -> "waktu habis, jaringan lambat"
                    is javax.net.ssl.SSLException -> "gagal koneksi aman (cek jam HP, VPN)"
                    else -> e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
                }
                cb(Result.failure(AiError(0, "Gagal terhubung ke server AI: $why. Pastikan izin baterai flooww 'Tanpa batasan'.")))
            }

            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                val retryAfter = response.header("retry-after")
                val raw = response.use { it.body?.string().orEmpty() }
                if (code in listOf(500, 502, 503, 504) && retries > 0) {
                    send(req, retries - 1, cb)
                    return
                }
                val result: Result<String> = try {
                    val j = JSONObject(raw)
                    if (code !in 200..299) {
                        val msg = j.optJSONObject("error")?.optString("message").orEmpty()
                        Result.failure(AiError(code, friendly(code, msg, retryAfter)))
                    } else {
                        val ch = j.optJSONArray("choices")?.optJSONObject(0)
                        // sebagian model berpikir menaruh <think>…</think> di isi jawaban: dibuang
                        val txt = ch?.optJSONObject("message")?.optString("content").orEmpty()
                            .replace(Regex("(?s)<think>.*?</think>"), "").trim()
                        if (txt.isNotEmpty()) Result.success(txt)
                        else Result.failure(
                            AiError(code, "AI tidak memberi jawaban (alasan: ${ch?.optString("finish_reason").orEmpty().ifBlank { "kosong" }}).")
                        )
                    }
                } catch (e: Exception) {
                    Result.failure(AiError(code, "Respons tidak terbaca (HTTP $code): ${raw.take(120)}"))
                }
                cb(result)
            }
        })
    }

    private fun friendly(code: Int, msg: String, retryAfter: String?): String = when {
        code == 401 || code == 403 -> "API key ditolak. Periksa key di pengaturan aplikasi."
        code == 404 || msg.contains("decommissioned", true) || msg.contains("does not exist", true) ->
            "Model tidak tersedia. Ganti nama model di pengaturan flooww."
        code == 429 -> "Batas pemakaian gratis tercapai" + (retryAfter?.let { ", coba lagi sekitar $it detik lagi" } ?: ", tunggu sebentar") + "."
        code in listOf(500, 502, 503, 504) -> "Server AI sedang sibuk atau bermasalah. Coba lagi sebentar lagi."
        msg.isNotBlank() -> msg
        else -> "HTTP $code"
    }

    /**
     * Pemeriksaan bertahap untuk layar "Tes koneksi": internet, server + key + daftar model, lalu satu jawaban.
     * Berjalan di thread sendiri; onLine dipanggil dari thread itu.
     */
    fun diagnose(ctx: Context, onLine: (String) -> Unit) {
        Thread {
            val probe = OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()
            fun get(url: String, key: String? = null): Triple<Int, String, Long> {
                val t0 = System.currentTimeMillis()
                return try {
                    val rb = Request.Builder().url(url)
                    if (key != null) rb.header("Authorization", "Bearer $key")
                    probe.newCall(rb.build()).execute().use { r ->
                        Triple(r.code, r.body?.string().orEmpty(), System.currentTimeMillis() - t0)
                    }
                } catch (e: Exception) {
                    Triple(-1, e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""), System.currentTimeMillis() - t0)
                }
            }
            val key = Prefs.key(ctx)
            val model = Prefs.model(ctx)
            onLine("Penyedia: ${Prefs.providerName(ctx)} (${Prefs.base(ctx)})")
            onLine("Model dipilih: $model")
            onLine("Key: " + if (key.isBlank()) "BELUM DIISI" else "terisi (…${key.takeLast(4)})")
            val net = get("https://www.google.com/generate_204")
            onLine("1. Internet: " + if (net.first in 200..399) "OK (${net.third} ms)" else "GAGAL ${net.second.take(80)}")
            val ls = get("${Prefs.base(ctx)}/models", key.ifBlank { null })
            if (ls.first == 200) {
                val names = try {
                    val arr = JSONObject(ls.second).getJSONArray("data")
                    (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }
                } catch (e: Exception) { emptyList() }
                onLine("2. Server + key: OK (${ls.third} ms)")
                onLine("   Model $model " + if (model in names) "tersedia" else "TIDAK ada untuk key ini")
                val vm = Prefs.visionModel(ctx)
                onLine("   Model gambar $vm " + if (vm in names) "tersedia" else "TIDAK ada untuk key ini")
                val chat = names.filter { !it.contains("whisper") && !it.contains("tts") && !it.contains("guard") }
                if (chat.isNotEmpty()) onLine("   Model yang ada: " + chat.take(8).joinToString(", "))
            } else {
                val msg = try { JSONObject(ls.second).optJSONObject("error")?.optString("message").orEmpty() } catch (e: Exception) { ls.second }
                onLine("2. Server: GAGAL HTTP ${ls.first} ${msg.take(120)} (${ls.third} ms)")
            }
            val t0 = System.currentTimeMillis()
            onLine("3. Meminta jawaban dari $model…")
            generate(ctx, listOf("user" to "Balas satu kata saja: OK"), "Jawab sangat singkat.", false, null) { r ->
                val ms = System.currentTimeMillis() - t0
                onLine("   " + r.fold({ "Berhasil ($ms ms): $it" }, { "GAGAL ($ms ms): ${it.message}" }))
                onLine("Selesai.")
            }
        }.start()
    }
}
