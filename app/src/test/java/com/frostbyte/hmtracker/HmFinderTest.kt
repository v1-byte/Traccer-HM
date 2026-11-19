package com.frostbyte.hmtracker

import org.junit.Assert.*
import org.junit.Test

class HmFinderTest {
    private fun tok(s: String, l: Int, t: Int, r: Int, b: Int) = OcrTok(s, l, t, r, b)
    private val fw = 960
    private val fh = 1280

    // ---- label ----
    @Test fun recognisesHmKmAndNoiseLabels() {
        assertEquals(LabelTag(Lbl.HM, true), labelOf("HM"))
        assertEquals(LabelTag(Lbl.HM, true), labelOf("Hours"))
        assertEquals(LabelTag(Lbl.HM, true), labelOf("SMR"))
        assertEquals(LabelTag(Lbl.HM, true), labelOf("H.M"))
        assertEquals(LabelTag(Lbl.HM, false), labelOf("h"))
        assertEquals(LabelTag(Lbl.KM, true), labelOf("ODO"))
        assertEquals(Lbl.NOISE, labelOf("RPM")?.kind)
        assertNull(labelOf("km/h"))   // odometer berada tepat di bawah "km/h": bukan penanda angka lain
        assertEquals(Lbl.NOISE, labelOf("45%")?.kind)
        assertNull(labelOf("KOMATSU"))
        assertNull(labelOf(""))
    }

    @Test fun recognisesLabelsGluedToNumbers() {
        assertEquals(Lbl.NOISE, gluedLabelOf("SN12345")?.kind)
        assertEquals(LabelTag(Lbl.HM, false), gluedLabelOf("1234.5H"))
        assertEquals(LabelTag(Lbl.HM, true), gluedLabelOf("12345HM"))
        assertNull(gluedLabelOf("12345"))
        assertNull(gluedLabelOf("1O3OO"))   // huruf mirip angka bukan label
        assertEquals("12345" to Lbl.NOISE, splitGlued("SN12345").let { it.first to it.second?.kind })
        assertEquals("1234.5", splitGlued("1234.5H").first)
        assertEquals("1O3OO", splitGlued("1O3OO").first)
    }

    @Test fun detectsDatesAndMultipliers() {
        assertTrue(isDateLike("12:30")); assertTrue(isDateLike("12/03/2026")); assertTrue(isDateLike("17-11-2026"))
        assertTrue(isDateLike("22.22.44")); assertTrue(isDateLike("6.15.01")); assertTrue(isDateLike("1/10"))
        assertFalse(isDateLike("12345")); assertFalse(isDateLike("1234.5"))
        assertTrue(isMultiplier("x100")); assertTrue(isMultiplier("X1000")); assertFalse(isMultiplier("12345"))
    }

    @Test fun matchesAgainstWantedKind() {
        assertNull((null as LabelTag?).matches(MeterKind.HM))
        assertEquals(true, LabelTag(Lbl.HM).matches(MeterKind.HM))
        assertEquals(false, LabelTag(Lbl.KM).matches(MeterKind.HM))
        assertEquals(true, LabelTag(Lbl.KM).matches(MeterKind.KM))
        assertEquals(false, LabelTag(Lbl.NOISE).matches(MeterKind.HM))
    }

    // ---- pickMeter dengan label ----
    @Test fun hmLabelBeatsBiggerCentredNumberWithRpmLabel() {
        val lines = listOf(
            listOf(tok("RPM", 400, 540, 520, 580)),
            listOf(tok("123456", 300, 600, 640, 680)),     // di tengah, besar, tapi berlabel RPM
            listOf(tok("54321", 100, 900, 420, 970), tok("HM", 440, 905, 520, 965)),
        )
        assertEquals("123456", pickMeter(lines.take(2), fw, fh)?.text)          // tanpa HM, angka RPM menang
        val hit = pickMeter(lines, fw, fh, want = MeterKind.HM)
        assertEquals("54321", hit?.text)
        assertEquals(Lbl.HM, hit?.tag?.kind)
    }

    @Test fun wantedKindSelectsMatchingLabel() {
        val lines = listOf(
            listOf(tok("123456", 100, 500, 400, 560), tok("KM", 410, 505, 470, 555)),
            listOf(tok("654321", 100, 800, 400, 860), tok("HM", 410, 805, 470, 855)),
        )
        assertEquals("123456", pickMeter(lines, fw, fh, want = MeterKind.KM)?.text)
        assertEquals("654321", pickMeter(lines, fw, fh, want = MeterKind.HM)?.text)
    }

    @Test fun labelAboveNumberIsFound() {
        val lines = listOf(
            listOf(tok("HOURS", 150, 440, 330, 490)),
            listOf(tok("123456", 100, 500, 400, 560)),
            listOf(tok("999999", 100, 800, 400, 860)),   // lebih jauh dari tengah, tanpa label
        )
        val hit = pickMeter(lines, fw, fh, want = MeterKind.HM)
        assertEquals("123456", hit?.text)
        assertEquals(Lbl.HM, hit?.tag?.kind)
    }

    @Test fun serialNumberGluedLabelIsPenalised() {
        val lines = listOf(listOf(tok("SN12345", 100, 500, 400, 560)), listOf(tok("9876", 100, 800, 400, 860)))
        val hit = pickMeter(lines, fw, fh)
        assertEquals("9876", hit?.text)
        // huruf label tidak ikut "dikoreksi" jadi angka: "SN12345" menjadi 12345 (bukan 512345) berlabel SN
        val sn = pickMeter(listOf(listOf(tok("SN12345", 100, 500, 400, 560))), fw, fh)
        assertEquals("12345", sn?.text); assertEquals(Lbl.NOISE, sn?.tag?.kind)
    }

    @Test fun skipsTimesDatesAndMultipliers() {
        assertNull(pickMeter(listOf(listOf(tok("12:30", 100, 500, 300, 560))), fw, fh))
        assertNull(pickMeter(listOf(listOf(tok("12/03/2026", 100, 500, 400, 560))), fw, fh))
        assertNull(pickMeter(listOf(listOf(tok("x100", 100, 500, 300, 560))), fw, fh))
    }

    @Test fun separateDecimalPointIsJoined() {
        val line = listOf(tok("1234", 100, 500, 300, 560), tok(".", 302, 545, 310, 560), tok("5", 312, 500, 352, 560))
        val hit = pickMeter(listOf(line), fw, fh)
        assertEquals("1234.5", hit?.text)
        assertEquals(100, hit?.l); assertEquals(352, hit?.r)
    }

    @Test fun labelBetweenDigitGroupsSplitsThem() {
        val line = listOf(tok("123", 100, 500, 220, 560), tok("HM", 228, 500, 280, 560), tok("456", 288, 500, 408, 560))
        val hit = pickMeter(listOf(line), fw, fh)
        assertNotEquals("123456", hit?.text)
    }

    // ---- kotak pelacak ----
    private fun box(l: Float, t: Float, r: Float, b: Float, tag: LabelTag? = null) = NumTrack(l, t, r, b, fw, fh, tag)

    @Test fun mergeAdoptsFirstHitAndKeepsOldOnMiss() {
        val first = mergeTrack(TrackState(null), box(.4f, .4f, .6f, .45f), 1.0)
        assertEquals(.4f, first.track!!.l, 1e-6f); assertEquals(1.0, first.score, 1e-9)
        assertSame(first, mergeTrack(first, null, 0.0))
    }

    @Test fun mergeSmoothsOverlappingHit() {
        val cur = TrackState(box(.40f, .40f, .60f, .45f), 1.0)
        val m = mergeTrack(cur, box(.42f, .40f, .62f, .45f), 0.9)
        assertEquals(.41f, m.track!!.l, 1e-5f); assertEquals(.61f, m.track!!.r, 1e-5f)
        assertEquals(0.9, m.score, 1e-9)
    }

    @Test fun mergeSwitchesOnlyWhenClearlyBetter() {
        val cur = TrackState(box(.10f, .10f, .30f, .15f), 1.0)
        val far = box(.60f, .70f, .80f, .75f)
        assertSame(cur, mergeTrack(cur, far, 1.3))                                   // kurang dari 1,4x: bertahan
        assertEquals(.60f, mergeTrack(cur, far, 1.4).track!!.l, 1e-6f)               // 1,4x: pindah
        assertEquals(.60f, mergeTrack(cur, far, 0.1, force = true).track!!.l, 1e-6f) // dipaksa (mis. sesudah zoom)
    }

    @Test fun mergeKeepsTagWhenNewHitHasNone() {
        val tagged = LabelTag(Lbl.HM)
        val cur = TrackState(box(.40f, .40f, .60f, .45f, tagged), 1.0)
        assertEquals(tagged, mergeTrack(cur, box(.41f, .40f, .61f, .45f), 1.0).track!!.tag)
    }

    // ---- kebijakan fokus ----
    @Test fun focusFiresOnFirstSightThenWaitsMinimumGap() {
        val p = FocusPolicy()
        assertTrue(p.shouldTrigger(1000, .5f, .5f, 1.0, 0))
        p.markTriggered(1000, .5f, .5f)
        assertFalse(p.shouldTrigger(1500, .9f, .9f, 0.0, 0))     // jeda minimum 900 ms, walau angka pindah
        assertFalse(p.shouldTrigger(2000, .5f, .5f, 1.0, 0))     // tenang: tidak perlu fokus ulang
        assertTrue(p.shouldTrigger(2000, .7f, .5f, 1.0, 0))      // angka bergeser
    }

    @Test fun focusRetriggersWhenBlurredOrStale() {
        val p = FocusPolicy(); p.markTriggered(1000, .5f, .5f)
        assertFalse(p.shouldTrigger(2200, .5f, .5f, 0.01, 0))    // buram tapi baru 1,2 dtk
        assertTrue(p.shouldTrigger(2600, .5f, .5f, 0.01, 0))     // buram dan sudah 1,6 dtk
        assertFalse(p.shouldTrigger(8000, .5f, .5f, 1.0, 0))
        assertTrue(p.shouldTrigger(9100, .5f, .5f, 1.0, 0))      // penyegaran berkala 8 dtk
    }

    @Test fun manualTapSuppressesAutoFocus() {
        val p = FocusPolicy()
        assertFalse(p.shouldTrigger(20_000, .9f, .9f, 0.0, 21_000))
        assertTrue(p.shouldTrigger(21_000, .9f, .9f, 0.0, 21_000))
    }

    @Test fun failedFocusBacksOffThenGivesUpUntilTargetMoves() {
        val p = FocusPolicy(); p.markTriggered(1000, .5f, .5f); p.onResult(false)
        assertFalse(p.shouldTrigger(2500, .5f, .5f, 1.0, 0))     // jeda 1,8 dtk setelah gagal pertama
        assertTrue(p.shouldTrigger(3000, .5f, .5f, 1.0, 0))
        p.markTriggered(3000, .5f, .5f); p.onResult(false)       // gagal kedua: jeda 3,6 dtk
        assertFalse(p.shouldTrigger(6000, .5f, .5f, 1.0, 0))
        assertTrue(p.shouldTrigger(6700, .5f, .5f, 1.0, 0))
        repeat(3) { p.onResult(false) }                          // total 5 gagal > maxRetries
        assertFalse(p.shouldTrigger(1_000_000, .5f, .5f, 1.0, 0))
        assertTrue(p.shouldTrigger(1_000_000, .8f, .5f, 1.0, 0)) // angka bergeser: coba lagi
        p.onResult(true); assertEquals(0, p.failures)
    }

    @Test fun resetForgetsHistory() {
        val p = FocusPolicy(); p.markTriggered(1000, .5f, .5f); p.onResult(false); p.reset()
        assertEquals(0, p.failures); assertTrue(p.shouldTrigger(1001, .5f, .5f, 1.0, 0))
    }

    @Test fun focusHintsAreShortAndOnlyWhenUseful() {
        assertEquals("", afHint(AfState.IDLE, 0)); assertEquals("", afHint(AfState.LOCKED, 0))
        assertTrue(afHint(AfState.SEARCHING, 0).isNotEmpty())
        assertNotEquals(afHint(AfState.FAILED, 1), afHint(AfState.FAILED, 3))
    }

    // ---- zoom otomatis ----
    @Test fun smallCentredDigitsZoomInByOneStep() {
        assertEquals(1.6f, autoZoomFor(box(.4f, .48f, .6f, .51f), 1f, 1f, 8f), 1e-4f)
    }

    @Test fun doesNotZoomWhenTargetWouldLeaveFrame() {
        assertEquals(1f, autoZoomFor(box(.80f, .48f, .94f, .51f), 1f, 1f, 8f), 1e-6f)
        assertTrue(zoomRoom(box(.80f, .48f, .94f, .51f)) < 1.08f)
        assertTrue(zoomRoom(box(.4f, .48f, .6f, .51f)) > 4f)
    }

    @Test fun comfortableSizeKeepsZoomAndLimitsAreRespected() {
        assertEquals(2f, autoZoomFor(box(.4f, .45f, .6f, .55f), 2f, 1f, 8f), 1e-6f)    // tinggi 10%: pas
        assertEquals(3f, autoZoomFor(box(.4f, .48f, .6f, .51f), 3f, 1f, 3f), 1e-6f)    // sudah di batas atas
        assertEquals(1.2f, autoZoomFor(box(.3f, .3f, .7f, .7f), 2f, 1f, 8f), 1e-4f)    // kebesaran: diperkecil
        assertEquals(1f, autoZoomFor(box(.3f, .3f, .7f, .7f), 1f, 1f, 8f), 1e-6f)      // sudah di batas bawah
    }

    @Test fun remapForZoomScalesAroundCentre() {
        val r = remapForZoom(box(.4f, .45f, .6f, .55f), 2f)
        assertEquals(.3f, r.l, 1e-5f); assertEquals(.7f, r.r, 1e-5f); assertEquals(.4f, r.t, 1e-5f); assertEquals(.6f, r.b, 1e-5f)
        val edge = remapForZoom(box(.1f, .45f, .3f, .55f), 2f)
        assertEquals(0f, edge.l, 1e-6f)                                                  // terpotong di tepi, tetap dalam 0..1
        assertSame(r, remapForZoom(r, 1f))
    }

    // ---- kasus dari foto meter asli ----
    // Koordinat piksel diambil dari foto sampel; hanya teks yang relevan yang ditulis ulang.

    @Test fun photoTruckOdometerWithSpeedDialAndDotTimestamp() {   // 1280x960: ODO 113775, dial 100-160, "22.22.44"
        val lines = listOf(
            listOf(tok("100", 700, 5, 810, 60)), listOf(tok("120", 830, 120, 950, 190)),
            listOf(tok("140", 880, 290, 1000, 360)), listOf(tok("160", 850, 490, 960, 550)),
            listOf(tok("km/h", 560, 460, 690, 500)),
            listOf(tok("ODO", 455, 600, 510, 625), tok("113775", 585, 570, 805, 620)),
            listOf(tok("31", 875, 640, 905, 675), tok("Agu", 915, 640, 975, 680), tok("2026", 985, 640, 1070, 675), tok("22.22.44", 1085, 640, 1230, 675)),
        )
        val km = pickMeter(lines, 1280, 960, want = MeterKind.KM)
        assertEquals("113775", km?.text); assertEquals(Lbl.KM, km?.tag?.kind)
        assertEquals("113775", pickMeter(lines, 1280, 960)?.text)
    }

    @Test fun photoOdometerBelowKmhWithoutReadableLabel() {   // 1200x1600: "km/h" tepat di atas odometer, dial 3 digit lebih besar
        val lines = listOf(
            listOf(tok("100", 620, 255, 730, 320)), listOf(tok("120", 740, 365, 850, 430)),
            listOf(tok("160", 760, 675, 860, 740)), listOf(tok("km/h", 512, 650, 625, 695)),
            listOf(tok("113041", 540, 745, 735, 800)),
            listOf(tok("11", 15, 1435, 70, 1470), tok("November", 80, 1435, 250, 1470), tok("2026", 260, 1435, 340, 1470)),
        )
        assertEquals("113041", pickMeter(lines, 1200, 1600, want = MeterKind.KM)?.text)
    }

    @Test fun photoExcavatorHmSmallDigitsBeatsUtmCoordinatesAndDates() {   // 575x1280: "9127 0h", 01/04/2000, UTM di watermark
        val lines = listOf(
            listOf(tok("9127", 340, 343, 418, 368), tok("0h", 424, 343, 447, 368)),
            listOf(tok("16:58", 353, 385, 440, 418)), listOf(tok("01/04/2000", 220, 750, 332, 772)),
            listOf(tok("H", 230, 540, 248, 560)),
            listOf(tok("50N", 25, 993, 57, 1013), tok("641099", 66, 993, 142, 1013), tok("134778", 152, 993, 232, 1013)),
            listOf(tok("2026", 196, 960, 258, 977)),
        )
        val hit = pickMeter(lines, 575, 1280, want = MeterKind.HM)
        assertEquals("91270", hit?.text)                       // "0h": huruf h = label HM, bukan bagian angka
        assertEquals(LabelTag(Lbl.HM, false), hit?.tag)
        assertEquals("91270", pickMeter(lines, 575, 1280)?.text)
    }

    @Test fun photoMechanicalDrumUsesEngineHoursLabelBelowAndSkipsSerialAndTime() {   // 720x960: drum 010410, "ENGINE HOURS", 6.15.01 PM
        val lines = listOf(
            listOf(tok("QUARTZ", 250, 295, 440, 335)),
            listOf(tok("010410", 180, 390, 500, 440)), listOf(tok("1/10", 450, 462, 500, 490)),
            listOf(tok("ENGINE", 212, 508, 345, 540), tok("HOURS", 370, 508, 485, 540)),
            listOf(tok("V3-08687-22402", 210, 650, 500, 700)),
            listOf(tok("16", 360, 708, 385, 730), tok("Nov", 392, 708, 430, 730), tok("2026", 437, 708, 495, 730), tok("6.15.01", 502, 708, 590, 730), tok("PM", 597, 708, 630, 730)),
        )
        val hit = pickMeter(lines, 720, 960, want = MeterKind.HM)
        assertEquals("010410", hit?.text); assertEquals(LabelTag(Lbl.HM, true), hit?.tag)
    }

    @Test fun photoSmallLcdOdometerWithKmLabel() {   // 1200x1600: LCD kecil (1,4% tinggi) "159108" + "km", dial 3 digit lebih besar
        val lines = listOf(
            listOf(tok("100", 1005, 378, 1062, 408)), listOf(tok("160", 1140, 538, 1200, 568)),
            listOf(tok("200", 1085, 676, 1145, 706)), listOf(tok("x1000r/min", 250, 383, 345, 400)),
            listOf(tok("159108", 590, 583, 695, 606)), listOf(tok("km", 672, 610, 695, 623)),
        )
        val hit = pickMeter(lines, 1200, 1600, want = MeterKind.KM)
        assertEquals("159108", hit?.text); assertEquals(Lbl.KM, hit?.tag?.kind)
    }
}
