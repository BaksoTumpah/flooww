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

object AiClient {
    private val http = OkHttpClient.Builder()
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    const val SYSTEM =
        "Kamu tutor belajar yang sabar. Jawab dalam bahasa Indonesia. " +
            "Jelaskan konsep dan langkah penyelesaian secara runtut supaya siswa paham, " +
            "lalu beri jawaban akhir. Pakai teks biasa tanpa format markdown berat."

    fun ask(
        ctx: Context,
        prompt: String,
        imageB64: String?,
        system: String = SYSTEM,
        cb: (Result<String>) -> Unit
    ) {
        val key = Prefs.key(ctx)
        if (key.isBlank()) {
            cb(Result.failure(IllegalStateException("API key belum tertanam. Tambahkan secret ANTHROPIC_API_KEY di GitHub lalu build ulang.")))
            return
        }

        val content = JSONArray()
        if (imageB64 != null) {
            content.put(
                JSONObject()
                    .put("type", "image")
                    .put(
                        "source",
                        JSONObject()
                            .put("type", "base64")
                            .put("media_type", "image/jpeg")
                            .put("data", imageB64)
                    )
            )
        }
        content.put(JSONObject().put("type", "text").put("text", prompt))

        val body = JSONObject()
            .put("model", Prefs.model(ctx))
            .put("max_tokens", 1500)
            .put("system", system)
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", content))
            )

        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", key)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cb(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                val result: Result<String> = response.use { r ->
                    val raw = r.body?.string().orEmpty()
                    try {
                        val j = JSONObject(raw)
                        if (!r.isSuccessful) {
                            val msg = j.optJSONObject("error")?.optString("message")
                            Result.failure(Exception(msg ?: "HTTP ${r.code}"))
                        } else {
                            val arr = j.getJSONArray("content")
                            val sb = StringBuilder()
                            for (n in 0 until arr.length()) {
                                val b = arr.getJSONObject(n)
                                if (b.optString("type") == "text") sb.append(b.optString("text"))
                            }
                            Result.success(sb.toString().trim())
                        }
                    } catch (e: Exception) {
                        Result.failure(Exception("Respons tidak terbaca (HTTP ${r.code})"))
                    }
                }
                cb(result)
            }
        })
    }
}
