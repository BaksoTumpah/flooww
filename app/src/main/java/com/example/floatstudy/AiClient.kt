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
        .callTimeout(90, TimeUnit.SECONDS)
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
        val key = Prefs.key(ctx)
        if (key.isBlank()) {
            cb(Result.failure(AiError(401, "API key Gemini belum diisi. Buka aplikasi flooww, bagian API key.")))
            return
        }
        val model = Prefs.model(ctx)

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
            .put("maxOutputTokens", 4096)
        if (json) gen.put("responseMimeType", "application/json")
        if (model.contains("2.5")) {
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

        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cb(Result.failure(AiError(0, "Tidak bisa terhubung. Periksa internet.")))
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
                            val cand = j.getJSONArray("candidates").getJSONObject(0)
                            val parts = cand.getJSONObject("content").getJSONArray("parts")
                            val sb = StringBuilder()
                            for (n in 0 until parts.length()) sb.append(parts.getJSONObject(n).optString("text"))
                            Result.success(sb.toString().trim())
                        }
                    } catch (e: Exception) {
                        Result.failure(AiError(r.code, "Respons tidak terbaca (HTTP ${r.code})"))
                    }
                }
                cb(result)
            }
        })
    }

    private fun friendly(code: Int, msg: String): String = when {
        code == 429 -> "Kuota Gemini habis atau terlalu cepat. Tunggu sebentar atau ganti API key."
        code == 400 && msg.contains("API key", ignoreCase = true) -> "API key Gemini tidak valid. Periksa di pengaturan aplikasi."
        code == 401 || code == 403 -> "API key Gemini ditolak. Periksa di pengaturan aplikasi."
        msg.isNotBlank() -> msg
        else -> "HTTP $code"
    }
}
