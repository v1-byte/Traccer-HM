package com.frostbyte.hmtracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class IntegrityTest {
    private val signer = object : Signer {
        override fun sign(data: String): String? {
            val m = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec("k".toByteArray(), "HmacSHA256")) }
            return m.doFinal(data.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
    private val noKey = object : Signer { override fun sign(data: String): String? = null }

    private fun chain(vararg vals: Double): List<Audit> {
        var prev = ""
        return vals.mapIndexed { i, v ->
            val a = Audit(100L + i, "DT01", "$v", "$v", v, false, 0L, true, "", "sha$i", n = i + 1L, prev = prev, src = "cam")
            val s = a.copy(sig = signer.sign(canonical(a))!!)
            prev = s.sig; s
        }
    }
    private fun check(l: List<Audit>, s: Signer = signer, file: (Audit) -> String? = { it.sha }, hm: (Audit) -> Double? = { it.value }) =
        verifyAudits(l, s, file, hm)

    @Test fun rantaiUtuhTerverifikasi() {
        val r = check(chain(10.0, 20.0, 30.0))
        assertEquals(3, r.size)
        assertEquals(true, r.values.all { it.trust == Trust.VERIFIED })
    }

    @Test fun catatanDiubahTerdeteksi() {
        val l = chain(10.0, 20.0, 30.0).toMutableList()
        l[1] = l[1].copy(value = 25.0)
        assertEquals(Trust.RECORD_EDITED, check(l, hm = { 25.0 })[101L]?.trust)
    }

    @Test fun catatanDihapusMemutuskanRantai() {
        val l = chain(10.0, 20.0, 30.0).filterIndexed { i, _ -> i != 1 }
        val r = check(l)
        assertEquals(Trust.VERIFIED, r[100L]?.trust)
        assertEquals(Trust.CHAIN_BROKEN, r[102L]?.trust)
    }

    @Test fun fotoDiganti() {
        val r = check(chain(10.0, 20.0), file = { if (it.t == 101L) "lain" else it.sha })
        assertEquals(Trust.FILE_CHANGED, r[101L]?.trust)
        assertEquals(Trust.VERIFIED, r[100L]?.trust)
    }

    @Test fun hmDiRiwayatDiubah() {
        val r = check(chain(10.0, 20.0), hm = { if (it.t == 100L) 11.0 else it.value })
        assertEquals(Trust.HM_CHANGED, r[100L]?.trust)
    }

    @Test fun fotoSudahDipangkasBukanManipulasi() {
        val r = check(chain(10.0), file = { null })
        assertEquals(Trust.FILE_MISSING, r[100L]?.trust)
        assertFalse(r[100L]!!.bad)
    }

    @Test fun entriLamaDanImporTidakBertandaTangan() {
        val legacy = Audit(1L, "DT01", "", "", 5.0, false, 0L, true, "", "s")
        val imp = Audit(2L, "DT01", "", "", 6.0, false, 0L, true, "", "s", src = "imp")
        val r = check(listOf(legacy, imp))
        assertEquals(Trust.UNSIGNED, r[1L]?.trust)
        assertEquals(Trust.UNSIGNED, r[2L]?.trust)
        assertFalse(r[1L]!!.bad)
    }

    @Test fun kunciTidakAdaBukanDituduhDiedit() {
        val r = check(chain(10.0), s = noKey)
        assertEquals(Trust.UNSIGNED, r[100L]?.trust)
    }

    @Test fun entriBertandaTanganSetelahLegacyTetapBerantai() {
        val legacy = Audit(1L, "DT01", "", "", 5.0, false, 0L, true, "", "s")
        val l = listOf(legacy) + chain(10.0, 20.0)
        val r = check(l, hm = { it.value })
        assertEquals(Trust.VERIFIED, r[100L]?.trust)
        assertEquals(Trust.VERIFIED, r[101L]?.trust)
    }
}
