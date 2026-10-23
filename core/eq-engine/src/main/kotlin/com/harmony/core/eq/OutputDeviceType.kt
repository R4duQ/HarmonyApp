package com.harmony.core.eq

/** Where the sound is playing, as the EQ engine distinguishes it. */
enum class OutputDeviceType(internal val engineCode: Int) {
    /** 90 Hz high-pass: keeps a phone speaker's tiny cone from rattling and saves headroom. */
    PHONE_SPEAKER(0),

    /** 18 Hz high-pass: the full sub-bass. */
    WIRED_HEADPHONES(1),

    /** 24 Hz high-pass and a 0.3 dB pad for the lossy codec's inter-sample peaks. */
    BLUETOOTH_A2DP(2),

    /** 10 Hz high-pass and a -0.05 dBFS ceiling: a clean converter gets everything. */
    USB_DAC_HIRES(3),
}
