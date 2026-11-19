package com.harmony.feature.downloads

/** The engines that can resolve a track from metadata alone (not the YouTube converter, which needs a link per song). */
val TransferCapableSources: List<DownloadSource> =
    listOf(DownloadSource.SPOTIFLAC, DownloadSource.SOULSEEK)
