package com.harmony.feature.downloads

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotiFlacNativeRuntimeTest {
    @Test
    fun mapsAndroidAbisToElfMachines() {
        assertEquals(183, SpotiFlacNativeRuntime.elfMachineForAbi("arm64-v8a"))
        assertEquals(62, SpotiFlacNativeRuntime.elfMachineForAbi("x86_64"))
        assertEquals(40, SpotiFlacNativeRuntime.elfMachineForAbi("armeabi-v7a"))
        assertEquals(3, SpotiFlacNativeRuntime.elfMachineForAbi("x86"))
        assertNull(SpotiFlacNativeRuntime.elfMachineForAbi("riscv64"))
    }

    @Test
    fun readsLittleEndianElfMachine() {
        val file = Files.createTempFile("harmony-elf", ".so").toFile()
        try {
            val header = ByteArray(20).apply {
                this[0] = 0x7f
                this[1] = 'E'.code.toByte()
                this[2] = 'L'.code.toByte()
                this[3] = 'F'.code.toByte()
                this[5] = 1
                this[18] = 62
                this[19] = 0
            }
            file.writeBytes(header)
            assertEquals(62, SpotiFlacNativeRuntime.readElfMachine(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsNonElfPayload() {
        val file = Files.createTempFile("harmony-not-elf", ".so").toFile()
        try {
            file.writeText("not an ELF library")
            assertNull(SpotiFlacNativeRuntime.readElfMachine(file))
        } finally {
            file.delete()
        }
    }
}
