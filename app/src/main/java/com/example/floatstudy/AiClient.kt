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

/** Klien Gemini. Hanya dipakai oleh Chat dan Auto Jawab. */
object AiClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    const val CHAT_SYSTEM =
        "Kamu tutor belajar yang sabar. Jawab dalam bahasa Indonesia. " +
            "Jelaskan konsep dan langkah penyelesaian secara runtut supaya siswa paham, " +
            "lalu beri jawaban akhir. Pakai teks biasa tanpa format markdown berat."

    /**
     * turns: pasangan (peran, teks) dengan peran "user" atau "model".
     * Gambar (bila ada) dilampirkan pada giliran terakhir.
     * json = true meminta keluaran JSON dan sedikit berpikir untuk ketelitian.
     */
    fun generate(
        ctx: Context,
        turns: List<Pair<String, String>>,
        imageB64: String?,
        system: String,
        json: Boolean,
        cb: (Result<String>) -> Unit
    ) {
        val m = Prefs.model(ctx)
        // urutan percobaan: model pilihan (dua kali), lalu model cadangan bila server penuh
        val queue = listOf(m, "gemini-3.6-flash", "gemini-3.5-flash-lite").distinct()
        generate(ctx, turns, imageB64, system, json, true, queue, cb)
    }

    /** Dipanggil tiap kali sebuah model dicoba (untuk tampilan "Mencoba …"). */
    @Volatile
    var progress: ((String) -> Unit)? = null

    private fun generate(
        ctx: Context,
        turns: List<Pair<String, String>>,
        imageB64: String?,
        system: String,
        json: Boolean,
        think: Boolean,
        queue: List<String>,
        cb: (Result<String>) -> Unit
    ) {
        val key = Prefs.key(ctx)
        if (key.isBlank()) {
            cb(Result.failure(AiError(401, "API key Gemini belum diisi. Buka aplikasi flooww, bagian API key.")))
            return
        }
        val model = queue.first()
        progress?.invoke(model)

        val contents = JSONArray()
        turns.forEachIndexed { i, (role, text) ->
            val parts = JSONArray()
            if (imageB64 != null && i == turns.lastIndex) {
                parts.put(
                    JSONObject().put(
                        "inline_data",
                        JSONObject().put("mime_type", "image/jpeg").put("data", imageB64)
                    )
                )
            }
            parts.put(JSONObject().put("text", text))
            contents.put(JSONObject().put("role", role).put("parts", parts))
        }

        val gen = JSONObject()
            .put("temperature", if (json) 0.2 else 0.7)
            .put("maxOutputTokens", 8192)
        if (json) gen.put("responseMimeType", "application/json")
        if (think && Regex("gemini-(3|4|5)").containsMatchIn(model)) {
            // seri 3 ke atas: tingkat berpikir rendah agar token tidak habis untuk berpikir dan jawaban cepat
            gen.put("thinkingConfig", JSONObject().put("thinkingLevel", if (json) "low" else "minimal"))
        } else if (model.contains("2.5")) {
            val budget = when {
                json -> 1024
                model.contains("pro") -> 512
                else -> 0
            }
            gen.put("thinkingConfig", JSONObject().put("thinkingBudget", budget))
        }

        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .put("generationConfig", gen)

        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        send(req, 0) { r ->
            val e = r.exceptionOrNull()
            // sebagian model menolak pengaturan berpikir: ulangi tanpa itu
            if (think && e is AiError && e.code == 400 && e.message.orEmpty().contains("think", ignoreCase = true)) {
                generate(ctx, turns, imageB64, system, json, false, queue, cb)
            } else if (e is AiError && retryable(e) && queue.size > 1) {
                // server penuh atau model tidak ada: tunggu sebentar lalu coba berikutnya
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    generate(ctx, turns, imageB64, system, json, think, queue.drop(1), cb)
                }, 600)
            } else cb(r)
        }
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
                cb(Result.failure(AiError(0, "Gagal terhubung ke Gemini: $why. Pastikan izin baterai flooww 'Tanpa batasan'.")))
            }

            override fun onResponse(call: Call, response: Response) {
                val result: Result<String> = response.use { r ->
                    val raw = r.body?.string().orEmpty()
                    try {
                        val j = JSONObject(raw)
                        if (!r.isSuccessful) {
                            val msg = j.optJSONObject("error")?.optString("message").orEmpty()
                            Result.failure(AiError(r.code, friendly(r.code, msg)))
                        } else {
                            val cands = j.optJSONArray("candidates")
                            val cand = cands?.optJSONObject(0)
                            val parts = cand?.optJSONObject("content")?.optJSONArray("parts")
                            val sb = StringBuilder()
                            if (parts != null) {
                                for (n in 0 until parts.length()) {
                                    val p = parts.getJSONObject(n)
                                    if (!p.optBoolean("thought", false)) sb.append(p.optString("text"))
                                }
                            }
                            val txt = sb.toString().trim()
                            if (txt.isNotEmpty()) Result.success(txt)
                            else {
                                val why = cand?.optString("finishReason").orEmpty()
                                    .ifBlank { j.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty() }
                                    .ifBlank { "kosong" }
                                Result.failure(AiError(r.code, "Gemini tidak memberi jawaban (alasan: $why). Coba lagi atau persempit area soal."))
                            }
                        }
                    } catch (e: Exception) {
                        Result.failure(AiError(r.code, "Respons tidak terbaca (HTTP ${r.code}): ${raw.take(120)}"))
                    }
                }
                cb(result)
            }
        })
    }

    private fun retryable(e: AiError): Boolean =
        e.code in listOf(0, 404, 429, 500, 502, 503, 504) ||
            e.message.orEmpty().contains("high demand", ignoreCase = true) ||
            e.message.orEmpty().contains("overloaded", ignoreCase = true)

    private fun friendly(code: Int, msg: String): String = when {
        code in listOf(500, 502, 503, 504) || msg.contains("high demand", ignoreCase = true) ->
            "Server Gemini sedang penuh (bukan masalah internetmu). Sudah dicoba ulang otomatis. Coba lagi beberapa detik lagi."
        code == 404 || msg.contains("no longer available", ignoreCase = true) ->
            "Model Gemini tidak tersedia untuk key ini. Ganti nama model di pengaturan flooww (mis. gemini-3.8-flash)."
        code == 429 -> "Kuota Gemini habis atau terlalu cepat. Tunggu sebentar atau ganti API key."
        code == 400 && msg.contains("API key", ignoreCase = true) -> "API key Gemini tidak valid. Periksa di pengaturan aplikasi."
        code == 401 || code == 403 -> "API key Gemini ditolak. Periksa di pengaturan aplikasi."
        msg.isNotBlank() -> msg
        else -> "HTTP $code"
    }
}
