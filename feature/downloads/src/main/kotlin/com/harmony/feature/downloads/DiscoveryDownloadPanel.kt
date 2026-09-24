package com.harmony.feature.downloads

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.*
import com.harmony.domain.library.repository.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

/** The provider check before a batch starts. Always ends in Ready, an action for the user, or an error. */
sealed interface ProviderCheck {
    data object Idle : ProviderCheck
    data class Checking(val label: String) : ProviderCheck
    data object Ready : ProviderCheck
    /** Something only the user can do: sign in, verify in the browser. */
    data class ActionNeeded(val message: String, val verificationUrl: String? = null, val openDownloads: Boolean = false) : ProviderCheck
    data class Failed(val message: String) : ProviderCheck
}

@HiltViewModel
class DiscoveryDownloadViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val repository: SongDiscoveryRepository,
    private val soulseek: SoulseekClient,
    private val spoti: SpotiFlacDownloadEngine,
) : ViewModel() {
    private val work = WorkManager.getInstance(context)
    val jobs = work.getWorkInfosForUniqueWorkFlow(DiscoveryDownloadWorker.WORK).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val providerCheck = MutableStateFlow<ProviderCheck>(ProviderCheck.Idle)
    val soulseekState = soulseek.connectionState
    val transfer = DiscoveryTransferProgress.state

    init {
        viewModelScope.launch { spoti.verificationEvents.collect { result ->
            if (spoti.pendingVerificationOwner() == SpotiFlacRequestOwner.DISCOVERY_DOWNLOAD) {
                providerCheck.value = if (result.accepted) ProviderCheck.Ready else ProviderCheck.ActionNeeded(result.message)
                message.value = if (result.accepted) "Provider verified. Tap Download to continue." else null
            }
        } }
    }

    /** Checks the chosen provider without starting anything. */
    fun preflight(id: String, source: DownloadSource) = action { runPreflight(id, source) }

    fun start(id: String, source: DownloadSource, format: SpotiFlacOutputFormat, wifi: Boolean) = action {
        check(withContext(Dispatchers.IO) { work.getWorkInfosForUniqueWork(DiscoveryDownloadWorker.WORK).get() }.none { !it.state.isFinished }) {
            "Another Discover download is running. Pause it or wait for it to finish."
        }
        val batch = repository.state.value.batches.first { it.id == id }
        val needsNetwork = batch.songs.any { it.localUri == null && batch.uris[it.key] == null }
        if (needsNetwork && runPreflight(id, source) !is ProviderCheck.Ready) return@action
        val next = batch.copy(source = source.name, format = AlbumDownloadPolicy.formatFor(source, format).name,
            status = "Queued · waiting for network and storage", locked = true)
        repository.update { it.copy(batches = it.batches.map { b -> if (b.id == id) next else b }) }
        val request = DiscoveryDownloadWorker.request(next, wifi)
        withContext(Dispatchers.IO) {
            work.enqueueUniqueWork(DiscoveryDownloadWorker.WORK, ExistingWorkPolicy.KEEP, request).result.get()
            check(work.getWorkInfosForUniqueWork(DiscoveryDownloadWorker.WORK).get().any { it.id == request.id }) { "Another download was queued first. Try again in a moment." }
        }
        message.value = "Queued. Finished files are kept if you pause."
    }

    fun pause() { work.cancelUniqueWork(DiscoveryDownloadWorker.WORK) }

    private suspend fun runPreflight(id: String, source: DownloadSource): ProviderCheck {
        val result = try {
            when (source) {
                DownloadSource.SOULSEEK -> {
                    providerCheck.value = ProviderCheck.Checking("Checking the Soulseek session…")
                    // A login in progress gets a moment to finish; a dead one is reported, not waited on forever.
                    val state = withTimeoutOrNull(10_000) { soulseek.connectionState.first { it.status != SoulseekConnectionStatus.CONNECTING } }
                        ?: soulseek.connectionState.value
                    when (state.status) {
                        SoulseekConnectionStatus.CONNECTED -> if (state.username.isNotBlank()) ProviderCheck.Ready
                            else ProviderCheck.ActionNeeded("Soulseek is connected without an account. Sign in from Downloads.", openDownloads = true)
                        SoulseekConnectionStatus.CONNECTING -> ProviderCheck.Failed("Soulseek is still connecting after 10 seconds. Try again.")
                        SoulseekConnectionStatus.ERROR -> ProviderCheck.ActionNeeded("Soulseek sign-in failed: ${state.message}. Check it in Downloads.", openDownloads = true)
                        SoulseekConnectionStatus.DISCONNECTED -> ProviderCheck.ActionNeeded("Soulseek isn't connected. Sign in from Downloads, then come back.", openDownloads = true)
                    }
                }
                else -> {
                    providerCheck.value = ProviderCheck.Checking("Checking verification…")
                    val batch = repository.state.value.batches.first { it.id == id }
                    val song = batch.songs.firstOrNull { it.key !in batch.uris }
                    if (song != null) spoti.rememberPendingVerificationTrackFor(identifiedDiscoverySong(song), SpotiFlacRequestOwner.DISCOVERY_DOWNLOAD)
                    val challenge = withTimeout(20_000) { spoti.getVerificationChallenge(batch.verificationProvider ?: "tidal-web") }
                    when {
                        challenge.authenticated || !challenge.pending -> ProviderCheck.Ready
                        challenge.verificationUrl != null -> ProviderCheck.ActionNeeded(
                            challenge.instructions ?: "The provider needs you to verify in your browser. Come back here afterwards.", challenge.verificationUrl)
                        else -> ProviderCheck.Failed("The provider asked for verification but gave no link. Try again later.")
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            ProviderCheck.Failed("Checking verification took too long. Check your connection and try again.")
        } catch (e: CancellationException) {
            providerCheck.value = ProviderCheck.Idle; throw e
        } catch (e: SpotiFlacException) {
            if (e.errorType.equals("verification_required", true) || e.verificationUrl != null)
                ProviderCheck.ActionNeeded("Your provider session expired. Verify again in your browser.", e.verificationUrl ?: e.verificationChallenge?.verificationUrl)
            else ProviderCheck.Failed(e.message ?: "The provider isn't available right now. Try again later.")
        } catch (e: Exception) {
            ProviderCheck.Failed(e.message ?: "The provider check failed. Try again.")
        }
        providerCheck.value = result
        return result
    }

    private fun action(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try { block() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message.value = e.message ?: "Couldn't start the download." }
            finally { busy.value = false }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiscoveryDownloadPanel(batch: DiscoveryBatch, onOpenDownloads: () -> Unit, viewModel: DiscoveryDownloadViewModel = hiltViewModel()) {
    val jobs by viewModel.jobs.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val check by viewModel.providerCheck.collectAsStateWithLifecycle()
    val transfer by viewModel.transfer.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var sourceName by rememberSaveable(batch.id) { mutableStateOf(batch.source) }
    var formatName by rememberSaveable(batch.id) { mutableStateOf(batch.format) }
    var wifi by rememberSaveable(batch.id) { mutableStateOf(false) }
    var browserError by remember { mutableStateOf<String?>(null) }
    val active = jobs.firstOrNull { !it.state.isFinished }
    val thisActive = active?.tags?.contains(batch.id) == true
    val source = DownloadSource.valueOf(sourceName)
    val current = transfer?.takeIf { it.batchId == batch.id && thisActive }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (current != null) {
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("discover:transfer")) {
                Text("Downloading “${current.title}”" + (current.fraction?.let { " · ${(it * 100).toInt()}%" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium)
                if (current.fraction != null) LinearProgressIndicator(progress = { current.fraction ?: 0f }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        Text(batch.status, style = MaterialTheme.typography.bodySmall)
        Text("Songs already in your library are reused; only missing ones are downloaded.", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(DownloadSource.SPOTIFLAC, DownloadSource.SOULSEEK).forEach { option ->
                FilterChip(source == option, onClick = {
                    sourceName = option.name
                    formatName = AlbumDownloadPolicy.formatFor(option, SpotiFlacOutputFormat.fromName(formatName)).name
                    viewModel.providerCheck.value = ProviderCheck.Idle
                }, enabled = active == null && !busy, label = { Text(if (option == DownloadSource.SPOTIFLAC) "SpotiFLAC" else "Soulseek") })
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SpotiFlacOutputFormat.entries.filter { source != DownloadSource.SOULSEEK || it != SpotiFlacOutputFormat.FLAC_HI_RES_96 }.forEach { format ->
                FilterChip(format.name == formatName, onClick = { formatName = format.name }, enabled = active == null && !busy,
                    label = { Text(if (source == DownloadSource.SOULSEEK) { if (format.extension == "mp3") "MP3 · native" else "FLAC · native" } else format.label) })
            }
        }
        Row { Checkbox(wifi, { wifi = it }, enabled = active == null && !busy); Text("Unmetered network only", Modifier.padding(top = 12.dp)) }
        // The provider check, with a clear end state.
        when (val c = check) {
            is ProviderCheck.Checking -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text(c.label, style = MaterialTheme.typography.bodySmall)
            }
            ProviderCheck.Ready -> Text("Provider ready.", style = MaterialTheme.typography.bodySmall)
            is ProviderCheck.ActionNeeded -> Column {
                Text(c.message, style = MaterialTheme.typography.bodySmall)
                c.verificationUrl?.let { url -> TextButton(onClick = {
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    catch (_: Exception) { browserError = "No browser available to open verification." }
                }) { Text("Verify in browser") } }
                if (c.openDownloads) TextButton(onClick = onOpenDownloads) { Text("Open Downloads") }
            }
            is ProviderCheck.Failed -> Row {
                Text(c.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                TextButton(onClick = { viewModel.preflight(batch.id, source) }, enabled = !busy) { Text("Retry") }
            }
            ProviderCheck.Idle -> Unit
        }
        if (thisActive) Button(onClick = viewModel::pause, modifier = Modifier.fillMaxWidth()) { Text("Pause · keep downloaded files") }
        else Button(onClick = { viewModel.start(batch.id, source, SpotiFlacOutputFormat.fromName(formatName), wifi) },
            enabled = !busy && active == null && check !is ProviderCheck.Checking, modifier = Modifier.fillMaxWidth()) {
            Text(if (batch.errors.isNotEmpty() || batch.uris.isNotEmpty()) "Retry missing songs" else "Download missing songs")
        }
        if (active != null && !thisActive) Text("Another Discover download is running. Open it from Earlier selections to pause it.",
            style = MaterialTheme.typography.bodySmall)
        if (source == DownloadSource.SPOTIFLAC && check == ProviderCheck.Idle && active == null)
            TextButton(onClick = { viewModel.preflight(batch.id, source) }, enabled = !busy) { Text("Check provider first") }
        Text("Quality depends on the recording and the provider. Max 24/96 doesn't upscale lower-resolution audio.", style = MaterialTheme.typography.bodySmall)
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        browserError?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
