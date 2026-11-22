package com.frostbyte.hmtracker

import org.junit.Assert.*
import org.junit.Test

class AutoFindTest {
    private fun tok(s: String, l: Int, t: Int, r: Int, b: Int) = OcrTok(s, l, t, r, b)
    private val fw = 960
    private val fh = 1280

    // ---- pickMeter ----
    @Test fun picksDigitStringAndIgnoresLabelsAndShortNumbers() {
        val lines = listOf(
            listOf(tok("SERIAL", 50, 50, 200, 80), tok("12", 210, 50, 240, 80)),
            listOf(tok("12345", 300, 600, 600, 700)),
        )
        val f = pickMeter(lines, fw, fh)
        assertEquals("12345", f?.text)
        assertEquals(300, f?.l); assertEquals(600, f?.r); assertEquals(600, f?.t); assertEquals(700, f?.b)
    }

    @Test fun mergesCloseElementsIntoOneNumber() {
        val f = pickMeter(listOf(listOf(tok("123", 100, 500, 220, 560), tok("45", 230, 500, 290, 560))), fw, fh)
        assertEquals("12345", f?.text)
        assertEquals(100, f?.l); assertEquals(290, f?.r)
    }

    @Test fun farApartNumbersStaySeparateAndCenterWins() {
        val lines = listOf(listOf(tok("123", 100, 500, 220, 560), tok("456", 600, 500, 720, 560)))
        assertEquals("456", pickMeter(lines, fw, fh)?.text)   // "456" lebih dekat ke tengah frame
    }

    @Test fun tappedPointOverridesCenter() {
        val lines = listOf(listOf(tok("123", 100, 500, 220, 560), tok("456", 600, 500, 720, 560)))
        assertEquals("123", pickMeter(lines, fw, fh, prefX = 0.17f, prefY = 0.41f)?.text)
    }

    @Test fun rejectsTinyTextSquareBlobsAndEmptyInput() {
        assertNull(pickMeter(listOf(listOf(tok("12345", 10, 10, 110, 20))), fw, fh))      // tinggi < 1% frame
        assertNull(pickMeter(listOf(listOf(tok("123", 100, 100, 150, 160))), fw, fh))     // hampir persegi
        assertNull(pickMeter(listOf(listOf(tok("ABC", 100, 100, 400, 160))), fw, fh))     // tanpa digit
        assertNull(pickMeter(emptyList(), fw, fh))
        assertNull(pickMeter(listOf(listOf(tok("12345", 100, 100, 400, 160))), 0, 0))
    }

    @Test fun fixesLookalikeLettersInsideDigitStrings() {
        assertEquals("10300", pickMeter(listOf(listOf(tok("1O3OO", 100, 500, 400, 560))), fw, fh)?.text)
    }

    // ---- geometri pelacakan ----
    @Test fun trackToRoiScalesAndPads() {
        val tr = trackOf(128, 256, 384, 320, 1024, 1024)
        assertEquals(Roi(128, 256, 256, 64), trackToRoi(tr, 1024, 1024, 0f, 0f))
        assertEquals(Roi(64, 224, 384, 128), trackToRoi(tr, 1024, 1024, 0.25f, 0.5f))
        assertEquals(Roi(256, 512, 512, 128), trackToRoi(tr, 2048, 2048, 0f, 0f))   // resolusi foto lebih tinggi, rasio sama
    }

    @Test fun trackToRoiStaysInsideFrame() {
        val tr = trackOf(0, 0, 256, 64, 1024, 1024)
        assertEquals(Roi(0, 0, 512, 128), trackToRoi(tr, 1024, 1024, 0.5f, 0.5f))
    }

    @Test fun viewToNormMatchesFillCenter() {
        val c = viewToNorm(540f, 960f, 1080, 1920, 960, 1280)
        assertEquals(0.5f, c.first, 1e-4f); assertEquals(0.5f, c.second, 1e-4f)
        val e = viewToNorm(0f, 0f, 1080, 1920, 960, 1280)   // sisi kiri terpotong 180px oleh FILL_CENTER
        assertEquals(0.125f, e.first, 1e-4f); assertEquals(0f, e.second, 1e-4f)
    }

    @Test fun trackInViewIsCenteredAndRespectsMinimumSize() {
        val tr = NumTrack(0.4f, 0.45f, 0.6f, 0.5f, 960, 1280)
        val b = trackInView(tr, 1080, 1920, 2f)
        assertEquals(540f, b[0] + b[2] / 2f, 1f); assertEquals(912f, b[1] + b[3] / 2f, 1f)
        val tiny = trackInView(NumTrack(0.5f, 0.5f, 0.51f, 0.51f, 960, 1280), 1080, 1920, 2f)
        assertEquals(260f, tiny[2], 0.01f); assertEquals(120f, tiny[3], 0.01f)
    }

    @Test fun trackInViewStaysInsideView() {
        val b = trackInView(NumTrack(0.95f, 0.5f, 1f, 0.55f, 960, 1280), 1080, 1920, 2f)
        assertTrue(b[0] >= 0f && b[0] + b[2] <= 1080.01f)
        assertTrue(b[1] >= 0f && b[1] + b[3] <= 1920.01f)
    }
}
