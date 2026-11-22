package com.frostbyte.hmtracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Daftar template watermark: id unik, template lama tidak berubah, dan 8 template baru terdaftar. */
class WatermarkTemplatesTest {
    private val lama = listOf("pro", "minimal", "bold", "strip", "classic", "detail")
    private val baru = listOf("capsule", "ticket", "ring", "grid", "stamp", "banner", "glass", "poster")

    @Test fun idUnikDanTidakKosong() {
        val ids = WM_TEMPLATES.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(WM_TEMPLATES.all { it.id.isNotBlank() && it.label.isNotBlank() && it.desc.isNotBlank() })
    }

    @Test fun enamTemplateLamaTetapAdaDenganUrutanSama() {
        assertEquals(lama, WM_TEMPLATES.take(6).map { it.id })
    }

    @Test fun delapanTemplateBaruTerdaftar() {
        assertEquals(baru, WM_TEMPLATES.drop(6).map { it.id })
        assertEquals(14, WM_TEMPLATES.size)
    }

    @Test fun templateBawaanDanClassicTetapGratis() {
        assertFalse(wmTemplateOf("pro")!!.premium)
        assertFalse(wmTemplateOf("classic")!!.premium)
        assertTrue(lama.filter { it !in setOf("pro", "classic") }.all { wmTemplateOf(it)!!.premium })   // sama seperti sebelum penambahan
    }

    @Test fun templateBaruButuhPremiumKecualiDiubah() {
        assertTrue(baru.all { wmTemplateOf(it)!!.premium })
    }

    @Test fun idTakDikenalTidakAda() {
        assertEquals(null, wmTemplateOf("tidak-ada"))
        assertNotNull(wmTemplateOf(AppData().wmTpl))   // template bawaan AppData harus terdaftar
    }

    @Test fun paketBasicTidakMemakaiTemplateTambahan() {
        assertFalse(AppData(tier = "basic").allows(Feature.WM_EXTRA_TEMPLATES))
        assertTrue(AppData(tier = "premium").allows(Feature.WM_EXTRA_TEMPLATES))
    }
}
