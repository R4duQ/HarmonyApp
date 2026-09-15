package com.harmony.feature.downloads

internal object SpotiFlacCompatibilityPolicy {
    fun canTryAnotherSource(errorType: String): Boolean = errorType in setOf(
        "missing_output", "not_lossless_flac", "lossless_finalization_failed", "flac_quality_limit_failed",
    )

    // Verification, cancellation, storage and network errors still need their
    // original UI action. Ordinary fallback misses must not mask the converter.
    fun preserveConversionFailure(laterErrorType: String?): Boolean = laterErrorType in setOf(
        "not_found", "provider_timeout", "rate_limit", "too_many_requests", "empty_response",
        "invalid_backend_response", "unsupported_lossless_quality", "missing_output", "not_lossless_flac",
    )
}
