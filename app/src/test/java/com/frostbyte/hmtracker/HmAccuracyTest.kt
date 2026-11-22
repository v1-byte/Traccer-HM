package com.frostbyte.hmtracker

import org.junit.Assert.*
import org.junit.Test

/** Akurasi hitungan HM: ambang per satuan, pembulatan, estimasi servis, prediksi, tafsiran desimal, dan anomali laju. */
class HmAccuracyTest {
    private val H = 3_600_000L
    private val D = 86_400_000L

    private fun jam(vararg r: Reading, interval: Double = 200.0) = UnitData(name = "DT01", interval = interval, readings = r.toList())
    private fun km(vararg r: Reading, interval: Double = 10_000.0) = UnitData(name = "DT01", sat = "km", interval = interval, readings = r.toList())

    // ---- ambang status per satuan ----
    @Test fun kmThresholdsScaleWithInterval() {
        val w = warnLevels(km())
        assertEquals(200.0, w.first, 1e-6); assertEquals(500.0, w.second, 1e-6)
        assertEquals(10.0 to 20.0, warnLevels(jam()))
    }

    @Test fun kmStatusUsesScaledThresholds() {
        assertEquals("ok", calc(km(Reading(0, 0.0, true), Reading(H, 9000.0, false))).st)    // sisa 1000
        assertEquals("wa", calc(km(Reading(0, 0.0, true), Reading(H, 9700.0, false))).st)    // sisa 300
        assertEquals("er", calc(km(Reading(0, 0.0, true), Reading(H, 9850.0, false))).st)    // sisa 150
    }

    @Test fun hourThresholdsUnchanged() {
        assertEquals("wa", calc(jam(Reading(0, 0.0, true), Reading(H, 185.0, false))).st)
        assertEquals("er", calc(jam(Reading(0, 0.0, true), Reading(H, 195.0, false))).st)
    }

    // ---- pembulatan ----
    @Test fun noBinaryFloatNoiseInUsedAndRemaining() {
        val c = calc(jam(Reading(0, 100.1, true), Reading(H, 100.4, false)))
        assertEquals(0.3, c.used, 1e-12); assertEquals(199.7, c.rem, 1e-12)
    }

    // ---- estimasi servis ----
    @Test fun calcEstimateFollowsRecentRateNotOverallAverage() {
        // lambat 10/hari, lalu 100/hari: rata-rata keseluruhan 46/hari, laju terbaru (4 pembacaan) 70/hari
        val u = jam(
            Reading(0, 0.0, true), Reading(1 * D, 10.0, false), Reading(2 * D, 20.0, false),
            Reading(3 * D, 30.0, false), Reading(4 * D, 130.0, false), Reading(5 * D, 230.0, false), interval = 500.0
        )
        val c = calc(u)
        assertEquals(270.0, c.rem, 1e-9)
        assertEquals(5 * D + 270.0 / 70.0 * D, c.est!!.toDouble(), 2.0)
    }

    @Test fun calcEstimateNullWithoutUsageData() {
        assertNull(calc(jam(Reading(0, 100.0, true))).est)
        assertNull(calc(jam(Reading(0, 100.0, true), Reading(D, 100.0, false))).est)
    }

    // ---- prediksi memperhitungkan waktu sejak pembacaan terakhir ----
    private fun steady() = jam(
        Reading(0, 100.0, true), Reading(1 * D, 110.0, false), Reading(2 * D, 120.0, false),
        Reading(3 * D, 130.0, false), Reading(4 * D, 140.0, false), Reading(5 * D, 150.0, false)
    )

    @Test fun forecastCountsFromLastReadingNotFromNow() {
        val f = forecast(steady(), 8 * D)          // pembacaan terakhir sudah 3 hari lalu
        assertEquals((20 * D), f.dueAt!!)          // 5 hari + sisa 150 jam / 10 per hari
        assertEquals(12.0, f.daysLeft!!, 1e-9)
        assertEquals(180.0, f.projected!!, 1e-9)   // 150 + 10/hari x 3 hari
    }

    @Test fun forecastProjectionAbsentRightAtLastReading() {
        assertNull(forecast(steady(), 5 * D).projected)
    }

    @Test fun forecastOverdueIsDueNow() {
        val f = forecast(steady().copy(interval = 40.0), 8 * D)   // terpakai 50 dari interval 40
        assertEquals(0.0, f.daysLeft!!, 1e-9)
        assertEquals(5 * D, f.dueAt!!)
    }

    // ---- km tidak ditafsirkan berdesimal ----
    @Test fun kmExtraDigitIsNotTurnedIntoDecimal() {
        val u = km(Reading(0, 50_000.0, true))
        val r = resolveReading(u, "500000", 2 * H, false)!!
        assertFalse(r.dotAdded); assertEquals(500_000.0, r.value, 1e-6); assertTrue(r.warned)
        // unit jam: perilaku lama dipertahankan (titik dipasang bila hanya itu yang masuk akal)
        assertTrue(resolveReading(u.copy(sat = "jam"), "500000", 2 * H, false)!!.dotAdded)
    }

    // ---- petunjuk desimal pembacaan pertama ----
    @Test fun firstReadingDecimalHint() {
        val u = jam()
        val r = resolveReading(u, "10410", 0, false, 0, null, decimalHint = true)!!
        assertTrue(r.dotAdded); assertEquals(1041.0, r.value, 1e-9); assertEquals("1041.0", r.text)
        assertTrue(r.issues.any { it.sev == Sev.INFO }); assertFalse(r.warned)
    }

    @Test fun decimalHintIgnoredWhenNotApplicable() {
        val u = jam()
        assertEquals(10410.0, resolveReading(u, "10410", 0, false)!!.value, 1e-9)                                   // tanpa petunjuk
        assertEquals(10410.0, resolveReading(km(), "10410", 0, false, 0, null, decimalHint = true)!!.value, 1e-9)    // km bulat
        assertEquals(1041.0, resolveReading(u, "1041.0", 0, false, 0, null, decimalHint = true)!!.value, 1e-9)       // titik sudah terbaca
        assertEquals(853.0, resolveReading(u, "853", 0, false, 0, null, decimalHint = true)!!.value, 1e-9)           // terlalu pendek
    }

    // ---- anomali laju pemakaian ----
    private fun slow() = jam(Reading(0, 100.0, true), Reading(D, 102.0, false), Reading(2 * D, 104.0, false), Reading(3 * D, 106.0, false))

    @Test fun flagsRiseFarAboveUsualUsage() {
        val u = slow()                                             // 2 jam/hari
        val big = checkReading(u, 206.0, 8 * D, false, 0)          // +100 dalam 5 hari: di bawah batas fisik 132,5 tetapi 10x biasa
        assertTrue(big.any { it.sev == Sev.WARN && it.msg.contains("jauh di atas pemakaian biasa") })
        assertTrue(checkReading(u, 136.0, 8 * D, false, 0).isEmpty())   // +30 dalam 5 hari: masih wajar
    }

    @Test fun rateAnomalyNeedsDataAndTime() {
        assertNull(rateAnomaly(slow(), 50.0, 12 * H))                                       // belum 1 hari
        assertNull(rateAnomaly(jam(Reading(0, 100.0, true)), 500.0, 5 * D))                 // laju belum diketahui
    }

    @Test fun rateAnomalyForKm() {
        val u = km(Reading(0, 50_000.0, true), Reading(D, 50_100.0, false), Reading(2 * D, 50_200.0, false), Reading(3 * D, 50_300.0, false))
        assertTrue(checkReading(u, 52_300.0, 8 * D, false, 0).any { it.sev == Sev.WARN })    // +2000 dalam 5 hari (biasa 500)
        assertTrue(checkReading(u, 52_000.0, 8 * D, false, 0).isEmpty())                      // +1700: masih di bawah 3x + 300
    }

    // ---- tafsiran alternatif ----
    @Test fun alternativesShiftDecimalAndRankPlausibleFirst() {
        val u = jam(Reading(0, 1234.0, true))
        val a = alternativesOf(u, listOf("12345"), 3 * H, false)
        assertEquals(2, a.size)
        assertEquals("1234.5", a[0].text); assertTrue(a[0].ok)
        assertEquals("12345", a[1].text); assertFalse(a[1].ok)
    }

    @Test fun alternativesOfferConfusableDigitSwapsThatPassHistory() {
        val u = jam(Reading(0, 5000.0, true))
        val a = alternativesOf(u, listOf("5008"), 3 * H, false)
        assertTrue(a.any { it.text == "5003" && it.ok })     // 8 -> 3 tertukar, kenaikan 3 jam dalam 3 jam: wajar
        assertTrue(a.any { it.text == "5000" && it.ok })
        assertTrue(a.first().ok)                              // yang lolos pemeriksaan selalu di depan
    }

    @Test fun alternativesForKmNeverShiftDecimal() {
        val a = alternativesOf(km(Reading(0, 113_700.0, true)), listOf("113775"), 3 * H, false)
        assertEquals(listOf("113775"), a.map { it.text })
    }

    // ---- rincian selisih ----
    @Test fun describeRiseShowsDeltaAndLimit() {
        val u = jam(Reading(0, 1000.0, true))
        val up = describeRise(u, 1012.5, 8 * H)!!
        assertTrue(up.startsWith("+")); assertTrue(up.contains("batas wajar"))
        val down = describeRise(u, 990.0, 8 * H)!!
        assertTrue(down.startsWith("−")); assertFalse(down.contains("batas wajar"))
        assertNull(describeRise(jam(), 5.0, 0))
    }
}
