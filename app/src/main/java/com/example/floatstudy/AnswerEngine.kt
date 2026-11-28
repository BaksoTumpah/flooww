package com.example.floatstudy

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Mesin AI untuk fitur Answer: WebView sendiri di jendela transparan (alpha 0, tidak bisa disentuh, tidak fokus),
 * jadi berjalan di latar belakang tanpa terlihat. Mengirim gambar soal + perintah ke situs AI pilihan (Duck.ai atau
 * Google AI Mode), lalu membaca balasan "JAWABAN: X. isi" dari teks halaman.
 * Pemilih elemen halaman memakai cara yang sama dengan Tanya AI; belum teruji di HP.
 */
class AnswerEngine(
    private val c: Context,
    private val ui: Handler,
    private val wm: WindowManager,
    private val ovType: Int
) {
    /** kind: J = pilihan ganda (huruf, bisa lebih dari satu), P = menjodohkan, I = isian singkat. text = isi balasan setelah titik dua. */
    class Reply(val kind: Char, val text: String)

    private var web: WebView? = null
    private var holder: android.widget.FrameLayout? = null
    private var b64: String = ""
    private var gen = 0
    private var busy = false

    val isBusy: Boolean get() = busy

    inner class Bridge {
        @android.webkit.JavascriptInterface
        fun get(): String = b64
    }

    private val URLS = listOf("https://duck.ai/", "https://www.google.com/search?udm=50")

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    fun start(target: Int) {
        stop()
        val dm = c.resources.displayMetrics
        val h = android.widget.FrameLayout(c)
        val w = WebView(c)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.userAgentString = w.settings.userAgentString.replace("; wv", "")
        w.addJavascriptInterface(Bridge(), "floowwImg")
        try {
            CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        } catch (e: Exception) {
        }
        w.webViewClient = WebViewClient()
        h.addView(w, android.widget.FrameLayout.LayoutParams(-1, -1))
        val lp = WindowManager.LayoutParams(
            minOf(dm.widthPixels, (420 * dm.density).toInt()), (dm.heightPixels * 0.8f).toInt(), ovType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.alpha = 0f
        try {
            wm.addView(h, lp)
        } catch (e: Exception) {
            Diag.add(c, "Answer: jendela AI gagal: ${e.javaClass.simpleName} ${e.message}")
            return
        }
        holder = h
        web = w
        w.loadUrl(URLS[target])
        Diag.add(c, "Answer: AI tersembunyi dimuat ${URLS[target]}")
    }

    fun stop() {
        gen++
        busy = false
        val h = holder
        val w = web
        holder = null
        web = null
        try {
            if (h != null) wm.removeView(h)
        } catch (e: Exception) {
        }
        try {
            w?.stopLoading()
            w?.destroy()
        } catch (e: Exception) {
        }
    }

    private val TERMS_JS = "(function(){try{var ds=document.querySelectorAll('[role=dialog],[aria-modal=true],dialog');" +
        "for(var i=0;i<ds.length;i++){var bs=ds[i].querySelectorAll('button');" +
        "for(var j=0;j<bs.length;j++){var t=(bs[j].innerText||'').toLowerCase();" +
        "if(/agree and continue|setuju dan lanjut|i agree|accept|terima/.test(t)){bs[j].click();return 1;}}}return 0;}catch(e){return 0;}})()"

    private val COUNT_JS = "(function(){return document.querySelectorAll('img[src^=\"blob:\"],img[src^=\"data:\"],canvas').length+(document.body?document.body.innerText.split('soal.jpg').length-1:0);})()"

    // jumlah dan isi balasan terakhir berformat "JAWABAN: ...", "PASANGAN: ..." atau "ISIAN: ..." di teks halaman.
    // Isi yang diawali "[" dilewati: itu contoh format dari perintah kita sendiri yang ikut tampil di halaman.
    private val REPLY_JS = "(function(){var t=document.body?document.body.innerText:'';" +
        "var re=/(JAWABAN|PASANGAN|ISIAN)\\s*[:：]\\s*([^\\s\\[][^\\n]{0,200})/gi;var n=0,m,k='',x='';" +
        "while((m=re.exec(t))){n++;k=m[1].charAt(0).toUpperCase();x=m[2];}return {n:n,k:k,t:x};})()"

    private val INJECT_JS = "(function(){try{var b=window.floowwImg.get();if(!b)return 0;" +
        "var s=atob(b),a=new Uint8Array(s.length);for(var i=0;i<s.length;i++)a[i]=s.charCodeAt(i);" +
        "var f=new File([a],'soal.jpg',{type:'image/jpeg'});var dt=new DataTransfer();dt.items.add(f);" +
        "var ins=document.querySelectorAll('input[type=file]');var t=null;" +
        "for(var k=0;k<ins.length;k++){var ac=(ins[k].accept||'');if(!ac||ac.indexOf('image')>=0||ac.indexOf('*')>=0){t=ins[k];break;}}" +
        "if(t){t.files=dt.files;t.dispatchEvent(new Event('input',{bubbles:true}));t.dispatchEvent(new Event('change',{bubbles:true}));return 2;}" +
        "var ok=function(x){return x&&(x.isContentEditable||x.tagName=='TEXTAREA'||(x.getAttribute&&x.getAttribute('role')=='textbox'));};" +
        "var e=document.activeElement;" +
        "if(!ok(e))e=document.querySelector('rich-textarea [contenteditable],div.ql-editor,textarea,[contenteditable],[role=textbox]');" +
        "if(e){e.focus();e.dispatchEvent(new ClipboardEvent('paste',{clipboardData:dt,bubbles:true,cancelable:true}));" +
        "['dragenter','dragover','drop'].forEach(function(n){e.dispatchEvent(new DragEvent(n,{dataTransfer:dt,bubbles:true,cancelable:true}));});return 1;}" +
        "return 0;}catch(x){return 0;}})()"

    private fun fillJs(prompt: String): String {
        val q = org.json.JSONObject.quote(prompt)
        return "(function(){var e=document.querySelector('rich-textarea [contenteditable],div.ql-editor,#prompt-textarea,textarea,[contenteditable=true],[role=textbox]');" +
            "if(!e)return 0;e.focus();var t=$q;" +
            "if(e.tagName=='TEXTAREA'){var d=Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value');d.set.call(e,t);e.dispatchEvent(new Event('input',{bubbles:true}));}" +
            "else{document.execCommand('selectAll',false,null);if(!document.execCommand('insertText',false,t)){e.textContent=t;e.dispatchEvent(new Event('input',{bubbles:true}));}}" +
            "var n=0;var iv=setInterval(function(){n++;var b=document.querySelector('button[data-testid=send-button],button[aria-label*=Send],button[aria-label*=Kirim],button[aria-label*=send],button[aria-label*=Submit],button[aria-label*=submit],button.send-button');" +
            "if(!b){var f=e.closest&&e.closest('form');if(f)b=f.querySelector('button[type=submit]');}" +
            "if(b&&!b.disabled&&b.getAttribute('aria-disabled')!='true'){clearInterval(iv);b.click();}" +
            "else if(!b&&n==25){['keydown','keypress','keyup'].forEach(function(k){e.dispatchEvent(new KeyboardEvent(k,{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true}));});}" +
            "else if(n>1200)clearInterval(iv);},60);return 1;})()"
    }

    /** Kirim gambar soal + perintah, tunggu balasan. cb dipanggil di thread utama; null bila gagal atau waktu habis. */
    fun ask(imageB64: String, prompt: String, cb: (Reply?) -> Unit) {
        val w = web
        if (w == null || busy) {
            cb(null)
            return
        }
        busy = true
        b64 = imageB64
        val my = ++gen
        val t0 = SystemClock.elapsedRealtime()
        fun alive() = my == gen && web === w
        fun fail(why: String) {
            if (!alive()) return
            busy = false
            Diag.add(c, "Answer: gagal ($why)")
            cb(null)
        }

        // 4) tunggu balasan baru (jumlah kecocokan naik dan nilainya sama dua kali berturut-turut)
        fun waitReply(base: Int) {
            var lastSeen = ""
            var stable = 0
            fun poll() {
                if (!alive()) return
                if (SystemClock.elapsedRealtime() - t0 > 90_000) {
                    fail("balasan tidak muncul")
                    return
                }
                w.evaluateJavascript(REPLY_JS) { r ->
                    if (!alive()) return@evaluateJavascript
                    var n = 0
                    var k = ""
                    var t = ""
                    try {
                        val o = org.json.JSONObject(r ?: "{}")
                        n = o.optInt("n")
                        k = o.optString("k")
                        t = o.optString("t")
                    } catch (e: Exception) {
                    }
                    if (n > base && k.isNotEmpty()) {
                        val key = "$k|$t"
                        // balasan panjang (isian, pasangan) bisa masih diketik AI: tunggu lebih lama sampai tidak berubah
                        val need = if (k == "J") 1 else 2
                        if (key == lastSeen) stable++ else stable = 0
                        lastSeen = key
                        if (stable >= need) {
                            busy = false
                            cb(Reply(k[0], t.trim().trimEnd('*', ' ')))
                            return@evaluateJavascript
                        }
                    }
                    ui.postDelayed({ poll() }, 700)
                }
            }
            poll()
        }

        // 3) isi perintah lalu kirim
        fun send(base: Int) {
            w.evaluateJavascript(fillJs(prompt)) { r ->
                if (!alive()) return@evaluateJavascript
                if (r?.trim() == "0") fail("kolom pesan tidak ada") else waitReply(base)
            }
        }

        // 2) tempel gambar, cek muncul (maks 6 detik), lalu kirim
        fun attach(base: Int, imgBase: Int) {
            var tries = 0
            fun inject() {
                if (!alive()) return
                w.evaluateJavascript(INJECT_JS) {
                    if (!alive()) return@evaluateJavascript
                    var waited = 0
                    fun check() {
                        if (!alive()) return
                        w.evaluateJavascript("(function(b){return ($COUNT_JS)>b?1:0;})($imgBase)") { v ->
                            if (!alive()) return@evaluateJavascript
                            waited += 100
                            when {
                                v?.trim() == "1" -> send(base)
                                waited >= 3000 && tries < 2 -> {
                                    tries++
                                    inject()
                                }
                                waited >= 6000 -> send(base)
                                else -> ui.postDelayed({ check() }, 100)
                            }
                        }
                    }
                    ui.postDelayed({ check() }, 100)
                }
            }
            inject()
        }

        // 1) tunggu halaman AI siap (kolom pesan ada)
        fun ready() {
            if (!alive()) return
            if (SystemClock.elapsedRealtime() - t0 > 60_000) {
                fail("halaman AI tidak siap")
                return
            }
            w.evaluateJavascript(TERMS_JS, null)
            w.evaluateJavascript(
                "(function(){var e=document.querySelector('textarea,[contenteditable=true],[contenteditable],[role=textbox]');" +
                    "return e&&document.readyState!='loading'?1:0;})()"
            ) { v ->
                if (!alive()) return@evaluateJavascript
                if (v?.trim() == "1") {
                    w.evaluateJavascript(REPLY_JS) { r0 ->
                        w.evaluateJavascript(COUNT_JS) { c0 ->
                            if (!alive()) return@evaluateJavascript
                            val base = try {
                                org.json.JSONObject(r0 ?: "{}").optInt("n")
                            } catch (e: Exception) {
                                0
                            }
                            attach(base, c0?.trim()?.toIntOrNull() ?: 0)
                        }
                    }
                } else ui.postDelayed({ ready() }, 300)
            }
        }
        ready()
    }
}
