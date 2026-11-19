package com.harmony.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import com.harmony.core.datastore.SettingsRepository
import com.harmony.core.datastore.ThemeMode
import com.harmony.core.datastore.UserSettings
import com.harmony.core.model.ReplayGainMode
import com.harmony.core.ui.component.EditorialCard
import com.harmony.core.ui.component.EditorialCircleButton
import com.harmony.core.ui.component.EditorialChoiceChips
import com.harmony.core.ui.component.EditorialSectionGap
import com.harmony.core.ui.component.EditorialSectionLabel
import com.harmony.core.ui.component.EditorialSettingRow
import com.harmony.core.ui.component.EditorialSlider
import com.harmony.core.ui.component.EditorialSwitch
import com.harmony.core.ui.component.EditorialTextAction
import com.harmony.core.ui.component.stonePalette
import com.harmony.core.ui.component.LocalFloatingChromeHeight
import com.harmony.domain.library.model.ScanEvent
import com.harmony.domain.library.usecase.ScanLibraryUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val scanLibrary: ScanLibraryUseCase,
) : ViewModel() {

    val state: StateFlow<UserSettings> = settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())

    /** null when idle; otherwise a human-readable progress line. */
    private val _rescan = MutableStateFlow<String?>(null)
    val rescan: StateFlow<String?> = _rescan.asStateFlow()

    /**
     * Re-reads every file, ignoring the usual size/timestamp check.
     *
     * Needed whenever the app changes how it INTERPRETS tags — album
     * grouping, for instance — since the incremental scan compares file
     * stats and a re-interpretation doesn't touch the file. Rows update in
     * place, so playlists and favourites are preserved.
     */
    fun rescanLibrary() {
        if (_rescan.value != null) return
        _rescan.value = "Starting…"
        viewModelScope.launch {
            try {
                var read = 0
                try {
                    scanLibrary(force = true).collect { event ->
                        _rescan.value = when (event) {
                            is ScanEvent.Started -> "Found ${event.totalCandidates} files"
                            is ScanEvent.TrackScanned -> "Reading ${++read} of ${event.total}"
                            is ScanEvent.Completed ->
                                "Done — ${event.added} added, ${event.updated} updated"
                            else -> _rescan.value
                        }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    _rescan.value = "Scan failed. Check folder access and try again."
                }
                kotlinx.coroutines.delay(2_500)
            } finally {
                _rescan.value = null
            }
        }
    }

    fun setThemeMode(mode: ThemeMode) = launch { settings.setThemeMode(mode) }
    fun setAmoled(on: Boolean) = launch { settings.setAmoledDark(on) }
    fun setDynamicColors(on: Boolean) = launch { settings.setDynamicColors(on) }
    fun setChargingOnly(on: Boolean) = launch { settings.setChargingOnlyAnalysis(on) }
    fun setExperimentalParallelDownloads(on: Boolean) = launch { settings.setExperimentalParallelDownloads(on) }
    fun setOnlineMixEnabled(on: Boolean) = launch { settings.setOnlineMixEnabled(on) }
    fun setOnlineMixOnMobileData(on: Boolean) = launch { settings.setOnlineMixOnMobileData(on) }
    fun setOnlineMixAutoKeep(on: Boolean) = launch { settings.setOnlineMixAutoKeep(on) }

    fun setOnlineMixSource(source: String) = launch { settings.setOnlineMixSource(source) }
    fun setKeepPlayingAfterSwipe(on: Boolean) = launch { settings.setKeepPlayingAfterSwipe(on) }
    fun setCrossfade(seconds: Int) = launch { settings.setCrossfadeSeconds(seconds) }
    fun setReplayGain(mode: ReplayGainMode) = launch { settings.setReplayGainMode(mode) }
    fun setSmartFamiliarity(value: Float) = launch { settings.setSmartShuffleFamiliarity(value) }
    fun setSmartDiscovery(value: Float) = launch { settings.setSmartShuffleDiscovery(value) }
    fun setSmartVariety(value: Float) = launch { settings.setSmartShuffleVariety(value) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}

/**
 * Settings in the editorial treatment, on the one unsaturated field in the
 * set — see [stonePalette] for why.
 *
 * Every setting is now a card rather than a Material ListItem on a divider-
 * separated surface, and the segmented buttons became chip rows, which wrap
 * instead of squeezing three labels into a third of the screen each.
 *
 * Nothing about what the settings DO changed: the same DataStore writes, the
 * same SAF folder grant/release (persisting the permission here is what makes
 * SafTreeSource pick the tree up on the next scan), and the same non-
 * observable persistedUriPermissions refresh trick.
 */
@Composable
fun SettingsScreen(
    onOpenFlacCheck: () -> Unit = {},
    onOpenTagEditor: () -> Unit = {},
    /** Equalizer moved out of the bottom bar; this is one of its two entry points. */
    onOpenEqualizer: () -> Unit = {},
    /**
     * Supplied once Settings stopped being a bottom-navigation destination and
     * became the Home header's gear. Null keeps the old chrome-less header for
     * any caller that still shows it as a tab.
     */
    onBack: (() -> Unit)? = null,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val palette = com.harmony.core.ui.component.catalogPalette()

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: SecurityException) {
                android.widget.Toast.makeText(context, "This folder did not grant lasting read access. Choose another folder.", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    val rescanStatus by viewModel.rescan.collectAsStateWithLifecycle()
    var showDiagnostics by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }
    // Granted folders, with removal; refreshKey re-reads them after a grant or revoke.
    val grantedTrees = remember(refreshKey) {
        context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri }
    }
    val r = com.harmony.core.ui.theme.Room
    val T = com.harmony.core.ui.theme.RoomType

    Column(
        Modifier
            .fillMaxSize()
            .background(r.ground)
            .verticalScroll(rememberScrollState())
            .padding(bottom = LocalFloatingChromeHeight.current + 24.dp),
    ) {
        com.harmony.core.ui.room.RoomTopBar(stringResource(R.string.settings_title), onBack = onBack)

        SettingsSection(stringResource(R.string.settings_look)) {
            SettingChoice(
                stringResource(R.string.settings_theme), null,
                listOf(stringResource(R.string.settings_theme_system), stringResource(R.string.settings_theme_light), stringResource(R.string.settings_theme_dark)),
                ThemeMode.entries.indexOf(state.themeMode),
            ) { viewModel.setThemeMode(ThemeMode.entries[it]) }
            SettingSwitch(stringResource(R.string.settings_black), stringResource(R.string.settings_black_body), state.amoledDark, viewModel::setAmoled)
            SettingSwitch(stringResource(R.string.settings_wallpaper), stringResource(R.string.settings_wallpaper_body), state.dynamicColors, viewModel::setDynamicColors)
        }

        SettingsSection(stringResource(R.string.settings_playback)) {
            SettingSwitch(
                stringResource(R.string.settings_crossfade),
                if (state.crossfadeSeconds > 0) stringResource(R.string.settings_crossfade_on, state.crossfadeSeconds) else stringResource(R.string.settings_crossfade_off),
                state.crossfadeSeconds > 0,
            ) { on -> viewModel.setCrossfade(if (on) 5 else 0) }
            if (state.crossfadeSeconds > 0) {
                SettingSlider(
                    stringResource(R.string.settings_crossfade_length), "1 s", "12 s",
                    state.crossfadeSeconds.toFloat(), { viewModel.setCrossfade(it.toInt().coerceAtLeast(1)) },
                    valueRange = 1f..12f, steps = 10, spoken = "${state.crossfadeSeconds} s",
                )
            }
            SettingChoice(
                stringResource(R.string.settings_levelling), stringResource(R.string.settings_levelling_body),
                listOf(stringResource(R.string.settings_off), stringResource(R.string.settings_per_song), stringResource(R.string.settings_per_album)),
                ReplayGainMode.entries.indexOf(state.replayGainMode),
            ) { viewModel.setReplayGain(ReplayGainMode.entries[it]) }
            SettingSwitch(
                stringResource(R.string.settings_keep_playing),
                stringResource(if (state.keepPlayingAfterSwipe) R.string.settings_keep_playing_on else R.string.settings_keep_playing_off),
                state.keepPlayingAfterSwipe, viewModel::setKeepPlayingAfterSwipe,
            )
            SettingLink(stringResource(R.string.settings_sound), stringResource(R.string.settings_sound_body), onOpenEqualizer)
        }

        SettingsSection(stringResource(R.string.settings_mixes)) {
            SettingSlider(stringResource(R.string.settings_familiar), stringResource(R.string.settings_familiar_low), stringResource(R.string.settings_familiar_high),
                state.smartShuffleFamiliarity, viewModel::setSmartFamiliarity)
            SettingSlider(stringResource(R.string.settings_discovery), stringResource(R.string.settings_discovery_low), stringResource(R.string.settings_discovery_high),
                state.smartShuffleDiscovery, viewModel::setSmartDiscovery)
            SettingSlider(stringResource(R.string.settings_variety), stringResource(R.string.settings_variety_low), stringResource(R.string.settings_variety_high),
                state.smartShuffleVariety, viewModel::setSmartVariety)
            SettingSwitch(stringResource(R.string.settings_online), stringResource(R.string.settings_online_body), state.onlineMixEnabled, viewModel::setOnlineMixEnabled)
            if (state.onlineMixEnabled) {
                val sources = listOf("OFFLINE", "COMBINED", "ONLINE")
                SettingChoice(
                    stringResource(R.string.settings_mix_source),
                    stringResource(when (state.onlineMixSource) {
                        "ONLINE" -> R.string.settings_mix_online_body
                        "OFFLINE" -> R.string.settings_mix_library_body
                        else -> R.string.settings_mix_combined_body
                    }),
                    listOf(stringResource(R.string.settings_mix_library), stringResource(R.string.settings_mix_combined), stringResource(R.string.settings_mix_online)),
                    sources.indexOf(state.onlineMixSource).takeIf { it >= 0 } ?: 1,
                ) { viewModel.setOnlineMixSource(sources[it]) }
                SettingSwitch(stringResource(R.string.settings_mobile_data), stringResource(R.string.settings_mobile_data_body),
                    state.onlineMixOnMobileData, viewModel::setOnlineMixOnMobileData)
                SettingSwitch(stringResource(R.string.settings_auto_keep), stringResource(R.string.settings_auto_keep_body),
                    state.onlineMixAutoKeep, viewModel::setOnlineMixAutoKeep)
            }
        }

        SettingsSection(stringResource(R.string.settings_library)) {
            SettingLink(stringResource(R.string.settings_add_folder), stringResource(R.string.settings_add_folder_body), { folderPicker.launch(null) })
            grantedTrees.forEach { treeUri ->
                SettingLink(treeUri.lastPathSegment ?: treeUri.toString(), stringResource(R.string.settings_included_folder), {}) {
                    com.harmony.core.ui.room.RoomButton(stringResource(R.string.settings_remove), {
                        context.contentResolver.releasePersistableUriPermission(treeUri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        refreshKey++
                    }, kind = com.harmony.core.ui.room.RoomButtonKind.Text)
                }
            }
            SettingLink(
                stringResource(R.string.settings_rescan), rescanStatus ?: stringResource(R.string.settings_rescan_body), viewModel::rescanLibrary,
            ) {
                if (rescanStatus != null) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = r.amberText)
            }
            SettingSwitch(stringResource(R.string.settings_charging), stringResource(R.string.settings_charging_body),
                state.chargingOnlyAnalysis, viewModel::setChargingOnly)
        }

        SettingsSection(stringResource(R.string.settings_adding)) {
            SettingSwitch(stringResource(R.string.settings_parallel), stringResource(R.string.settings_parallel_body),
                state.experimentalParallelDownloads, viewModel::setExperimentalParallelDownloads)
            Text(stringResource(R.string.settings_sources_note), style = T.bodySmall, color = r.muted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }

        SettingsSection(stringResource(R.string.settings_tools)) {
            SettingLink(stringResource(R.string.settings_edit_tags), stringResource(R.string.settings_edit_tags_body), onOpenTagEditor)
            SettingLink(stringResource(R.string.settings_check_file), stringResource(R.string.settings_check_file_body), onOpenFlacCheck)
            SettingLink(stringResource(R.string.settings_diagnostics), stringResource(R.string.settings_diagnostics_body), { showDiagnostics = true })
        }

        SettingsSection(stringResource(R.string.settings_privacy)) {
            Text(
                "Harmony has no Harmony cloud account, analytics, or ads. Local playback " +
                    "and audio analysis stay on this device. Online features such as Downloads " +
                    "and Peer Search communicate with third-party services or peers only when " +
                    "you choose to use them.\n\nOnline mixes, when on: if a song's tags aren't enough, " +
                    "a sound fingerprint of 12 seconds of it (never the audio) is sent to Shazam to name it, " +
                    "and its name is searched for on Deezer and YouTube Music; phones with Gemini Nano also " +
                    "guess from the name with their own AI.",
                style = T.bodySmall, color = r.muted, modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        SettingsSection(stringResource(R.string.settings_about)) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val versionName = remember(context) {
                    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "1.0"
                }
                Text("Harmony", style = T.nameL, color = r.ink)
                Text(stringResource(R.string.settings_version, versionName), style = T.bodySmall, color = r.muted)
                Text("Owner / Administrator: r4duq", style = T.title, color = r.ink)
                Text("© 2026 r4duq. All rights reserved.", style = T.title, color = r.ink)
                Text(
                    "Harmony's original source code, user interface, branding and original " +
                        "assets are copyright r4duq. Unauthorized copying, redistribution or " +
                        "representation of Harmony's proprietary components as another person's " +
                        "work is prohibited except where permitted by applicable law or an " +
                        "applicable third-party license.",
                    style = T.bodySmall, color = r.muted,
                )
                Text(
                    "Harmony does not grant rights to music or other third-party content. " +
                        "Online download and peer-to-peer features should only be used for " +
                        "content you are permitted to access or download.",
                    style = T.bodySmall, color = r.muted,
                )
                Text("Third-party software", style = T.nameM, color = r.ink, modifier = Modifier.padding(top = 8.dp))
                Text(
                    "Harmony includes open-source components. Copyright and license rights " +
                        "for these components remain with their respective authors and projects.",
                    style = T.bodySmall, color = r.muted,
                )
                ThirdPartyLine("AndroidX / Jetpack Compose / Media3 / Room / WorkManager / DataStore", "Apache License 2.0", palette)
                ThirdPartyLine("Kotlin / kotlinx.coroutines", "Apache License 2.0", palette)
                ThirdPartyLine("Dagger / Hilt", "Apache License 2.0", palette)
                ThirdPartyLine("Coil", "Apache License 2.0", palette)
                ThirdPartyLine("Figtree, Bricolage Grotesque", "SIL Open Font License 1.1", palette)
                ThirdPartyLine("youtubedl-android 0.18.1", "GNU GPL v3", palette)
                ThirdPartyLine("yt-dlp", "The Unlicense / public-domain dedication", palette)
                ThirdPartyLine("FFmpeg", "LGPL v2.1+; optional GPL components may apply to a particular build", palette)
                Text("Third-party services", style = T.nameM, color = r.ink, modifier = Modifier.padding(top = 8.dp))
                Text(
                    "Soulseek, YouTube, Google, SHFL and other third-party service names, trademarks " +
                        "and content belong to their respective owners. Harmony and r4duq are not " +
                        "affiliated with, sponsored by, or endorsed by those services or by the " +
                        "open-source projects listed above.",
                    style = T.bodySmall, color = r.muted,
                )
            }
        }
    }
    if (showDiagnostics) DiagnosticsLogDialog(onDismiss = { showDiagnostics = false })
}

@Composable
private fun ThirdPartyLine(
    name: String,
    license: String,
    palette: com.harmony.core.ui.component.EditorialPalette,
) {
    Column(Modifier.padding(top = 12.dp)) {
        Text(
            name,
            style = com.harmony.core.ui.theme.RoomType.title,
            color = com.harmony.core.ui.theme.Room.ink,
        )
        Text(
            license,
            style = com.harmony.core.ui.theme.RoomType.bodySmall,
            color = com.harmony.core.ui.theme.Room.muted,
            modifier = Modifier.padding(top = 1.dp),
        )
    }
}

private fun ThemeMode.label(): String = when (this) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}

private fun ReplayGainMode.label(): String = when (this) {
    ReplayGainMode.OFF -> "Off"
    ReplayGainMode.TRACK -> "Track"
    ReplayGainMode.ALBUM -> "Album"
}

/**
 * The diagnostics log (crashes with their stack, errors the app caught and
 * went on from, the online mix's steps), newest at the bottom, to copy or
 * share as text. It names code, ids and timings, not songs.
 */
@Composable
private fun DiagnosticsLogDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(com.harmony.core.common.crash.DiagnosticsLog.read()) }
    val scroll = rememberScrollState()
    androidx.compose.runtime.LaunchedEffect(text) { scroll.scrollTo(scroll.maxValue) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text("Diagnostics log") },
        text = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(scroll),
            ) {
                androidx.compose.material3.Text(
                    text.ifBlank { "Nothing logged yet." },
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
            }
        },
        confirmButton = {
            Row {
                androidx.compose.material3.TextButton(onClick = {
                    val share = android.content.Intent(android.content.Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_SUBJECT, "Harmony diagnostics log")
                        .putExtra(android.content.Intent.EXTRA_TEXT, text)
                    context.startActivity(android.content.Intent.createChooser(share, "Share the diagnostics log"))
                }, enabled = text.isNotBlank()) { androidx.compose.material3.Text("Share") }
                androidx.compose.material3.TextButton(onClick = {
                    context.getSystemService(android.content.ClipboardManager::class.java)
                        ?.setPrimaryClip(android.content.ClipData.newPlainText("Harmony diagnostics log", text))
                    android.widget.Toast.makeText(context, "Diagnostics log copied", android.widget.Toast.LENGTH_SHORT).show()
                }, enabled = text.isNotBlank()) { androidx.compose.material3.Text("Copy") }
            }
        },
        dismissButton = {
            Row {
                androidx.compose.material3.TextButton(onClick = {
                    com.harmony.core.common.crash.DiagnosticsLog.clear()
                    text = ""
                }) { androidx.compose.material3.Text("Clear") }
                androidx.compose.material3.TextButton(onClick = onDismiss) { androidx.compose.material3.Text("Close") }
            }
        },
    )
}
