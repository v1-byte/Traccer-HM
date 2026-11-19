package com.frostbyte.hmtracker

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TierTest {
    @Test fun idPaketDikenali() {
        assertEquals(Tier.PREMIUM, tierOf("premium"))
        assertEquals(Tier.PLATINUM, tierOf("platinum"))
        assertEquals(Tier.BASIC, tierOf("tidak-ada"))
        assertEquals(Tier.BASIC, tierOf(null))
    }

    @Test fun basicTidakMemakaiFiturBaru() {
        assertFalse(Tier.BASIC.allows(Feature.CAM_LEVEL))
        assertFalse(Tier.BASIC.allows(Feature.CALC_FORECAST))
        assertFalse(Tier.PREMIUM.allows(Feature.CAM_FOCUS_LOCK))
    }

    @Test fun premiumDanPlatinumBertingkat() {
        assertTrue(Tier.PREMIUM.allows(Feature.CAM_LEVEL))
        assertTrue(Tier.PREMIUM.allows(Feature.CALC_EXPORT))
        assertTrue(Tier.PLATINUM.allows(Feature.CAM_FOCUS_LOCK))
        assertTrue(Feature.entries.all { Tier.PLATINUM.allows(it) })
    }

    @Test fun paketTersimpanDiBackup() {
        val j = AppData(tier = "platinum").toJson()
        assertEquals("platinum", cfgFrom(j, listOf(UnitData())).tier)
        assertEquals("basic", cfgFrom(JSONObject("{}"), listOf(UnitData())).tier)   // backup lama tanpa field tier
    }

    // ---- paket terikat tanda tangan perangkat ----
    private val signer = object : Signer {
        override fun sign(data: String): String? {
            val m = javax.crypto.Mac.getInstance("HmacSHA256").apply { init(javax.crypto.spec.SecretKeySpec("k".toByteArray(), "HmacSHA256")) }
            return m.doFinal(data.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
    private val noKey = object : Signer { override fun sign(data: String): String? = null }

    @Test fun paketBertandaTanganDipercaya() {
        val sig = tierSig(signer, Tier.PLATINUM)
        assertEquals(Tier.PLATINUM, verifiedTier("platinum", sig, signer))
    }

    @Test fun paketDiubahTanpaTandaTanganKembaliKeBasic() {
        assertEquals(Tier.BASIC, verifiedTier("platinum", null, signer))
        assertEquals(Tier.BASIC, verifiedTier("platinum", "", signer))
        assertEquals(Tier.BASIC, verifiedTier("platinum", "ngawur", signer))
    }

    @Test fun tandaTanganPremiumTidakMenaikkanKePlatinum() {
        val sigPremium = tierSig(signer, Tier.PREMIUM)
        assertEquals(Tier.BASIC, verifiedTier("platinum", sigPremium, signer))
    }

    @Test fun tanpaKunciPaketBerbayarTidakDipercaya() {
        assertNull(tierSig(noKey, Tier.PREMIUM))
        assertEquals(Tier.BASIC, verifiedTier("premium", tierSig(signer, Tier.PREMIUM), noKey))
        assertEquals(Tier.BASIC, verifiedTier("basic", null, noKey))
    }
}
