package com.harmony.core.media.scanner

import com.harmony.core.media.model.MediaCandidate
import org.junit.Assert.*
import org.junit.Test

class ScanIdentityTest {
    private fun file(uri: String, path: String? = null) = MediaCandidate(uri, "Intro.flac", 1000, 1, "internal", physicalPath = path)

    @Test fun equalNamesAndSizesInDifferentAlbumsRemainDifferentFiles() {
        val files = listOf(file("one", "external_primary/One/Intro.flac"), file("two", "external_primary/Two/Intro.flac"))
        assertEquals(files, ScanIdentity.deduplicate(files, emptySet()))
    }
    @Test fun opaqueProvidersAreNotDeduplicatedByName() {
        val files = listOf(file("content://one/1"), file("content://two/1"))
        assertEquals(files, ScanIdentity.deduplicate(files, emptySet()))
    }
    @Test fun mediaStoreAndSafIdentityMatchOnlyForTheSamePhysicalPath() {
        val ms = ScanIdentity.mediaPath("external_primary", "Music/One/", "Intro.flac")
        val saf = ScanIdentity.documentPath("com.android.externalstorage.documents", "primary:Music/One/Intro.flac")
        assertEquals(ms, saf)
        assertEquals(listOf(file("ms", ms)), ScanIdentity.deduplicate(listOf(file("ms", ms), file("saf", saf)), emptySet()))
        assertNull(ScanIdentity.documentPath("another.provider", "primary:Music/One/Intro.flac"))
        assertNull(ScanIdentity.mediaPath("primary", null, "Intro.flac"))
    }
    @Test fun addingAProviderPreservesTheExistingUriAndItsPlaylistReferences() {
        val old = file("saf", "external_primary/Intro.flac")
        val fresh = file("ms", old.physicalPath)
        assertEquals(listOf(old), ScanIdentity.deduplicate(listOf(fresh, old), setOf(old.uri)))
    }
    @Test fun disconnectedStorageAndRevokedFoldersDoNotBecomeDeletions() {
        val mounted = "content://media/external_primary/audio/media/"
        val detached = "content://media/abcd-1234/audio/media/1"
        val folder = "content://documents/tree/one/document/song"
        assertEquals(listOf(mounted + "2"), ScanIdentity.missing(setOf(mounted + "1", mounted + "2", detached, folder),
            setOf(mounted + "1"), setOf(mounted)))
    }
    @Test fun removableVolumeIdsNormalizeButFileNamesKeepTheirCase() {
        assertEquals(ScanIdentity.mediaPath("ABCD-1234", "Music/", "Intro.flac"),
            ScanIdentity.documentPath("com.android.externalstorage.documents", "abcd-1234:Music/Intro.flac"))
        assertNotEquals(ScanIdentity.mediaPath("primary", "Music/", "Intro.flac"), ScanIdentity.mediaPath("primary", "Music/", "intro.flac"))
    }
}
