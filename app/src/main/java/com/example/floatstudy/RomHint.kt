package com.example.floatstudy

import android.os.Build

/** Petunjuk izin overlay yang jelas sesuai merek HP, dipakai saat ROM memblokir jendela mengambang. */
object RomHint {
    private fun brand() = Build.MANUFACTURER.lowercase()

    fun overlayBlocked(): String {
        val b = brand()
        val step = when {
            b.contains("xiaomi") || b.contains("redmi") || b.contains("poco") ->
                "Xiaomi/Redmi/POCO: Pengaturan > Aplikasi > Kelola aplikasi > flooww > Izin lainnya. Aktifkan \"Tampilkan di atas aplikasi lain\" dan \"Tampilkan jendela pop-up saat berjalan di latar belakang\"."
            b.contains("oppo") || b.contains("realme") || b.contains("oneplus") ->
                "OPPO/realme/OnePlus: Pengaturan > Aplikasi > flooww > Izin > Tampil di atas aplikasi lain. Aktifkan juga Mulai otomatis dan izinkan aktivitas di latar belakang."
            b.contains("vivo") || b.contains("iqoo") ->
                "vivo/iQOO: Pengaturan > Aplikasi > flooww > Izin > Tampil di atas aplikasi lain, dan izinkan Pop-up di latar belakang."
            b.contains("samsung") ->
                "Samsung: Pengaturan > Aplikasi > flooww > Tampil di atas aplikasi lain, lalu aktifkan."
            b.contains("huawei") || b.contains("honor") ->
                "Huawei/Honor: Pengaturan > Aplikasi > flooww > Izin > Tampil di atas aplikasi lain."
            else ->
                "Pengaturan > Aplikasi > flooww > Tampil di atas aplikasi lain, lalu aktifkan."
        }
        return "HP memblokir jendela mengambang. $step Cara lain: aktifkan layanan aksesibilitas flooww (tidak butuh izin tampil di atas aplikasi lain)."
    }
}
