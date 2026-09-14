package com.harmony.feature.downloads

import android.content.Context
import android.os.Build
import java.io.File
import java.io.RandomAccessFile

/**
 * Guards the GoMobile boundary before any generated Gobackend class is used.
 *
 * Some Android emulators can install an ARM64-only APK through native
 * translation. Most of Harmony continues to work because it is Kotlin, but
 * loading the Go runtime from Downloads can abort the process. Comparing the
 * packaged ELF with the emulator's primary ABI avoids entering that unsafe
 * boundary and produces a normal, actionable UI error instead.
 */
internal object SpotiFlacNativeRuntime {
    private const val ELF_HEADER_BYTES = 20
    private const val ELF_DATA_LITTLE_ENDIAN = 1
    private const val ELF_DATA_BIG_ENDIAN = 2

    private const val EM_386 = 3
    private const val EM_ARM = 40
    private const val EM_X86_64 = 62
    private const val EM_AARCH64 = 183

    fun requireCompatible(context: Context) {
        val deviceAbis = Build.SUPPORTED_ABIS.toList()
        val primaryAbi = deviceAbis.firstOrNull().orEmpty()
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val goLibrary = File(nativeDir, System.mapLibraryName("gojni"))

        if (!goLibrary.isFile) {
            throw incompatibleRuntime(
                userMessage = "SpotiFLAC is unavailable because this Harmony APK does not contain a native backend for $primaryAbi.",
                deviceAbis = deviceAbis,
                primaryAbi = primaryAbi,
                nativeDir = nativeDir,
                packagedMachine = null,
                hint = apkHint(primaryAbi),
            )
        }

        val packagedMachine = readElfMachine(goLibrary)
            ?: throw incompatibleRuntime(
                userMessage = "SpotiFLAC is unavailable because its native backend could not be validated safely.",
                deviceAbis = deviceAbis,
                primaryAbi = primaryAbi,
                nativeDir = nativeDir,
                packagedMachine = null,
                hint = apkHint(primaryAbi),
            )
        val expectedMachine = elfMachineForAbi(primaryAbi)

        if (expectedMachine == null) {
            throw incompatibleRuntime(
                userMessage = "SpotiFLAC is unavailable on the unsupported Android ABI ${primaryAbi.ifBlank { "unknown" }}.",
                deviceAbis = deviceAbis,
                primaryAbi = primaryAbi,
                nativeDir = nativeDir,
                packagedMachine = packagedMachine,
                hint = apkHint(primaryAbi),
            )
        }

        if (packagedMachine != expectedMachine) {
            throw incompatibleRuntime(
                userMessage = "This Harmony APK contains a ${elfMachineLabel(packagedMachine)} SpotiFLAC backend, but Android runs $primaryAbi. Soulseek remains available in this build.",
                deviceAbis = deviceAbis,
                primaryAbi = primaryAbi,
                nativeDir = nativeDir,
                packagedMachine = packagedMachine,
                hint = apkHint(primaryAbi),
            )
        }
    }

    internal fun elfMachineForAbi(abi: String): Int? = when (abi) {
        "x86" -> EM_386
        "armeabi-v7a" -> EM_ARM
        "x86_64" -> EM_X86_64
        "arm64-v8a" -> EM_AARCH64
        else -> null
    }

    internal fun readElfMachine(file: File): Int? = runCatching {
        val header = ByteArray(ELF_HEADER_BYTES)
        RandomAccessFile(file, "r").use { input ->
            if (input.length() < header.size) return@runCatching null
            input.readFully(header)
        }
        if (
            header[0] != 0x7f.toByte() ||
            header[1] != 'E'.code.toByte() ||
            header[2] != 'L'.code.toByte() ||
            header[3] != 'F'.code.toByte()
        ) {
            return@runCatching null
        }

        val low = header[18].toInt() and 0xff
        val high = header[19].toInt() and 0xff
        when (header[5].toInt() and 0xff) {
            ELF_DATA_LITTLE_ENDIAN -> low or (high shl 8)
            ELF_DATA_BIG_ENDIAN -> (low shl 8) or high
            else -> null
        }
    }.getOrNull()

    private fun apkHint(primaryAbi: String): String = when (primaryAbi) {
        "x86_64" -> "Install the Harmony x86_64 emulator APK."
        "arm64-v8a" -> "Install the Harmony arm64-v8a phone APK."
        else -> "Use a 64-bit ARM device or a 64-bit x86 Android emulator."
    }

    private fun elfMachineLabel(machine: Int): String = when (machine) {
        EM_386 -> "x86"
        EM_ARM -> "armeabi-v7a"
        EM_X86_64 -> "x86_64"
        EM_AARCH64 -> "arm64-v8a"
        else -> "machine-$machine"
    }

    private fun incompatibleRuntime(
        userMessage: String,
        deviceAbis: List<String>,
        primaryAbi: String,
        nativeDir: File,
        packagedMachine: Int?,
        hint: String,
    ): SpotiFlacException = SpotiFlacException(
        message = "$userMessage $hint",
        errorType = "unsupported_native_abi",
        technicalDetails = buildString {
            appendLine("device_abis=${deviceAbis.joinToString()}")
            appendLine("primary_abi=${primaryAbi.ifBlank { "unknown" }}")
            appendLine("expected_elf_machine=${elfMachineForAbi(primaryAbi) ?: "unknown"}")
            appendLine("packaged_elf_machine=${packagedMachine ?: "missing_or_invalid"}")
            append("native_library_dir=${nativeDir.absolutePath}")
        },
    )
}
