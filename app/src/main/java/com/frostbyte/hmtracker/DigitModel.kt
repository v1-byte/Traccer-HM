package com.frostbyte.hmtracker

import android.graphics.Bitmap
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bacaan model sendiri: [text] mentah (digit dan titik), [conf] keyakinan rata-rata 0..1. */
class ModelRead(val text: String, val conf: Float)

/**
 * Pembaca angka meter buatan sendiri (CNN + CTC, format TFLite) yang dilatih dari sampel aplikasi dengan tools/train_digits.py.
 * Berkas: digits.tflite + digits.json ({"h":32,"w":160,"charset":"0123456789."}). Pra-olah gambar HARUS sama dengan skrip latih:
 * [prepForOcr] (abu-abu, regang kontras persentil 2-98, angka gelap di latar terang), tinggi h, lebar maks w (lebih lebar = dipepatkan,
 * lebih sempit = diisi putih di kanan), nilai 0..1.
 */
class DigitModel private constructor(
    private val itp: Interpreter, val h: Int, val w: Int, val charset: String,
) {
    private val inBuf: ByteBuffer = ByteBuffer.allocateDirect(4 * h * w).order(ByteOrder.nativeOrder())
    private val outShape: IntArray = itp.getOutputTensor(0).shape()      // [1, T, C]
    private val out = Array(1) { Array(outShape[1]) { FloatArray(outShape[2]) } }

    @Synchronized
    fun read(crop: Bitmap): ModelRead? = try {
        val g = prepForModel(crop, h, w)
        inBuf.rewind()
        for (v in g) inBuf.putFloat(v)
        inBuf.rewind()
        itp.run(inBuf, out)
        val (txt, conf) = ctcGreedy(out[0], charset)
        if (txt.isEmpty()) null else ModelRead(txt, conf)
    } catch (e: Throwable) { null }

    fun close() { runCatching { itp.close() } }

    companion object {
        /** Muat model dari [dir]; null bila berkas tidak ada atau bentuknya tidak cocok. */
        fun load(dir: File): DigitModel? = try {
            val f = File(dir, "digits.tflite"); val m = File(dir, "digits.json")
            if (!f.exists() || !m.exists()) null else {
                val j = JSONObject(m.readText())
                val h = j.getInt("h"); val w = j.getInt("w"); val cs = j.getString("charset")
                val itp = Interpreter(f, Interpreter.Options().setNumThreads(2))
                val os = itp.getOutputTensor(0).shape()
                val ins = itp.getInputTensor(0).shape()
                if (os.size != 3 || os[2] != cs.length + 1 || ins.size != 4 || ins[1] != h || ins[2] != w) { itp.close(); null }
                else DigitModel(itp, h, w, cs)
            }
        } catch (e: Throwable) { null }
    }
}

/** Gambar [crop] -> larik h*w (baris demi baris) bernilai 0..1, sama dengan fungsi prep() di tools/train_digits.py. */
fun prepForModel(crop: Bitmap, h: Int, w: Int): FloatArray {
    val g = prepForOcr(crop, h, null)
    val src = if (g.width > w) Bitmap.createScaledBitmap(g, w, h, true) else g
    val px = IntArray(src.width * src.height)
    src.getPixels(px, 0, src.width, 0, 0, src.width, src.height)
    val out = FloatArray(h * w) { 1f }
    for (y in 0 until h) for (x in 0 until src.width) out[y * w + x] = (px[y * src.width + x] and 255) / 255f
    return out
}
