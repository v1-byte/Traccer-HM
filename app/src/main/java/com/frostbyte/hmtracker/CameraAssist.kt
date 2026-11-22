package com.frostbyte.hmtracker

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Pendeteksi HP yang sedang goyang, dari akselerometer. Gravitasi dibuang dengan penyaring lolos-rendah, sisanya
 * (getaran tangan) dirata-rata pada jendela sampel terakhir. Histeresis ([enter] > [exit]) menahan status agar tidak
 * berkedip. Logika murni (tanpa Android) sehingga bisa diuji di JVM; ambang perlu dicoba di HP asli.
 */
class MotionGate(private val window: Int = 10, private val enter: Float = 0.7f, private val exit: Float = 0.4f) {
    private val buf = FloatArray(window)
    private var n = 0
    private var pos = 0
    private var gx = 0f
    private var gy = 0f
    private var gz = 0f
    private var seeded = false

    /** true = HP sedang bergerak/bergetar cukup besar untuk membuat foto buram. */
    var moving = false
        private set

    /** Masukkan satu sampel akselerometer (m/s²); mengembalikan status [moving] terbaru. */
    fun feed(x: Float, y: Float, z: Float): Boolean {
        if (!seeded) { gx = x; gy = y; gz = z; seeded = true }
        gx += GRAV * (x - gx); gy += GRAV * (y - gy); gz += GRAV * (z - gz)
        val dx = x - gx; val dy = y - gy; val dz = z - gz
        buf[pos] = sqrt(dx * dx + dy * dy + dz * dz)
        pos = (pos + 1) % window
        if (n < window) n++
        if (n >= window / 2) {
            val lv = level
            if (moving) { if (lv < exit) moving = false } else if (lv > enter) moving = true
        }
        return moving
    }

    /** Rata-rata besar gerakan (di luar gravitasi) pada jendela sampel. */
    val level: Float
        get() {
            if (n == 0) return 0f
            var s = 0f
            for (k in 0 until n) s += buf[k]
            return s / n
        }

    fun reset() { n = 0; pos = 0; seeded = false; moving = false }

    private companion object { const val GRAV = 0.08f }
}

/** Pratinjau zoom cepat (1×, 2×, 4×) yang didukung lensa; kosong bila lensa hanya punya satu langkah. */
fun zoomPresets(minZ: Float, maxZ: Float): List<Float> {
    val p = listOf(1f, 2f, 4f).filter { it >= minZ - 0.01f && it <= maxZ + 0.01f }
    return if (p.size >= 2) p else emptyList()
}

/** Apakah zoom [cur] cukup dekat dengan pratinjau [p] untuk dianggap sedang memakainya. */
fun onPreset(cur: Float, p: Float): Boolean = abs(cur - p) <= 0.12f * p

/** Mode latih: bacaan live diterima apa adanya (koma jadi titik), tanpa pemeriksaan riwayat/interval unit. Null bila bukan angka. */
fun trainReading(raw: String): Resolved? {
    val t = raw.trim().replace(',', '.')
    val v = t.toDoubleOrNull() ?: return null
    return Resolved(v, t, false, emptyList())
}

/**
 * Gerbang jepret otomatis. Memotret hanya bila bacaan valid ([ready]) dengan nilai yang sama terus-menerus selama
 * [holdMs], dan HP tidak goyang. Setelah menembak (atau ditolak pemeriksaan kualitas) ada jeda [cooldownMs] supaya
 * tidak memotret beruntun. Logika murni agar bisa diuji di JVM.
 */
class AutoShootGate(private val holdMs: Long = 1000, private val cooldownMs: Long = 3000) {
    private var value = ""
    private var since = 0L
    private var coolUntil = 0L

    /** [now] dalam ms (monoton). Mengembalikan true tepat saat harus memotret. */
    fun feed(now: Long, ready: Boolean, value: String, steady: Boolean): Boolean {
        if (!ready || !steady || value.isEmpty()) { reset(); return false }
        if (value != this.value) { this.value = value; since = now; return false }
        if (now < coolUntil || now - since < holdMs) return false
        coolUntil = now + cooldownMs
        since = now
        return true
    }

    /** Kemajuan tahan-stabil 0..1 untuk cincin di tombol rana; 0 saat tak ada bacaan atau sedang jeda setelah menembak. */
    fun progress(now: Long): Float {
        if (value.isEmpty() || now < coolUntil) return 0f
        return ((now - since).toFloat() / holdMs).coerceIn(0f, 1f)
    }

    fun reset() { value = ""; since = 0L }
}

/** Zoom [z] ditarik ke 1×/2×/4× bila dalam 4% (magnet penggeser zoom halus). */
fun zoomSnap(z: Float): Float = listOf(1f, 2f, 4f).firstOrNull { abs(z - it) <= 0.04f * it } ?: z

/** Posisi 0..1 untuk zoom [z] pada penggeser berskala log antara [minZ] dan [maxZ] (zoom terasa merata di tiap langkah). */
fun zoomToFrac(z: Float, minZ: Float, maxZ: Float): Float {
    if (minZ <= 0f || maxZ <= minZ) return 0f
    return (kotlin.math.ln(z.coerceIn(minZ, maxZ) / minZ) / kotlin.math.ln(maxZ / minZ)).coerceIn(0f, 1f)
}

/** Kebalikan [zoomToFrac], dengan magnet ke 1×/2×/4× dan dijepit ke batas lensa. */
fun zoomFromFrac(f: Float, minZ: Float, maxZ: Float): Float {
    if (minZ <= 0f || maxZ <= minZ) return minZ
    val raw = minZ * Math.pow((maxZ / minZ).toDouble(), f.coerceIn(0f, 1f).toDouble()).toFloat()
    return zoomSnap(raw).coerceIn(minZ, maxZ)
}

/** Teks zoom untuk penggeser: "1×" bila bulat, selain itu satu desimal ("2.3×"). */
fun fmtZoom(z: Float): String =
    if (abs(z - Math.round(z)) < 0.05f) "${Math.round(z)}×" else String.format(java.util.Locale.US, "%.1f×", z)

/**
 * Pangkas area baca [roi] supaya tidak menyentuh stempel (tanggal/jam/koordinat) di dekat angka. Hanya bantalan di
 * sekeliling angka ([core]) yang dipangkas; kotak angka itu sendiri tidak pernah dipotong. Stempel yang menimpa
 * [core] diabaikan.
 */
fun trimRoiAwayFromStamps(roi: Roi, core: Roi, stamps: List<Roi>): Roi {
    var l = roi.x; var t = roi.y; var r = roi.x + roi.w; var b = roi.y + roi.h
    val cl = core.x; val ct = core.y; val cr = core.x + core.w; val cb = core.y + core.h
    for (s in stamps) {
        val sl = s.x; val st = s.y; val sr = s.x + s.w; val sb = s.y + s.h
        if (sr <= l || sl >= r || sb <= t || st >= b) continue                    // tidak bersinggungan
        if (sr > cl && sl < cr && sb > ct && st < cb) continue                    // menimpa angka itu sendiri
        val vOverlap = sb > ct && st < cb                                         // sejajar secara vertikal dengan angka
        val hOverlap = sr > cl && sl < cr                                         // sejajar secara horizontal
        when {
            hOverlap && st >= cb -> b = minOf(b, maxOf(cb, st))                   // di bawah angka
            hOverlap && sb <= ct -> t = maxOf(t, minOf(ct, sb))                   // di atas angka
            vOverlap && sl >= cr -> r = minOf(r, maxOf(cr, sl))                   // di kanan angka
            vOverlap && sr <= cl -> l = maxOf(l, minOf(cl, sr))                   // di kiri angka
            st >= cb -> b = minOf(b, maxOf(cb, st))                               // serong: pangkas sisi terdekat
            sb <= ct -> t = maxOf(t, minOf(ct, sb))
            sl >= cr -> r = minOf(r, maxOf(cr, sl))
            else -> l = maxOf(l, minOf(cl, sr))
        }
    }
    return Roi(l, t, (r - l).coerceAtLeast(2), (b - t).coerceAtLeast(2))
}

/** Bagian bar ketajaman yang terisi (0..1): penuh bila ketajaman sudah 1,6x ambang peringatan. */
fun sharpFraction(sharp: Double?): Float = ((sharp ?: 0.0) / (SHARP_WARN * 1.6)).toFloat().coerceIn(0f, 1f)

/** Histogram kecerahan (luma) dari piksel ARGB: [bins] kolom, tiap kolom = bagian piksel (jumlah semua kolom = 1). */
fun lumaHistogram(pixels: IntArray, bins: Int = 32): FloatArray {
    val h = FloatArray(bins)
    if (pixels.isEmpty() || bins <= 0) return h
    for (p in pixels) {
        val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
        val y = (r * 299 + g * 587 + b * 114) / 1000
        h[(y * bins / 256).coerceIn(0, bins - 1)] += 1f
    }
    val n = pixels.size.toFloat()
    for (i in h.indices) h[i] /= n
    return h
}

/** Hasil penilaian eksposur dari histogram: bagian piksel terbakar (terang) dan gelap pekat, serta saran langkah EV. */
class ExposureAdvice(val clipHi: Float, val clipLo: Float, val evSteps: Int, val msg: String?)

/**
 * Menilai eksposur dari histogram ternormalisasi-jumlah (tiap kolom = bagian piksel, jumlah = 1). Layar LCD/LED meter
 * sering terbakar oleh pantulan: bila ≥ [hiLimit] piksel berada di kolom terang paling ujung, disarankan menurunkan EV;
 * bila ≥ [loLimit] di kolom gelap paling ujung, disarankan menaikkan EV. [evSteps] = jumlah langkah EV (−2..+2).
 */
fun exposureAdvice(hist: FloatArray, hiLimit: Float = 0.12f, loLimit: Float = 0.45f): ExposureAdvice {
    if (hist.size < 8) return ExposureAdvice(0f, 0f, 0, null)
    val edge = maxOf(1, hist.size / 16)
    var hi = 0f; var lo = 0f
    for (i in 0 until edge) { lo += hist[i]; hi += hist[hist.size - 1 - i] }
    return when {
        hi >= hiLimit * 2f -> ExposureAdvice(hi, lo, -2, "Terlalu terang · turunkan eksposur")
        hi >= hiLimit -> ExposureAdvice(hi, lo, -1, "Sebagian terbakar · turunkan eksposur")
        lo >= loLimit * 1.6f -> ExposureAdvice(hi, lo, 2, "Terlalu gelap · naikkan eksposur")
        lo >= loLimit -> ExposureAdvice(hi, lo, 1, "Agak gelap · naikkan eksposur")
        else -> ExposureAdvice(hi, lo, 0, null)
    }
}

/** Indeks EV baru setelah menambah [steps] langkah, dibatasi ke rentang yang didukung kamera. */
fun clampEv(cur: Int, steps: Int, min: Int, max: Int): Int = (cur + steps).coerceIn(min, max)

/** Pilihan kisi bidik: 0 = mati, 1 = sepertiga 3×3, 2 = sepertiga + silang tengah. */
const val GRID_MODES = 3
fun nextGrid(g: Int): Int = (g + 1) % GRID_MODES
fun gridLabel(g: Int): String = when (g) { 1 -> "3×3"; 2 -> "3×3+"; else -> "Mati" }

/** Mode timer berikutnya dalam putaran: mati → 3 → 5 → 10 → mati. */
fun nextTimer(t: Int): Int = when (t) { 0 -> 3; 3 -> 5; 5 -> 10; else -> 0 }

/**
 * Jumlah mode pemotretan yang sedang mengubah hasil foto (AF/AE terkunci, jepret oto, timer, EV, kilat foto); tampil sebagai
 * lencana di tombol Pro supaya pengguna tahu ada pengaturan aktif walau panel tertutup. Zoom oto/kisi/histogram tidak dihitung
 * (tidak mengubah foto).
 */
fun activeModeCount(aeLock: Boolean, autoShoot: Boolean, timerSec: Int, ev: Int, flashOn: Boolean): Int =
    listOf(aeLock, autoShoot, timerSec > 0, ev != 0, flashOn).count { it }

/**
 * Bingkai baca manual: pusat ([cx], [cy]) dan ukuran ([w], [h]) dinormalkan ke ukuran view (0..1). Bila aktif, hanya area ini yang
 * dibaca OCR, dan fokus + eksposur dikunci di titik tengahnya, jadi hasil tidak tergantung pencarian angka otomatis.
 */
data class ReadFrame(val cx: Float, val cy: Float, val w: Float, val h: Float) {
    /** Ukuran dijaga antara minimum dan maksimum, dan bingkai tidak keluar dari view. */
    fun clamped(): ReadFrame {
        val nw = w.coerceIn(MIN_W, MAX_W); val nh = h.coerceIn(MIN_H, MAX_H)
        return ReadFrame(cx.coerceIn(nw / 2f, 1f - nw / 2f), cy.coerceIn(nh / 2f, 1f - nh / 2f), nw, nh)
    }

    /** Kotak di layar (px view): x, y, lebar, tinggi. */
    fun toView(vw: Int, vh: Int): FloatArray = floatArrayOf((cx - w / 2f) * vw, (cy - h / 2f) * vh, w * vw, h * vh)

    fun moved(dx: Float, dy: Float): ReadFrame = copy(cx = cx + dx, cy = cy + dy).clamped()
    fun withSize(nw: Float, nh: Float): ReadFrame = copy(w = nw, h = nh).clamped()

    /** Seret satu sudut ([corner]: 0 kiri-atas, 1 kanan-atas, 2 kiri-bawah, 3 kanan-bawah) sejauh [dx],[dy] (normal); sudut seberangnya diam. */
    fun dragCorner(corner: Int, dx: Float, dy: Float): ReadFrame {
        var l = cx - w / 2f; var r = cx + w / 2f; var t = cy - h / 2f; var b = cy + h / 2f
        val left = corner == 0 || corner == 2; val top = corner == 0 || corner == 1
        if (left) l += dx else r += dx
        if (top) t += dy else b += dy
        l = l.coerceIn(0f, 1f); r = r.coerceIn(0f, 1f); t = t.coerceIn(0f, 1f); b = b.coerceIn(0f, 1f)
        if (left) l = minOf(l, r - MIN_W) else r = maxOf(r, l + MIN_W)
        if (top) t = minOf(t, b - MIN_H) else b = maxOf(b, t + MIN_H)
        return ReadFrame((l + r) / 2f, (t + b) / 2f, r - l, b - t).clamped()
    }

    /** Lebar area ukur fokus/eksposur (pecahan): kecil supaya yang diukur hanya angka di tengah bingkai. */
    val focusSize: Float get() = (h * 0.8f).coerceIn(0.05f, 0.14f)

    fun encode(): String = String.format(java.util.Locale.US, "%.4f,%.4f,%.4f,%.4f", cx, cy, w, h)

    companion object {
        const val MIN_W = 0.12f
        const val MAX_W = 0.98f
        const val MIN_H = 0.04f
        const val MAX_H = 0.5f
        val DEFAULT = ReadFrame(0.5f, 0.42f, 0.80f, 0.12f)
        /** Baris angka panjang (meter drum 6 angka, odometer). */
        val WIDE = ReadFrame(0.5f, 0.42f, 0.92f, 0.10f)
        /** Layar LCD berbentuk kotak. */
        val BLOCK = ReadFrame(0.5f, 0.42f, 0.55f, 0.20f)

        /** Kebalikan [encode]; null bila teks kosong atau rusak. */
        fun decode(s: String?): ReadFrame? {
            val p = s?.split(",")?.mapNotNull { it.trim().toFloatOrNull() } ?: return null
            if (p.size != 4 || p.any { it.isNaN() || it.isInfinite() }) return null
            return ReadFrame(p[0], p[1], p[2], p[3]).clamped()
        }
    }
}

/** Derajat kemiringan yang ditampilkan: dibulatkan, tanda ± dibuang, dan dianggap 0 di dalam [tol]. */
fun rollLabel(roll: Float, tol: Float = 1.5f): String {
    val a = kotlin.math.abs(roll)
    return if (a < tol) "0°" else "${Math.round(a)}°"
}

/**
 * Pengaturan kamera yang diingat antar-sesi (disimpan sebagai satu string "k=v;k=v" di SharedPreferences).
 * Jepret otomatis sengaja TIDAK disimpan: harus dinyalakan sadar tiap sesi supaya tidak memotret tanpa disadari.
 */
class CamPrefs(
    val grid: Int = 0, val hist: Boolean = false, val level: Boolean = true, val sound: Boolean = true,
    val haptic: Boolean = true, val timer: Int = 0, val flash: Int = 2, val autoZoom: Boolean = false
) {
    fun encode(): String = "g=$grid;h=${b(hist)};l=${b(level)};s=${b(sound)};k=${b(haptic)};t=$timer;f=$flash;z=${b(autoZoom)}"
    private fun b(v: Boolean) = if (v) "1" else "0"

    companion object {
        /** flash: 2 = ImageCapture.FLASH_MODE_OFF (nilai konstanta CameraX: AUTO 0, ON 1, OFF 2). */
        fun decode(s: String?): CamPrefs {
            if (s.isNullOrBlank()) return CamPrefs()
            val m = s.split(';').mapNotNull { kv -> kv.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            fun bo(k: String, d: Boolean) = m[k]?.let { it == "1" } ?: d
            val t = m["t"]?.toIntOrNull()?.takeIf { it in listOf(0, 3, 5, 10) } ?: 0
            val f = m["f"]?.toIntOrNull()?.takeIf { it in 0..2 } ?: 2
            return CamPrefs(
                (m["g"]?.toIntOrNull() ?: 0).coerceIn(0, GRID_MODES - 1), bo("h", false), bo("l", true), bo("s", true),
                bo("k", true), t, f, bo("z", false)
            )
        }
    }
}
