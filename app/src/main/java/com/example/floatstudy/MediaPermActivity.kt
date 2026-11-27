package com.example.floatstudy

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle

/** Layar transparan untuk meminta izin akses foto, dipanggil saat Galeri mengambang dibuka. */
class MediaPermActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            requestPermissions(
                if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
                else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                9
            )
        } catch (e: Exception) {
            finish()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        FloatService.onMediaPerm?.invoke(grantResults.any { it == PackageManager.PERMISSION_GRANTED })
        finish()
    }
}
