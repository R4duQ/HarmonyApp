package com.harmony.feature.downloads

import org.junit.Assert.*
import org.junit.Test

class SpotiFlacProviderQualityTest {
    private val hiRes = SpotiFlacOutputFormat.FLAC_HI_RES_96
    // Official extension manifest shapes inspected 2026-09-13. Order matters:
    // Go 4.9.5 replaces unrecognized tokens with the first advertised option.
    private val tidal = listOf(
        SpotiFlacQualityOption("DOLBY_ATMOS", "spatial"),
        SpotiFlacQualityOption("HI_RES_LOSSLESS", "lossless"),
        SpotiFlacQualityOption("LOSSLESS", "lossless"),
        SpotiFlacQualityOption("HIGH", "lossy"),
        SpotiFlacQualityOption("LOW", "lossy"),
    )
    private val qobuz = listOf("HI_RES_LOSSLESS", "HI_RES", "LOSSLESS").map { SpotiFlacQualityOption(it, "lossless") }
    private val amazon = listOf(
        SpotiFlacQualityOption("best", "lossless"), SpotiFlacQualityOption("opus", "lossy"),
        SpotiFlacQualityOption("eac3", "spatial"), SpotiFlacQualityOption("ac4", "spatial"),
    )

    @Test fun tidalHiResDoesNotTriggerTheNativeAtmosFallback() {
        fun nativeRouter(requested: String) = tidal.firstOrNull { it.id == requested }?.id ?: tidal.first().id
        assertEquals("DOLBY_ATMOS", nativeRouter("HI_RES")) // Previous request reproduced.
        val selected = requireNotNull(SpotiFlacProviderQuality.select("tidal-web", hiRes, tidal))
        assertEquals("HI_RES_LOSSLESS", selected)
        assertEquals("HI_RES_LOSSLESS", nativeRouter(selected))
    }

    @Test fun qobuzPrefersIts96kHzTier() {
        assertEquals("HI_RES", SpotiFlacProviderQuality.select("qobuz-web", hiRes, qobuz))
    }

    @Test fun amazonUsesItsOwnBestLosslessToken() {
        assertEquals("best", SpotiFlacProviderQuality.select("amazon", hiRes, amazon))
    }

    @Test fun cdOnlyProvidersStillReturnAPlayableNativeSource() {
        assertEquals("LOSSLESS", SpotiFlacProviderQuality.select("deezer", hiRes, listOf(SpotiFlacQualityOption("LOSSLESS"))))
        assertEquals("LOSSLESS", SpotiFlacProviderQuality.select("tidal-web", hiRes, listOf(SpotiFlacQualityOption("LOSSLESS"))))
    }

    @Test fun cdAndMp3KeepRequestingStandardLossless() {
        for (format in listOf(SpotiFlacOutputFormat.FLAC_LOSSLESS, SpotiFlacOutputFormat.MP3_320)) {
            assertEquals("LOSSLESS", SpotiFlacProviderQuality.select("tidal-web", format, tidal))
            assertEquals("LOSSLESS", SpotiFlacProviderQuality.select("qobuz-web", format, qobuz))
            assertEquals("best", SpotiFlacProviderQuality.select("amazon", format, amazon))
        }
    }

    @Test fun legacyManifestsUseProviderSpecificLosslessDefaults() {
        assertEquals("HI_RES_LOSSLESS", SpotiFlacProviderQuality.select("tidal-web", hiRes, null))
        assertEquals("HI_RES", SpotiFlacProviderQuality.select("qobuz-web", hiRes, emptyList()))
        assertEquals("best", SpotiFlacProviderQuality.select("amazon", hiRes, null))
        assertEquals("LOSSLESS", SpotiFlacProviderQuality.select("deezer", hiRes, null))
    }

    @Test fun neverSelectsFirstSpatialOrLossyOptionWithoutALosslessCapability() {
        assertNull(SpotiFlacProviderQuality.select("tidal-web", hiRes, listOf(tidal.first(), tidal.last())))
        assertNull(SpotiFlacProviderQuality.select("amazon", hiRes, amazon.drop(1)))
        assertNull(SpotiFlacProviderQuality.select("new-provider", hiRes, null))
    }

    @Test fun availableCapabilitiesOverrideKnownProviderDefaults() {
        val changed = listOf(SpotiFlacQualityOption("DOLBY_ATMOS", "spatial"), SpotiFlacQualityOption("HI_RES", "lossless"))
        assertEquals("HI_RES", SpotiFlacProviderQuality.select("tidal-web", hiRes, changed))
        assertEquals("HI_RES_LOSSLESS", SpotiFlacProviderQuality.select("qobuz-web", hiRes, qobuz.take(1)))
    }

    @Test fun handlesMissingKindAndPreservesTheProvidersExactToken() {
        assertEquals("hi_res_lossless", SpotiFlacProviderQuality.select("tidal-web", hiRes, listOf(
            SpotiFlacQualityOption("DOLBY_ATMOS"), SpotiFlacQualityOption("hi_res_lossless"),
        )))
    }

    @Test fun explicitlyNonLosslessOptionsAreExcludedEvenWithKnownNames() {
        assertNull(SpotiFlacProviderQuality.select("tidal-web", hiRes, listOf(SpotiFlacQualityOption("HI_RES_LOSSLESS", "spatial"))))
    }
}
