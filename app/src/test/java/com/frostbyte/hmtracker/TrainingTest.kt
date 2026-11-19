package com.frostbyte.hmtracker

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TrainingTest {
    private val H = 3_600_000L
    private fun unit(vararg r: Reading) = UnitData(name = "DT 10", readings = r.toList())
    private fun repeat(l: Learned, n: Int, unit: String, truth: String, read: String, winners: List<String>): Learned {
        var x = l
        repeat(n) { x = x.withEvent(unit, truth, read, winners) }
        return x
    }

    // ---- salah baca ----
    @Test fun confusionPairsOnlyForSmallSameLengthDigitDiffs() {
        assertEquals(listOf("8>3"), confusionPairs("12845", "12345"))
        assertEquals(listOf("1>7", "8>3"), confusionPairs("12845", "72345"))   // dua posisi berbeda masih dianggap salah baca OCR
        assertTrue(confusionPairs("12345", "12345").isEmpty())
        assertTrue(confusionPairs("1234.5", "12345").isEmpty())      // panjang beda (titik)
        assertTrue(confusionPairs("11111", "22222").isEmpty())       // terlalu banyak yang beda: bukan salah baca OCR biasa
        assertTrue(confusionPairs("12.45", "12345").isEmpty())       // bukan digit
    }

    @Test fun normTruthAcceptsMeterNumbersOnly() {
        assertEquals("1041.0", normTruth("1041,0"))
        assertEquals("113775", normTruth(" 113775 "))
        assertNull(normTruth("abc"))
        assertNull(normTruth("1"))
    }

    // ---- kejadian & statistik ----
    @Test fun eventUpdatesConfusionsAndUnitStats() {
        val l = Learned().withEvent("DT 10", "12345", "12845", listOf("auto", "auto", "inv"), 3)
        assertEquals(3, l.conf["8>3"])
        val st = l.units["DT 10"]!!
        assertEquals(3, st.total); assertEquals(3, st.passWins["auto"]); assertEquals(3, st.passWins["inv"])
        assertEquals(3, st.digitHist[5]); assertEquals(0, st.decimal); assertEquals(1, l.events)
    }

    @Test fun blankUnitNameUsesDash() {
        assertNotNull(Learned().withEvent("", "12345", "12345", emptyList()).units["-"])
    }

    // ---- penyesuaian kandidat ----
    @Test fun refineIsNoOpBeforeFiveSamples() {
        val l = repeat(Learned(), 4, "HM 1", "1041.0", "1041.0", listOf("auto"))
        assertEquals(listOf("10410"), l.refine("HM 1", listOf("10410")))
    }

    @Test fun refinePutsDotForDrumMeterWithTenthsDrum() {
        val l = repeat(Learned(), 8, "HM 1", "1041.0", "1041.0", listOf("auto"))
        assertEquals(listOf("1041.0"), l.refine("HM 1", listOf("10410")))
    }

    @Test fun refineDoesNotTurn1041Into104dot1WhenRedDrumIsMissed() {
        // drum desimal merah tidak terbaca: 4 digit, padahal unit ini lazimnya 5 digit -> jangan dipasangi titik
        val l = repeat(Learned(), 8, "HM 1", "1041.0", "1041.0", listOf("auto"))
        assertEquals(listOf("1041"), l.refine("HM 1", listOf("1041")))
    }

    @Test fun refinePrefersCandidateWithUsualDigitCount() {
        val l = repeat(Learned(), 8, "HM 1", "1041.0", "1041.0", listOf("auto"))
        assertEquals(listOf("1041.0", "1041"), l.refine("HM 1", listOf("1041", "10410")))
    }

    @Test fun refineStripsSpuriousDotForIntegerOdometer() {
        val l = repeat(Learned(), 10, "DT 10", "113775", "113775", listOf("auto"))
        assertEquals(listOf("113775"), l.refine("DT 10", listOf("11377.5")))
    }

    // ---- rencana lintasan & polaritas ----
    @Test fun passPlanKeepsAllUntilEnoughSamples() {
        assertEquals(ALL_PASSES.toSet(), Learned().passPlan("x"))
        assertEquals(ALL_PASSES.toSet(), repeat(Learned(), 29, "U", "12345", "12345", listOf("auto")).passPlan("U"))
    }

    @Test fun passPlanDropsPassesThatNeverWin() {
        val l = repeat(Learned(), 30, "U", "12345", "12345", listOf("auto", "inv", "raw"))
        assertEquals(setOf("auto", "inv", "raw"), l.passPlan("U"))
    }

    @Test fun preferInverseOnlyWhenInverseClearlyWins() {
        assertTrue(repeat(Learned(), 10, "U", "12345", "12345", listOf("inv")).preferInverse("U"))
        assertFalse(repeat(Learned(), 10, "U", "12345", "12345", listOf("auto", "inv")).preferInverse("U"))
        assertFalse(repeat(Learned(), 9, "U", "12345", "12345", listOf("inv")).preferInverse("U"))
    }

    // ---- koreksi hasil belajar ----
    @Test fun learnedRepairUsesOnlyLearnedPairs() {
        val u = unit(Reading(0, 1210.0, true))
        // 4<->1 tidak termasuk pasangan bawaan, jadi hanya bisa diperbaiki setelah dipelajari
        assertNull(repairByHistory(u, "1240", H, false))
        val fix = learnedRepair(u, "1240", H, false, mapOf("4>1" to 3))
        assertNotNull(fix); assertEquals("1210", fix!!.text); assertEquals(2, fix.pos)
        assertNull(learnedRepair(u, "1240", H, false, mapOf("4>1" to 1)))        // baru sekali: belum dipercaya
        assertNull(learnedRepair(unit(), "1240", H, false, mapOf("4>1" to 3)))   // tanpa riwayat tidak ada yang bisa dicocokkan
    }

    @Test fun resolveReadingAppliesLearnedRepair() {
        val u = unit(Reading(0, 1210.0, true))
        val plain = resolveReading(u, "1240", H, false, 0L)!!
        assertFalse(plain.repaired)
        val learned = resolveReading(u, "1240", H, false, 0L, mapOf("4>1" to 3))!!
        assertTrue(learned.repaired); assertEquals("1210", learned.text); assertEquals(1210.0, learned.value, 0.001)
        assertTrue(learned.warned)    // koreksi selalu ditandai agar dicocokkan dengan meter asli
    }

    // ---- JSON ----
    @Test fun learnedJsonRoundTrip() {
        var l = repeat(Learned(), 6, "HM 1", "1041.0", "1041.0", listOf("auto", "bin"))
        l = l.withEvent("DT 10", "113775", "113715", listOf("inv"), 3)
        assertEquals(l, learnedFrom(JSONObject(l.toJson().toString())))
        assertEquals(Learned(), learnedFrom(null))
    }

    @Test fun sampleJsonRoundTrip() {
        val s = Sample(5L, "HM 1", "1041.0", "gal", true)
        assertEquals(s, sampleFrom(s.toJson()))
        assertNull(sampleFrom(JSONObject().put("id", 1)))
    }

    // ---- CTC ----
    private fun row(k: Int, p: Float) = FloatArray(12) { 0.01f }.also { it[k] = p }

    @Test fun ctcGreedyCollapsesRepeatsAndBlanks() {
        val cs = "0123456789."
        val seq = arrayOf(row(1, .9f), row(1, .8f), row(11, .95f), row(1, .7f), row(2, .6f))
        val (t, c) = ctcGreedy(seq, cs)
        assertEquals("112", t)
        assertEquals((0.9f + 0.7f + 0.6f) / 3f, c, 0.001f)
        assertEquals("" to 0f, ctcGreedy(arrayOf(row(11, .9f)), cs))
    }
}
