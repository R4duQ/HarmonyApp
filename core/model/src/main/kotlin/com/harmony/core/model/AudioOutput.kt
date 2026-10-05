package com.harmony.core.model

/**
 * Where audio is currently going. [CAR] means Android Auto is connected to the
 * player; [COMPUTER] means it plays on a computer through Harmony Connect.
 */
enum class AudioOutputType { SPEAKER, WIRED, BLUETOOTH, USB, HDMI, OTHER, CAR, COMPUTER }

/**
 * The active audio output route.
 *
 * [name] is the device's own reported product name — for Bluetooth that's
 * the headphone/speaker name the user knows it by. It can be null or a
 * generic string on some devices/OEMs, so the UI must always be able to
 * fall back to the type label alone.
 */
data class AudioOutput(
    val type: AudioOutputType = AudioOutputType.SPEAKER,
    val name: String? = null,
    /** What kind of listening device it is, when that can be told; picks the icon. */
    val form: OutputForm? = null,
) {
    /** Human-readable label: prefers the real device name when we have one. */
    val label: String
        get() = when {
            !name.isNullOrBlank() && type == AudioOutputType.BLUETOOTH -> name
            !name.isNullOrBlank() && type == AudioOutputType.USB -> name
            // The car's Bluetooth name when there is one, so two cars can be told apart.
            !name.isNullOrBlank() && type == AudioOutputType.CAR -> "Android Auto · $name"
            !name.isNullOrBlank() && type == AudioOutputType.COMPUTER -> name
            else -> when (type) {
                AudioOutputType.SPEAKER -> "Phone speaker"
                AudioOutputType.WIRED -> "Headphones"
                AudioOutputType.BLUETOOTH -> "Bluetooth"
                AudioOutputType.USB -> "USB audio"
                AudioOutputType.HDMI -> "HDMI"
                AudioOutputType.OTHER -> "External audio"
                AudioOutputType.CAR -> "Android Auto"
                AudioOutputType.COMPUTER -> "Computer"
            }
        }
}

/** The shape of a listening device. Only drives the icon; playback never depends on it. */
enum class OutputForm(val label: String) {
    EARBUDS("Earbuds"),
    HEADPHONES("Headphones"),
    NECKBAND("Neckband"),
    EARPHONES("Wired earphones"),
    SPEAKER("Speaker"),
    CAR_STEREO("Car stereo"),
    HEARING_AID("Hearing aid"),
}

/**
 * Tells earbuds from headphones from speakers. Without a Bluetooth
 * permission Android only says "a Bluetooth device", so the kind comes from
 * Android's own device type when it says more, then from the name the device
 * advertises. Anything unrecognised stays null and shows the general
 * Bluetooth headphones icon; the listener can pick the kind for that device.
 */
object OutputForms {
    /**
     * Word patterns: each starts the name or follows a non-alphanumeric, and one
     * ending in a letter can't run into another letter, so "car" misses "Carbon".
     */
    private fun patterns(vararg p: String) = p.map {
        val end = if (it.last().isLetter() || it.last() == '?') "(?![a-z])" else ""
        Regex("(?i)(^|[^a-z0-9])(?:$it)$end")
    }

    // Checked in this order: "QC Earbuds" is earbuds, "Echo Buds" isn't a speaker.
    private val rules = listOf(
        OutputForm.HEADPHONES to patterns("airpods max", "elite \\d+h"),
        OutputForm.NECKBAND to patterns("neckband", "neck", "wi-", "bullets", "collar"),
        OutputForm.EARBUDS to patterns("\\w*buds?", "\\w*pods", "earbuds?", "tws", "wf-", "elite \\d+t", "elite active",
            "freebuds", "liberty", "life p\\d", "fit pro", "powerbeats", "nothing ear", "ear \\(", "in-?ear", "true wireless",
            "live (pro|free|flex|beam)", "tune \\d+tws", "motif", "wave buds"),
        OutputForm.SPEAKER to patterns("speaker", "flip", "charge", "boombox", "xtreme", "clip", "jbl go", "pulse", "soundlink",
            "\\w*boom", "sonos", "soundbar", "srs-", "echo", "nest", "homepod", "stanmore", "acton", "emberton", "partybox",
            "motion\\+?", "soundcore motion"),
        OutputForm.CAR_STEREO to patterns("car", "carkit", "vw", "volkswagen", "skoda", "seat", "audi", "mmi", "bmw", "mercedes",
            "toyota", "honda", "nissan", "mazda", "hyundai", "kia", "ford", "sync", "uconnect", "renault", "dacia", "peugeot",
            "citroen", "opel", "volvo", "tesla", "multimedia"),
        OutputForm.HEADPHONES to patterns("headphones?", "wh-", "quietcomfort", "qc ?(35|45|ultra)", "nc ?700", "studio ?\\d",
            "studio pro", "solo", "momentum", "space one", "life q", "live \\d{3}", "tune \\d{3}", "pxc", "major", "monitor",
            "over-?ear", "on-?ear", "hd ?\\d{3}", "ath-"),
    )

    /** [hint] is what Android's device type already says, if anything. */
    fun guess(type: AudioOutputType, hint: OutputForm?, name: String?): OutputForm? {
        // Android is sure about these; the name can't overrule it.
        if (hint == OutputForm.HEARING_AID || hint == OutputForm.SPEAKER) return hint
        if (type != AudioOutputType.BLUETOOTH && type != AudioOutputType.WIRED && type != AudioOutputType.USB) return hint
        val n = name?.trim().orEmpty()
        if (n.isEmpty()) return hint
        return rules.firstOrNull { (_, list) -> list.any { it.containsMatchIn(n) } }?.first ?: hint
    }

    /** Key under which the listener's own choice for a device is saved. */
    fun key(name: String): String = name.trim().lowercase()

    /** The listener's choice for this device wins over the guess. */
    fun effective(output: AudioOutput, chosen: Map<String, OutputForm>): OutputForm? =
        output.name?.takeIf { it.isNotBlank() }?.let { chosen[key(it)] } ?: output.form
}
