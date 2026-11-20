package com.harmony.feature.player.gesture

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.harmony.feature.player.R

/**
 * The mini player on its own, with the design's sample songs: play each
 * transition again, slow it down, hold any moment of it, and swipe it for
 * real. Nothing plays; the "player" here just moves between the two songs.
 *
 *     adb shell am start -n com.harmony.app/com.harmony.feature.player.gesture.MiniMotionLabActivity
 */
class MiniMotionLabActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { MiniMotionLab() } }
    }
}

private val labSongs = listOf(
    MiniTrack(key = "midnight", title = "Midnight City", artist = "Luma & The Echo", artwork = R.drawable.lab_midnight),
    MiniTrack(key = "solar", title = "Solar Drift", artist = "NOVA", artwork = R.drawable.lab_solar),
)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MiniMotionLab() {
    val state = rememberMiniMotionState()
    val scope = rememberCoroutineScope()
    var index by remember { mutableStateOf(0) }
    var playing by remember { mutableStateOf(true) }
    var reduced by remember { mutableStateOf(false) }
    var hasNext by remember { mutableStateOf(true) }
    var slow by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf("Swipe the player, or tap a transition.") }
    var hold by remember { mutableStateOf<MiniMode?>(null) }
    var holdAt by remember { mutableFloatStateOf(0f) }
    val current = labSongs[index]
    val next = if (hasNext) labSongs[(index + 1) % labSongs.size] else null
    LaunchedEffect(slow) { state.timeScale = if (slow) 4f else 1f }
    LaunchedEffect(hold, holdAt) {
        val mode = hold ?: return@LaunchedEffect
        state.showFrame(mode, holdAt, if (mode == MiniMode.Replay) current else next ?: labSongs[1])
    }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF0E1412)).statusBarsPadding().padding(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Mini player motion lab", color = Color.White, style = MaterialTheme.typography.titleLarge)
        GestureMiniPlayer(
            current = current, next = next, isPlaying = playing,
            progress = { 90f / 138f }, positionMs = { 120_000L },
            actions = GestureMiniActions(
                onSkip = { log = "Skip command at the handoff"; index = (index + 1) % labSongs.size },
                onReplay = { log = "Replay command at the handoff (seek to 0)" },
                onPlayPause = { playing = !playing },
                onExpand = { log = "Open player" },
            ),
            labels = GestureMiniLabels(
                nowPlaying = "Now playing: ${current.title}, by ${current.artist}", state = if (playing) "Playing" else "Paused",
                next = "Next song", replay = "Restart song", play = "Play", pause = "Pause", open = "Open player",
            ),
            reducedMotion = reduced,
            state = state,
            modifier = Modifier.padding(horizontal = 12.dp),
        ) { track, modifier ->
            Image(painterResource(track.artwork as Int), null, modifier = modifier, contentScale = ContentScale.Crop)
        }
        Text(log, color = Color(0xFFBDFFD9))

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
            Button(onClick = { hold = null; next?.let { state.skip(scope, it, reduced) { log = "Skip command at the handoff"; index = (index + 1) % labSongs.size } } }) { Text("02 Skip") }
            Button(onClick = { hold = null; state.preview(scope, MiniMode.SkipCompleted, next ?: labSongs[1]) }) { Text("03 Skip completed") }
            Button(onClick = { hold = null; state.replay(scope, current, reduced) { log = "Replay command at the handoff (seek to 0)" } }) { Text("04 Replay") }
            Button(onClick = { hold = null; state.preview(scope, MiniMode.ReplayCompleted, null) }) { Text("05 Replay completed") }
        }
        Toggle("Slow motion (4x)", slow) { slow = it }
        Toggle("Reduced motion", reduced) { reduced = it }
        Toggle("A next song to skip to", hasNext) { hasNext = it }

        Text("Hold a moment", color = Color.White)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
            for (mode in listOf(MiniMode.Skip, MiniMode.SkipCompleted, MiniMode.Replay, MiniMode.ReplayCompleted)) {
                OutlinedButton(onClick = { hold = mode; holdAt = 0f }) { Text(mode.name) }
            }
        }
        hold?.let { mode ->
            val duration = timelineOf(mode)!!.durationMs
            Text("$mode at ${holdAt.toInt()} of ${duration.toInt()} ms", color = Color.White)
            Slider(holdAt, { holdAt = it }, valueRange = 0f..duration, modifier = Modifier.padding(horizontal = 24.dp))
            OutlinedButton(onClick = { hold = null; scope.launch { state.showFrame(MiniMode.Rest, 0f, null) } }) { Text("Release") }
        }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, set: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, modifier = Modifier.weight(1f))
        Switch(on, set)
    }
}
