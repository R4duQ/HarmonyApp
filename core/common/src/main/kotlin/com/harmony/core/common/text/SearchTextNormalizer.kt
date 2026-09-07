package com.harmony.core.common.text

import java.text.Normalizer
import java.util.Locale

/**
 * Produces a stable comparison form for user-facing library search.
 *
 * NFD separates a base letter from its combining mark, so Romanian letters
 * such as ă, â, î, ș and ț compare like a, a, i, s and t. Locale.ROOT keeps
 * the result deterministic on every device and makes this utility reusable
 * by the planned desktop client as well as Android.
 */
object SearchTextNormalizer {
    private val combiningMarks = Regex("\\p{M}+")
    private val whitespace = Regex("\\s+")

    fun foldForSearch(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(combiningMarks, "")
            .lowercase(Locale.ROOT)
            .replace(whitespace, " ")
            .trim()
}
