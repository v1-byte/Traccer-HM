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

    fun reset() { value = ""; since = 0L }
}

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
