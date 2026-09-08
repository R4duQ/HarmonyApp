package com.harmony.feature.settings

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
    val appVersion = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull().orEmpty().ifBlank { "Unknown" }
    }
    val palette = stonePalette()

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

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.field)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            Modifier.padding(start = 22.dp, end = 22.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                EditorialCircleButton(
                    onClick = onBack,
                    contentDescription = "Back",
                    palette = palette,
                    modifier = Modifier.padding(end = 14.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = null,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
            Text(
                "Settings",
                fontSize = 40.sp,
                lineHeight = 44.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-1).sp,
                color = palette.ink,
            )
        }

        // ---- Appearance ----
        EditorialSectionLabel(
            "Appearance",
            palette,
            Modifier.padding(start = 22.dp, bottom = 8.dp),
        )
        EditorialSettingRow(
            title = "Theme",
            subtitle = "Follow the system, or pin it",
            palette = palette,
            content = {
                EditorialChoiceChips(
                    options = ThemeMode.entries.map { it.label() },
                    selectedIndex = ThemeMode.entries.indexOf(state.themeMode),
                    onSelect = { viewModel.setThemeMode(ThemeMode.entries[it]) },
                    palette = palette,
                )
            },
        )
        EditorialSettingRow(
            title = "AMOLED black",
            subtitle = "True black backgrounds in dark mode",
            palette = palette,
            trailing = {
                EditorialSwitch(state.amoledDark, viewModel::setAmoled, palette)
            },
        )
        EditorialSettingRow(
            title = "Dynamic colors",
            subtitle = "Match your wallpaper (Android 12+). Library, Playlists, " +
                "Equalizer and the player keep their own palettes.",
            palette = palette,
            trailing = {
                EditorialSwitch(state.dynamicColors, viewModel::setDynamicColors, palette)
            },
        )

        EditorialSectionGap()

        // ---- Playback ----
        EditorialSectionLabel(
            "Playback",
            palette,
            Modifier.padding(start = 22.dp, bottom = 8.dp),
        )
        EditorialSettingRow(
            title = "Crossfade",
            subtitle = if (state.crossfadeSeconds > 0) {
                "${state.crossfadeSeconds}s between tracks"
            } else {
                "Off — tracks change instantly"
            },
            palette = palette,
            trailing = {
                EditorialSwitch(
                    checked = state.crossfadeSeconds > 0,
                    onCheckedChange = { on -> viewModel.setCrossfade(if (on) 5 else 0) },
                    palette = palette,
                )
            },
            content = {
                // The slider grows and shrinks with the toggle instead of
                // popping in and shoving the rows below it.
                AnimatedContent(
                    targetState = state.crossfadeSeconds > 0,
                    transitionSpec = {
                        (fadeIn(tween(220)) + expandVertically())
                            .togetherWith(fadeOut(tween(150)) + shrinkVertically())
                    },
                    label = "crossfade-setting",
                ) { enabled ->
                    if (enabled) {
                        EditorialSlider(
                            value = state.crossfadeSeconds.toFloat(),
                            onValueChange = { viewModel.setCrossfade(it.toInt().coerceAtLeast(1)) },
                            palette = palette,
                            valueRange = 1f..12f,
                            steps = 10,
                        )
                    } else {
                        EditorialSectionGap(0)
                    }
                }
            },
        )
        EditorialSettingRow(
            title = "ReplayGain",
            subtitle = "Even out volume differences between tracks",
            palette = palette,
            content = {
                EditorialChoiceChips(
                    options = ReplayGainMode.entries.map { it.label() },
                    selectedIndex = ReplayGainMode.entries.indexOf(state.replayGainMode),
                    onSelect = { viewModel.setReplayGain(ReplayGainMode.entries[it]) },
                    palette = palette,
                )
            },
        )

        EditorialSettingRow(
            title = "Equalizer",
            subtitle = "10 bands, presets and the tone dial. Applied to everything playing.",
            palette = palette,
            onClick = onOpenEqualizer,
        )

        EditorialSectionGap()

        // ---- Smart Shuffle -----------------------------------------------
        EditorialSectionLabel(
            "Smart Shuffle",
            palette,
            Modifier.padding(start = 22.dp, bottom = 8.dp),
        )
        EditorialSettingRow(
            title = "Familiarity",
            subtitle = "Higher values favor favourites and songs you usually finish",
            palette = palette,
            content = {
                EditorialSlider(
                    value = state.smartShuffleFamiliarity,
                    onValueChange = viewModel::setSmartFamiliarity,
                    palette = palette,
                    valueRange = 0f..1f,
                )
            },
        )
        EditorialSettingRow(
            title = "Discovery",
            subtitle = "Bring back rare, unplayed and long-unheard tracks",
            palette = palette,
            content = {
                EditorialSlider(
                    value = state.smartShuffleDiscovery,
                    onValueChange = viewModel::setSmartDiscovery,
                    palette = palette,
                    valueRange = 0f..1f,
                )
            },
        )
        EditorialSettingRow(
            title = "Variety",
            subtitle = "Low stays focused; high explores more of the candidate pool",
            palette = palette,
            content = {
                EditorialSlider(
                    value = state.smartShuffleVariety,
                    onValueChange = viewModel::setSmartVariety,
                    palette = palette,
                    valueRange = 0f..1f,
                )
            },
        )

        EditorialSectionGap()

        // ---- Library & analysis ----
        EditorialSectionLabel(
            "Library & analysis",
            palette,
            Modifier.padding(start = 22.dp, bottom = 8.dp),
        )
        EditorialSettingRow(
            title = "Add music folder",
            subtitle = "Include a USB drive or custom folder in your library",
            palette = palette,
            onClick = { folderPicker.launch(null) },
        )

        // Granted folders, with removal. refreshKey forces recomposition after
        // grant/revoke since persistedUriPermissions isn't observable.
        var refreshKey by remember { mutableIntStateOf(0) }
        val grantedTrees = remember(refreshKey) {
            context.contentResolver.persistedUriPermissions
                .filter { it.isReadPermission }
                .map { it.uri }
        }
        grantedTrees.forEach { treeUri ->
            EditorialSettingRow(
                title = treeUri.lastPathSegment ?: treeUri.toString(),
                subtitle = "Included folder",
                palette = palette,
                trailing = {
                    EditorialTextAction(
                        text = "Remove",
                        onClick = {
                            context.contentResolver.releasePersistableUriPermission(
                                treeUri,
                                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                            )
                            refreshKey++
                        },
                        palette = palette,
                    )
                },
            )
        }

        val rescanStatus by viewModel.rescan.collectAsStateWithLifecycle()
        EditorialSettingRow(
            title = "Rescan library",
            subtitle = rescanStatus
                ?: "Re-read every file. Use after album grouping changes. " +
                "Playlists and favourites are kept.",
            palette = palette,
            onClick = viewModel::rescanLibrary,
            trailing = {
                if (rescanStatus != null) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = palette.ink,
                    )
                }
            },
        )

        EditorialSettingRow(
            title = "Analyze only while charging",
            subtitle = "Audio analysis pauses on battery power",
            palette = palette,
            trailing = {
                EditorialSwitch(state.chargingOnlyAnalysis, viewModel::setChargingOnly, palette)
            },
        )

        EditorialSettingRow(
            title = "Edit tags",
            subtitle = "Fix a song's title, artist, album or artwork. Applies " +
                "everywhere in Harmony; the files aren't changed.",
            palette = palette,
            onClick = onOpenTagEditor,
        )

        EditorialSettingRow(
            title = "Check a file",
            subtitle = "Inspect a track's spectrum — see if a FLAC is really lossless, " +
                "plus its sample rate, bit depth and bitrate",
            palette = palette,
            onClick = onOpenFlacCheck,
        )

        EditorialSectionGap()

        // ---- Privacy ----
        EditorialSectionLabel(
            "Privacy",
            palette,
            Modifier.padding(start = 22.dp, bottom = 8.dp),
        )
        EditorialCard(
            palette = palette,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 5.dp),
        ) {
            Text(
                "Harmony has no Harmony cloud account, analytics, or ads. Local playback " +
                    "and audio analysis stay on this device. Online features such as Downloads " +
                    "and Peer Search communicate with third-party services or peers only when " +
                    "you choose to use them.",
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = palette.muted,
                modifier = Modifier.padding(16.dp),
            )
        }

        EditorialSectionGap()

        // ---- About & legal ----
        EditorialSectionLabel(
            "About & legal",
            palette,
            Modifier.padding(start = 22.dp, bottom = 8.dp),
        )
        EditorialCard(
            palette = palette,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 5.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Harmony",
                    fontSize = 22.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.ink,
                )
                Text(
                    "Version $appVersion",
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = palette.muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    "Owner / Administrator: r4duq",
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.ink,
                    modifier = Modifier.padding(top = 14.dp),
                )
                Text(
                    "© 2026 r4duq. All rights reserved.",
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.ink,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "Harmony's original source code, user interface, branding and original " +
                        "assets are copyright r4duq. Unauthorized copying, redistribution or " +
                        "representation of Harmony's proprietary components as another person's " +
                        "work is prohibited except where permitted by applicable law or an " +
                        "applicable third-party license.",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = palette.muted,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Text(
                    "Harmony does not grant rights to music or other third-party content. " +
                        "Online download and peer-to-peer features should only be used for " +
                        "content you are permitted to access or download.",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = palette.muted,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }

        EditorialSectionLabel(
            "Third-party software",
            palette,
            Modifier.padding(start = 22.dp, top = 18.dp, bottom = 8.dp),
        )
        EditorialCard(
            palette = palette,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 5.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Harmony includes open-source components. Copyright and license rights " +
                        "for these components remain with their respective authors and projects.",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = palette.muted,
                )
                ThirdPartyLine(
                    "AndroidX / Jetpack Compose / Media3 / Room / WorkManager / DataStore",
                    "Apache License 2.0",
                    palette,
                )
                ThirdPartyLine("Kotlin / kotlinx.coroutines", "Apache License 2.0", palette)
                ThirdPartyLine("Dagger / Hilt", "Apache License 2.0", palette)
                ThirdPartyLine("Coil", "Apache License 2.0", palette)
                ThirdPartyLine("youtubedl-android 0.18.1", "GNU GPL v3", palette)
                ThirdPartyLine("yt-dlp", "The Unlicense / public-domain dedication", palette)
                ThirdPartyLine(
                    "FFmpeg",
                    "LGPL v2.1+; optional GPL components may apply to a particular build",
                    palette,
                )
            }
        }

        EditorialSectionLabel(
            "Third-party services",
            palette,
            Modifier.padding(start = 22.dp, top = 18.dp, bottom = 8.dp),
        )
        EditorialCard(
            palette = palette,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 5.dp),
        ) {
            Text(
                "Soulseek, YouTube, Google, SHFL and other third-party service names, trademarks " +
                    "and content belong to their respective owners. Harmony and r4duq are not " +
                    "affiliated with, sponsored by, or endorsed by those services or by the " +
                    "open-source projects listed above.",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = palette.muted,
                modifier = Modifier.padding(16.dp),
            )
        }

        EditorialSectionGap(28)
    }
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
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = palette.ink,
        )
        Text(
            license,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = palette.muted,
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
