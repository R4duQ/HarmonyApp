package com.harmony.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.runtime.remember
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harmony.desktop.dsp.ClarityPreset
import com.harmony.desktop.dsp.ClaritySettings
import com.harmony.desktop.dsp.WinampEqDesign
import com.harmony.desktop.DesktopApp
import com.harmony.desktop.engine.EngineStatus
import kotlin.math.roundToInt

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = LocalHarmonyColors.current
    Box(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(20.dp))
            .padding(horizontal = 24.dp, vertical = 22.dp),
    ) { content() }
}

private val BAND_LABELS = listOf("60", "170", "310", "600", "1K", "3K", "6K", "12K", "14K", "16K")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EqualizerPage(app: DesktopApp) {
    val c = LocalHarmonyColors.current
    val settings by app.settingsFlow.collectAsState()
    val engine by app.player.engineState.collectAsState()
    val eq = settings.eq
    val playing = engine.status == EngineStatus.PLAYING
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageHeader("Equalizer", "Winamp's equalizer, tone, Clarity and even volume: watch it work on the music below") {
            CompareButton(app, eq.enabled)
            Row(
                Modifier
                    .height(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.ink.copy(alpha = 0.06f))
                    .border(1.dp, c.line, RoundedCornerShape(12.dp))
                    .padding(start = 14.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (eq.enabled) "Sound shaping on" else "Sound shaping off", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.ink, modifier = Modifier.padding(end = 10.dp))
                HarmonySwitch(eq.enabled, { app.setEq(eq.copy(enabled = it)) }, Modifier.testTag("eq_on"))
            }
        }
        Card(Modifier.padding(horizontal = 32.dp).padding(bottom = 18.dp).fillMaxWidth()) {
            EqVisualizer(app, eq, playing)
        }
        Card(Modifier.padding(horizontal = 32.dp).fillMaxWidth()) {
            Column {
                CardHeader(Icons.Rounded.Equalizer, listOf(Color(0xFF2FCB6B), Color(0xFF1E9E8C)), "Winamp equalizer", "Ten bands and a preamp. Drag a slider; double-click it to centre it.") {
                    TonalButton("Reset", { app.setEq(eq.copy(winampGainsDb = List(10) { 0f }, winampPreampDb = 0f)) })
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp)
                        .height(264.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (c.dark) Color(0xFF0E1015) else Color(0xFF1A1C23))
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    EqSlider("PRE", eq.winampPreampDb, eq.enabled) { app.setEq(eq.copy(enabled = true, winampPreampDb = it)) }
                    Box(Modifier.width(1.dp).fillMaxSize().padding(vertical = 18.dp).background(Color.White.copy(alpha = 0.08f)))
                    BAND_LABELS.forEachIndexed { i, label ->
                        EqSlider(label, eq.winampGainsDb.getOrElse(i) { 0f }, eq.enabled) { v ->
                            app.setEq(eq.copy(enabled = true, winampGainsDb = eq.winampGainsDb.toMutableList().also { it[i] = v }))
                        }
                    }
                }
                Text("PRESETS", fontSize = 11.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold, color = c.muted, modifier = Modifier.padding(top = 20.dp, bottom = 10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WinampEqDesign.PRESETS.forEach { (name, values) ->
                        val (preamp, gains) = values
                        val on = eq.winampPreampDb == preamp && eq.winampGainsDb == gains
                        ChoiceChip(name, on, { app.setEq(eq.copy(enabled = true, winampPreampDb = preamp, winampGainsDb = gains.toList())) })
                    }
                }
            }
        }
        Card(Modifier.padding(horizontal = 32.dp).padding(top = 18.dp).fillMaxWidth()) {
            ToneCard(app, eq)
        }
        Card(Modifier.padding(horizontal = 32.dp, vertical = 18.dp).fillMaxWidth()) {
            Column {
                CardHeader(Icons.Rounded.AutoAwesome, listOf(Color(0xFFB45CFF), Color(0xFF7C4DFF)), "Clarity", "A model of human hearing brings out what's covered up and holds back what pushes forward.") {
                    HarmonySwitch(eq.clarity, { app.setEq(eq.copy(enabled = eq.enabled || it, clarity = it)) }, Modifier.padding(start = 12.dp).testTag("clarity_on"))
                }
                if (eq.clarity) {
                    val s = eq.claritySettings
                    FlowRow(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val current = ClarityPreset.matching(s)
                        ClarityPreset.entries.forEach { p ->
                            val on = p == current
                            val accent = if (c.dark) Accent.PurpleLight else Accent.Purple
                            val shape = RoundedCornerShape(12.dp)
                            Column(
                                Modifier
                                    .width(160.dp)
                                    .clip(shape)
                                    .background(if (on) Accent.Purple.copy(alpha = if (c.dark) 0.18f else 0.1f) else c.ink.copy(alpha = 0.045f))
                                    .border(1.dp, if (on) Accent.Purple.copy(alpha = 0.55f) else Color.Transparent, shape)
                                    .pointerHoverIcon(PointerIcon.Hand)
                                    .clickable(role = Role.Button) { app.setEq(eq.copy(claritySettings = p.settings.copy(boostDb = s.boostDb))) }
                                    .padding(12.dp),
                            ) {
                                Text(p.label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (on) accent else c.ink)
                                Text(p.blurb, fontSize = 11.5.sp, color = c.muted, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                            }
                        }
                    }
                    ClarityControl("Recover", "${(s.recover * 100).roundToInt()}%", s.recover, 0f..1f, false) { app.setEq(eq.copy(claritySettings = s.copy(recover = it))) }
                    ClarityControl("Tame", "${(s.tame * 100).roundToInt()}%", s.tame, 0f..1f, false) { app.setEq(eq.copy(claritySettings = s.copy(tame = it))) }
                    ClarityControl("Bias", lean(s.bias, "Even", "Tame", "Recover"), s.bias, -1f..1f, true) { app.setEq(eq.copy(claritySettings = s.copy(bias = it))) }
                    ClarityControl("Brighten", lean(s.brighten, "Neutral", "Darker", "Brighter"), s.brighten, -1f..1f, true) { app.setEq(eq.copy(claritySettings = s.copy(brighten = it))) }
                    ClarityControl("Boost", "%+.1f dB".format(s.boostDb), s.boostDb, -ClaritySettings.MAX_BOOST_DB..ClaritySettings.MAX_BOOST_DB, true) { v ->
                        app.setEq(eq.copy(claritySettings = s.copy(boostDb = (v * 2).roundToInt() / 2f)))
                    }
                }
            }
        }
        Card(Modifier.padding(start = 32.dp, end = 32.dp, bottom = 28.dp).fillMaxWidth()) {
            Column {
                CardHeader(Icons.Rounded.Folder, listOf(Color(0xFF5B8DEF), Color(0xFF7C4DFF)), "Music folders", "Where Harmony looks for your songs.")
                Spacer(Modifier.height(12.dp))
                FolderList(app)
            }
        }
    }
}

private fun lean(v: Float, even: String, below: String, above: String): String {
    val pct = (kotlin.math.abs(v) * 100).roundToInt()
    return when {
        pct == 0 -> even
        v < 0 -> "$below $pct%"
        else -> "$above $pct%"
    }
}

@Composable
private fun ClarityControl(title: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, bipolar: Boolean, onChange: (Float) -> Unit) {
    val c = LocalHarmonyColors.current
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.ink, modifier = Modifier.width(120.dp))
        HSlider(v, { onChange((it * 100).roundToInt() / 100f) }, Modifier.weight(1f).height(28.dp), range, listOf(Accent.Purple, Accent.PurpleLight), bipolar)
        ValueChip(value, Modifier.padding(start = 14.dp).width(110.dp))
    }
}

/**
 * A Winamp slider on its dark panel: a ladder of LEDs lighting green to red
 * from the middle, a metal cap to drag, dB on top and the band underneath.
 */
@Composable
private fun EqSlider(label: String, value: Float, lit: Boolean, onChange: (Float) -> Unit) {
    val change by rememberUpdatedState(onChange)
    val max = WinampEqDesign.MAX_DB
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val green = Color(0xFF3CDB5A)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(48.dp)) {
        Text(
            if (value > 0) "+${value.roundToInt()}" else "${value.roundToInt()}",
            fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
            color = if (lit) green else Color.White.copy(alpha = 0.35f),
        )
        Canvas(
            Modifier
                .weight(1f)
                .width(36.dp)
                .padding(vertical = 8.dp)
                .hoverable(hover)
                .pointerHoverIcon(PointerIcon.Hand)
                .pointerInput(Unit) {
                    fun at(y: Float) = ((1f - (y / size.height)) * 2f - 1f).coerceIn(-1f, 1f) * max
                    detectTapGestures(onDoubleTap = { change(0f) }, onTap = { change((at(it.y) * 2.5f).roundToInt() / 2.5f) })
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures { ch, _ ->
                        ch.consume()
                        val y = ch.position.y
                        change((((1f - y / size.height) * 2f - 1f).coerceIn(-1f, 1f) * max * 2.5f).roundToInt() / 2.5f)
                    }
                },
        ) {
            val cells = 24
            val cellH = size.height / cells
            val cx = size.width / 2
            val w = size.width * 0.34f
            val t = value / max
            val valueY = size.height * (1f - (t + 1f) / 2f)
            val centre = size.height / 2
            for (k in 0 until cells) {
                val top = k * cellH
                val mid = top + cellH / 2
                val level = 1f - mid / size.height * 2f
                val on = lit && ((valueY <= mid && mid <= centre) || (centre <= mid && mid <= valueY))
                val color = when {
                    kotlin.math.abs(level) > 0.66f -> Color(0xFFFF5252)
                    kotlin.math.abs(level) > 0.3f -> Color(0xFFF5D547)
                    else -> green
                }
                drawRoundRect(
                    if (on) color else Color.White.copy(alpha = 0.06f),
                    Offset(cx - w / 2, top + 1.5f), Size(w, cellH - 3f), CornerRadius(1.5.dp.toPx()),
                )
            }
            // 0 dB.
            drawLine(Color.White.copy(alpha = 0.3f), Offset(cx - w * 1.15f, centre), Offset(cx + w * 1.15f, centre), strokeWidth = 1.dp.toPx())
            // The cap: a shadow, a brushed-metal face, a groove across.
            val capW = size.width * 0.8f
            val capH = 13.dp.toPx()
            val left = cx - capW / 2
            val top = valueY - capH / 2
            drawRoundRect(Color.Black.copy(alpha = 0.45f), Offset(left, top + 2.dp.toPx()), Size(capW, capH), CornerRadius(4.dp.toPx()))
            drawRoundRect(
                Brush.verticalGradient(listOf(Color(0xFFF7F8FB), Color(0xFFC9CDD6), Color(0xFFE3E6EC)), startY = top, endY = top + capH),
                Offset(left, top), Size(capW, capH), CornerRadius(4.dp.toPx()),
            )
            drawLine(Color(0xFF7D8290), Offset(left + 4.dp.toPx(), valueY), Offset(left + capW - 4.dp.toPx(), valueY), strokeWidth = 1.5.dp.toPx())
            if (hovered) drawRoundRect(Accent.PurpleLight, Offset(left, top), Size(capW, capH), CornerRadius(4.dp.toPx()), style = Stroke(1.5.dp.toPx()))
        }
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.55f))
    }
}

/** Where a phone pairs: the code, how to use it, and the phones already allowed. */
@Composable
fun ConnectPage(app: DesktopApp) {
    val c = LocalHarmonyColors.current
    val state by app.connect.state.collectAsState()
    val phone by app.player.phone.collectAsState()
    val link by app.connect.link.collectAsState()
    val reconnecting = link is com.harmony.core.remote.PhoneLink.Reconnecting
    val settings by app.settingsFlow.collectAsState()
    // The code changes once a phone uses it; the host refreshes this state when that happens.
    LaunchedEffect(Unit) { app.connect.refresh() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageHeader("Connect", "Play the music on your phone through this computer")
        Row(Modifier.padding(horizontal = 32.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Card(Modifier.weight(1f)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Box(
                        Modifier.size(72.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Accent.Purple, Color(0xFF39B9D3)))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Cast, contentDescription = null, tint = Color.White, modifier = Modifier.size(34.dp))
                    }
                    Text(
                        when {
                            phone != null && reconnecting -> "Reconnecting to $phone…"
                            phone != null -> "Playing from $phone"
                            state.running -> "Ready for your phone"
                            else -> "Not listening"
                        },
                        fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.ink, modifier = Modifier.padding(top = 14.dp),
                    )
                    Text(settings.pcName, fontSize = 14.sp, color = c.muted)
                    Text("PAIRING CODE", fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold, color = c.muted, modifier = Modifier.padding(top = 22.dp))
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        state.code.forEach { d ->
                            Box(
                                Modifier
                                    .size(width = 58.dp, height = 74.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(c.surfaceHigh)
                                    .border(1.5.dp, Brush.linearGradient(Accent.Rim), RoundedCornerShape(16.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(d.toString(), fontSize = 36.sp, fontWeight = FontWeight.Bold, color = c.ink, modifier = Modifier.testTag("code_digit"))
                            }
                        }
                    }
                    if (state.error != null) Text(state.error!!, fontSize = 12.sp, color = Accent.Pink, modifier = Modifier.padding(top = 14.dp))
                }
            }
            Card(Modifier.weight(1f)) {
                Column {
                    Text("On your phone", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = c.ink)
                    Step(1, "Connect the phone to the same Wi-Fi as this computer.", Icons.Rounded.Wifi)
                    Step(2, "In Harmony, open the song that's playing and tap the device under the title.", Icons.Rounded.PhoneAndroid)
                    Step(3, "Choose ${settings.pcName} and type the code on the left. You only do this once.", Icons.Rounded.Cast)
                    Text(
                        "If Windows asks whether Harmony may use the network, allow it on private networks.",
                        fontSize = 12.sp, color = c.muted, modifier = Modifier.padding(top = 14.dp),
                    )
                    if (state.addresses.isNotEmpty()) {
                        Text(
                            "This computer: " + state.addresses.joinToString(", ") { "$it:${state.port}" },
                            fontSize = 12.sp, color = c.muted, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
        Card(Modifier.padding(start = 32.dp, end = 32.dp, top = 32.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Shared queue (beta)", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = c.ink)
                    Text(
                        "While a phone plays here, its whole queue shows on this computer, with what you queued and what was recommended marked. The phone stays in charge of the queue.",
                        fontSize = 12.sp, color = c.muted, modifier = Modifier.padding(top = 6.dp),
                    )
                }
                HarmonySwitch(settings.sharedQueue, { app.setSharedQueue(it) }, Modifier.padding(start = 16.dp).testTag("shared_queue"))
            }
        }
        app.cache?.let { cache -> CacheCard(cache, settings.cacheLimitGb, app::setCacheLimit) }
        Card(Modifier.padding(32.dp).fillMaxWidth()) {
            Column {
                Text("Phones allowed to play here", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = c.ink)
                if (state.phones.isEmpty()) {
                    Text("None yet.", fontSize = 13.sp, color = c.muted, modifier = Modifier.padding(top = 8.dp))
                }
                state.phones.forEach { (token, name) ->
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.PhoneAndroid, contentDescription = null, tint = Accent.PurpleLight, modifier = Modifier.size(20.dp))
                        Text(name, fontSize = 14.sp, color = c.ink, modifier = Modifier.padding(start = 10.dp).weight(1f))
                        if (name == phone) {
                            if (reconnecting) Pill("RECONNECTING", Color(0xFFF2B33D)) else Pill("PLAYING", Color(0xFF3CDB5A))
                        }
                        Text("Forget", fontSize = 13.sp, color = Accent.Pink, modifier = Modifier.clickable { app.connect.forget(token) }.padding(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Step(n: Int, text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    val c = LocalHarmonyColors.current
    Row(Modifier.padding(top = 14.dp).widthIn(max = 520.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(Accent.Purple.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
            Text("$n", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Accent.PurpleLight)
        }
        Icon(icon, contentDescription = null, tint = c.muted, modifier = Modifier.padding(start = 12.dp).size(18.dp))
        Text(text, fontSize = 14.sp, color = c.ink, modifier = Modifier.padding(start = 10.dp))
    }
}

/**
 * The phone's songs kept on this computer (Universal Queue cache): how much
 * they take, the most they may take, and a way to empty it.
 */
@Composable
private fun CacheCard(cache: com.harmony.core.remote.cache.MediaCache, limitGb: Int, setLimit: (Int) -> Unit) {
    val c = LocalHarmonyColors.current
    var used by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(cache.usage()) }
    LaunchedEffect(cache) {
        while (true) {
            used = cache.usage()
            kotlinx.coroutines.delay(2_000)
        }
    }
    Card(Modifier.padding(start = 32.dp, end = 32.dp, top = 32.dp).fillMaxWidth()) {
        Column {
            Text("Songs kept from the phone", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = c.ink)
            Text(
                "The song playing and the next few are fetched whole, in their original quality, so they play without waiting on the Wi-Fi and carry on if it drops. Kept apart from your library; the oldest go when the limit is reached (never more than a tenth of the free space).",
                fontSize = 12.sp, color = c.muted, modifier = Modifier.padding(top = 6.dp),
            )
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "%.1f GB used of".format(used / 1_073_741_824.0),
                    fontSize = 13.sp, color = c.ink, modifier = Modifier.padding(end = 10.dp).testTag("cache_used"),
                )
                for (gb in listOf(1, 2, 5, 10, 20)) {
                    val on = gb == limitGb
                    Text(
                        "$gb GB",
                        fontSize = 13.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        color = if (on) Accent.PurpleLight else c.muted,
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (on) Accent.Purple.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { setLimit(gb) }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .testTag("cache_limit_$gb"),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "Empty",
                    fontSize = 13.sp, color = Accent.Pink,
                    modifier = Modifier.clickable { cache.clear(); used = cache.usage() }.padding(8.dp).testTag("cache_clear"),
                )
            }
        }
    }
}
