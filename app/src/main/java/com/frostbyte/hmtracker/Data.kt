package com.frostbyte.hmtracker

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

val ID = Locale("id", "ID")

data class Reading(val t: Long, val hm: Double, val svc: Boolean)
data class UnitData(
    val name: String = "", val cat: String = "Alat berat", val type: String = "Excavator",
    val sat: String = "jam", val loc: String = "", val interval: Double = 200.0,
    val operator: String = "", val opId: String = "", val readings: List<Reading> = emptyList(),
    /** Profil format panel/OCR; kosong berarti inferensi aman dari jenis unit. */
    val meterProfileId: String = "",
    /** Mode display: auto, odo, hm_lcd, atau drum. */
    val meterDisplayId: String = DISPLAY_AUTO
)
data class Profile(val name: String = "", val role: String = "", val company: String = "")
/**
 * Bagian yang boleh tampil di watermark. [unit]/[op]/[loc] = nama unit, operator, lokasi; [hm] = tiga angka utama (HM saat ini,
 * terpakai, sisa); [smr] = baris SMR + servis berikutnya; [bar] = bar interval berpersen; [more] = selisih dari pembacaan
 * sebelumnya, laju, dan perkiraan servis; [ref] = baris bukti (sumber waktu, asal angka, sumber foto) + kode Ref.
 */
data class WmShow(
    val unit: Boolean = true, val op: Boolean = true, val loc: Boolean = true,
    val hm: Boolean = true, val smr: Boolean = true, val bar: Boolean = true,
    val more: Boolean = true, val ref: Boolean = true
)

/** Batas ukuran (persen) dan kepekatan kartu (persen) watermark; dipakai slider Atur dan penggambar. */
const val WM_SCALE_MIN = 35
const val WM_SCALE_MAX = 120
const val WM_ALPHA_MIN = 10
/** Ukuran lama (sm/md/lg) -> persen, untuk data dan backup dari versi sebelumnya. */
fun wmScaleOf(legacy: String): Int = when (legacy) { "sm" -> 55; "lg" -> 100; else -> 80 }
data class AppData(
    val profile: Profile = Profile(), val units: List<UnitData> = listOf(UnitData()), val active: Int = 0,
    val acc: String = "#FFB020", val mode: String = "auto", val fs: String = "md",
    val wmTpl: String = "pro", val wmScale: Int = 80, val wmAlpha: Int = 88, val wmGps: Boolean = true,
    val wmShow: WmShow = WmShow(), val det: Boolean = true, val gal: Boolean = true,
    /** Paket layanan: basic | premium | platinum (lihat Tier.kt). */
    val tier: String = "basic"
) {
    val unit: UnitData get() = units[active.coerceIn(0, units.lastIndex)]
}
data class Photo(val t: Long, val u: String, val hm: Double)

/** Jejak audit satu foto: asal angka (OCR live / OCR foto / manual), keutuhan file (SHA-256), dan kondisi waktu/GPS. */
data class Audit(
    val t: Long, val unit: String, val live: String, val photo: String, val value: Double,
    val edited: Boolean, val skewMs: Long, val gps: Boolean, val warn: String, val sha: String = "",
    val n: Long = 0, val prev: String = "", val sig: String = "", val src: String = ""   // n/prev/sig: rantai bertanda tangan; src: cam | gal | imp
)

val TYPES = linkedMapOf(
    "Angkut" to listOf("Dump truck (DT)", "Truk tangki", "Truk lainnya"),
    "Kendaraan ringan (LV)" to listOf("LV / light vehicle", "Pickup", "Double cabin", "Bus", "Kendaraan ringan lainnya"),
    "Farm tractor" to listOf("John Deere", "Kubota", "Traktor lainnya"),
    "Alat berat" to listOf("Excavator", "Dozer", "Grader", "Wheel loader", "Lainnya")
)
val ACCENTS = listOf("#FFB020", "#30D158", "#0A84FF", "#BF5AF2", "#FF453A", "#64D2FF", "#FF9F0A", "#AC8E68")

// ===== mesin hitungan HM =====
data class Calc(val b: Double, val c: Double, val used: Double, val rem: Double, val pct: Double, val st: String, val est: Long?)

fun UnitData.un() = if (sat == "km") "km" else "jam"
fun UnitData.cfg() = name.isNotEmpty() && readings.isNotEmpty()
fun emptyCalc(u: UnitData) = Calc(0.0, 0.0, 0.0, if (u.interval > 0) u.interval else 200.0, 0.0, "ok", null)

/**
 * Ambang status sisa interval: (kritis, peringatan). Jam: 10 dan 20 jam. Km: 2% dan 5% interval (minimal 10 dan 20 km),
 * sebab interval km (mis. 10.000 km) jauh lebih besar sehingga ambang tetap 10/20 km baru memperingatkan sehari-dua
 * hari sebelum jatuh tempo, terlambat untuk truk yang menempuh ratusan km per hari.
 */
fun warnLevels(u: UnitData): Pair<Double, Double> {
    val iv = if (u.interval > 0) u.interval else 200.0
    return if (u.sat == "km") maxOf(10.0, iv * 0.02) to maxOf(20.0, iv * 0.05) else 10.0 to 20.0
}

/** Bulatkan ke 2 desimal supaya selisih desimal biner (mis. 100.4 - 100.1 = 0.30000000000000426) tidak bocor ke tampilan. */
private fun r2(x: Double) = Math.round(x * 100.0) / 100.0

fun calc(u: UnitData): Calc {
    val r = u.readings
    var s = 0
    for (i in r.indices) if (r[i].svc) s = i
    val b = r.getOrNull(s)?.hm ?: 0.0
    val c = r.lastOrNull()?.hm ?: 0.0
    val iv = if (u.interval > 0) u.interval else 200.0
    val used = r2(maxOf(0.0, c - b))
    val rem = r2(iv - used)
    val pct = (used / iv * 100).coerceIn(0.0, 100.0)
    val (er, wa) = warnLevels(u)
    val st = if (rem < er) "er" else if (rem < wa) "wa" else "ok"
    // perkiraan tanggal servis memakai laju terbaru (4 pembacaan terakhir) seperti prediksi di Analitik, bukan rata-rata
    // seluruh periode yang menyamarkan lonjakan pemakaian
    var est: Long? = null
    val rate = ratePerDay(u, tail = 4) ?: ratePerDay(u)
    if (rate != null && rate > 0 && r.isNotEmpty()) est = (r.last().t + maxOf(0.0, rem) / rate * 864e5).toLong()
    return Calc(b, c, used, rem, pct, st, est)
}

fun diffs(u: UnitData): List<Double> {
    val r = u.readings
    val a = ArrayList<Double>()
    for (i in 1 until r.size) a.add(if (r[i].svc) 0.0 else maxOf(0.0, r[i].hm - r[i - 1].hm))
    return a.takeLast(7)
}

fun f1(n: Double): String = String.format(ID, "%,.1f", n)
/** Angka tanpa desimal bila bulat ("200"), selain itu satu desimal ("12,5"): untuk teks ambang. */
fun n0(n: Double): String = if (n % 1.0 == 0.0) String.format(ID, "%,d", n.toLong()) else f1(n)
fun num(s: String): Double? = s.trim().replace(",", ".").toDoubleOrNull()
fun dt(t: Long): String = SimpleDateFormat("dd MMM yyyy, HH.mm", ID).format(Date(t))
fun galName(u: String, t: Long): String =
    "HM_" + u.ifEmpty { "unit" }.replace(Regex("[^\\w\\-]+"), "_") + "_" +
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(t)) + ".jpg"

// ===== penyimpanan (format JSON sama dengan versi web, jadi backup web bisa dipulihkan) =====
const val SCHEMA = 2
const val MAX_PHOTOS = 200

fun AppData.toJson(): JSONObject = JSONObject().apply {
    put("schema", SCHEMA)
    put("profile", JSONObject().put("name", profile.name).put("role", profile.role).put("company", profile.company))
    put("units", JSONArray().also { arr ->
        units.forEach { u ->
            arr.put(JSONObject().put("name", u.name).put("cat", u.cat).put("type", u.type).put("sat", u.sat)
                .put("loc", u.loc).put("interval", u.interval).put("operator", u.operator).put("opId", u.opId)
                .put("meterProfile", u.meterProfileId).put("meterDisplay", u.meterDisplayId)
                .put("readings", JSONArray().also { ra ->
                    u.readings.forEach { ra.put(JSONObject().put("t", it.t).put("hm", it.hm).put("svc", it.svc)) }
                }))
        }
    })
    put("active", active); put("acc", acc); put("mode", mode); put("fs", fs)
    put("wmTpl", wmTpl); put("wmScale", wmScale); put("wmAlpha", wmAlpha); put("wmGps", wmGps)
    put("wmShow", JSONObject().put("unit", wmShow.unit).put("op", wmShow.op).put("loc", wmShow.loc)
        .put("hm", wmShow.hm).put("smr", wmShow.smr).put("bar", wmShow.bar).put("more", wmShow.more).put("ref", wmShow.ref))
    put("det", det); put("gal", gal); put("tier", tier)
}

fun Audit.toJson(): JSONObject = JSONObject().put("t", t).put("unit", unit).put("live", live).put("photo", photo)
    .put("value", value).put("edited", edited).put("skew", skewMs).put("gps", gps).put("warn", warn).put("sha", sha)
    .put("n", n).put("prev", prev).put("sig", sig).put("src", src)

fun auditFrom(o: JSONObject) = Audit(
    o.getLong("t"), o.optString("unit"), o.optString("live"), o.optString("photo"), o.optDouble("value", 0.0),
    o.optBoolean("edited"), o.optLong("skew"), o.optBoolean("gps"), o.optString("warn"), o.optString("sha"),
    o.optLong("n"), o.optString("prev"), o.optString("sig"), o.optString("src")
)

fun parseApp(j: JSONObject): AppData? {
    val ua = j.optJSONArray("units") ?: return null
    if (ua.length() == 0) return null
    val units = (0 until ua.length()).map { i ->
        val o = ua.getJSONObject(i)
        val ra = o.optJSONArray("readings") ?: JSONArray()
        UnitData(
            name = o.optString("name"), cat = o.optString("cat", "Alat berat").ifEmpty { "Alat berat" },
            type = o.optString("type", "Excavator"), sat = if (o.optString("sat") == "km") "km" else "jam",
            loc = o.optString("loc"), interval = o.optDouble("interval", 200.0).takeIf { it > 0 } ?: 200.0,
            operator = o.optString("operator"), opId = o.optString("opId"),
            readings = (0 until ra.length()).map { k ->
                val r = ra.getJSONObject(k); Reading(r.optLong("t"), r.optDouble("hm", 0.0), r.optBoolean("svc"))
            }, meterProfileId = o.optString("meterProfile", ""), meterDisplayId = o.optString("meterDisplay", DISPLAY_AUTO)
        )
    }
    return cfgFrom(j, units)
}

/** Pengaturan + profil dari JSON, digabung dengan daftar unit yang sudah dibaca. */
fun cfgFrom(j: JSONObject, units: List<UnitData>): AppData {
    val p = j.optJSONObject("profile"); val w = j.optJSONObject("wmShow")
    return AppData(
        profile = Profile(p?.optString("name") ?: "", p?.optString("role") ?: "", p?.optString("company") ?: ""),
        units = units, active = j.optInt("active", 0).coerceIn(0, units.lastIndex),
        acc = j.optString("acc", "#FFB020"), mode = j.optString("mode", "auto"), fs = j.optString("fs", "md"),
        wmTpl = j.optString("wmTpl", "pro"),
        wmScale = (if (j.has("wmScale")) j.optInt("wmScale", 80) else wmScaleOf(j.optString("wmSize", "md"))).coerceIn(WM_SCALE_MIN, WM_SCALE_MAX),
        wmAlpha = j.optInt("wmAlpha", 88).coerceIn(WM_ALPHA_MIN, 100), wmGps = j.optBoolean("wmGps", true),
        wmShow = WmShow(
            w?.optBoolean("unit", true) ?: true, w?.optBoolean("op", true) ?: true, w?.optBoolean("loc", true) ?: true,
            w?.optBoolean("hm", true) ?: true, w?.optBoolean("smr", true) ?: true, w?.optBoolean("bar", true) ?: true,
            w?.optBoolean("more", true) ?: true, w?.optBoolean("ref", true) ?: true
        ),
        det = j.optBoolean("det", true), gal = j.optBoolean("gal", true),
        tier = tierOf(j.optString("tier", "basic")).id
    )
}

// ===== penyimpanan SQLite (menggantikan SharedPreferences JSON; data lama dimigrasi otomatis) =====

private const val AUDIT_COLS = "t,unit,live,photo,value,edited,skew,gps,warn,sha,n,prev,sig,src"

private class Db(ctx: Context) : SQLiteOpenHelper(ctx, "hmt.db", null, 4) {
    init { setWriteAheadLoggingEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT NOT NULL)")
        db.execSQL("CREATE TABLE unit(idx INTEGER PRIMARY KEY, name TEXT, cat TEXT, type TEXT, sat TEXT, loc TEXT, interval REAL, operator TEXT, opId TEXT, meter_profile TEXT DEFAULT '', meter_display TEXT DEFAULT 'auto')")
        db.execSQL("CREATE TABLE reading(unit_idx INTEGER NOT NULL, seq INTEGER NOT NULL, t INTEGER NOT NULL, hm REAL NOT NULL, svc INTEGER NOT NULL, PRIMARY KEY(unit_idx, seq))")
        db.execSQL("CREATE TABLE photo(t INTEGER PRIMARY KEY, u TEXT, hm REAL)")
        db.execSQL("CREATE TABLE audit(t INTEGER PRIMARY KEY, unit TEXT, live TEXT, photo TEXT, value REAL, edited INTEGER, skew INTEGER, gps INTEGER, warn TEXT, sha TEXT, n INTEGER DEFAULT 0, prev TEXT DEFAULT '', sig TEXT DEFAULT '', src TEXT DEFAULT '')")
    }
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        if (o < 2) listOf("n INTEGER DEFAULT 0", "prev TEXT DEFAULT ''", "sig TEXT DEFAULT ''", "src TEXT DEFAULT ''")
            .forEach { db.execSQL("ALTER TABLE audit ADD COLUMN $it") }
        if (o < 3) {
            db.execSQL("ALTER TABLE unit ADD COLUMN meter_profile TEXT DEFAULT ''")
        }
        if (o < 4) db.execSQL("ALTER TABLE unit ADD COLUMN meter_display TEXT DEFAULT 'auto'")
    }
}

class Store(private val ctx: Context, private val signer: Signer = KeystoreSigner) {
    private val sp = ctx.getSharedPreferences("hmt1", Context.MODE_PRIVATE)   // hanya untuk migrasi data lama
    private val db: SQLiteDatabase = Db(ctx).writableDatabase
    private val io = Executors.newSingleThreadExecutor()                       // tulis pengaturan/unit di luar main thread, berurutan
    private val dir = File(ctx.filesDir, "photos").also { it.mkdirs() }
    private val origDir = File(ctx.filesDir, "orig").also { it.mkdirs() }

    init { migrateLegacy() }

    // ---- meta ----
    private fun meta(k: String): String? =
        db.rawQuery("SELECT v FROM meta WHERE k=?", arrayOf(k)).use { if (it.moveToFirst()) it.getString(0) else null }

    // ---- data utama ----
    fun load(): AppData = try { readAll() ?: AppData() } catch (e: Exception) { AppData() }

    private fun readAll(): AppData? {
        val cfg = meta("cfg") ?: return null
        val units = ArrayList<UnitData>()
            db.rawQuery("SELECT idx,name,cat,type,sat,loc,interval,operator,opId,meter_profile,meter_display FROM unit ORDER BY idx", null).use { c ->
            while (c.moveToNext()) {
                val rd = ArrayList<Reading>()
                db.rawQuery("SELECT t,hm,svc FROM reading WHERE unit_idx=? ORDER BY seq", arrayOf(c.getInt(0).toString())).use { r ->
                    while (r.moveToNext()) rd += Reading(r.getLong(0), r.getDouble(1), r.getInt(2) != 0)
                }
                units += UnitData(
                    name = c.getString(1) ?: "", cat = c.getString(2) ?: "Alat berat", type = c.getString(3) ?: "Excavator",
                    sat = c.getString(4) ?: "jam", loc = c.getString(5) ?: "", interval = c.getDouble(6),
                    operator = c.getString(7) ?: "", opId = c.getString(8) ?: "", readings = rd,
                    meterProfileId = c.getString(9) ?: "", meterDisplayId = c.getString(10) ?: DISPLAY_AUTO
                )
            }
        }
        if (units.isEmpty()) return null
        val d = cfgFrom(JSONObject(cfg), units)
        return d.copy(tier = verifiedTier(d.tier, meta("tier_sig"), signer).id)
    }

    /** Simpan di thread terpisah dan atomik (satu transaksi). Objek [d] immutable, aman dibaca dari thread lain. */
    fun save(d: AppData) { io.execute { runCatching { writeAll(d) } } }

    private fun writeAll(d: AppData) {
        db.beginTransaction()
        try {
            db.delete("unit", null, null); db.delete("reading", null, null)
            d.units.forEachIndexed { i, u ->
                db.insert("unit", null, ContentValues().apply {
                    put("idx", i); put("name", u.name); put("cat", u.cat); put("type", u.type); put("sat", u.sat)
                    put("loc", u.loc); put("interval", u.interval); put("operator", u.operator); put("opId", u.opId); put("meter_profile", u.meterProfileId); put("meter_display", u.meterDisplayId)
                })
                u.readings.forEachIndexed { k, r ->
                    db.insert("reading", null, ContentValues().apply {
                        put("unit_idx", i); put("seq", k); put("t", r.t); put("hm", r.hm); put("svc", if (r.svc) 1 else 0)
                    })
                }
            }
            val cfg = d.toJson().also { it.remove("units") }.toString()
            db.execSQL("INSERT OR REPLACE INTO meta(k,v) VALUES('cfg', ?)", arrayOf<Any?>(cfg))
            val tsig = tierSig(signer, tierOf(d.tier)).takeIf { tierOf(d.tier) != Tier.BASIC }
            if (tsig != null) db.execSQL("INSERT OR REPLACE INTO meta(k,v) VALUES('tier_sig', ?)", arrayOf<Any?>(tsig))
            else db.delete("meta", "k=?", arrayOf("tier_sig"))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    // ---- foto ----
    fun photos(): List<Photo> = try {
        val l = ArrayList<Photo>()
        db.rawQuery("SELECT t,u,hm FROM photo ORDER BY t DESC", null).use { c ->
            while (c.moveToNext()) l += Photo(c.getLong(0), c.getString(1) ?: "-", c.getDouble(2))
        }
        l.filter { file(it.t).exists() }
    } catch (e: Exception) { emptyList() }

    fun file(t: Long) = File(dir, "$t.jpg")
    fun origFile(t: Long) = File(origDir, "$t.jpg")
    fun hasOrig(t: Long) = origFile(t).exists()
    fun origBitmap(t: Long): Bitmap? = BitmapFactory.decodeFile(origFile(t).path)

    /**
     * Simpan foto ber-watermark, foto asli, dan jejak audit (dengan SHA-256 file hasil).
     * Foto terlama dipangkas bila melebihi [MAX_PHOTOS], tetapi jejak audit TIDAK pernah dihapus.
     */
    @Suppress("UNUSED_PARAMETER")
    fun addPhoto(bm: Bitmap, p: Photo, cur: List<Photo>, orig: Bitmap? = null, audit: Audit? = null): List<Photo> {
        file(p.t).outputStream().use { bm.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        if (orig != null) runCatching { origFile(p.t).outputStream().use { orig.compress(Bitmap.CompressFormat.JPEG, 92, it) } }
        db.beginTransaction()
        try {
            db.execSQL("INSERT OR REPLACE INTO photo(t,u,hm) VALUES(?,?,?)", arrayOf<Any?>(p.t, p.u, p.hm))
            if (audit != null) putAudit(signNext(audit.copy(sha = sha256(file(p.t)))), replace = true)
            val old = ArrayList<Long>()
            db.rawQuery("SELECT t FROM photo ORDER BY t DESC LIMIT -1 OFFSET $MAX_PHOTOS", null).use { while (it.moveToNext()) old += it.getLong(0) }
            old.forEach { t -> db.delete("photo", "t=?", arrayOf(t.toString())); file(t).delete(); origFile(t).delete() }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return photos()
    }

    @Suppress("UNUSED_PARAMETER")
    fun removePhoto(t: Long, cur: List<Photo>): List<Photo> {
        file(t).delete(); origFile(t).delete()
        db.delete("photo", "t=?", arrayOf(t.toString()))
        return photos()
    }

    fun clearAll() {
        io.submit {
            db.beginTransaction()
            try { listOf("meta", "unit", "reading", "photo", "audit").forEach { db.delete(it, null, null) }; db.setTransactionSuccessful() }
            finally { db.endTransaction() }
        }.get()
        sp.edit().clear().apply(); dir.listFiles()?.forEach { it.delete() }; origDir.listFiles()?.forEach { it.delete() }
        db.execSQL("INSERT OR REPLACE INTO meta(k,v) VALUES('migrated','1')")
        ctx.getSharedPreferences("hmt1auth", Context.MODE_PRIVATE).edit().clear().apply()
        ow.edit().clear().apply()
    }

    fun bitmap(t: Long, sample: Int = 1): Bitmap? =
        BitmapFactory.decodeFile(file(t).path, BitmapFactory.Options().apply { inSampleSize = sample })

    // ---- jejak audit (tanpa batas jumlah) ----
    private fun putAudit(a: Audit, replace: Boolean) {
        db.execSQL(
            "INSERT OR ${if (replace) "REPLACE" else "IGNORE"} INTO audit($AUDIT_COLS) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(a.t, a.unit, a.live, a.photo, a.value, if (a.edited) 1 else 0, a.skewMs, if (a.gps) 1 else 0, a.warn, a.sha, a.n, a.prev, a.sig, a.src)
        )
    }
    private fun auditAt(c: Cursor) = Audit(
        c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "", c.getDouble(4),
        c.getInt(5) != 0, c.getLong(6), c.getInt(7) != 0, c.getString(8) ?: "", c.getString(9) ?: "",
        c.getLong(10), c.getString(11) ?: "", c.getString(12) ?: "", c.getString(13) ?: ""
    )

    /** (nomor urut, tanda tangan sebelumnya) yang akan dipakai entri audit berikutnya. Dipakai juga untuk kode Ref di watermark. */
    fun nextAuditSlot(): Pair<Long, String> {
        val n = db.rawQuery("SELECT COALESCE(MAX(n),0) FROM audit", null).use { if (it.moveToFirst()) it.getLong(0) else 0L } + 1
        val prev = db.rawQuery("SELECT sig FROM audit WHERE sig<>'' ORDER BY n DESC, t DESC LIMIT 1", null)
            .use { if (it.moveToFirst()) it.getString(0) ?: "" else "" }
        return n to prev
    }

    /** Kode Ref untuk entri dengan isi ini; null bila kunci Keystore tidak tersedia. */
    fun proofFor(n: Long, t: Long, unit: String, value: Double, prev: String): String? = proofCode(signer, n, t, unit, value, prev)

    /** Cari entri bertanda tangan yang kode Ref-nya sama dengan [code] (format bebas, mis. "3f9a27c1"). Panggil dari thread IO. */
    fun findByRef(code: String): Audit? {
        val want = normRef(code)
        if (want.length != 8) return null
        return audits().firstOrNull { a ->
            a.sig.isNotEmpty() && proofCode(signer, a.n, a.t, a.unit, a.value, a.prev)?.let { normRef(it) == want } == true
        }
    }

    /** Beri nomor urut, sambungkan ke tanda tangan entri bertanda tangan terakhir, lalu tandatangani. Gagal tanda tangan = entri tanpa tanda tangan. */
    private fun signNext(a: Audit): Audit {
        val (n, prev) = nextAuditSlot()
        val base = a.copy(n = n, prev = prev, sig = "")
        return base.copy(sig = signer.sign(canonical(base)) ?: "")
    }

    /** Periksa rantai audit. [only] != null: hash file hanya dihitung untuk satu foto (lebih cepat untuk layar detail). */
    fun verifyAll(d: AppData, only: Long? = null): Map<Long, Verdict> {
        val readings = d.units.flatMap { it.readings }.associateBy { it.t }
        return verifyAudits(
            audits(), signer,
            fileSha = { a -> if (only != null && a.t != only) a.sha.ifEmpty { null } else file(a.t).takeIf { it.exists() }?.let { sha256(it) } },
            hmOf = { a -> readings[a.t]?.hm }
        )
    }

    fun audit(t: Long): Audit? = try {
        db.rawQuery("SELECT $AUDIT_COLS FROM audit WHERE t=?", arrayOf(t.toString())).use { if (it.moveToFirst()) auditAt(it) else null }
    } catch (e: Exception) { null }

    fun audits(): List<Audit> = try {
        val l = ArrayList<Audit>()
        db.rawQuery("SELECT $AUDIT_COLS FROM audit ORDER BY t DESC", null).use { while (it.moveToNext()) l += auditAt(it) }
        l
    } catch (e: Exception) { emptyList() }

    /** Gabungkan jejak audit dari backup tanpa menimpa entri yang sudah ada. */
    fun importAudits(arr: JSONArray?) {
        if (arr == null) return
        db.beginTransaction()
        try {
            for (i in 0 until arr.length()) runCatching { putAudit(auditFrom(arr.getJSONObject(i)).copy(n = 0, prev = "", sig = "", src = "imp"), replace = false) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    // ---- migrasi dari SharedPreferences (versi 1.0/1.1 awal) ----
    private fun migrateLegacy() {
        if (meta("migrated") != null) return
        val legacy = sp.getString("S", null)
        if (legacy != null) {
            runCatching { parseApp(JSONObject(legacy)) }.getOrNull()?.let { writeAll(it) }
            runCatching {
                val a = JSONArray(sp.getString("P", "[]"))
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    db.execSQL("INSERT OR REPLACE INTO photo(t,u,hm) VALUES(?,?,?)", arrayOf<Any?>(o.getLong("t"), o.optString("u", "-"), o.optDouble("hm", 0.0)))
                }
            }
            runCatching {
                val a = JSONArray(sp.getString("A", "[]"))
                for (i in 0 until a.length()) putAudit(auditFrom(a.getJSONObject(i)), replace = false)
            }
        }
        db.execSQL("INSERT OR REPLACE INTO meta(k,v) VALUES('migrated','1')")
        if (legacy != null) sp.edit().remove("S").remove("P").remove("A").apply()
    }

    // ---- sesi login ----
    private val au = ctx.getSharedPreferences("hmt1auth", Context.MODE_PRIVATE)
    fun isLogged() = au.getBoolean("ok", false) && au.getLong("exp", 0) > System.currentTimeMillis()
    fun saveAuth(ident: String, mode: String) {
        au.edit().putBoolean("ok", true).putString("ident", ident).putString("mode", mode)
            .putLong("exp", System.currentTimeMillis() + 30L * 24 * 3600 * 1000).apply()
    }
    fun clearAuth() {
        // simpan pemilik data sebelum sesi dihapus, supaya login berikutnya dengan akun lain bisa dikenali
        if (owner() == null) au.getString("ident", null)?.let { setOwner(it) }
        au.edit().clear().apply()
    }

    // ---- pemilik data: data HM hanya milik satu akun; ganti akun = data lama dihapus ----
    private val ow = ctx.getSharedPreferences("hmt1owner", Context.MODE_PRIVATE)
    /** Identitas (email/nomor) pemilik data saat ini; null bila belum pernah ada yang login. */
    fun owner(): String? = ow.getString("ident", null) ?: au.getString("ident", null)
    fun setOwner(ident: String) { ow.edit().putString("ident", ident).apply() }
}

fun sha256(f: File): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    f.inputStream().use { st ->
        val b = ByteArray(8192)
        while (true) { val n = st.read(b); if (n < 0) break; md.update(b, 0, n) }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/** Simpan ke galeri HP (Pictures/HM Tracker) dari bitmap. */
fun saveToGallery(ctx: Context, bm: Bitmap, name: String): Boolean =
    saveGallery(ctx, name) { bm.compress(Bitmap.CompressFormat.JPEG, 92, it) }

/** Salin file apa adanya ke galeri (tanpa kompres ulang) agar SHA-256 di jejak audit tetap cocok. */
fun saveFileToGallery(ctx: Context, src: File, name: String): Boolean =
    saveGallery(ctx, name) { os -> src.inputStream().use { it.copyTo(os) } }

private fun saveGallery(ctx: Context, name: String, write: (java.io.OutputStream) -> Unit): Boolean = try {
    if (Build.VERSION.SDK_INT >= 29) {
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/HM Tracker")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)!!
        ctx.contentResolver.openOutputStream(uri)!!.use { write(it) }
    } else {
        val d = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "HM Tracker").also { it.mkdirs() }
        val f = File(d, name)
        f.outputStream().use { write(it) }
        @Suppress("DEPRECATION")
        ctx.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, android.net.Uri.fromFile(f)))
    }
    true
} catch (e: Exception) { false }
