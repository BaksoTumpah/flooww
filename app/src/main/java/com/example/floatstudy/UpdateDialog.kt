package com.example.floatstudy

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/**
 * Jendela pembaruan buatan sendiri (bukan kotak dialog putih bawaan Android): kartu bersudut bulat bernuansa aplikasi,
 * lencana, versi lama dan baru, catatan rilis, dan bilah kemajuan unduhan di dalam jendela yang sama.
 */
object UpdateDialog {
    /**
     * name: nama aplikasi di judul versi. accent: warna utama (lencana, tombol, bilah). onLater: dipanggil bila ditutup tanpa memperbarui.
     * onStatus: teks status singkat untuk kartu Pembaruan di layar utama.
     */
    fun show(
        a: Activity, info: Updater.Info, name: String, currentName: String, accent: Int,
        onLater: () -> Unit, onStatus: (String) -> Unit
    ) {
        val p = Ui.pal(a)
        fun dp(v: Float) = Ui.dp(a, v)
        val d = Dialog(a)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.clay(a, p.surface, dp(28f), 6f)
            setPadding(dp(22f), dp(22f), dp(22f), dp(26f))
        }

        // kepala: lencana bulat + judul + chip versi
        val badge = TextView(a).apply {
            text = "↑"
            textSize = 26f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(accent)
            }
        }
        val chip = TextView(a).apply {
            text = "$name  $currentName  →  ${info.version}"
            textSize = 12.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(p.on)
            setPadding(dp(10f), dp(3f), dp(10f), dp(4f))
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(Ui.mix(p.surface, accent, 0.22f))
            }
        }
        val titles = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(a).apply {
                text = "Pembaruan tersedia"
                textSize = 19f
                typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
                setTextColor(p.on)
            })
            addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6f)
            })
        }
        card.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(badge, LinearLayout.LayoutParams(dp(52f), dp(52f)))
            addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(14f) })
        })

        // catatan rilis
        card.addView(TextView(a).apply {
            text = "YANG BARU"
            textSize = 11f
            letterSpacing = 0.1f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(p.muted)
            setPadding(dp(2f), dp(18f), 0, dp(6f))
        })
        val notes = TextView(a).apply {
            text = info.notes.ifBlank { "Perbaikan dan peningkatan." }
            textSize = 14f
            setTextColor(p.on)
            setLineSpacing(0f, 1.15f)
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
        }
        card.addView(ScrollView(a).apply {
            background = Ui.dent(a, p.field, dp(16f))
            addView(notes)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            if (notes.text.length > 220) dp(150f) else ViewGroup.LayoutParams.WRAP_CONTENT))

        // kemajuan unduhan (tersembunyi sampai tombol unduh ditekan)
        val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(accent)
            progressBackgroundTintList = ColorStateList.valueOf(Ui.mix(p.surface, accent, 0.25f))
        }
        val status = TextView(a).apply {
            textSize = 13f
            setTextColor(p.muted)
            setPadding(0, dp(6f), 0, 0)
        }
        val progress = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10f)))
            addView(status)
        }
        card.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16f)
        })

        // tombol
        val buttons = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        card.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(18f)
        })

        var later = true
        fun setButtons(primary: String?, secondary: String, onPrimary: () -> Unit) {
            buttons.removeAllViews()
            buttons.addView(Ui.button(a, secondary, false) { d.dismiss() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8f) })
            if (primary != null) {
                buttons.addView(Ui.button(a, primary, true) { onPrimary() },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.5f))
            }
        }

        fun startDownload() {
            later = false
            d.setCancelable(false)
            d.setCanceledOnTouchOutside(false)
            buttons.visibility = View.GONE
            progress.visibility = View.VISIBLE
            bar.progress = 0
            status.text = "Mengunduh versi ${info.version}... 0%"
            onStatus(status.text.toString())
            Thread {
                try {
                    val f = Updater.download(a, info) { pct ->
                        a.runOnUiThread {
                            bar.progress = pct
                            status.text = "Mengunduh versi ${info.version}... $pct%"
                            onStatus(status.text.toString())
                        }
                    }
                    a.runOnUiThread {
                        val started = Updater.install(a, f)
                        d.setCancelable(true)
                        buttons.visibility = View.VISIBLE
                        if (started) {
                            bar.progress = 100
                            status.text = "Unduhan selesai. Ikuti pemasangan di layar Android."
                            setButtons(null, "Tutup") { }
                        } else {
                            status.text = "Izinkan pasang aplikasi dari sumber ini di pengaturan yang terbuka, lalu kembali dan tekan Unduh lagi."
                            setButtons("Unduh lagi", "Tutup") { startDownload() }
                        }
                        onStatus(status.text.toString())
                    }
                } catch (e: Exception) {
                    a.runOnUiThread {
                        d.setCancelable(true)
                        buttons.visibility = View.VISIBLE
                        status.text = "Gagal mengunduh: ${e.message ?: e.javaClass.simpleName}"
                        setButtons("Coba lagi", "Tutup") { startDownload() }
                        onStatus(status.text.toString())
                    }
                }
            }.start()
        }

        setButtons("Unduh dan pasang", "Nanti") { startDownload() }
        d.setOnDismissListener { if (later) onLater() }

        d.setContentView(card)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.55f)
            val w = a.resources.displayMetrics.widthPixels
            setLayout(minOf(w - dp(40f), dp(420f)), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        d.show()
    }
}
