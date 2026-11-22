package com.frostbyte.hmtracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Meter drum mekanik QUARTZ "ENGINE HOURS 1/10" (foto lapangan unit Jd 08): lima drum gelap di kiri = HM (01058),
 * drum oranye/merah = 1/10 jam (tidak wajib), lalu drum terang. Strip sintetis di bawah meniru tata letak foto itu
 * (strip 300 x 110 px; digit HM berakhir di x = 178, drum merah di x = 210..228).
 */
class DrumSplitTest {
    private val w = 300
    private val h = 110
    private val dark = 0xFF1E1E1E.toInt()
    private val light = 0xFFD8D8D8.toInt()
    private val orange = 0xFFD79277.toInt()      // warna digit 1/10 pada foto asli (215,146,119)
    private val marker = 0xFFE56B65.toInt()      // warna coretan spidol merah (229,107,101)

    private fun fill(a: IntArray, x0: Int, x1: Int, y0: Int, y1: Int, c: Int) {
        for (y in y0..y1) for (x in x0..x1) a[y * w + x] = c
    }

    /** Lima digit terang (drum gelap), satu digit oranye 1/10 tebal-tinggi, dan satu drum terang di kanan. */
    private fun strip(): IntArray {
        val a = IntArray(w * h) { dark }
        for (i in 0 until 5) fill(a, 20 + i * 34, 20 + i * 34 + 22, 25, 85, light)   // digit sampai x = 178
        fill(a, 210, 228, 28, 82, orange)
        fill(a, 236, 260, 25, 85, light)
        return a
    }

    @Test fun cutsRightBeforeTheRedTenthDrum() {
        val dc = findDrumCut(strip(), w, h)
        assertNotNull(dc)
        assertTrue("potongan harus sesudah digit HM terakhir", dc!!.cut > 178)
        assertTrue("potongan harus sebelum drum merah", dc.cut < 210)
        assertTrue(dc.tenthLeft >= dc.cut && dc.tenthRight > 228)
    }

    @Test fun noRedDrumMeansReadWholeStrip() {
        val a = IntArray(w * h) { dark }
        for (i in 0 until 6) fill(a, 20 + i * 34, 20 + i * 34 + 22, 25, 85, light)
        assertNull(findDrumCut(a, w, h))
    }

    @Test fun thinRedMarkerLinesDoNotMoveTheCut() {
        val clean = findDrumCut(strip(), w, h)!!
        val a = strip()
        // coretan spidol merah tipis (2 px) di atas dan bawah strip, dari kiri drum merah sampai ujung kanan
        fill(a, 156, 290, 2, 3, marker); fill(a, 156, 290, 105, 106, marker)
        assertEquals(clean.cut, findDrumCut(a, w, h)!!.cut)
    }

    @Test fun redBlobOnTheLeftSideIsNotATenthDrum() {
        val a = strip()
        fill(a, 210, 228, 28, 82, dark)    // buang drum merah asli
        fill(a, 20, 42, 25, 85, orange)    // merah di sisi kiri strip (bukan drum 1/10)
        assertNull(findDrumCut(a, w, h))
    }

    @Test fun tooSmallStripIsIgnored() {
        assertNull(findDrumCut(IntArray(20 * 4) { orange }, 20, 4))
    }

    @Test fun redPixelRuleAcceptsOrangeAndRejectsDarkAndWhite() {
        assertTrue(isRedPx(orange)); assertTrue(isRedPx(marker))
        assertTrue(!isRedPx(dark)); assertTrue(!isRedPx(light))
    }

    /**
     * Tata letak foto QUARTZ Jd 08 (00991, drum 1/10 = 2): drum 1/10 berlatar PUTIH dan hanya sebagian angka merahnya yang
     * lolos isRedPx. Pemotong lama memakai tengah gugus merah dan memotong di dalam latar putih (x = 209).
     */
    private fun whiteTenthStrip(): IntArray {
        val a = IntArray(w * h) { dark }
        for (i in 0 until 5) fill(a, 20 + i * 34, 20 + i * 34 + 22, 25, 85, light)   // digit sampai x = 178
        fill(a, 196, 250, 22, 88, 0xFFE8E8E8.toInt())                                  // latar putih drum 1/10
        fill(a, 214, 224, 30, 80, 0xFFE05A3C.toInt())                                  // bagian angka merah yang terdeteksi
        return a
    }

    @Test fun cutMovesBackToTheLeftEdgeOfTheWhiteTenthDrum() {
        val dc = findDrumCut(whiteTenthStrip(), w, h)
        assertNotNull(dc)
        assertTrue("potongan harus sesudah digit HM terakhir", dc!!.cut > 178)
        assertTrue("potongan harus sebelum latar putih drum 1/10 (x = 196)", dc.cut < 196)
        assertTrue(dc.tenthRight > 224)
    }

    @Test fun leftEdgeStopsAtTheDarkGapAndNeverEatsThePreviousDrum() {
        val a = whiteTenthStrip()
        assertEquals(196, drumLeftEdge(a, w, h, 214, 11, 30, 80))
        // tanpa latar putih (strip lama): tidak ada yang digeser
        assertEquals(210, drumLeftEdge(strip(), w, h, 210, 19, 28, 82))
    }

    @Test fun lowContrastStripKeepsTheOriginalRule() {
        val a = IntArray(w * h) { 0xFF808080.toInt() }
        assertEquals(150, drumLeftEdge(a, w, h, 150, 20, 20, 90))
    }
}
