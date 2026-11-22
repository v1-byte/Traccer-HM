package com.frostbyte.hmtracker

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Bantuan AI opsional untuk menebak angka meter saat mengambil sampel latih.
 *
 * - Mati secara bawaan. Hanya aktif bila pengguna menyalakannya dan mengisi kunci API Google AI Studio miliknya sendiri
 *   (ada jatah gratis; batas dan syaratnya ditentukan Google dan bisa berubah).
 * - Yang dikirim hanya potongan gambar area angka (tanpa watermark, lokasi, atau nama unit).
 * - Hasilnya hanya mengisi kolom angka; pengguna tetap harus mencocokkan dengan meter asli sebelum menyimpan.
 * - Kunci disimpan di prefs terpisah `hm_ai` yang dikecualikan dari backup (`res/xml/backup_rules.xml`).
 */
object AiAssist {
    const val DEFAULT_MODEL = "gemini-2.5-flash"
    const val KEYS_URL = "https://aistudio.google.com/apikey"
    private const val PREFS = "hm_ai"
    private const val MAX_SIDE = 640

    const val PROMPT = "Gambar ini potongan layar meter jam kerja (HM) atau odometer. Baca HANYA angka utama yang dihitung. " +
        "Balas dengan angkanya saja: digit tanpa spasi, titik hanya bila ada tepat satu digit desimal, tanpa satuan atau kata lain. " +
        "Pada meter drum mekanik, abaikan drum merah/oranye di sisi kanan. Bila angka tidak terbaca jelas, balas TIDAK."

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun enabled(ctx: Context) = p(ctx).getBoolean("on", false)
    fun setEnabled(ctx: Context, v: Boolean) { p(ctx).edit().putBoolean("on", v).apply() }
    fun key(ctx: Context) = p(ctx).getString("key", "") ?: ""
    fun setKey(ctx: Context, v: String) { p(ctx).edit().putString("key", v.trim()).apply() }
    fun model(ctx: Context) = (p(ctx).getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL).ifBlank { DEFAULT_MODEL }
    fun setModel(ctx: Context, v: String) { p(ctx).edit().putString("model", v.trim()).apply() }
    fun ready(ctx: Context) = enabled(ctx) && key(ctx).isNotBlank()

    /** Badan permintaan REST generateContent: satu teks perintah + satu gambar JPEG (base64). */
    fun buildBody(b64: String): String = JSONObject()
        .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
            .put(JSONObject().put("text", PROMPT))
            .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64))))))
        .put("generationConfig", JSONObject().put("temperature", 0))
        .toString()

    /** Ambil teks jawaban lalu terima HANYA bentuk angka meter (digit, opsional satu digit desimal); selain itu null. */
    fun parseReply(json: String): String? {
        val text = JSONObject(json).optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text") ?: return null
        val t = text.trim().replace(" ", "").replace(',', '.')
        return Regex("""\d{1,9}(\.\d)?""").matchEntire(t)?.value
    }

    private fun small(src: Bitmap): Bitmap {
        val m = maxOf(src.width, src.height)
        if (m <= MAX_SIDE) return src
        val k = MAX_SIDE.toFloat() / m
        return Bitmap.createScaledBitmap(src, (src.width * k).toInt().coerceAtLeast(1), (src.height * k).toInt().coerceAtLeast(1), true)
    }

    /** Panggil di thread latar (Dispatchers.IO). Gagal = Result.failure dengan pesan yang bisa langsung ditampilkan. */
    fun guess(crop: Bitmap, key: String, model: String): Result<String> = runCatching {
        val bos = ByteArrayOutputStream()
        small(crop).compress(Bitmap.CompressFormat.JPEG, 90, bos)
        val body = buildBody(Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP))
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/" + Uri.encode(model) + ":generateContent")
        val c = url.openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.connectTimeout = 10_000; c.readTimeout = 25_000; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", key)            // kunci lewat header, tidak pernah masuk URL atau log
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code != 200) error(when (code) {
                400 -> "Permintaan ditolak (cek kunci dan nama model)"
                401, 403 -> "Kunci API ditolak"
                404 -> "Nama model tidak ditemukan; ganti di kolom Model"
                429 -> "Jatah gratis sedang habis, coba lagi nanti"
                else -> "AI gagal dihubungi (kode $code)"
            })
            val reply = c.inputStream.bufferedReader().use { it.readText() }
            parseReply(reply) ?: error("AI tidak bisa membaca angka ini; isi manual")
        } finally { c.disconnect() }
    }.recoverCatching { e -> throw if (e is java.io.IOException) IllegalStateException("Tidak ada koneksi internet") else e }
}
