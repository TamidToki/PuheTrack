package com.puhetrack.app.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelRepositoryTest {
    @Test
    fun pinnedTokensManifestMatchesVerifiedHostFile() {
        assertEquals(93_939L, ModelRepository.expectedBytes("tokens.txt"))
        assertEquals(
            "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d",
            ModelRepository.expectedSha256("tokens.txt")
        )
    }

    @Test
    fun completePartialIsVerifiedLocallyInsteadOfRequestingEmptyRange() {
        assertEquals(
            PartialDownloadAction.VERIFY_COMPLETE,
            partialDownloadAction(localBytes = 652_184_281L, expectedBytes = 652_184_281L)
        )
    }

    @Test
    fun validPartialResumesAndOversizedPartialRestarts() {
        assertEquals(PartialDownloadAction.RESUME, partialDownloadAction(123L, 1_000L))
        assertEquals(PartialDownloadAction.DISCARD_AND_RESTART, partialDownloadAction(1_001L, 1_000L))
        assertEquals(PartialDownloadAction.START_FRESH, partialDownloadAction(0L, 1_000L))
    }

    @Test
    fun contentRangeMustStartAtRequestedOffset() {
        assertEquals(123L, contentRangeStart("bytes 123-999/1000"))
        assertEquals(123L, contentRangeStart("Bytes 123-999/*"))
        assertNull(contentRangeStart("bytes */1000"))
        assertNull(contentRangeStart("not-a-range"))
    }
}
