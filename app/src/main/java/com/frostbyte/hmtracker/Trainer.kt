package com.frostbyte.hmtracker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Pusat sistem latih. Semua data tinggal di HP: filesDir/training/
 *   profile.json   statistik hasil belajar (lihat [Learned])
 *   samples/       potongan foto area angka (jpg) + samples.jsonl (angka benar tiap sampel)
 *   digits.tflite, digits.json   model sendiri (opsional)
 */
class Trainer(private val ctx: Context) {
    private val dir = File(ctx.filesDir, "training").also { it.mkdirs() }
    private val sdir = File(dir, "samples").also { it.mkdirs() }
    private val pf = File(dir, "profile.json")
    private val lf = File(dir, "samples.jsonl")
    private val prefs = ctx.getSharedPreferences("hm_train", Context.MODE_PRIVATE)
    private val io = Executors.newSingleThreadExecutor()

    var learned by mutableStateOf(loadLearned())
        private set
    var samples by mutableStateOf(readSamples())
        private set
    @Volatile var model: DigitModel? = null
        private set
    var modelOn by mutableStateOf(false)
        private set

    /** Belajar otomatis dari tiap bacaan yang disimpan (default hidup). Sampel manual tidak terpengaruh. */
    var autoLearn: Boolean
        get() = prefs.getBoolean("auto", true)
        set(v) { prefs.edit().putBoolean("auto", v).apply() }

    init { reloadModel() }

    // ===== belajar =====

    private fun loadLearned(): Learned = try {
        if (pf.exists()) learnedFrom(JSONObject(pf.readText())) else Learned()
    } catch (e: Exception) { Learned() }

    private fun persist(l: Learned) {
        io.execute {
            runCatching {
                val tmp = File(dir, "profile.json.tmp")
                tmp.writeText(l.toJson().toString())
                tmp.renameTo(pf)
            }
        }
    }

    /** Catat satu bacaan yang sudah dikonfirmasi pengguna. */
    fun record(unit: String, truth: String, read: String, winners: List<String>, w: Int = 1) {
        learned = learned.withEvent(unit, truth, read, winners, w).also { persist(it) }
    }

    fun replaceLearned(l: Learned) { learned = l; persist(l) }

    fun resetLearning() { replaceLearned(Learned()) }

    // ===== sampel =====

    private fun readSamples(): List<Sample> = try {
        if (!lf.exists()) emptyList()
        else lf.readLines().mapNotNull { runCatching { sampleFrom(JSONObject(it)) }.getOrNull() }.filter { File(sdir, "${it.id}.jpg").exists() }
    } catch (e: Exception) { emptyList() }

    private fun rewrite(list: List<Sample>) {
        runCatching { lf.writeText(list.joinToString("") { it.toJson().toString() + "\n" }) }
    }

    /** Simpan potongan area angka [crop] dengan angka benar [label]. [auto] = hasil belajar otomatis (dibatasi [MAX_AUTO]); sampel manual tidak pernah dibuang otomatis. */
    fun addSample(crop: Bitmap, unit: String, label: String, src: String, auto: Boolean, meta: DatasetMeta = DatasetMeta(exactText = label, unit = unit)): Boolean {
        return try {
            val id = System.currentTimeMillis()
            val bm = if (crop.width > 640) Bitmap.createScaledBitmap(crop, 640, (crop.height * 640f / crop.width).toInt().coerceAtLeast(8), true) else crop
            File(sdir, "$id.jpg").outputStream().use { bm.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            val s = Sample(id, unit.ifEmpty { "-" }, label, src, auto, meta.copy(exactText = meta.exactText.ifEmpty { label }, unit = meta.unit.ifEmpty { unit.ifEmpty { "-" } }))
            lf.appendText(s.toJson().toString() + "\n")
            var all = samples + s
            val autos = all.filter { it.auto }
            if (autos.size > MAX_AUTO) {
                val drop = autos.sortedBy { it.id }.take(autos.size - MAX_AUTO).map { it.id }.toSet()
                drop.forEach { File(sdir, "$it.jpg").delete() }
                all = all.filter { it.id !in drop }
                rewrite(all)
            }
            samples = all
            true
        } catch (e: Exception) { false }
    }

    fun removeSample(id: Long) {
        File(sdir, "$id.jpg").delete()
        samples = samples.filter { it.id != id }.also { rewrite(it) }
    }

    fun clearSamples() {
        samples.forEach { File(sdir, "${it.id}.jpg").delete() }
        samples = emptyList(); rewrite(emptyList())
    }

    fun sampleBitmap(id: Long): Bitmap? = BitmapFactory.decodeFile(File(sdir, "$id.jpg").path)

    // ===== latih ulang + uji akurasi =====

    /**
     * Baca ulang semua sampel dengan semua lintasan ML Kit, bangun ulang statistik dari nol, dan ukur akurasi SECARA BERURUTAN:
     * tiap sampel diuji dengan statistik dari sampel sebelumnya saja, jadi angka "belajar" tidak melihat jawabannya sendiri.
     * Model sendiri (bila terpasang) diukur terpisah; sampel yang ikut melatihnya membuat angka itu terlalu optimistis.
     */
    suspend fun retrain(progress: (Int, Int) -> Unit): TrainReport = withContext(Dispatchers.Default) {
        val rc = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val list = samples.filter { it.meta.targetStatus == "target" && it.label.isNotBlank() }.sortedBy { it.id }
            val mdl = model
            var l = Learned(); var n = 0; var base = 0; var withL = 0; var withM = 0
            val passOk = HashMap<String, Int>()
            for (s in list) {
                val bm = sampleBitmap(s.id) ?: continue
                val fr = try { readFinalDetailed(rc, bm, Roi(0, 0, bm.width, bm.height), null, drum = s.meta.displayMode == DISPLAY_DRUM) } catch (e: Exception) { null }
                n++
                if (fr != null) {
                    if (fr.ranked.firstOrNull() == s.label) base++
                    if (l.refine(s.unit, fr.ranked).firstOrNull() == s.label) withL++
                    val winners = fr.passes.filter { it.second.firstOrNull() == s.label }.map { it.first }.distinct()
                    winners.forEach { passOk[it] = (passOk[it] ?: 0) + 1 }
                    l = l.withEvent(s.unit, s.label, fr.ranked.firstOrNull() ?: "", winners, if (s.auto) 1 else 3)
                }
                if (mdl != null) mdl.read(bm)?.let { if (normalizeCandidate(it.text) == s.label) withM++ }
                progress(n, list.size)
            }
            TrainReport(n, base, withL, if (mdl != null) withM else null, passOk, l)
        } finally { rc.close() }
    }

    // ===== ekspor dataset / model sendiri =====

    /** Tulis dataset (berkas gambar JPG dan labels.csv) ke [uri] sebagai zip untuk dilatih di komputer. */
    fun exportTo(uri: Uri): Boolean = try {
        ctx.contentResolver.openOutputStream(uri)?.use { os ->
            ZipOutputStream(os.buffered()).use { z ->
                val sb = StringBuilder(DATASET_SCHEMA_FIELDS.joinToString(",") + "\n")
                for (s in samples.sortedBy { it.id }) {
                    val f = File(sdir, "${s.id}.jpg"); if (!f.exists()) continue
                    z.putNextEntry(ZipEntry("images/${s.id}.jpg")); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
                    val m = s.meta
                    val row = listOf(s.id.toString(), "images/${s.id}.jpg", s.label, m.exactText, m.semanticLabel, m.targetStatus, m.regionType, m.unit, s.unit, m.machineType, m.brandProfile, m.displayMode, m.sourceKind, m.sourceId, m.modelContext, m.glare, m.blur, m.occlusion, m.crop, m.brightness, m.notes, if (s.auto) "auto" else "manual", m.vehicleModel, m.unitYear, m.countryMarket, m.lightCondition, m.glareLevel, m.cameraAngle, m.displayState)
                    sb.append(row.joinToString(",") { it.toString().replace(',', ' ').replace('\n', ' ') }).append('\n')
                }
                z.putNextEntry(ZipEntry("labels.csv")); z.write(sb.toString().toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("dataset_manifest.json")); z.write(datasetManifestJson().toString(2).toByteArray()); z.closeEntry()
            }
        } != null
    } catch (e: Exception) { false }

    /** Pasang model dari zip (hm_digits_model.zip: digits.tflite + digits.json). Diuji dimuat dulu; bila gagal model lama tetap. */
    fun importModel(uri: Uri): Boolean = try {
        val tmp = File(ctx.cacheDir, "mdl_new").also { it.deleteRecursively(); it.mkdirs() }
        var got = 0
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            ZipInputStream(ins.buffered()).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    val nm = e.name.substringAfterLast('/')
                    if (!e.isDirectory && (nm == "digits.tflite" || nm == "digits.json")) {
                        File(tmp, nm).outputStream().use { o -> z.copyTo(o) }; got++
                    }
                    e = z.nextEntry
                }
            }
        }
        val test = if (got == 2) DigitModel.load(tmp) else null
        if (test == null) { tmp.deleteRecursively(); false }
        else {
            test.close()
            File(tmp, "digits.tflite").copyTo(File(dir, "digits.tflite"), overwrite = true)
            File(tmp, "digits.json").copyTo(File(dir, "digits.json"), overwrite = true)
            tmp.deleteRecursively(); reloadModel(); model != null
        }
    } catch (e: Exception) { false }

    fun removeModel() {
        File(dir, "digits.tflite").delete(); File(dir, "digits.json").delete(); reloadModel()
    }

    private fun reloadModel() {
        val old = model
        model = DigitModel.load(dir)
        modelOn = model != null
        if (old != null && old !== model) old.close()
    }

    companion object { const val MAX_AUTO = 300 }
}
