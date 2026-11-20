package com.frostbyte.hmtracker

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Teks pendamping saat foto dibagikan: unit, angka HM/ODO, dan waktu foto. */
fun photoCaption(p: Photo): String =
    p.u.ifEmpty { "Unit" } + (if (p.hm > 0) " · " + f1(p.hm) else "") + " · " + dt(p.t)

/**
 * Bagikan foto ber-watermark lewat menu berbagi Android (WhatsApp, email, Drive, dll). File dikirim apa adanya (tanpa kompres
 * ulang) lewat FileProvider, jadi SHA-256 di jejak audit tetap cocok. Mengembalikan false bila gagal membuka menu berbagi.
 */
fun sharePhoto(ctx: Context, f: File, caption: String): Boolean = try {
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    val send = Intent(Intent.ACTION_SEND).setType("image/jpeg")
        .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_TEXT, caption)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri("", uri)
    ctx.startActivity(Intent.createChooser(send, "Bagikan foto HM").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: Exception) { false }
