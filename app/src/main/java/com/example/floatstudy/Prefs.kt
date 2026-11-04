package com.example.floatstudy

import android.content.Context

object Prefs {
    const val DEFAULT_MODEL = "claude-sonnet-5-5"

    private fun sp(c: Context) = c.getSharedPreferences("floatstudy", Context.MODE_PRIVATE)

    fun key(c: Context): String = sp(c).getString("key", "") ?: ""

    fun model(c: Context): String =
        (sp(c).getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL).ifBlank { DEFAULT_MODEL }

    fun save(c: Context, key: String, model: String) {
        sp(c).edit().putString("key", key.trim()).putString("model", model.trim()).apply()
    }
}
