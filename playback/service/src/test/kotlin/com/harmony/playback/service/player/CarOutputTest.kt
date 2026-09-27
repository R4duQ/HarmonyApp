package com.harmony.playback.service.player

import com.harmony.core.model.AudioOutput
import com.harmony.core.model.AudioOutputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarOutputTest {
    private val earbuds = AudioOutput(AudioOutputType.BLUETOOTH, "Galaxy Buds")

    @Test fun withoutAndroidAutoTheDeviceIsReported() {
        assertEquals(earbuds, CarOutput.resolve(earbuds, carConnections = 0))
        assertEquals("Galaxy Buds", CarOutput.resolve(earbuds, 0).label)
    }

    @Test fun androidAutoOverBluetoothKeepsTheCarsName() {
        val car = CarOutput.resolve(AudioOutput(AudioOutputType.BLUETOOTH, "VW BT 4521"), carConnections = 1)
        assertEquals(AudioOutputType.CAR, car.type)
        assertEquals("Android Auto · VW BT 4521", car.label)
    }

    @Test fun androidAutoOverUsbDoesNotBorrowTheUsbName() {
        val car = CarOutput.resolve(AudioOutput(AudioOutputType.USB, "Android Accessory"), carConnections = 2)
        assertEquals(AudioOutput(AudioOutputType.CAR), car)
        assertEquals("Android Auto", car.label)
    }

    @Test fun onlyAndroidAutosControllerCounts() {
        assertTrue(CarOutput.isCarController("com.google.android.projection.gearhead"))
        assertFalse(CarOutput.isCarController("com.android.systemui"))
        assertFalse(CarOutput.isCarController("com.google.android.wearable.app"))
    }
}
