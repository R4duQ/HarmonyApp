package com.harmony.feature.downloads

import org.junit.Assert.*
import org.junit.Test

class SpotiFlacCompatibilityPolicyTest {
    @Test fun bothConversionStagesCanTryAnotherLosslessSource() {
        assertTrue(SpotiFlacCompatibilityPolicy.canTryAnotherSource("lossless_finalization_failed"))
        assertTrue(SpotiFlacCompatibilityPolicy.canTryAnotherSource("flac_quality_limit_failed"))
    }
    @Test fun missingOrUnverifiedOutputCanStillRotate() {
        assertTrue(SpotiFlacCompatibilityPolicy.canTryAnotherSource("missing_output"))
        assertTrue(SpotiFlacCompatibilityPolicy.canTryAnotherSource("not_lossless_flac"))
    }
    @Test fun actionableFailuresAreNeverTreatedAsOutputConversionErrors() {
        for (error in listOf("cancelled", "storage", "permission", "network_unavailable", "verification_required", "protected_stream_decryption_required")) {
            assertFalse(error, SpotiFlacCompatibilityPolicy.canTryAnotherSource(error))
            assertFalse(error, SpotiFlacCompatibilityPolicy.preserveConversionFailure(error))
        }
    }
    @Test fun laterProviderMissesDoNotHideOriginalConverterFailure() {
        for (error in listOf("not_found", "provider_timeout", "rate_limit", "too_many_requests", "missing_output", "not_lossless_flac")) {
            assertTrue(error, SpotiFlacCompatibilityPolicy.preserveConversionFailure(error))
        }
    }
    @Test fun unknownFailuresKeepTheirOwnDiagnosis() {
        assertFalse(SpotiFlacCompatibilityPolicy.preserveConversionFailure(null))
        assertFalse(SpotiFlacCompatibilityPolicy.preserveConversionFailure("native_bridge"))
    }
}
