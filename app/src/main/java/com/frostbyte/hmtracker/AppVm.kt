package com.frostbyte.hmtracker

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel

class AppVm(app: Application) : AndroidViewModel(app) {
    val store = Store(app)
    val trainer = Trainer(app)
    var d by mutableStateOf(store.load())
        private set
    var photos by mutableStateOf(store.photos())
        private set
    var tab by mutableStateOf("home")
    var camOpen by mutableStateOf(false)
    var cm by mutableStateOf("")            // "" = foto biasa, "init" = HM awal setelah servis
    var logged by mutableStateOf(store.isLogged())
    var viewPhoto by mutableStateOf<Photo?>(null)
    var stopAlert by mutableStateOf(false)
    var frame by mutableStateOf<Bitmap?>(null)   // foto yang sedang di-review
    var frameRoi by mutableStateOf<Roi?>(null)   // area angka pada foto (null bila dari galeri)
    var frameSrc by mutableStateOf("cam")       // asal foto yang di-review: cam | gal
    var frameWarn by mutableStateOf<Issue?>(null) // peringatan kualitas foto (buram/silau/kontras rendah)
    var toastMsg by mutableStateOf<String?>(null)
    var toastId by mutableIntStateOf(0)
    var shutter: (() -> Unit)? = null        // dipasang CameraScreen selama kamera terbuka; dipanggil tombol volume
    var rev by mutableIntStateOf(0)         // naik saat data diganti dari luar (restore, tambah/hapus unit)

    fun upd(f: (AppData) -> AppData) { d = f(d); store.save(d) }
    fun toast(m: String) { toastMsg = m; toastId++ }
    fun go(t: String) { tab = t }
    fun openCam(mode: String = "") { cm = mode; camOpen = true }

    fun updUnit(i: Int, f: (UnitData) -> UnitData) = upd { a -> a.copy(units = a.units.mapIndexed { k, u -> if (k == i) f(u) else u }) }
    fun addHM(i: Int, hm: Double, svc: Boolean, t: Long = TrustedClock.now()) = updUnit(i) { u ->
        u.copy(readings = u.readings + Reading(t, hm, svc || u.readings.isEmpty()))
    }
    fun addUnit() { upd { it.copy(units = it.units + UnitData(), active = it.units.size) }; rev++; go("set") }
    fun delUnit(i: Int) = upd { it.copy(units = it.units.filterIndexed { k, _ -> k != i }, active = 0) }.also { rev++ }
    fun delReading(i: Int) = updUnit(d.active) { u ->
        val l = u.readings.toMutableList().also { it.removeAt(i) }
        u.copy(readings = if (l.isNotEmpty() && l.none { it.svc }) listOf(l[0].copy(svc = true)) + l.drop(1) else l)
    }

    fun addPhoto(bm: Bitmap, p: Photo, orig: Bitmap? = null, audit: Audit? = null) { photos = store.addPhoto(bm, p, photos, orig, audit) }
    fun removePhoto(t: Long) { photos = store.removePhoto(t, photos) }

    /** Backup lengkap: data HM + pengaturan + seluruh jejak audit (schema 2). Foto tetap di perangkat. */
    fun backupJson(): String = d.toJson().put("audits", org.json.JSONArray().also { a -> store.audits().forEach { a.put(it.toJson()) } }).toString()

    fun restore(json: String): Boolean = try {
        val j = org.json.JSONObject(json)
        if (j.optInt("schema", 1) > SCHEMA) throw IllegalArgumentException("backup dari versi lebih baru")
        val a = parseApp(j) ?: throw IllegalArgumentException()
        val a2 = a.copy(tier = d.tier)   // backup tidak pernah memberi paket; paket terikat pada perangkat ini
        d = a2; store.save(a2); store.importAudits(j.optJSONArray("audits")); rev++; tab = "home"; true
    } catch (e: Exception) { false }

    fun resetAll() { store.clearAll(); d = AppData(); photos = emptyList(); logged = false; rev++ }
    /** True bila [ident] berbeda dari pemilik data di perangkat ini, sehingga login akan menghapus data lama. */
    fun needsWipe(ident: String): Boolean { val o = store.owner(); return o != null && o != ident }
    fun login(ident: String, mode: String) {
        if (needsWipe(ident)) {
            val tier = d.tier                      // paket terikat pada perangkat, bukan akun
            store.clearAll(); d = AppData(tier = tier); store.save(d); photos = emptyList(); rev++
        }
        store.saveAuth(ident, mode); store.setOwner(ident); logged = true; tab = "home"
    }
    fun logout() { store.clearAuth(); logged = false }
}
