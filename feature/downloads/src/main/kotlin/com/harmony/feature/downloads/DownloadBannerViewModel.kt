package com.harmony.feature.downloads

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** The download mirror ([DownloadStatusCenter]) for the shell: the Activity pill and sheet read it. */
@HiltViewModel
class DownloadBannerViewModel @Inject constructor(
    private val statusCenter: DownloadStatusCenter,
) : ViewModel() {
    val active = statusCenter.active
    val outcome = statusCenter.outcome
    fun consumeOutcome(id: Long) = statusCenter.consumeOutcome(id)
}
