package com.frostbyte.hmtracker

import org.junit.Assert.*
import org.junit.Test

class CameraAssistTest {
    private fun still(g: MotionGate, n: Int = 40) { repeat(n) { g.feed(0.02f, -0.01f, 9.81f) } }

    @Test fun stillPhoneIsNotMoving() {
        val g = MotionGate(); still(g)
        assertFalse(g.moving)
        assertTrue(g.level < 0.2f)
    }

    @Test fun shakingIsDetectedAndClearsAfterRest() {
        val g = MotionGate(); still(g)
        repeat(30) { i -> g.feed(if (i % 2 == 0) 3f else -3f, 0f, 9.81f) }
        assertTrue(g.moving)
        still(g, 80)
        assertFalse(g.moving)
    }

    @Test fun slowTiltIsNotCountedAsShake() {
        val g = MotionGate(); still(g)
        // HP dimiringkan pelan (±6 dtk): gravitasi berpindah sumbu sedikit demi sedikit
        for (i in 0..300) { val a = i / 300f * 0.6f; g.feed(9.81f * kotlin.math.sin(a), 0f, 9.81f * kotlin.math.cos(a)) }
        assertFalse(g.moving)
    }

    @Test fun moderateShakeStillCounts() {
        val g = MotionGate()
        still(g)
        repeat(20) { i -> g.feed(if (i % 2 == 0) 2f else -2f, 0f, 9.81f) }
        assertTrue(g.moving)
    }

    @Test fun resetClearsState() {
        val g = MotionGate(); repeat(20) { i -> g.feed(if (i % 2 == 0) 3f else -3f, 0f, 9.81f) }
        g.reset()
        assertFalse(g.moving); assertEquals(0f, g.level, 0f)
    }

    @Test fun zoomPresetsFollowLensRange() {
        assertEquals(listOf(1f, 2f, 4f), zoomPresets(1f, 8f))
        assertEquals(listOf(1f, 2f), zoomPresets(1f, 2f))
        assertTrue(zoomPresets(1f, 1f).isEmpty())
        assertEquals(listOf(2f, 4f), zoomPresets(2f, 10f))
    }

    @Test fun onPresetTolerance() {
        assertTrue(onPreset(2.05f, 2f)); assertTrue(onPreset(1f, 1f))
        assertFalse(onPreset(1.5f, 1f)); assertFalse(onPreset(3f, 2f))
    }

    @Test fun trainReadingAcceptsAnyNumberWithoutUnitChecks() {
        val r = trainReading("1041,0")
        assertEquals(1041.0, r!!.value, 0.0); assertEquals("1041.0", r.text)
        assertFalse(r.blocked); assertFalse(r.warned)
        assertNull(trainReading("abc")); assertNull(trainReading(""))
    }
}

class AutoShootGateTest {
    @Test fun firesOnlyAfterHold() {
        val g = AutoShootGate(1000, 3000)
        assertFalse(g.feed(0, true, "853.1", true))
        assertFalse(g.feed(600, true, "853.1", true))
        assertTrue(g.feed(1100, true, "853.1", true))
    }

    @Test fun valueChangeRestartsHold() {
        val g = AutoShootGate(1000, 3000)
        g.feed(0, true, "853.1", true)
        assertFalse(g.feed(900, true, "853.2", true))
        assertFalse(g.feed(1500, true, "853.2", true))
        assertTrue(g.feed(1950, true, "853.2", true))
    }

    @Test fun shakeOrNotReadyResets() {
        val g = AutoShootGate(1000, 3000)
        g.feed(0, true, "9127.0", true)
        assertFalse(g.feed(800, true, "9127.0", false))
        assertFalse(g.feed(1200, true, "9127.0", true))     // mulai hitung lagi
        assertTrue(g.feed(2300, true, "9127.0", true))
        g.reset()
        assertFalse(g.feed(2400, false, "9127.0", true))
    }

    @Test fun cooldownBlocksRepeat() {
        val g = AutoShootGate(1000, 3000)
        g.feed(0, true, "113775", true)
        assertTrue(g.feed(1000, true, "113775", true))
        assertFalse(g.feed(2200, true, "113775", true))
        assertFalse(g.feed(3900, true, "113775", true))
        assertTrue(g.feed(4100, true, "113775", true))
    }
}

class TrimRoiTest {
    private val core = Roi(100, 100, 200, 40)

    @Test fun stampBelowTrimsBottomButKeepsNumber() {
        val roi = Roi(70, 60, 260, 200)                       // bantalan lebar, bawahnya kena stempel di y=170
        val out = trimRoiAwayFromStamps(roi, core, listOf(Roi(80, 170, 200, 20)))
        assertEquals(170, out.y + out.h)
        assertTrue(out.y + out.h >= core.y + core.h)
        assertEquals(60, out.y)
    }

    @Test fun stampAtRightTrimsRight() {
        val roi = Roi(70, 60, 400, 120)
        val out = trimRoiAwayFromStamps(roi, core, listOf(Roi(380, 90, 90, 50)))
        assertEquals(380, out.x + out.w)
        assertTrue(out.x + out.w >= core.x + core.w)
    }

    @Test fun stampOverlappingNumberIsIgnored() {
        val roi = Roi(70, 60, 260, 120)
        assertEquals(roi, trimRoiAwayFromStamps(roi, core, listOf(Roi(150, 110, 60, 20))))
    }

    @Test fun farawayStampChangesNothing() {
        val roi = Roi(70, 60, 260, 120)
        assertEquals(roi, trimRoiAwayFromStamps(roi, core, listOf(Roi(700, 700, 90, 50))))
    }

    @Test fun trimNeverCutsBelowCore() {
        val roi = Roi(90, 90, 220, 60)
        val out = trimRoiAwayFromStamps(roi, core, listOf(Roi(90, 130, 220, 20)))   // menempel tepat di tepi bawah angka
        assertTrue(out.y + out.h >= core.y + core.h)
    }

    // ---- histogram, saran eksposur, pengaturan tersimpan ----
    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    @Test fun histogramSumsToOneAndPlacesBins() {
        val h = lumaHistogram(IntArray(100) { gray(0) } + IntArray(100) { gray(255) }, 32)
        assertEquals(1f, h.sum(), 0.001f)
        assertEquals(.5f, h[0], 0.001f); assertEquals(.5f, h[31], 0.001f)
        assertEquals(0f, lumaHistogram(IntArray(0), 32).sum(), 0f)
    }

    @Test fun burntHighlightsAdviseLoweringExposure() {
        val burnt = lumaHistogram(IntArray(100) { gray(if (it < 30) 255 else 120) }, 32)
        assertEquals(-2, exposureAdvice(burnt).evSteps)
        val some = lumaHistogram(IntArray(100) { gray(if (it < 15) 255 else 120) }, 32)
        assertEquals(-1, exposureAdvice(some).evSteps)
        assertNotNull(exposureAdvice(some).msg)
    }

    @Test fun darkFrameAdvisesRaisingAndBalancedFrameNothing() {
        val dark = lumaHistogram(IntArray(100) { gray(if (it < 80) 3 else 120) }, 32)
        assertEquals(2, exposureAdvice(dark).evSteps)
        val mid = lumaHistogram(IntArray(100) { gray(60 + it) }, 32)
        val a = exposureAdvice(mid)
        assertEquals(0, a.evSteps); assertNull(a.msg)
        assertEquals(0, exposureAdvice(FloatArray(3)).evSteps)
    }

    @Test fun evIsClampedToCameraRange() {
        assertEquals(12, clampEv(10, 5, -12, 12)); assertEquals(-12, clampEv(-10, -5, -12, 12)); assertEquals(3, clampEv(1, 2, -12, 12))
    }

    @Test fun gridAndTimerCycle() {
        assertEquals(listOf(0, 1, 2, 0), listOf(0, 1, 2).let { l -> l + nextGrid(l.last()) })
        assertEquals(listOf(3, 5, 10, 0, 3), listOf(0, 3, 5, 10).map { nextTimer(it) } + nextTimer(0))
        assertEquals("Mati", gridLabel(0)); assertEquals("3×3+", gridLabel(2))
    }

    @Test fun rollLabelRoundsAndTreatsSmallTiltAsLevel() {
        assertEquals("0°", rollLabel(1.2f)); assertEquals("0°", rollLabel(-0.4f))
        assertEquals("4°", rollLabel(-3.6f)); assertEquals("12°", rollLabel(12.2f))
    }

    @Test fun camPrefsRoundTripAndTolerateGarbage() {
        val p = CamPrefs(2, true, false, false, false, 5, 0, true)
        val q = CamPrefs.decode(p.encode())
        assertEquals(2, q.grid); assertTrue(q.hist); assertFalse(q.level); assertFalse(q.sound); assertFalse(q.haptic)
        assertEquals(5, q.timer); assertEquals(0, q.flash); assertTrue(q.autoZoom)
        val d = CamPrefs.decode("g=99;t=7;f=9;zzz;h=x")
        assertEquals(2, d.grid); assertEquals(0, d.timer); assertEquals(2, d.flash); assertFalse(d.hist)
        val n = CamPrefs.decode(null)
        assertEquals(0, n.grid); assertTrue(n.level); assertTrue(n.sound); assertTrue(n.haptic); assertFalse(n.autoZoom)
    }

    @Test fun activeModeCountOnlyCountsModesThatChangeThePhoto() {
        assertEquals(0, activeModeCount(false, false, 0, 0, false))
        assertEquals(5, activeModeCount(true, true, 10, -2, true))
        assertEquals(1, activeModeCount(false, false, 3, 0, false))
        assertEquals(1, activeModeCount(false, false, 0, 4, false))
        assertEquals(2, activeModeCount(true, false, 0, 0, true))
    }
}

class ZoomAndProgressTest {
    @Test fun shootProgressFillsWhileHeldThenResets() {
        val g = AutoShootGate(1000, 3000)
        assertEquals(0f, g.progress(0), 0f)
        g.feed(0, true, "853.1", true)
        assertEquals(0.5f, g.progress(500), 0.001f)
        assertEquals(1f, g.progress(5000), 0f)
        g.feed(300, true, "853.1", false)                       // HP goyang: mulai dari nol lagi
        assertEquals(0f, g.progress(400), 0f)
    }

    @Test fun shootProgressIsZeroDuringCooldown() {
        val g = AutoShootGate(1000, 3000)
        g.feed(0, true, "1.0", true)
        assertTrue(g.feed(1100, true, "1.0", true))
        assertEquals(0f, g.progress(2000), 0f)
    }

    @Test fun zoomSliderRoundTripsAndSnapsToPresets() {
        assertEquals(0f, zoomToFrac(0.6f, 0.6f, 8f), 0.001f)
        assertEquals(1f, zoomToFrac(8f, 0.6f, 8f), 0.001f)
        assertEquals(2f, zoomFromFrac(zoomToFrac(2.05f, 1f, 8f), 1f, 8f), 0f)   // magnet ke 2×
        assertEquals(1f, zoomFromFrac(0.01f, 1f, 8f), 0f)
        val z = zoomFromFrac(0.62f, 1f, 8f)
        assertEquals(0.62f, zoomToFrac(z, 1f, 8f), 0.01f)
        assertTrue(z in 1f..8f)
    }

    @Test fun zoomHelpersTolerateOddRanges() {
        assertEquals(0f, zoomToFrac(2f, 1f, 1f), 0f)
        assertEquals(1f, zoomFromFrac(0.7f, 1f, 1f), 0f)
        assertEquals(3f, zoomSnap(3f), 0f)
    }

    @Test fun zoomLabel() {
        assertEquals("1×", fmtZoom(1.0f)); assertEquals("2×", fmtZoom(1.97f)); assertEquals("2.3×", fmtZoom(2.3f))
    }
}

