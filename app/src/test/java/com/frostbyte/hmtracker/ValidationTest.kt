package com.frostbyte.hmtracker

import org.junit.Assert.*
import org.junit.Test

class ValidationTest {
    private val H = 3_600_000L
    private fun unit(vararg r: Reading, interval: Double = 200.0, sat: String = "jam") =
        UnitData(name = "EX-01", interval = interval, sat = sat, readings = r.toList())

    // ---- calc ----
    @Test fun calcUsesLastServiceAsBase() {
        val u = unit(Reading(0, 1000.0, true), Reading(H, 1100.0, false), Reading(2 * H, 1150.0, true), Reading(3 * H, 1190.0, false))
        val c = calc(u)
        assertEquals(1150.0, c.b, 0.001); assertEquals(1190.0, c.c, 0.001)
        assertEquals(40.0, c.used, 0.001); assertEquals(160.0, c.rem, 0.001)
        assertEquals("ok", c.st)
    }

    @Test fun calcStatusThresholds() {
        assertEquals("wa", calc(unit(Reading(0, 0.0, true), Reading(H, 185.0, false))).st)   // sisa 15
        assertEquals("er", calc(unit(Reading(0, 0.0, true), Reading(H, 195.0, false))).st)   // sisa 5
        assertEquals("er", calc(unit(Reading(0, 0.0, true), Reading(H, 230.0, false))).st)   // lewat interval
    }

    @Test fun calcSurvivesZeroInterval() {
        val c = calc(unit(Reading(0, 0.0, true), Reading(H, 50.0, false), interval = 0.0))
        assertFalse(c.pct.isNaN()); assertEquals(200.0 - 50.0, c.rem, 0.001)
    }

    // ---- checkReading ----
    @Test fun blocksReadingLowerThanLast() {
        val u = unit(Reading(0, 1250.0, true))
        val r = checkReading(u, 1249.9, H, false, 0)
        assertTrue(r.any { it.sev == Sev.BLOCK })
    }

    @Test fun initModeOnlyWarnsWhenLower() {
        val u = unit(Reading(0, 1250.0, true))
        val r = checkReading(u, 1200.0, H, true, 0)
        assertTrue(r.none { it.sev == Sev.BLOCK }); assertTrue(r.any { it.sev == Sev.WARN })
    }

    @Test fun warnsWhenRiseExceedsRealElapsedTime() {
        val u = unit(Reading(0, 1250.0, true))
        // 2 jam nyata, tapi HM naik 6000 (1250 -> 7250: salah baca)
        assertTrue(checkReading(u, 7250.0, 2 * H, false, 0).any { it.sev == Sev.WARN })
        // 8 jam nyata, naik 7,5 jam: wajar
        assertTrue(checkReading(u, 1257.5, 8 * H, false, 0).isEmpty())
    }

    @Test fun kmUnitAllowsDrivingSpeed() {
        val u = unit(Reading(0, 50_000.0, true), sat = "km")
        assertTrue(checkReading(u, 50_100.0, 2 * H, false, 0).isEmpty())          // 100 km dalam 2 jam
        assertTrue(checkReading(u, 52_000.0, 2 * H, false, 0).any { it.sev == Sev.WARN })
    }

    @Test fun flagsClockSkewAndBackwardClock() {
        val u = unit(Reading(10 * H, 100.0, true))
        assertTrue(checkReading(u, 101.0, 11 * H, false, 5 * 60_000L).any { it.sev == Sev.WARN })
        assertTrue(checkReading(u, 101.0, 9 * H, false, 0).any { it.msg.contains("Jam HP lebih awal") })
    }

    @Test fun sameAsLastIsInfoOnly() {
        val u = unit(Reading(0, 100.0, true))
        val r = checkReading(u, 100.0, H, false, 0)
        assertTrue(r.all { it.sev == Sev.INFO })
    }

    // ---- resolveReading ----
    @Test fun addsDecimalPointWhenOnlyThatIsPlausible() {
        val u = unit(Reading(0, 1234.0, true))
        val r = resolveReading(u, "12345", 3 * H, false)!!      // 12345 mustahil, 1234.5 masuk akal
        assertTrue(r.dotAdded); assertEquals(1234.5, r.value, 0.001); assertEquals("1234.5", r.text)
    }

    @Test fun keepsRawWhenRawIsPlausible() {
        val u = unit(Reading(0, 5000.0, true))
        val r = resolveReading(u, "5002", 3 * H, false)!!        // 500.2 < terakhir, jadi tetap 5002
        assertFalse(r.dotAdded); assertEquals(5002.0, r.value, 0.001)
    }

    @Test fun acceptsCommaDecimalAndRejectsGarbage() {
        val u = unit()
        assertEquals(123.4, resolveReading(u, "123,4", 0, true)!!.value, 0.001)
        assertNull(resolveReading(u, "abc", 0, true))
    }

    @Test fun chooseFinalPrefersPlausibleCandidate() {
        val u = unit(Reading(0, 2000.0, true))
        val r = chooseFinal(u, listOf("9000", "2003"), 4 * H, false)!!
        assertEquals(2003.0, r.value, 0.001)
    }

    // ---- normalizeCandidate ----
    @Test fun normalizesOcrTokens() {
        assertEquals("1234.5", normalizeCandidate("1234,5"))
        assertEquals("12345", normalizeCandidate("12 345"))
        assertNull(normalizeCandidate("7"))
        assertNull(normalizeCandidate("123456789"))
    }

    // ---- kualitas foto ----
    private fun stripes(w: Int, h: Int, blur: Int): IntArray {
        val g = IntArray(w * h) { i -> if ((i % w) / 6 % 2 == 0) 40 else 210 }
        var cur = g
        repeat(blur) {
            val nx = cur.copyOf()
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                var s = 0; for (dy in -1..1) for (dx in -1..1) s += cur[(y + dy) * w + x + dx]
                nx[y * w + x] = s / 9
            }
            cur = nx
        }
        return cur
    }

    @Test fun sharpImageScoresHigherThanBlurred() {
        val sharp = frameStats(stripes(160, 40, 0), 160, 40).sharp
        val blurred = frameStats(stripes(160, 40, 6), 160, 40).sharp
        assertTrue(sharp > blurred * 3)
        assertNull(judgeQuality(frameStats(stripes(160, 40, 0), 160, 40)))
    }

    @Test fun flagsBlackAndFlatFrames() {
        assertEquals(Sev.BLOCK, judgeQuality(frameStats(IntArray(100 * 30) { 2 }, 100, 30))!!.sev)
        assertNotNull(judgeQuality(frameStats(IntArray(100 * 30) { 120 }, 100, 30)))
    }

    // ---- geometri ----
    @Test fun roiStaysInsideFrameAndMatchesFillCenter() {
        // layar 1080x2000, foto 4:3 potret 1500x2000 -> skala 1,0, tanpa offset vertikal
        val r = boxToFrame(1080, 2000, 3f, 1500, 2000)
        assertTrue(r.x >= 0 && r.y >= 0 && r.x + r.w <= 1500 && r.y + r.h <= 2000)
        val b = boxInView(1080, 2000, 3f)
        assertEquals(b[2].toInt(), r.w)                       // skala 1 -> lebar sama
        val wide = boxToFrame(1080, 2000, 3f, 1500, 2000, 0.25f)
        assertTrue(wide.w > r.w && wide.h > r.h)
    }

    @Test fun sameValueTolerance() {
        assertTrue(sameValue(1234.5, 1234.5)); assertFalse(sameValue(1234.5, 1235.0)); assertFalse(sameValue(null, 1.0))
    }

    // ---- koreksi digit berdasarkan riwayat ----
    @Test fun repairsSingleConfusedDigitWhenOnlyOneFits() {
        val u = unit(Reading(0, 1250.0, true))
        // 3 jam nyata -> kenaikan maks. 3,8. "1259" mustahil; hanya 1250 (9 -> 0) yang masuk akal
        val r = resolveReading(u, "1259", 3 * H, false)!!
        assertTrue(r.repaired); assertEquals(1250.0, r.value, 0.001); assertEquals("1250", r.text)
        assertTrue(r.warned); assertNotNull(r.repairNote)
    }

    @Test fun doesNotGuessWhenSeveralRepairsFit() {
        val u = unit(Reading(0, 1250.0, true))
        // 4 jam nyata -> kenaikan maks. 4,9: 1250 dan 1254 sama-sama masuk akal, jadi tidak ditebak
        val r = resolveReading(u, "1259", 4 * H, false)!!
        assertFalse(r.repaired); assertEquals(1259.0, r.value, 0.001); assertTrue(r.warned)
    }

    @Test fun neverRepairsAPlausibleReading() {
        val u = unit(Reading(0, 1250.0, true))
        val r = resolveReading(u, "1252", 3 * H, false)!!
        assertFalse(r.repaired); assertEquals(1252.0, r.value, 0.001)
    }

    @Test fun repairNeedsHistory() {
        assertNull(repairByHistory(unit(), "1259", 3 * H, false))
    }

    // ---- konsensus per digit ----
    @Test fun consensusFixesDifferentErrorsPerReading() {
        assertEquals("12345", consensusOf(listOf("12845", "12345", "12355")))
    }

    @Test fun consensusRefusesWhenReadingsDisagree() {
        assertNull(consensusOf(listOf("12345", "12845", "12945")))        // digit ke-3 terbelah tiga: tak ada mayoritas
        assertNull(consensusOf(listOf("12345")))                           // satu bacaan bukan konsensus
        assertNull(consensusOf(listOf("12345", "1234.5")))                 // bentuk berbeda tidak dicampur
    }

    @Test fun consensusRespectsMinAgree() {
        assertNull(consensusOf(listOf("12845", "12345", "12355", "99999"), 4))
        assertEquals("12345", consensusOf(listOf("12345", "12345", "12345", "12845", "12355"), 3))
    }

    // ---- salah baca huruf mirip angka ----
    @Test fun fixDigitsMapsLookalikeLetters() {
        assertEquals("12060", fixDigits("12O6D"))
        assertEquals("1265", fixDigits("1Z6S"))
        assertNull(fixDigits("HOURS"))
    }
}
