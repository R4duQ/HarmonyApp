package com.harmony.core.ui.component

import com.harmony.core.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VinylQualityLabelTest {
    private fun song(bits: Int?, rate: Int?, kbps: Int?) = Song(
        1, "content://media/1", "T", "A", "Al", 1, null, null, null, null, null, null, 200_000,
        kbps, rate, bits, 2, null, null, null, null,
    )

    @Test fun `high resolution and CD quality are told apart`() {
        assertEquals("HI-RES 24/96", qualityLabel(song(24, 96_000, 2_800)))
        assertEquals("HI-RES 24/44.1", qualityLabel(song(24, 44_100, null)))
        assertEquals("LOSSLESS 16/44.1", qualityLabel(song(16, 44_100, 1_411)))
        assertEquals("LOSSLESS 16/48", qualityLabel(song(16, 48_000, null)))
    }

    @Test fun `lossy files show their bitrate, unknown files nothing`() {
        assertEquals("320 KBPS", qualityLabel(song(null, 44_100, 320)))
        assertNull(qualityLabel(song(null, null, null)))
        assertNull(qualityLabel(song(0, 0, 0)))
    }
}
