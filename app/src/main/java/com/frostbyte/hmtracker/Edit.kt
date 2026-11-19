package com.frostbyte.hmtracker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint

/**
 * Penyesuaian foto sebelum disimpan. Hanya diterapkan saat menyimpan: OCR dan foto asli (bukti)
 * tetap memakai foto mentah, sehingga jejak audit tidak terpengaruh oleh edit tampilan.
 */
data class PhotoEdit(val rot: Int = 0, val contrast: Float = 0f) {
    val isNone: Boolean get() = rot == 0 && contrast == 0f
}

/** Rotasi dibulatkan ke kelipatan 90° (0..270) dan kontras dibatasi ±0,5 agar hasilnya selalu wajar. */
fun PhotoEdit.normalized(): PhotoEdit {
    val quarter = (((rot / 90) % 4) + 4) % 4
    return PhotoEdit(rot = quarter * 90, contrast = contrast.coerceIn(-0.5f, 0.5f))
}

fun applyEdit(src: Bitmap, edit: PhotoEdit): Bitmap {
    val e = edit.normalized()
    if (e.isNone) return src
    var b = src
    if (e.rot != 0) {
        b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(e.rot.toFloat()) }, true)
    }
    if (e.contrast != 0f) {
        // kontras di sekitar titik tengah 128: out = c*in + 128*(1-c)
        val c = 1f + e.contrast
        val t = 128f * (1f - c)
        val cm = ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f))
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(b, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(cm) })
        b = out
    }
    return b
}
