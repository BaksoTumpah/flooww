package com.example.floatstudy

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle

/**
 * Layar transparan untuk meminta izin saat alat flooww membutuhkannya.
 * kind = "media" (akses foto, dari Galeri) atau "cam" (kamera dan mikrofon, dari Kamera).
 */
class MediaPermActivity : Activity() {
    private var kind = "media"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        kind = intent?.getStringExtra("kind") ?: "media"
        try {
            val perms = if (kind == "cam") {
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            } else if (Build.VERSION.SDK_INT >= 33) {
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            requestPermissions(perms, 9)
        } catch (e: Exception) {
            finish()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (kind == "cam") {
            // yang wajib hanya kamera; mikrofon opsional (video tanpa suara bila ditolak)
            FloatService.onCamPerm?.invoke(checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        } else {
            FloatService.onMediaPerm?.invoke(grantResults.any { it == PackageManager.PERMISSION_GRANTED })
        }
        finish()
    }
}
