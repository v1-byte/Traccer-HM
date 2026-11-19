package com.frostbyte.hmtracker

import org.junit.Assert.*
import org.junit.Test

/** Teks rinci untuk template watermark Detail (penggambaran Canvas sendiri tidak bisa dites di JVM). */
class WatermarkDetailTest {
    private val D = 86_400_000L

    private fun unit(sat: String = "jam") = UnitData(
        name = "DT01", sat = sat, interval = 200.0,
        readings = listOf(Reading(0, 100.0, true), Reading(1 * D, 110.0, false), Reading(2 * D, 125.0, false))
    )

    @Test fun finalPhotoShowsRiseSincePreviousReadingRateAndEstimate() {
        val u = unit()
        val lines = wmDetailLines(u, calc(u), 2 * D)      // pembacaan baru (hari 2) sudah di riwayat: pembandingnya hari 1
        assertEquals(2, lines.size)
        assertTrue(lines[0].matches(Regex("""\+15[,.]0 jam sejak .* \(24[,.]0 jam\)""")))
        assertTrue(lines[1].matches(Regex("""Laju ≈12[,.]5 jam/hari.*""")))
        assertTrue(lines[1].contains("Perkiraan servis") && lines[1].contains("1970"))
    }

    @Test fun noRiseLineWhenValueEqualsLastReading() {
        val u = unit()                                    // kamera: t setelah pembacaan terakhir dan angka yang sama
        val lines = wmDetailLines(u, calc(u), 3 * D)
        assertTrue(lines.none { it.startsWith("+") })
        assertTrue(lines.first().startsWith("Laju"))
    }

    @Test fun usesUnitSatuan() {
        val u = unit("km")
        assertTrue(wmDetailLines(u, calc(u), 2 * D)[0].contains(" km sejak "))
    }

    @Test fun emptyWhenNotEnoughData() {
        val u = UnitData(name = "DT01", readings = listOf(Reading(0, 100.0, true)))
        assertTrue(wmDetailLines(u, calc(u), 1 * D).isEmpty())
        assertTrue(wmDetailLines(UnitData(), emptyCalc(UnitData()), 0).isEmpty())
    }

    @Test fun refCodeIsStableSixCharBase36() {
        assertEquals("000000", wmRef(0))
        assertEquals("001000", wmRef(46_656))             // 36^3
        assertTrue(wmRef(1_700_000_000_000L).matches(Regex("[0-9A-Z]{6}")))
        assertEquals(wmRef(1_700_000_000_000L), wmRef(1_700_000_000_000L))
    }

    // ---- baris bukti, kode Ref bertanda tangan, tinggi kartu ----

    private val signer = object : Signer {
        override fun sign(data: String): String? {
            val m = javax.crypto.Mac.getInstance("HmacSHA256").apply { init(javax.crypto.spec.SecretKeySpec("k".toByteArray(), "HmacSHA256")) }
            return m.doFinal(data.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
    private val noKey = object : Signer { override fun sign(data: String): String? = null }

    @Test fun proofCodeFormatAndDeterminism() {
        val a = proofCode(signer, 5, 1_700_000_000_000L, "DT01", 1041.5, "prevsig")!!
        assertTrue(a.matches(Regex("[0-9A-F]{4}-[0-9A-F]{4}")))
        assertEquals(a, proofCode(signer, 5, 1_700_000_000_000L, "DT01", 1041.5, "prevsig"))
    }

    @Test fun proofCodeChangesWithAnyField() {
        val base = proofCode(signer, 5, 1000L, "DT01", 10.0, "p")
        assertNotEquals(base, proofCode(signer, 6, 1000L, "DT01", 10.0, "p"))
        assertNotEquals(base, proofCode(signer, 5, 1001L, "DT01", 10.0, "p"))
        assertNotEquals(base, proofCode(signer, 5, 1000L, "DT02", 10.0, "p"))
        assertNotEquals(base, proofCode(signer, 5, 1000L, "DT01", 10.1, "p"))
        assertNotEquals(base, proofCode(signer, 5, 1000L, "DT01", 10.0, "q"))
    }

    @Test fun proofCodeNullWithoutKeyAndOtherKeyDiffers() {
        assertNull(proofCode(noKey, 1, 1L, "U", 1.0, ""))
        val other = object : Signer {
            override fun sign(data: String): String? {
                val m = javax.crypto.Mac.getInstance("HmacSHA256").apply { init(javax.crypto.spec.SecretKeySpec("lain".toByteArray(), "HmacSHA256")) }
                return m.doFinal(data.toByteArray()).joinToString("") { "%02x".format(it) }
            }
        }
        assertNotEquals(proofCode(signer, 1, 1L, "U", 1.0, ""), proofCode(other, 1, 1L, "U", 1.0, ""))
    }

    @Test fun normRefIgnoresSeparatorsAndCase() {
        assertEquals("3F9A27C1", normRef("3f9a-27c1"))
        assertEquals("3F9A27C1", normRef(" 3F9A 27C1 "))
    }

    @Test fun evidenceLinesCameraOkNoNoise() {
        val l = wmEvidenceLines(WmEvidence(srcNote = "OCR TERVERIFIKASI"), clockSynced = true, gpsWanted = true, hasGps = true)
        assertEquals(2, l.size)
        assertEquals("Waktu GPS  ·  OCR TERVERIFIKASI", l[0].text)
        assertFalse(l[0].warn)
        assertEquals("Foto kamera", l[1].text)
        assertFalse(l[1].warn)
    }

    @Test fun evidenceLinesFlagGalleryNoGpsWarningsAndSkew() {
        val ev = WmEvidence(srcNote = "DIEDIT MANUAL", source = "gal", skewMs = 5 * 60_000L, warnCount = 2)
        val l = wmEvidenceLines(ev, clockSynced = true, gpsWanted = true, hasGps = false)
        assertTrue(l[0].text.contains("selisih 5 mnt"))
        assertTrue(l[0].warn)                                   // diedit manual ditandai
        assertTrue(l[1].text.contains("GALERI"))
        assertTrue(l[1].text.contains("GPS tidak tersedia"))
        assertTrue(l[1].text.contains("2 peringatan"))
        assertTrue(l[1].warn)
    }

    @Test fun evidenceNoGpsNoteWhenGpsNotWantedAndPhoneClockLabel() {
        val l = wmEvidenceLines(WmEvidence(skewMs = 10_000L), clockSynced = false, gpsWanted = false, hasGps = false)
        assertEquals("Waktu HP", l[0].text)                     // selisih kecil tidak disebut
        assertFalse(l[1].text.contains("GPS"))
    }

    @Test fun srcNoteRules() {
        assertNull(srcNoteOf(need = false, train = false, ocrState = "ok", input = "10", autoVal = "10"))
        assertNull(srcNoteOf(true, true, "ok", "10", "10"))
        assertNull(srcNoteOf(true, false, "run", "10", "10"))
        assertEquals("DIEDIT MANUAL", srcNoteOf(true, false, "ok", "11", "10"))
        assertEquals("DIEDIT MANUAL", srcNoteOf(true, false, "ok", "10,5", "10.0"))
        assertEquals("OCR TERVERIFIKASI", srcNoteOf(true, false, "ok", "10,5", "10.5"))
        assertEquals("OCR", srcNoteOf(true, false, "conflict", "10", "10"))
        assertNull(srcNoteOf(true, false, "none", "10", "10"))
    }

    @Test fun cardHeightGrowsWithContent() {
        val base = detailHeightUnits(2, 2, hasLoc = true, nEv = 2)
        assertEquals(base + 19, detailHeightUnits(3, 2, true, 2))
        assertEquals(base - 16, detailHeightUnits(2, 2, false, 2))
        assertEquals(base + 12, detailHeightUnits(2, 2, true, 3))
        assertTrue(detailHeightUnits(0, 0, false, 1) > 0)
    }
}
