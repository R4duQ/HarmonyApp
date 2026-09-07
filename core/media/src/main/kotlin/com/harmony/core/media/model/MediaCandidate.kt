package com.harmony.core.media.model

/** A file discovered by a source, before any metadata extraction. */
data class MediaCandidate(
    val uri: String,
    val displayName: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val storageVolume: String,
    /**
     * The directory this file sits in, e.g. "Music/Compilations/80s Hits".
     * Used to group tracks into albums when their tags disagree — see
     * MetadataExtractor.albumId. Blank if the source can't determine it.
     *
     * This has to be carried explicitly: a MediaStore URI is
     * content://media/external/audio/media/<id>, which says nothing about
     * where the file actually lives.
     */
    val folder: String = "",
    /** Present only when the provider identifies the actual volume and full file path. */
    val physicalPath: String? = null,
)
