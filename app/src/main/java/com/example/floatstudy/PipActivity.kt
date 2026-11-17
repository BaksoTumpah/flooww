package com.example.floatstudy

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.app.Activity

/**
 * Jendela Picture-in-Picture (PiP). Memuat halaman (mis. video) lalu masuk ke mode PiP lewat
 * Activity.enterPictureInPictureMode(). Jendela PiP dikelola sistem Android, jadi tetap melayang
 * di atas aplikasi lain tanpa izin overlay.
 */
class PipActivity : Activity() {

    companion object {
        const val EXTRA_URL = "url"
    }

    private var web: WebView? = null
    private var entered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val w = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.userAgentString = settings.userAgentString.replace("; wv", "")
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        web = w
        setContentView(w)
        load(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        load(intent)
    }

    private fun load(i: Intent?) {
        val u = i?.getStringExtra(EXTRA_URL) ?: "https://www.google.com"
        web?.loadUrl(u)
        // masuk PiP otomatis setelah halaman dibuka
        web?.postDelayed({ enterPip() }, 600)
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < 26 || isInPictureInPictureMode) return
        try {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
            )
            entered = true
        } catch (e: Exception) {
            // perangkat atau ROM tidak mendukung PiP
        }
    }

    // tombol Home juga memicu PiP
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPip()
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(inPip, newConfig)
        // jendela PiP ditutup (bukan diperbesar): hentikan halaman
        if (!inPip && entered && isFinishing) web?.loadUrl("about:blank")
    }

    override fun onDestroy() {
        web?.destroy()
        web = null
        super.onDestroy()
    }
}
