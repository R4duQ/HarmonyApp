package com.harmony.playback.service.player

import com.harmony.core.model.AudioOutput
import com.harmony.core.model.AudioOutputType.BLUETOOTH
import com.harmony.core.model.AudioOutputType.SPEAKER
import com.harmony.core.model.AudioOutputType.WIRED
import com.harmony.core.model.OutputForm
import com.harmony.core.model.OutputForm.CAR_STEREO
import com.harmony.core.model.OutputForm.EARBUDS
import com.harmony.core.model.OutputForm.EARPHONES
import com.harmony.core.model.OutputForm.HEADPHONES
import com.harmony.core.model.OutputForm.HEARING_AID
import com.harmony.core.model.OutputForms
import org.junit.Assert.assertEquals
import org.junit.Test

class OutputFormsTest {
    private fun bt(name: String) = OutputForms.guess(BLUETOOTH, null, name)

    @Test fun earbudsAreRecognisedByTheirNames() {
        listOf("Galaxy Buds2 Pro", "AirPods Pro", "Pixel Buds A-Series", "WF-1000XM5", "Jabra Elite 85t", "Nothing Ear (2)",
            "Soundcore Liberty 4", "Soundcore Life P3", "Beats Fit Pro", "HUAWEI FreeBuds 5i", "Bose QC Earbuds II",
            "JBL Live Pro 2 TWS", "Redmi Buds 4", "OnePlus Buds Pro 2", "Echo Buds", "Momentum True Wireless 3")
            .forEach { assertEquals(it, EARBUDS, bt(it)) }
    }

    @Test fun overEarHeadphonesNeckbandsSpeakersAndCars() {
        listOf("WH-1000XM4", "Bose QuietComfort 45", "AirPods Max", "Jabra Elite 85h", "Beats Studio3", "Soundcore Life Q30",
            "JBL Tune 510BT", "Sennheiser HD 450BT", "MOMENTUM 4", "Marshall Major IV")
            .forEach { assertEquals(it, HEADPHONES, bt(it)) }
        listOf("WI-C200", "OnePlus Bullets Wireless Z2", "Realme Buds Wireless neckband")
            .forEach { assertEquals(it, OutputForm.NECKBAND, bt(it)) }
        listOf("JBL Flip 6", "JBL Charge 5", "Bose SoundLink Flex", "UE BOOM 3", "Marshall Emberton", "Soundcore Motion+", "SRS-XB13")
            .forEach { assertEquals(it, OutputForm.SPEAKER, bt(it)) }
        listOf("VW BT 4521", "MY CAR", "Dacia MediaNav", "SYNC", "Toyota Touch", "CAR MULTIMEDIA")
            .forEach { assertEquals(it, CAR_STEREO, bt(it)) }
    }

    @Test fun unknownOrLookalikeNamesStayGeneric() {
        listOf("T15", "Carbon X", "Nestor's", "Budapest Radio", "Kiara", "BT-Audio").forEach { assertEquals(it, null, bt(it)) }
        assertEquals(null, bt(""))
    }

    @Test fun androidsOwnDeviceTypeComesFirstWhenItIsSure() {
        assertEquals(HEARING_AID, OutputForms.guess(BLUETOOTH, HEARING_AID, "Galaxy Buds"))
        assertEquals(OutputForm.SPEAKER, OutputForms.guess(BLUETOOTH, OutputForm.SPEAKER, "Galaxy Buds"))
        // A wired headset is usually earphones, but a name can say otherwise.
        assertEquals(EARPHONES, OutputForms.guess(WIRED, EARPHONES, null))
        assertEquals(HEADPHONES, OutputForms.guess(WIRED, EARPHONES, "Sennheiser HD 600"))
        assertEquals(null, OutputForms.guess(SPEAKER, null, "Phone"))
    }

    @Test fun theListenersChoiceForADeviceWins() {
        val output = AudioOutput(BLUETOOTH, "T15", form = null)
        assertEquals(null, OutputForms.effective(output, emptyMap()))
        assertEquals(EARBUDS, OutputForms.effective(output, mapOf(OutputForms.key(" t15 ") to EARBUDS)))
        assertEquals(HEADPHONES, OutputForms.effective(output.copy(form = HEADPHONES), mapOf("other" to EARBUDS)))
    }
}
