package com.harmony.feature.downloads

/** Only the public capability fields from an installed extension's manifest. */
internal data class SpotiFlacQualityOption(val id: String, val kind: String = "")

internal object SpotiFlacProviderQuality {
    /**
     * Go 4.9.5 substitutes the manifest's FIRST option for an unknown token.
     * That can be Atmos, so a generic HI_RES token must never reach every provider.
     */
    fun select(
        providerId: String,
        format: SpotiFlacOutputFormat,
        advertised: List<SpotiFlacQualityOption>?,
    ): String? {
        val options = advertised?.takeIf { it.isNotEmpty() } ?: legacyOptions(providerId)
        val lossless = options.filter { option ->
            val knownLossless = option.id.uppercase() in setOf("LOSSLESS", "HI_RES", "HI_RES_LOSSLESS", "BEST")
            val safeKind = option.kind.isBlank() || option.kind.equals("lossless", ignoreCase = true)
            safeKind && (knownLossless || option.kind.equals("lossless", ignoreCase = true))
        }
        val preferences = when {
            format != SpotiFlacOutputFormat.FLAC_HI_RES_96 -> listOf("LOSSLESS", "HI_RES", "HI_RES_LOSSLESS", "BEST")
            providerId == "qobuz-web" -> listOf("HI_RES", "HI_RES_LOSSLESS", "BEST", "LOSSLESS")
            else -> listOf("HI_RES_LOSSLESS", "HI_RES", "BEST", "LOSSLESS")
        }
        return preferences.firstNotNullOfOrNull { preferred ->
            lossless.firstOrNull { it.id.equals(preferred, ignoreCase = true) }?.id
        } ?: lossless.firstOrNull()?.id
    }

    private fun legacyOptions(providerId: String): List<SpotiFlacQualityOption> = when (providerId) {
        "tidal-web" -> listOf("HI_RES_LOSSLESS", "LOSSLESS")
        "qobuz-web" -> listOf("HI_RES", "HI_RES_LOSSLESS", "LOSSLESS")
        "amazon" -> listOf("best")
        "deezer" -> listOf("LOSSLESS")
        else -> emptyList()
    }.map { SpotiFlacQualityOption(it, "lossless") }
}
