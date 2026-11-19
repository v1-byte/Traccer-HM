package com.frostbyte.hmtracker

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import kotlin.math.abs

/*
 * Integritas jejak audit.
 * - Tiap audit ditandatangani HMAC-SHA256 dengan kunci di Android Keystore (tidak bisa diekspor dari perangkat).
 * - Tanda tangan mencakup SHA-256 file foto, angka HM, waktu, dan hash entri sebelumnya (rantai):
 *   mengubah/menghapus entri, mengganti foto, atau mengubah HM di riwayat akan terdeteksi.
 * Batas: perangkat yang di-root penuh atau pengguna yang menghapus SELURUH jejak/instal ulang tidak bisa dideteksi
 * secara offline; untuk itu perlu jangkar di server.
 */

interface Signer { fun sign(data: String): String? }

object KeystoreSigner : Signer {
    private const val ALIAS = "hmt_audit_hmac"

    private fun key(): SecretKey? = try {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
            .apply { init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN).build()) }.generateKey()
    } catch (e: Exception) { null }

    override fun sign(data: String): String? = try {
        val k = key() ?: return null
        val mac = Mac.getInstance("HmacSHA256").apply { init(k) }
        mac.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    } catch (e: Exception) { null }
}

/** Teks baku yang ditandatangani. Urutan dan pemisah tidak boleh diubah (tanda tangan lama jadi tidak valid). */
fun canonical(a: Audit): String =
    listOf(a.n, a.t, a.unit, a.live, a.photo, a.value, a.edited, a.skewMs, a.gps, a.warn, a.sha, a.src, a.prev).joinToString("\u001f")

enum class Trust { VERIFIED, UNSIGNED, FILE_MISSING, HM_CHANGED, FILE_CHANGED, CHAIN_BROKEN, RECORD_EDITED }

data class Verdict(val trust: Trust, val detail: String) {
    val bad get() = trust >= Trust.HM_CHANGED
}

/**
 * Periksa seluruh rantai audit. [fileSha] = SHA-256 file foto saat ini (null bila file tidak ada),
 * [hmOf] = HM pada riwayat dengan waktu yang sama (null bila tidak ada).
 */
fun verifyAudits(list: List<Audit>, signer: Signer, fileSha: (Audit) -> String?, hmOf: (Audit) -> Double?): Map<Long, Verdict> {
    val out = HashMap<Long, Verdict>()
    var lastSig = ""
    for (a in list.sortedWith(compareBy({ it.n }, { it.t }))) {
        val signed = a.sig.isNotEmpty()
        var sigState = 1   // 1 = cocok, 0 = tidak cocok, -1 = tidak bisa diperiksa (kunci tidak ada)
        var chainOk = true
        if (signed) {
            val s = signer.sign(canonical(a))
            sigState = when { s == null -> -1; s == a.sig -> 1; else -> 0 }
            chainOk = a.prev == lastSig
            lastSig = a.sig
        }
        val fsha = fileSha(a)
        val fileBad = fsha != null && a.sha.isNotEmpty() && fsha != a.sha
        val hm = hmOf(a)
        val hmBad = !a.value.isNaN() && (hm == null || abs(hm - a.value) > 1e-6)
        out[a.t] = when {
            signed && sigState == 0 -> Verdict(Trust.RECORD_EDITED, "Catatan audit diubah setelah disimpan")
            signed && !chainOk -> Verdict(Trust.CHAIN_BROKEN, "Ada catatan audit sebelumnya yang dihapus atau diubah")
            fileBad -> Verdict(Trust.FILE_CHANGED, "File foto berbeda dari saat disimpan")
            hmBad -> Verdict(Trust.HM_CHANGED, "Angka HM di riwayat berbeda dari saat foto diambil")
            fsha == null -> Verdict(Trust.FILE_MISSING, "File foto sudah tidak ada (dihapus atau dipangkas)")
            !signed -> Verdict(Trust.UNSIGNED, if (a.src == "imp") "Dari backup, tidak dapat diverifikasi di perangkat ini" else "Dibuat sebelum fitur tanda tangan")
            sigState == -1 -> Verdict(Trust.UNSIGNED, "Kunci perangkat tidak tersedia, tanda tangan tidak bisa diperiksa")
            else -> Verdict(Trust.VERIFIED, "Tanda tangan, rantai, foto, dan HM cocok")
        }
    }
    return out
}

fun Trust.label(): String = when (this) {
    Trust.VERIFIED -> "Terverifikasi"
    Trust.UNSIGNED -> "Tidak bertanda tangan"
    Trust.FILE_MISSING -> "Foto tidak ada"
    Trust.HM_CHANGED -> "HM berubah"
    Trust.FILE_CHANGED -> "Foto berubah"
    Trust.CHAIN_BROKEN -> "Rantai terputus"
    Trust.RECORD_EDITED -> "Catatan diubah"
}

/**
 * Kode bukti pendek (mis. "3F9A-27C1") yang dicetak di watermark Detail Card. Berasal dari HMAC kunci Keystore atas
 * nomor urut, waktu, unit, angka, dan tanda tangan sebelumnya, jadi tidak bisa dibuat ulang tanpa perangkat asal.
 * Foto yang sudah jadi tidak bisa memuat hash dirinya sendiri (hash dihitung SETELAH watermark digambar),
 * maka kode ini mengikat isi catatan; hash file tetap diperiksa lewat jejak audit. Null bila kunci tidak tersedia.
 */
fun proofCode(signer: Signer, n: Long, t: Long, unit: String, value: Double, prev: String): String? {
    val s = signer.sign(listOf("REF", n, t, unit, value, prev).joinToString("\u001f")) ?: return null
    val h = s.take(8).uppercase()
    return h.substring(0, 4) + "-" + h.substring(4)
}

/** Ambil hanya karakter heks dari ketikan pengguna (huruf besar), untuk membandingkan kode Ref. */
fun normRef(s: String): String = s.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }
